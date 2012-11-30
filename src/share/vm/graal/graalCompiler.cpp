/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "memory/oopFactory.hpp"
#include "runtime/javaCalls.hpp"
#include "graal/graalCompiler.hpp"
#include "graal/graalJavaAccess.hpp"
#include "graal/graalVMToCompiler.hpp"
#include "graal/graalCompilerToVM.hpp"
#include "graal/graalVmIds.hpp"
#include "graal/graalEnv.hpp"
#include "c1/c1_Runtime1.hpp"
#include "runtime/arguments.hpp"
#include "runtime/compilationPolicy.hpp"

GraalCompiler* GraalCompiler::_instance = NULL;

GraalCompiler::GraalCompiler() {
  _initialized = false;
  assert(_instance == NULL, "only one instance allowed");
  _instance = this;
}

// Initialization
void GraalCompiler::initialize() {
  
  ThreadToNativeFromVM trans(JavaThread::current());
  JavaThread* THREAD = JavaThread::current();
  TRACE_graal_1("GraalCompiler::initialize");

  unsigned long heap_end = (long) Universe::heap()->reserved_region().end();
  unsigned long allocation_end = heap_end + 16l * 1024 * 1024 * 1024;
  guarantee(heap_end < allocation_end, "heap end too close to end of address space (might lead to erroneous TLAB allocations)");
  NOT_LP64(error("check TLAB allocation code for address space conflicts"));

  _deopted_leaf_graph_count = 0;

  initialize_buffer_blob();
  Runtime1::initialize(THREAD->get_buffer_blob());

  JNIEnv *env = ((JavaThread *) Thread::current())->jni_environment();
  jclass klass = env->FindClass("com/oracle/graal/hotspot/bridge/CompilerToVMImpl");
  if (klass == NULL) {
    tty->print_cr("graal CompilerToVMImpl class not found");
    vm_abort(false);
  }
  env->RegisterNatives(klass, CompilerToVM_methods, CompilerToVM_methods_count());

  ResourceMark rm;
  HandleMark hm;
  {
    VM_ENTRY_MARK;
    check_pending_exception("Could not register natives");
  }

  graal_compute_offsets();

  {
    VM_ENTRY_MARK;
    HandleMark hm;
    VMToCompiler::setDefaultOptions();
    for (int i = 0; i < Arguments::num_graal_args(); ++i) {
      const char* arg = Arguments::graal_args_array()[i];
      Handle option = java_lang_String::create_from_str(arg, THREAD);
      jboolean result = VMToCompiler::setOption(option);
      if (!result) {
        tty->print_cr("Invalid option for graal: -G:%s", arg);
        vm_abort(false);
      }
    }
    if (UseCompiler) {
      VMToCompiler::startCompiler();
      _initialized = true;
      if (BootstrapGraal) {
        VMToCompiler::bootstrap();
      }
    }
  }
}

void GraalCompiler::deopt_leaf_graph(jlong leaf_graph_id) {
  assert(leaf_graph_id != -1, "unexpected leaf graph id");

  if (_deopted_leaf_graph_count < LEAF_GRAPH_ARRAY_SIZE) {
    MutexLockerEx y(GraalDeoptLeafGraphIds_lock, Mutex::_no_safepoint_check_flag);
    if (_deopted_leaf_graph_count < LEAF_GRAPH_ARRAY_SIZE) {
      _deopted_leaf_graphs[_deopted_leaf_graph_count++] = leaf_graph_id;
    }
  }
}

oop GraalCompiler::dump_deopted_leaf_graphs(TRAPS) {
  if (_deopted_leaf_graph_count == 0) {
    return NULL;
  }
  jlong* elements;
  int length;
  {
    MutexLockerEx y(GraalDeoptLeafGraphIds_lock, Mutex::_no_safepoint_check_flag);
    if (_deopted_leaf_graph_count == 0) {
      return NULL;
    }
    if (_deopted_leaf_graph_count == LEAF_GRAPH_ARRAY_SIZE) {
      length = 0;
    } else {
      length = _deopted_leaf_graph_count;
    }
    elements = new jlong[length];
    for (int i = 0; i < length; i++) {
      elements[i] = _deopted_leaf_graphs[i];
    }
    _deopted_leaf_graph_count = 0;
  }
  typeArrayOop array = oopFactory::new_longArray(length, CHECK_NULL);
  for (int i = 0; i < length; i++) {
    array->long_at_put(i, elements[i]);
  }
  delete elements;
  return array;
}

