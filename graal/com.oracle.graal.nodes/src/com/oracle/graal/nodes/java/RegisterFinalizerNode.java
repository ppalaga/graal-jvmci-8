/*
 * Copyright (c) 2011, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.java;

import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * This node is used to perform the finalizer registration at the end of the java.lang.Object constructor.
 */
public final class RegisterFinalizerNode extends AbstractStateSplit implements StateSplit, Canonicalizable, LIRLowerable {

    @Input private ValueNode object;

    public ValueNode object() {
        return object;
    }

    public RegisterFinalizerNode(ValueNode object) {
        super(StampFactory.illegal());
        this.object = object;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        gen.emitCall(CiRuntimeCall.RegisterFinalizer, true, gen.operand(object()));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        RiResolvedType declaredType = object.declaredType();
        RiResolvedType exactType = object.exactType();
        if (exactType == null && declaredType != null) {
            exactType = declaredType.exactType();
        }

        boolean needsCheck = true;
        if (exactType != null) {
            // we have an exact type
            needsCheck = exactType.hasFinalizer();
        } else {
            // if either the declared type of receiver or the holder can be assumed to have no finalizers
            if (declaredType != null && !declaredType.hasFinalizableSubclass()) {
                if (tool.assumptions() != null && tool.assumptions().recordNoFinalizableSubclassAssumption(declaredType)) {
                    needsCheck = false;
                }
            }
        }

        if (!needsCheck) {
            return null;
        }

        return this;
    }
}
