/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.api.code.MemoryBarriers.*;
import static com.oracle.graal.api.meta.DeoptimizationAction.*;
import static com.oracle.graal.api.meta.DeoptimizationReason.*;
import static com.oracle.graal.api.meta.LocationIdentity.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.hotspot.meta.HotSpotForeignCallsProviderImpl.*;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.hotspot.replacements.NewObjectSnippets.*;
import static com.oracle.graal.nodes.java.ArrayLengthNode.*;
import static com.oracle.graal.phases.GraalOptions.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.debug.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hotspot.replacements.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.HeapAccess.BarrierType;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.debug.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.java.MethodCallTargetNode.InvokeKind;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.replacements.*;

/**
 * HotSpot implementation of {@link LoweringProvider}.
 */
public class HotSpotLoweringProvider implements LoweringProvider {

    protected final HotSpotGraalRuntime runtime;
    protected final MetaAccessProvider metaAccess;
    protected final ForeignCallsProvider foreignCalls;
    protected final HotSpotRegistersProvider registers;

    protected CheckCastDynamicSnippets.Templates checkcastDynamicSnippets;
    protected InstanceOfSnippets.Templates instanceofSnippets;
    protected NewObjectSnippets.Templates newObjectSnippets;
    protected MonitorSnippets.Templates monitorSnippets;
    protected WriteBarrierSnippets.Templates writeBarrierSnippets;
    protected BoxingSnippets.Templates boxingSnippets;
    protected LoadExceptionObjectSnippets.Templates exceptionObjectSnippets;
    protected UnsafeLoadSnippets.Templates unsafeLoadSnippets;

    public HotSpotLoweringProvider(HotSpotGraalRuntime runtime, MetaAccessProvider metaAccess, ForeignCallsProvider foreignCalls, HotSpotRegistersProvider registers) {
        this.runtime = runtime;
        this.metaAccess = metaAccess;
        this.foreignCalls = foreignCalls;
        this.registers = registers;
    }

    public void initialize(HotSpotProviders providers, HotSpotVMConfig config) {
        TargetDescription target = providers.getCodeCache().getTarget();
        checkcastDynamicSnippets = new CheckCastDynamicSnippets.Templates(providers, target);
        instanceofSnippets = new InstanceOfSnippets.Templates(providers, target);
        newObjectSnippets = new NewObjectSnippets.Templates(providers, target);
        monitorSnippets = new MonitorSnippets.Templates(providers, target, config.useFastLocking);
        writeBarrierSnippets = new WriteBarrierSnippets.Templates(providers, target);
        boxingSnippets = new BoxingSnippets.Templates(providers, target);
        exceptionObjectSnippets = new LoadExceptionObjectSnippets.Templates(providers, target);
        unsafeLoadSnippets = new UnsafeLoadSnippets.Templates(providers, target);
        providers.getReplacements().registerSnippetTemplateCache(new UnsafeArrayCopySnippets.Templates(providers, target));
    }