void GraalCompiler::initialize_buffer_blob() {

  JavaThread* THREAD = JavaThread::current();
  if (THREAD->get_buffer_blob() == NULL) {
    // setup CodeBuffer.  Preallocate a BufferBlob of size
    // NMethodSizeLimit plus some extra space for constants.
    int code_buffer_size = Compilation::desired_max_code_buffer_size() +
      Compilation::desired_max_constant_size();
    BufferBlob* blob = BufferBlob::create("graal temporary CodeBuffer",
                                          code_buffer_size);
    guarantee(blob != NULL, "must create code buffer");
    THREAD->set_buffer_blob(blob);
  }
}

void GraalCompiler::compile_method(methodHandle method, int entry_bci, jboolean blocking) {
  EXCEPTION_CONTEXT
  if (!_initialized) {
    method->clear_queued_for_compilation();
    method->invocation_counter()->reset();
    method->backedge_counter()->reset();
    return;
  }

  assert(_initialized, "must already be initialized");
  ResourceMark rm;
  assert(JavaThread::current()->env() == NULL, "ciEnv should be null");
  JavaThread::current()->set_compiling(true);
  Handle holder = GraalCompiler::createHotSpotResolvedObjectType(method, CHECK);
  jboolean success = VMToCompiler::compileMethod(method(), holder, entry_bci, blocking, method->graal_priority());
  JavaThread::current()->set_compiling(false);
  if (success != JNI_TRUE) {
    method->clear_queued_for_compilation();
    CompilationPolicy::policy()->delay_compilation(method());
  }
}

// Compilation entry point for methods
void GraalCompiler::compile_method(ciEnv* env, ciMethod* target, int entry_bci) {
  ShouldNotReachHere();
}

void GraalCompiler::exit() {
  if (_initialized) {
    VMToCompiler::shutdownCompiler();
  }
}

// Print compilation timers and statistics
void GraalCompiler::print_timers() {
  TRACE_graal_1("GraalCompiler::print_timers");
}

Handle GraalCompiler::get_JavaType(Symbol* klass_name, TRAPS) {
   return VMToCompiler::createUnresolvedJavaType(VmIds::toString<Handle>(klass_name, THREAD), THREAD);
}

Handle GraalCompiler::get_JavaTypeFromSignature(Symbol* signature, KlassHandle loading_klass, TRAPS) {
  
  BasicType field_type = FieldType::basic_type(signature);
  // If the field is a pointer type, get the klass of the
  // field.
  if (field_type == T_OBJECT || field_type == T_ARRAY) {
    KlassHandle handle = GraalEnv::get_klass_by_name(loading_klass, signature, false);
    if (handle.is_null()) {
      return get_JavaType(signature, CHECK_NULL);
    } else {
      return get_JavaType(handle, CHECK_NULL);
    }
  } else {
    return VMToCompiler::createPrimitiveJavaType(field_type, CHECK_NULL);
  }
}

Handle GraalCompiler::get_JavaType(constantPoolHandle cp, int index, KlassHandle loading_klass, TRAPS) {
  bool is_accessible = false;

  KlassHandle klass = GraalEnv::get_klass_by_index(cp, index, is_accessible, loading_klass);
  oop catch_class = NULL;
  if (klass.is_null()) {
    Symbol* klass_name = NULL;
    {
      // We have to lock the cpool to keep the oop from being resolved
      // while we are accessing it. But we must release the lock before
      // calling up into Java.
      MonitorLockerEx ml(cp->lock());
      constantTag tag = cp->tag_at(index);
      if (tag.is_klass()) {
        // The klass has been inserted into the constant pool
        // very recently.
        return GraalCompiler::get_JavaType(cp->resolved_klass_at(index), CHECK_NULL);
      } else if (tag.is_symbol()) {
        klass_name = cp->symbol_at(index);
      } else {
        assert(cp->tag_at(index).is_unresolved_klass(), "wrong tag");
        klass_name = cp->unresolved_klass_at(index);
      }
    }
    return GraalCompiler::get_JavaType(klass_name, CHECK_NULL);
  } else {
    return GraalCompiler::get_JavaType(klass, CHECK_NULL);
  }
}

