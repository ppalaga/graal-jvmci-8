/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package jdk.vm.ci.hotspot;

import static jdk.vm.ci.hotspot.HotSpotJVMCIRuntime.runtime;
import static jdk.vm.ci.hotspot.HotSpotVMConfig.config;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Predicate;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog;

/**
 * Reflection interface for reflecting on the internals of HotSpot JVMCI types and objects.
 */
abstract class HotSpotJVMCIReflection {

    abstract boolean isInstance(HotSpotResolvedObjectTypeImpl holder, HotSpotObjectConstantImpl obj);

    abstract boolean isAssignableFrom(HotSpotResolvedObjectTypeImpl holder, HotSpotResolvedObjectTypeImpl otherType);

    abstract Annotation[] getAnnotations(HotSpotResolvedObjectTypeImpl holder);

    abstract Annotation[] getDeclaredAnnotations(HotSpotResolvedObjectTypeImpl holder);

    abstract <T extends Annotation> T getAnnotation(HotSpotResolvedObjectTypeImpl holder, Class<T> annotationClass);

    abstract boolean isLocalClass(HotSpotResolvedObjectTypeImpl holder);

    abstract boolean isMemberClass(HotSpotResolvedObjectTypeImpl holder);

    abstract HotSpotResolvedObjectType getEnclosingClass(HotSpotResolvedObjectTypeImpl holder);

    abstract JavaConstant readFieldValue(HotSpotResolvedObjectTypeImpl holder, HotSpotResolvedJavaField field, boolean isVolatile);

    abstract JavaConstant readFieldValue(HotSpotObjectConstantImpl object, HotSpotResolvedJavaField field, boolean isVolatile);

    abstract Map<Long, SpeculationLog> getSpeculationLogs(HotSpotResolvedObjectTypeImpl holder);

    abstract boolean equals(HotSpotObjectConstantImpl hotSpotResolvedJavaType, HotSpotObjectConstantImpl that);

    abstract JavaConstant getJavaMirror(HotSpotResolvedPrimitiveType hotSpotResolvedJavaType);

    abstract ResolvedJavaMethod.Parameter[] getParameters(HotSpotResolvedJavaMethodImpl javaMethod);

    abstract Annotation[][] getParameterAnnotations(HotSpotResolvedJavaMethodImpl javaMethod);

    abstract Type[] getGenericParameterTypes(HotSpotResolvedJavaMethodImpl javaMethod);

    abstract Annotation[] getFieldAnnotations(HotSpotResolvedJavaFieldImpl javaMethod);

    abstract Annotation[] getMethodAnnotations(HotSpotResolvedJavaMethodImpl javaField);

    abstract Annotation[] getMethodDeclaredAnnotations(HotSpotResolvedJavaMethodImpl javaMethod);

    abstract Annotation[] getFieldDeclaredAnnotations(HotSpotResolvedJavaFieldImpl javaMethod);

    abstract <T extends Annotation> T getMethodAnnotation(HotSpotResolvedJavaMethodImpl javaMethod, Class<T> annotationClass);

    abstract HotSpotResolvedObjectTypeImpl getType(HotSpotObjectConstantImpl object);

    abstract String asString(HotSpotObjectConstantImpl object);

    /**
     * Given a {@link java.lang.Class} instance, return the corresponding ResolvedJavaType.
     */
    abstract ResolvedJavaType asJavaType(HotSpotObjectConstantImpl object);

    abstract <T> T asObject(HotSpotObjectConstantImpl object, Class<T> type);

    abstract Object asObject(HotSpotObjectConstantImpl object, HotSpotResolvedJavaType type);

    abstract String formatString(HotSpotObjectConstantImpl object);

    abstract Integer getLength(HotSpotObjectConstantImpl arrayObject);

    abstract JavaConstant readArrayElement(HotSpotObjectConstantImpl arrayObject, int index);

    abstract JavaConstant unboxPrimitive(HotSpotObjectConstantImpl source);

    abstract JavaConstant forObject(Object value);

    abstract JavaConstant boxPrimitive(JavaConstant source);

    abstract int getInt(HotSpotObjectConstantImpl object, long displacement);

    abstract byte getByte(HotSpotObjectConstantImpl object, long displacement);

    abstract short getShort(HotSpotObjectConstantImpl object, long displacement);

    abstract long getLong(HotSpotObjectConstantImpl object, long displacement);

    abstract void checkRead(HotSpotObjectConstantImpl constant, JavaKind kind, long displacement, HotSpotResolvedObjectType type);

    abstract <T extends Annotation> T getFieldAnnotation(HotSpotResolvedJavaFieldImpl javaField, Class<T> annotationClass);

    /**
     * @see HotSpotJVMCIRuntime#getIntrinsificationTrustPredicate(Class...)
     */
    abstract Predicate<ResolvedJavaType> getIntrinsificationTrustPredicate(Class<?>... compilerLeafClasses);

    /**
     * Resolves {@code objectHandle} to a raw object if possible.
     *
     * @throws HotSpotJVMCIUnsupportedOperationError if {@code objectHandle} refers to an object in
     *             another heap
     */
    abstract Object resolveObject(HotSpotObjectConstantImpl objectHandle);

    Class<?> getMirror(HotSpotResolvedObjectTypeImpl holder) {
        long oopAddress = holder.getMetaspaceKlass() + config().javaMirrorOffset;
        return (Class<?>) runtime().compilerToVm.getObjectAtAddress(oopAddress);
    }

    Class<?> getMirror(HotSpotResolvedJavaType type) {
        assert type != null;
        if (type instanceof HotSpotResolvedPrimitiveType) {
            return (Class<?>) resolveObject(((HotSpotResolvedPrimitiveType) type).mirror);
        } else {
            return getMirror((HotSpotResolvedObjectTypeImpl) type);
        }
    }
}