    @Override
    public void lower(Node n, LoweringTool tool) {
        StructuredGraph graph = (StructuredGraph) n.graph();

        if (n instanceof ArrayLengthNode) {
            lowerArrayLengthNode((ArrayLengthNode) n, tool);
        } else if (n instanceof Invoke) {
            lowerInvoke((Invoke) n, tool, graph);
        } else if (n instanceof LoadFieldNode) {
            lowerLoadFieldNode((LoadFieldNode) n, tool);
        } else if (n instanceof StoreFieldNode) {
            lowerStoreFieldNode((StoreFieldNode) n, tool);
        } else if (n instanceof CompareAndSwapNode) {
            lowerCompareAndSwapNode((CompareAndSwapNode) n);
        } else if (n instanceof LoadIndexedNode) {
            lowerLoadIndexedNode((LoadIndexedNode) n, tool);
        } else if (n instanceof StoreIndexedNode) {
            lowerStoreIndexedNode((StoreIndexedNode) n, tool);
        } else if (n instanceof UnsafeLoadNode) {
            lowerUnsafeLoadNode((UnsafeLoadNode) n, tool);
        } else if (n instanceof UnsafeStoreNode) {
            lowerUnsafeStoreNode((UnsafeStoreNode) n);
        } else if (n instanceof LoadHubNode) {
            lowerLoadHubNode((LoadHubNode) n);
        } else if (n instanceof LoadMethodNode) {
            lowerLoadMethodNode((LoadMethodNode) n);
        } else if (n instanceof StoreHubNode) {
            lowerStoreHubNode((StoreHubNode) n, graph);
        } else if (n instanceof CommitAllocationNode) {
            lowerCommitAllocationNode((CommitAllocationNode) n, tool);
        } else if (n instanceof OSRStartNode) {
            lowerOSRStartNode((OSRStartNode) n);
        } else if (n instanceof DynamicCounterNode) {
            lowerDynamicCounterNode((DynamicCounterNode) n);
        } else if (n instanceof DeferredForeignCallNode) {
            lowerDeferredForeignCallNode((DeferredForeignCallNode) n);
        } else if (n instanceof CheckCastDynamicNode) {
            checkcastDynamicSnippets.lower((CheckCastDynamicNode) n, tool);
        } else if (n instanceof InstanceOfNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.FIXED_DEOPTS) {
                instanceofSnippets.lower((InstanceOfNode) n, tool);
            }
        } else if (n instanceof InstanceOfDynamicNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.FIXED_DEOPTS) {
                instanceofSnippets.lower((InstanceOfDynamicNode) n, tool);
            }
        } else if (n instanceof NewInstanceNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
                newObjectSnippets.lower((NewInstanceNode) n, registers, tool);
            }
        } else if (n instanceof DynamicNewInstanceNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
                newObjectSnippets.lower((DynamicNewInstanceNode) n, registers, tool);
            }
        } else if (n instanceof NewArrayNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
                newObjectSnippets.lower((NewArrayNode) n, registers, tool);
            }
        } else if (n instanceof DynamicNewArrayNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
                newObjectSnippets.lower((DynamicNewArrayNode) n, registers, tool);
            }
        } else if (n instanceof MonitorEnterNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
                monitorSnippets.lower((MonitorEnterNode) n, registers, tool);
            }
        } else if (n instanceof MonitorExitNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
                monitorSnippets.lower((MonitorExitNode) n, tool);
            }
        } else if (n instanceof G1PreWriteBarrier) {
            writeBarrierSnippets.lower((G1PreWriteBarrier) n, registers, tool);
        } else if (n instanceof G1PostWriteBarrier) {
            writeBarrierSnippets.lower((G1PostWriteBarrier) n, registers, tool);
        } else if (n instanceof G1ReferentFieldReadBarrier) {
            writeBarrierSnippets.lower((G1ReferentFieldReadBarrier) n, registers, tool);
        } else if (n instanceof SerialWriteBarrier) {
            writeBarrierSnippets.lower((SerialWriteBarrier) n, tool);
        } else if (n instanceof SerialArrayRangeWriteBarrier) {
            writeBarrierSnippets.lower((SerialArrayRangeWriteBarrier) n, tool);
        } else if (n instanceof G1ArrayRangePreWriteBarrier) {
            writeBarrierSnippets.lower((G1ArrayRangePreWriteBarrier) n, registers, tool);
        } else if (n instanceof G1ArrayRangePostWriteBarrier) {
            writeBarrierSnippets.lower((G1ArrayRangePostWriteBarrier) n, registers, tool);
        } else if (n instanceof NewMultiArrayNode) {
            if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
                newObjectSnippets.lower((NewMultiArrayNode) n, tool);
            }
        } else if (n instanceof LoadExceptionObjectNode) {
            exceptionObjectSnippets.lower((LoadExceptionObjectNode) n, registers, tool);
        } else if (n instanceof IntegerDivNode || n instanceof IntegerRemNode || n instanceof UnsignedDivNode || n instanceof UnsignedRemNode) {
            // Nothing to do for division nodes. The HotSpot signal handler catches divisions by
            // zero and the MIN_VALUE / -1 cases.
        } else if (n instanceof BoxNode) {
            boxingSnippets.lower((BoxNode) n, tool);
        } else if (n instanceof UnboxNode) {
            boxingSnippets.lower((UnboxNode) n, tool);
        } else if (n instanceof DeoptimizeNode || n instanceof UnwindNode) {
            /* No lowering, we generate LIR directly for these nodes. */
        } else {
            throw GraalInternalError.shouldNotReachHere("Node implementing Lowerable not handled: " + n);
        }
    }

    private void lowerArrayLengthNode(ArrayLengthNode arrayLengthNode, LoweringTool tool) {
        StructuredGraph graph = arrayLengthNode.graph();
        ValueNode array = arrayLengthNode.array();
        ReadNode arrayLengthRead = graph.add(new ReadNode(array, ConstantLocationNode.create(ARRAY_LENGTH_LOCATION, Kind.Int, runtime.getConfig().arrayLengthOffset, graph),
                        StampFactory.positiveInt(), BarrierType.NONE, false));
        arrayLengthRead.setGuard(createNullCheck(array, arrayLengthNode, tool));
        graph.replaceFixedWithFixed(arrayLengthNode, arrayLengthRead);
    }

    private void lowerInvoke(Invoke invoke, LoweringTool tool, StructuredGraph graph) {
        if (invoke.callTarget() instanceof MethodCallTargetNode) {
            MethodCallTargetNode callTarget = (MethodCallTargetNode) invoke.callTarget();
            NodeInputList<ValueNode> parameters = callTarget.arguments();
            ValueNode receiver = parameters.size() <= 0 ? null : parameters.get(0);
            GuardingNode receiverNullCheck = null;
            if (!callTarget.isStatic() && receiver.stamp() instanceof ObjectStamp && !ObjectStamp.isObjectNonNull(receiver)) {
                receiverNullCheck = createNullCheck(receiver, invoke.asNode(), tool);
                invoke.setGuard(receiverNullCheck);
            }
            JavaType[] signature = MetaUtil.signatureToTypes(callTarget.targetMethod().getSignature(), callTarget.isStatic() ? null : callTarget.targetMethod().getDeclaringClass());

            LoweredCallTargetNode loweredCallTarget = null;
            if (callTarget.invokeKind() == InvokeKind.Virtual && InlineVTableStubs.getValue() && (AlwaysInlineVTableStubs.getValue() || invoke.isPolymorphic())) {

                HotSpotResolvedJavaMethod hsMethod = (HotSpotResolvedJavaMethod) callTarget.targetMethod();
                if (!hsMethod.getDeclaringClass().isInterface()) {
                    if (hsMethod.isInVirtualMethodTable()) {
                        int vtableEntryOffset = hsMethod.vtableEntryOffset();
                        assert vtableEntryOffset > 0;
                        Kind wordKind = runtime.getTarget().wordKind;
                        FloatingReadNode hub = createReadHub(graph, wordKind, receiver, receiverNullCheck);

                        ReadNode metaspaceMethod = createReadVirtualMethod(graph, wordKind, hub, hsMethod);
                        // We use LocationNode.ANY_LOCATION for the reads that access the
                        // compiled code entry as HotSpot does not guarantee they are final
                        // values.
                        ReadNode compiledEntry = graph.add(new ReadNode(metaspaceMethod, ConstantLocationNode.create(ANY_LOCATION, wordKind, runtime.getConfig().methodCompiledEntryOffset, graph),
                                        StampFactory.forKind(wordKind), BarrierType.NONE, false));

                        loweredCallTarget = graph.add(new HotSpotIndirectCallTargetNode(metaspaceMethod, compiledEntry, parameters, invoke.asNode().stamp(), signature, callTarget.targetMethod(),
                                        CallingConvention.Type.JavaCall));

                        graph.addBeforeFixed(invoke.asNode(), metaspaceMethod);
                        graph.addAfterFixed(metaspaceMethod, compiledEntry);
                    }
                }
            }

            if (loweredCallTarget == null) {
                loweredCallTarget = graph.add(new HotSpotDirectCallTargetNode(parameters, invoke.asNode().stamp(), signature, callTarget.targetMethod(), CallingConvention.Type.JavaCall,
                                callTarget.invokeKind()));
            }
            callTarget.replaceAndDelete(loweredCallTarget);
        }
    }

    private void lowerLoadFieldNode(LoadFieldNode loadField, LoweringTool tool) {
        StructuredGraph graph = loadField.graph();
        HotSpotResolvedJavaField field = (HotSpotResolvedJavaField) loadField.field();
        ValueNode object = loadField.isStatic() ? ConstantNode.forObject(field.getDeclaringClass().mirror(), metaAccess, graph) : loadField.object();
        assert loadField.kind() != Kind.Illegal;
        BarrierType barrierType = getFieldLoadBarrierType(field);
        ReadNode memoryRead = graph.add(new ReadNode(object, createFieldLocation(graph, field, false), loadField.stamp(), barrierType, (loadField.kind() == Kind.Object)));
        graph.replaceFixedWithFixed(loadField, memoryRead);
        memoryRead.setGuard(createNullCheck(object, memoryRead, tool));

        if (loadField.isVolatile()) {
            MembarNode preMembar = graph.add(new MembarNode(JMM_PRE_VOLATILE_READ));
            graph.addBeforeFixed(memoryRead, preMembar);
            MembarNode postMembar = graph.add(new MembarNode(JMM_POST_VOLATILE_READ));
            graph.addAfterFixed(memoryRead, postMembar);
        }
    }

    private void lowerStoreFieldNode(StoreFieldNode storeField, LoweringTool tool) {
        StructuredGraph graph = storeField.graph();
        HotSpotResolvedJavaField field = (HotSpotResolvedJavaField) storeField.field();
        ValueNode object = storeField.isStatic() ? ConstantNode.forObject(field.getDeclaringClass().mirror(), metaAccess, graph) : storeField.object();
        BarrierType barrierType = getFieldStoreBarrierType(storeField);
        WriteNode memoryWrite = graph.add(new WriteNode(object, storeField.value(), createFieldLocation(graph, field, false), barrierType, storeField.field().getKind() == Kind.Object));
        memoryWrite.setStateAfter(storeField.stateAfter());
        graph.replaceFixedWithFixed(storeField, memoryWrite);
        memoryWrite.setGuard(createNullCheck(object, memoryWrite, tool));
        FixedWithNextNode last = memoryWrite;
        FixedWithNextNode first = memoryWrite;

        if (storeField.isVolatile()) {
            MembarNode preMembar = graph.add(new MembarNode(JMM_PRE_VOLATILE_WRITE));
            graph.addBeforeFixed(first, preMembar);
            MembarNode postMembar = graph.add(new MembarNode(JMM_POST_VOLATILE_WRITE));
            graph.addAfterFixed(last, postMembar);
        }
    }

    private static void lowerCompareAndSwapNode(CompareAndSwapNode cas) {
        // Separate out GC barrier semantics
        StructuredGraph graph = cas.graph();
        LocationNode location = IndexedLocationNode.create(cas.getLocationIdentity(), cas.expected().kind(), cas.displacement(), cas.offset(), graph, 1);
        LoweredCompareAndSwapNode atomicNode = graph.add(new LoweredCompareAndSwapNode(cas.object(), location, cas.expected(), cas.newValue(), getCompareAndSwapBarrier(cas),
                        cas.expected().kind() == Kind.Object));
        atomicNode.setStateAfter(cas.stateAfter());
        graph.replaceFixedWithFixed(cas, atomicNode);
    }

    private void lowerLoadIndexedNode(LoadIndexedNode loadIndexed, LoweringTool tool) {
        StructuredGraph graph = loadIndexed.graph();
        Kind elementKind = loadIndexed.elementKind();
        LocationNode arrayLocation = createArrayLocation(graph, elementKind, loadIndexed.index(), false);
        ReadNode memoryRead = graph.add(new ReadNode(loadIndexed.array(), arrayLocation, loadIndexed.stamp(), BarrierType.NONE, elementKind == Kind.Object));
        memoryRead.setGuard(createBoundsCheck(loadIndexed, tool));
        graph.replaceFixedWithFixed(loadIndexed, memoryRead);
    }

    private void lowerStoreIndexedNode(StoreIndexedNode storeIndexed, LoweringTool tool) {
        StructuredGraph graph = storeIndexed.graph();
        GuardingNode boundsCheck = createBoundsCheck(storeIndexed, tool);
        Kind elementKind = storeIndexed.elementKind();
        LocationNode arrayLocation = createArrayLocation(graph, elementKind, storeIndexed.index(), false);
        ValueNode value = storeIndexed.value();
        ValueNode array = storeIndexed.array();

        CheckCastNode checkcastNode = null;
        CheckCastDynamicNode checkcastDynamicNode = null;
        if (elementKind == Kind.Object && !ObjectStamp.isObjectAlwaysNull(value)) {
            // Store check!
            ResolvedJavaType arrayType = ObjectStamp.typeOrNull(array);
            if (arrayType != null && ObjectStamp.isExactType(array)) {
                ResolvedJavaType elementType = arrayType.getComponentType();
                if (!MetaUtil.isJavaLangObject(elementType)) {
                    checkcastNode = graph.add(new CheckCastNode(elementType, value, null, true));
                    graph.addBeforeFixed(storeIndexed, checkcastNode);
                    value = checkcastNode;
                }
            } else {
                Kind wordKind = runtime.getTarget().wordKind;
                FloatingReadNode arrayClass = createReadHub(graph, wordKind, array, boundsCheck);
                LocationNode location = ConstantLocationNode.create(FINAL_LOCATION, wordKind, runtime.getConfig().arrayClassElementOffset, graph);
                /*
                 * Anchor the read of the element klass to the cfg, because it is only valid when
                 * arrayClass is an object class, which might not be the case in other parts of the
                 * compiled method.
                 */
                FloatingReadNode arrayElementKlass = graph.unique(new FloatingReadNode(arrayClass, location, null, StampFactory.forKind(wordKind), BeginNode.prevBegin(storeIndexed)));
                checkcastDynamicNode = graph.add(new CheckCastDynamicNode(arrayElementKlass, value, true));
                graph.addBeforeFixed(storeIndexed, checkcastDynamicNode);
                value = checkcastDynamicNode;
            }
        }
        BarrierType barrierType = getArrayStoreBarrierType(storeIndexed);
        WriteNode memoryWrite = graph.add(new WriteNode(array, value, arrayLocation, barrierType, elementKind == Kind.Object));
        memoryWrite.setGuard(boundsCheck);
        memoryWrite.setStateAfter(storeIndexed.stateAfter());
        graph.replaceFixedWithFixed(storeIndexed, memoryWrite);

        // Lower the associated checkcast node.
        if (checkcastNode != null) {
            checkcastNode.lower(tool);
        } else if (checkcastDynamicNode != null) {
            checkcastDynamicSnippets.lower(checkcastDynamicNode, tool);
        }
    }

    private void lowerUnsafeLoadNode(UnsafeLoadNode load, LoweringTool tool) {
        StructuredGraph graph = load.graph();
        if (load.getGuardingCondition() != null) {
            boolean compressible = (!load.object().isNullConstant() && load.accessKind() == Kind.Object);
            ConditionAnchorNode valueAnchorNode = graph.add(new ConditionAnchorNode(load.getGuardingCondition()));
            LocationNode location = createLocation(load);
            ReadNode memoryRead = graph.add(new ReadNode(load.object(), location, load.stamp(), valueAnchorNode, BarrierType.NONE, compressible));
            load.replaceAtUsages(memoryRead);
            graph.replaceFixedWithFixed(load, valueAnchorNode);
            graph.addAfterFixed(valueAnchorNode, memoryRead);
        } else if (graph.getGuardsStage().ordinal() > StructuredGraph.GuardsStage.FLOATING_GUARDS.ordinal()) {
            assert load.kind() != Kind.Illegal;
            boolean compressible = (!load.object().isNullConstant() && load.accessKind() == Kind.Object);
            if (addReadBarrier(load)) {
                unsafeLoadSnippets.lower(load, tool);
            } else {
                LocationNode location = createLocation(load);
                ReadNode memoryRead = graph.add(new ReadNode(load.object(), location, load.stamp(), BarrierType.NONE, compressible));
                // An unsafe read must not float outside its block otherwise
                // it may float above an explicit null check on its object.
                memoryRead.setGuard(AbstractBeginNode.prevBegin(load));
                graph.replaceFixedWithFixed(load, memoryRead);
            }
        }
    }

    private static void lowerUnsafeStoreNode(UnsafeStoreNode store) {
        StructuredGraph graph = store.graph();
        LocationNode location = createLocation(store);
        ValueNode object = store.object();
        BarrierType barrierType = getUnsafeStoreBarrierType(store);
        WriteNode write = graph.add(new WriteNode(object, store.value(), location, barrierType, store.value().kind() == Kind.Object));
        write.setStateAfter(store.stateAfter());
        graph.replaceFixedWithFixed(store, write);
    }

    private void lowerLoadHubNode(LoadHubNode loadHub) {
        StructuredGraph graph = loadHub.graph();
        if (graph.getGuardsStage().ordinal() >= StructuredGraph.GuardsStage.FIXED_DEOPTS.ordinal()) {
            Kind wordKind = runtime.getTarget().wordKind;
            assert loadHub.kind() == wordKind;
            ValueNode object = loadHub.object();
            GuardingNode guard = loadHub.getGuard();
            FloatingReadNode hub = createReadHub(graph, wordKind, object, guard);
            graph.replaceFloating(loadHub, hub);
        }
    }

    private void lowerLoadMethodNode(LoadMethodNode loadMethodNode) {
        StructuredGraph graph = loadMethodNode.graph();
        ResolvedJavaMethod method = loadMethodNode.getMethod();
        ReadNode metaspaceMethod = createReadVirtualMethod(graph, runtime.getTarget().wordKind, loadMethodNode.getHub(), method);
        graph.replaceFixed(loadMethodNode, metaspaceMethod);
    }

    private void lowerStoreHubNode(StoreHubNode storeHub, StructuredGraph graph) {
        WriteNode hub = createWriteHub(graph, runtime.getTarget().wordKind, storeHub.getObject(), storeHub.getValue());
        graph.replaceFixed(storeHub, hub);
    }

    private void lowerCommitAllocationNode(CommitAllocationNode commit, LoweringTool tool) {
        StructuredGraph graph = commit.graph();
        if (graph.getGuardsStage() == StructuredGraph.GuardsStage.FIXED_DEOPTS) {
            ValueNode[] allocations = new ValueNode[commit.getVirtualObjects().size()];
            BitSet omittedValues = new BitSet();
            int valuePos = 0;
            for (int objIndex = 0; objIndex < commit.getVirtualObjects().size(); objIndex++) {
                VirtualObjectNode virtual = commit.getVirtualObjects().get(objIndex);
                int entryCount = virtual.entryCount();
                FixedWithNextNode newObject;
                if (virtual instanceof VirtualInstanceNode) {
                    newObject = graph.add(new NewInstanceNode(virtual.type(), true));
                } else {
                    newObject = graph.add(new NewArrayNode(((VirtualArrayNode) virtual).componentType(), ConstantNode.forInt(entryCount, graph), true));
                }
                graph.addBeforeFixed(commit, newObject);
                allocations[objIndex] = newObject;
                for (int i = 0; i < entryCount; i++) {
                    ValueNode value = commit.getValues().get(valuePos);
                    if (value instanceof VirtualObjectNode) {
                        value = allocations[commit.getVirtualObjects().indexOf(value)];
                    }
                    if (value == null) {
                        omittedValues.set(valuePos);
                    } else if (!(value.isConstant() && value.asConstant().isDefaultForKind())) {
                        // Constant.illegal is always the defaultForKind, so it is skipped
                        Kind valueKind = value.kind();
                        Kind entryKind = virtual.entryKind(i);

                        // Truffle requires some leniency in terms of what can be put where:
                        Kind accessKind = valueKind.getStackKind() == entryKind.getStackKind() ? entryKind : valueKind;
                        assert valueKind.getStackKind() == entryKind.getStackKind() ||
                                        (valueKind == Kind.Long || valueKind == Kind.Double || (valueKind == Kind.Int && virtual instanceof VirtualArrayNode));
                        ConstantLocationNode location;
                        BarrierType barrierType;
                        if (virtual instanceof VirtualInstanceNode) {
                            ResolvedJavaField field = ((VirtualInstanceNode) virtual).field(i);
                            location = ConstantLocationNode.create(INIT_LOCATION, accessKind, ((HotSpotResolvedJavaField) field).offset(), graph);
                            barrierType = (entryKind == Kind.Object && !useDeferredInitBarriers()) ? BarrierType.IMPRECISE : BarrierType.NONE;
                        } else {
                            location = ConstantLocationNode.create(INIT_LOCATION, accessKind, getArrayBaseOffset(entryKind) + i * getScalingFactor(entryKind), graph);
                            barrierType = (entryKind == Kind.Object && !useDeferredInitBarriers()) ? BarrierType.PRECISE : BarrierType.NONE;
                        }
                        WriteNode write = new WriteNode(newObject, value, location, barrierType, entryKind == Kind.Object);
                        graph.addAfterFixed(newObject, graph.add(write));
                    }
                    valuePos++;

                }
            }
            valuePos = 0;

            for (int objIndex = 0; objIndex < commit.getVirtualObjects().size(); objIndex++) {
                VirtualObjectNode virtual = commit.getVirtualObjects().get(objIndex);
                int entryCount = virtual.entryCount();
                ValueNode newObject = allocations[objIndex];
                for (int i = 0; i < entryCount; i++) {
                    if (omittedValues.get(valuePos)) {
                        ValueNode value = commit.getValues().get(valuePos);
                        assert value instanceof VirtualObjectNode;
                        ValueNode allocValue = allocations[commit.getVirtualObjects().indexOf(value)];
                        if (!(allocValue.isConstant() && allocValue.asConstant().isDefaultForKind())) {
                            assert virtual.entryKind(i) == Kind.Object && allocValue.kind() == Kind.Object;
                            WriteNode write;
                            if (virtual instanceof VirtualInstanceNode) {
                                VirtualInstanceNode virtualInstance = (VirtualInstanceNode) virtual;
                                write = new WriteNode(newObject, allocValue, createFieldLocation(graph, (HotSpotResolvedJavaField) virtualInstance.field(i), true), BarrierType.IMPRECISE, true);
                            } else {
                                write = new WriteNode(newObject, allocValue, createArrayLocation(graph, virtual.entryKind(i), ConstantNode.forInt(i, graph), true), BarrierType.PRECISE, true);
                            }
                            graph.addBeforeFixed(commit, graph.add(write));
                        }
                    }
                    valuePos++;
                }
            }

            finishAllocatedObjects(tool, commit, allocations);
            graph.removeFixed(commit);
        }
    }

    private void lowerOSRStartNode(OSRStartNode osrStart) {
        StructuredGraph graph = osrStart.graph();
        if (graph.getGuardsStage() == StructuredGraph.GuardsStage.FIXED_DEOPTS) {
            StartNode newStart = graph.add(new StartNode());
            ParameterNode buffer = graph.unique(new ParameterNode(0, StampFactory.forKind(runtime.getTarget().wordKind)));
            ForeignCallNode migrationEnd = graph.add(new ForeignCallNode(foreignCalls, OSR_MIGRATION_END, buffer));
            migrationEnd.setStateAfter(osrStart.stateAfter());

            newStart.setNext(migrationEnd);
            FixedNode next = osrStart.next();
            osrStart.setNext(null);
            migrationEnd.setNext(next);
            graph.setStart(newStart);

            // mirroring the calculations in c1_GraphBuilder.cpp (setup_osr_entry_block)
            int localsOffset = (graph.method().getMaxLocals() - 1) * 8;
            for (OSRLocalNode osrLocal : graph.getNodes(OSRLocalNode.class)) {
                int size = HIRFrameStateBuilder.stackSlots(osrLocal.kind());
                int offset = localsOffset - (osrLocal.index() + size - 1) * 8;
                IndexedLocationNode location = IndexedLocationNode.create(ANY_LOCATION, osrLocal.kind(), offset, ConstantNode.forLong(0, graph), graph, 1);
                ReadNode load = graph.add(new ReadNode(buffer, location, osrLocal.stamp(), BarrierType.NONE, false));
                osrLocal.replaceAndDelete(load);
                graph.addBeforeFixed(migrationEnd, load);
            }
            osrStart.replaceAtUsages(newStart);
            osrStart.safeDelete();
        }
    }

    private void lowerDynamicCounterNode(DynamicCounterNode n) {
        StructuredGraph graph = n.graph();
        if (graph.getGuardsStage() == StructuredGraph.GuardsStage.AFTER_FSA) {
            BenchmarkCounters.lower(n, registers, runtime.getConfig(), runtime.getTarget().wordKind);
        }
    }

    private void lowerDeferredForeignCallNode(DeferredForeignCallNode deferred) {
        StructuredGraph graph = deferred.graph();
        if (graph.getGuardsStage() == StructuredGraph.GuardsStage.FLOATING_GUARDS) {
            ForeignCallNode foreignCallNode = graph.add(new ForeignCallNode(foreignCalls, deferred.getDescriptor(), deferred.stamp(), deferred.getArguments()));
            graph.replaceFixedWithFixed(deferred, foreignCallNode);
        }
    }

    protected static void finishAllocatedObjects(LoweringTool tool, CommitAllocationNode commit, ValueNode[] allocations) {
        StructuredGraph graph = commit.graph();
        for (int objIndex = 0; objIndex < commit.getVirtualObjects().size(); objIndex++) {
            FixedValueAnchorNode anchor = graph.add(new FixedValueAnchorNode(allocations[objIndex]));
            allocations[objIndex] = anchor;
            graph.addBeforeFixed(commit, anchor);
        }
        for (int objIndex = 0; objIndex < commit.getVirtualObjects().size(); objIndex++) {
            for (MonitorIdNode monitorId : commit.getLocks(objIndex)) {
                MonitorEnterNode enter = graph.add(new MonitorEnterNode(allocations[objIndex], monitorId));
                graph.addBeforeFixed(commit, enter);
                enter.lower(tool);
            }
        }
        for (Node usage : commit.usages().snapshot()) {
            AllocatedObjectNode addObject = (AllocatedObjectNode) usage;
            int index = commit.getVirtualObjects().indexOf(addObject.getVirtualObject());
            graph.replaceFloating(addObject, allocations[index]);
        }
    }

    private static LocationNode createLocation(UnsafeAccessNode access) {
        ValueNode offset = access.offset();
        if (offset.isConstant()) {
            long offsetValue = offset.asConstant().asLong();
            return ConstantLocationNode.create(access.getLocationIdentity(), access.accessKind(), offsetValue, access.graph());
        }

        long displacement = 0;
        int indexScaling = 1;
        if (offset instanceof IntegerAddNode) {
            IntegerAddNode integerAddNode = (IntegerAddNode) offset;
            if (integerAddNode.y() instanceof ConstantNode) {
                displacement = integerAddNode.y().asConstant().asLong();
                offset = integerAddNode.x();
            }
        }

        if (offset instanceof LeftShiftNode) {
            LeftShiftNode leftShiftNode = (LeftShiftNode) offset;
            if (leftShiftNode.y() instanceof ConstantNode) {
                long shift = leftShiftNode.y().asConstant().asLong();
                if (shift >= 1 && shift <= 3) {
                    if (shift == 1) {
                        indexScaling = 2;
                    } else if (shift == 2) {
                        indexScaling = 4;
                    } else {
                        indexScaling = 8;
                    }
                    offset = leftShiftNode.x();
                }
            }
        }

        return IndexedLocationNode.create(access.getLocationIdentity(), access.accessKind(), displacement, offset, access.graph(), indexScaling);
    }

    private static boolean addReadBarrier(UnsafeLoadNode load) {
        if (useG1GC() && load.graph().getGuardsStage() == StructuredGraph.GuardsStage.FIXED_DEOPTS && load.object().kind() == Kind.Object && load.accessKind() == Kind.Object &&
                        !ObjectStamp.isObjectAlwaysNull(load.object())) {
            ResolvedJavaType type = ObjectStamp.typeOrNull(load.object());
            if (type != null && !type.isArray()) {
                return true;
            }
        }
        return false;
    }

    private static ReadNode createReadVirtualMethod(StructuredGraph graph, Kind wordKind, ValueNode hub, ResolvedJavaMethod method) {
        HotSpotResolvedJavaMethod hsMethod = (HotSpotResolvedJavaMethod) method;
        assert !hsMethod.getDeclaringClass().isInterface();
        assert hsMethod.isInVirtualMethodTable();

        int vtableEntryOffset = hsMethod.vtableEntryOffset();
        assert vtableEntryOffset > 0;
        // We use LocationNode.ANY_LOCATION for the reads that access the vtable
        // entry as HotSpot does not guarantee that this is a final value.
        ReadNode metaspaceMethod = graph.add(new ReadNode(hub, ConstantLocationNode.create(ANY_LOCATION, wordKind, vtableEntryOffset, graph), StampFactory.forKind(wordKind), BarrierType.NONE, false));
        return metaspaceMethod;
    }

    private FloatingReadNode createReadHub(StructuredGraph graph, Kind wordKind, ValueNode object, GuardingNode guard) {
        HotSpotVMConfig config = runtime.getConfig();
        LocationNode location = ConstantLocationNode.create(FINAL_LOCATION, wordKind, config.hubOffset, graph);
        assert !object.isConstant() || object.asConstant().isNull();
        return graph.unique(new FloatingReadNode(object, location, null, StampFactory.forKind(wordKind), guard, BarrierType.NONE, config.useCompressedClassPointers));
    }

    private WriteNode createWriteHub(StructuredGraph graph, Kind wordKind, ValueNode object, ValueNode value) {
        HotSpotVMConfig config = runtime.getConfig();
        LocationNode location = ConstantLocationNode.create(HUB_LOCATION, wordKind, config.hubOffset, graph);
        assert !object.isConstant() || object.asConstant().isNull();
        return graph.add(new WriteNode(object, value, location, BarrierType.NONE, config.useCompressedClassPointers));
    }

    private static BarrierType getFieldLoadBarrierType(HotSpotResolvedJavaField loadField) {
        BarrierType barrierType = BarrierType.NONE;
        if (config().useG1GC && loadField.getKind() == Kind.Object && loadField.getDeclaringClass().mirror() == java.lang.ref.Reference.class && loadField.getName().equals("referent")) {
            barrierType = BarrierType.PRECISE;
        }
        return barrierType;
    }

    private static BarrierType getFieldStoreBarrierType(StoreFieldNode storeField) {
        BarrierType barrierType = BarrierType.NONE;
        if (storeField.field().getKind() == Kind.Object) {
            barrierType = BarrierType.IMPRECISE;
        }
        return barrierType;
    }

    private static BarrierType getArrayStoreBarrierType(StoreIndexedNode store) {
        BarrierType barrierType = BarrierType.NONE;
        if (store.elementKind() == Kind.Object) {
            barrierType = BarrierType.PRECISE;
        }
        return barrierType;
    }

    private static BarrierType getUnsafeStoreBarrierType(UnsafeStoreNode store) {
        BarrierType barrierType = BarrierType.NONE;
        if (store.value().kind() == Kind.Object) {
            ResolvedJavaType type = ObjectStamp.typeOrNull(store.object());
            if (type != null && !type.isArray()) {
                barrierType = BarrierType.IMPRECISE;
            } else {
                barrierType = BarrierType.PRECISE;
            }
        }
        return barrierType;
    }

    private static BarrierType getCompareAndSwapBarrier(CompareAndSwapNode cas) {
        BarrierType barrierType = BarrierType.NONE;
        if (cas.expected().kind() == Kind.Object) {
            ResolvedJavaType type = ObjectStamp.typeOrNull(cas.object());
            if (type != null && !type.isArray()) {
                barrierType = BarrierType.IMPRECISE;
            } else {
                barrierType = BarrierType.PRECISE;
            }
        }
        return barrierType;
    }

    protected static ConstantLocationNode createFieldLocation(StructuredGraph graph, HotSpotResolvedJavaField field, boolean initialization) {
        LocationIdentity loc = initialization ? INIT_LOCATION : field;
        return ConstantLocationNode.create(loc, field.getKind(), field.offset(), graph);
    }

    public int getScalingFactor(Kind kind) {
        if (useCompressedOops() && kind == Kind.Object) {
            return this.runtime.getTarget().arch.getSizeInBytes(Kind.Int);
        } else {
            return this.runtime.getTarget().arch.getSizeInBytes(kind);
        }
    }

    protected IndexedLocationNode createArrayLocation(Graph graph, Kind elementKind, ValueNode index, boolean initialization) {
        LocationIdentity loc = initialization ? INIT_LOCATION : NamedLocationIdentity.getArrayLocation(elementKind);
        int scale = getScalingFactor(elementKind);
        return IndexedLocationNode.create(loc, elementKind, getArrayBaseOffset(elementKind), index, graph, scale);
    }

    @Override
    public ValueNode reconstructArrayIndex(LocationNode location) {
        Kind elementKind = location.getValueKind();
        assert location.getLocationIdentity().equals(NamedLocationIdentity.getArrayLocation(elementKind));

        long base;
        ValueNode index;
        int scale = getScalingFactor(elementKind);

        if (location instanceof ConstantLocationNode) {
            base = ((ConstantLocationNode) location).getDisplacement();
            index = null;
        } else if (location instanceof IndexedLocationNode) {
            IndexedLocationNode indexedLocation = (IndexedLocationNode) location;
            assert indexedLocation.getIndexScaling() == scale;
            base = indexedLocation.getDisplacement();
            index = indexedLocation.getIndex();
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }

        base -= getArrayBaseOffset(elementKind);
        assert base >= 0 && base % scale == 0;

        base /= scale;
        assert NumUtil.isInt(base);

        StructuredGraph graph = location.graph();
        if (index == null) {
            return ConstantNode.forInt((int) base, graph);
        } else {
            if (base == 0) {
                return index;
            } else {
                return IntegerArithmeticNode.add(graph, ConstantNode.forInt((int) base, graph), index);
            }
        }
    }

    private GuardingNode createBoundsCheck(AccessIndexedNode n, LoweringTool tool) {
        StructuredGraph g = n.graph();
        ValueNode array = n.array();
        ValueNode arrayLength = readArrayLength(n.graph(), array, tool.getConstantReflection());
        if (arrayLength == null) {
            Stamp stamp = StampFactory.positiveInt();
            ReadNode readArrayLength = g.add(new ReadNode(array, ConstantLocationNode.create(FINAL_LOCATION, Kind.Int, runtime.getConfig().arrayLengthOffset, g), stamp, BarrierType.NONE, false));
            g.addBeforeFixed(n, readArrayLength);
            readArrayLength.setGuard(createNullCheck(array, readArrayLength, tool));
            arrayLength = readArrayLength;
        }

        return tool.createGuard(n, g.unique(new IntegerBelowThanNode(n.index(), arrayLength)), BoundsCheckException, InvalidateReprofile);
    }

    private static GuardingNode createNullCheck(ValueNode object, FixedNode before, LoweringTool tool) {
        if (ObjectStamp.isObjectNonNull(object)) {
            return null;
        }
        return tool.createGuard(before, before.graph().unique(new IsNullNode(object)), DeoptimizationReason.NullCheckException, DeoptimizationAction.InvalidateReprofile, true);
    }

}