Handle GraalCompiler::get_JavaTypeFromClass(Handle java_class, TRAPS) {
  oop graal_mirror = java_lang_Class::graal_mirror(java_class());
  if (graal_mirror != NULL) {
    return graal_mirror;
  }

  if (java_lang_Class::is_primitive(java_class())) {
    BasicType basicType = java_lang_Class::primitive_type(java_class());
    return VMToCompiler::createPrimitiveJavaType((int) basicType, THREAD);
  } else {
    KlassHandle klass = java_lang_Class::as_Klass(java_class());
    Handle name = java_lang_String::create_from_symbol(klass->name(), CHECK_NULL);
    return GraalCompiler::createHotSpotResolvedObjectType(klass, name, CHECK_NULL);
  }
}

Handle GraalCompiler::get_JavaType(KlassHandle klass, TRAPS) {
  Handle name = VmIds::toString<Handle>(klass->name(), THREAD);
  return createHotSpotResolvedObjectType(klass, name, CHECK_NULL);
}

Handle GraalCompiler::get_JavaField(int offset, int flags, Symbol* field_name, Handle field_holder, Handle field_type, TRAPS) {
  Handle name = VmIds::toString<Handle>(field_name, CHECK_NULL);
  return VMToCompiler::createJavaField(field_holder, name, field_type, offset, flags, false, CHECK_NULL);
}

Handle GraalCompiler::createHotSpotResolvedObjectType(methodHandle method, TRAPS) {
  KlassHandle klass = method->method_holder();
  oop java_class = klass->java_mirror();
  oop graal_mirror = java_lang_Class::graal_mirror(java_class);
  if (graal_mirror != NULL) {
    assert(graal_mirror->is_a(HotSpotResolvedObjectType::klass()), "unexpected class...");
    return graal_mirror;
  }
  Handle name = java_lang_String::create_from_symbol(klass->name(), CHECK_NULL);
  return GraalCompiler::createHotSpotResolvedObjectType(klass, name, CHECK_NULL);
}

Handle GraalCompiler::createHotSpotResolvedObjectType(KlassHandle klass, Handle name, TRAPS) {
  oop java_class = klass->java_mirror();
  oop graal_mirror = java_lang_Class::graal_mirror(java_class);
  if (graal_mirror != NULL) {
    assert(graal_mirror->is_a(HotSpotResolvedObjectType::klass()), "unexpected class...");
    return graal_mirror;
  }

  Handle simpleName = name;
  if (klass->oop_is_instance()) {
    ResourceMark rm;
    InstanceKlass* ik = (InstanceKlass*) klass();
    name = java_lang_String::create_from_str(ik->signature_name(), CHECK_NULL);
  }

  // TODO replace this with the correct value
  bool hasFinalizableSubclass = false;

  int sizeOrSpecies;
  if (klass->is_interface()) {
    sizeOrSpecies = (int) 0x80000000; // see HotSpotResolvedObjectType.INTERFACE_SPECIES_VALUE
  } else if (klass->oop_is_array()) {
    sizeOrSpecies = (int) 0x7fffffff; // see HotSpotResolvedObjectType.ARRAY_SPECIES_VALUE
  } else {
    sizeOrSpecies = InstanceKlass::cast(klass())->size_helper() * HeapWordSize;
    if (!InstanceKlass::cast(klass())->can_be_fastpath_allocated()) {
      sizeOrSpecies = -sizeOrSpecies;
    }
  }

  return VMToCompiler::createResolvedJavaType(klass(), name, simpleName, java_class, hasFinalizableSubclass, sizeOrSpecies, CHECK_NULL);
}

BasicType GraalCompiler::kindToBasicType(jchar ch) {
  switch(ch) {
    case 'z': return T_BOOLEAN;
    case 'b': return T_BYTE;
    case 's': return T_SHORT;
    case 'c': return T_CHAR;
    case 'i': return T_INT;
    case 'f': return T_FLOAT;
    case 'j': return T_LONG;
    case 'd': return T_DOUBLE;
    case 'a': return T_OBJECT;
    case 'r': return T_ADDRESS;
    case '-': return T_ILLEGAL;
    default:
      fatal(err_msg("unexpected Kind: %c", ch));
      break;
  }
  return T_ILLEGAL;
}
