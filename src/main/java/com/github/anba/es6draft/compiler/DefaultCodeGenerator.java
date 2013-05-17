/**
 * Copyright (c) 2012-2013 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.compiler;

import static com.github.anba.es6draft.semantics.StaticSemantics.ConstructorMethod;
import static com.github.anba.es6draft.semantics.StaticSemantics.PrototypeMethodDefinitions;
import static com.github.anba.es6draft.semantics.StaticSemantics.StaticMethodDefinitions;

import java.util.List;

import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

import com.github.anba.es6draft.ast.AssignmentPattern;
import com.github.anba.es6draft.ast.Binding;
import com.github.anba.es6draft.ast.CallExpression;
import com.github.anba.es6draft.ast.ClassDefinition;
import com.github.anba.es6draft.ast.CommaExpression;
import com.github.anba.es6draft.ast.ConditionalExpression;
import com.github.anba.es6draft.ast.DefaultNodeVisitor;
import com.github.anba.es6draft.ast.Expression;
import com.github.anba.es6draft.ast.MethodDefinition;
import com.github.anba.es6draft.compiler.InstructionVisitor.FieldDesc;
import com.github.anba.es6draft.compiler.InstructionVisitor.FieldType;
import com.github.anba.es6draft.compiler.InstructionVisitor.MethodDesc;
import com.github.anba.es6draft.compiler.InstructionVisitor.MethodType;

/**
 *
 */
abstract class DefaultCodeGenerator<R, V extends ExpressionVisitor> extends
        DefaultNodeVisitor<R, V> {
    private static class Fields {
        static final FieldDesc Double_NaN = FieldDesc.create(FieldType.Static, Types.Double, "NaN",
                Type.DOUBLE_TYPE);
    }

    private static class Methods {
        // class: AbstractOperations
        static final MethodDesc AbstractOperations_ToPrimitive = MethodDesc
                .create(MethodType.Static, Types.AbstractOperations, "ToPrimitive", Type
                        .getMethodType(Types.Object, Types.ExecutionContext, Types.Object,
                                Types._Type));

        static final MethodDesc AbstractOperations_ToBoolean = MethodDesc.create(MethodType.Static,
                Types.AbstractOperations, "ToBoolean",
                Type.getMethodType(Type.BOOLEAN_TYPE, Types.Object));

        static final MethodDesc AbstractOperations_ToBoolean_double = MethodDesc.create(
                MethodType.Static, Types.AbstractOperations, "ToBoolean",
                Type.getMethodType(Type.BOOLEAN_TYPE, Type.DOUBLE_TYPE));

        static final MethodDesc AbstractOperations_ToFlatString = MethodDesc.create(
                MethodType.Static, Types.AbstractOperations, "ToFlatString",
                Type.getMethodType(Types.String, Types.ExecutionContext, Types.Object));

        static final MethodDesc AbstractOperations_ToNumber = MethodDesc.create(MethodType.Static,
                Types.AbstractOperations, "ToNumber",
                Type.getMethodType(Type.DOUBLE_TYPE, Types.ExecutionContext, Types.Object));

        static final MethodDesc AbstractOperations_ToNumber_CharSequence = MethodDesc.create(
                MethodType.Static, Types.AbstractOperations, "ToNumber",
                Type.getMethodType(Type.DOUBLE_TYPE, Types.CharSequence));

        static final MethodDesc AbstractOperations_ToInt32 = MethodDesc.create(MethodType.Static,
                Types.AbstractOperations, "ToInt32",
                Type.getMethodType(Type.INT_TYPE, Types.ExecutionContext, Types.Object));

        static final MethodDesc AbstractOperations_ToInt32_double = MethodDesc.create(
                MethodType.Static, Types.AbstractOperations, "ToInt32",
                Type.getMethodType(Type.INT_TYPE, Type.DOUBLE_TYPE));

        static final MethodDesc AbstractOperations_ToUint32 = MethodDesc.create(MethodType.Static,
                Types.AbstractOperations, "ToUint32",
                Type.getMethodType(Type.LONG_TYPE, Types.ExecutionContext, Types.Object));

        static final MethodDesc AbstractOperations_ToUint32_double = MethodDesc.create(
                MethodType.Static, Types.AbstractOperations, "ToUint32",
                Type.getMethodType(Type.LONG_TYPE, Type.DOUBLE_TYPE));

        static final MethodDesc AbstractOperations_ToObject = MethodDesc.create(MethodType.Static,
                Types.AbstractOperations, "ToObject",
                Type.getMethodType(Types.ScriptObject, Types.ExecutionContext, Types.Object));

        static final MethodDesc AbstractOperations_ToString = MethodDesc.create(MethodType.Static,
                Types.AbstractOperations, "ToString",
                Type.getMethodType(Types.CharSequence, Types.ExecutionContext, Types.Object));

        static final MethodDesc AbstractOperations_ToString_double = MethodDesc.create(
                MethodType.Static, Types.AbstractOperations, "ToString",
                Type.getMethodType(Types.String, Type.DOUBLE_TYPE));

        // class: Boolean
        static final MethodDesc Boolean_toString = MethodDesc.create(MethodType.Static,
                Types.Boolean, "toString", Type.getMethodType(Types.String, Type.BOOLEAN_TYPE));

        // class: CharSequence
        static final MethodDesc CharSequence_length = MethodDesc.create(MethodType.Interface,
                Types.CharSequence, "length", Type.getMethodType(Type.INT_TYPE));

        // class: EnvironmentRecord
        static final MethodDesc EnvironmentRecord_createImmutableBinding = MethodDesc.create(
                MethodType.Interface, Types.EnvironmentRecord, "createImmutableBinding",
                Type.getMethodType(Type.VOID_TYPE, Types.String));

        static final MethodDesc EnvironmentRecord_initialiseBinding = MethodDesc.create(
                MethodType.Interface, Types.EnvironmentRecord, "initialiseBinding",
                Type.getMethodType(Type.VOID_TYPE, Types.String, Types.Object));

        // class: ExecutionContext
        static final MethodDesc ExecutionContext_getLexicalEnvironment = MethodDesc.create(
                MethodType.Virtual, Types.ExecutionContext, "getLexicalEnvironment",
                Type.getMethodType(Types.LexicalEnvironment));

        static final MethodDesc ExecutionContext_pushLexicalEnvironment = MethodDesc.create(
                MethodType.Virtual, Types.ExecutionContext, "pushLexicalEnvironment",
                Type.getMethodType(Type.VOID_TYPE, Types.LexicalEnvironment));

        static final MethodDesc ExecutionContext_popLexicalEnvironment = MethodDesc.create(
                MethodType.Virtual, Types.ExecutionContext, "popLexicalEnvironment",
                Type.getMethodType(Type.VOID_TYPE));

        // class: LexicalEnvironment
        static final MethodDesc LexicalEnvironment_getEnvRec = MethodDesc.create(
                MethodType.Virtual, Types.LexicalEnvironment, "getEnvRec",
                Type.getMethodType(Types.EnvironmentRecord));

        static final MethodDesc LexicalEnvironment_newDeclarativeEnvironment = MethodDesc.create(
                MethodType.Static, Types.LexicalEnvironment, "newDeclarativeEnvironment",
                Type.getMethodType(Types.LexicalEnvironment, Types.LexicalEnvironment));

        static final MethodDesc LexicalEnvironment_newObjectEnvironment = MethodDesc.create(
                MethodType.Static, Types.LexicalEnvironment, "newObjectEnvironment", Type
                        .getMethodType(Types.LexicalEnvironment, Types.ScriptObject,
                                Types.LexicalEnvironment, Type.BOOLEAN_TYPE));

        // class: Reference
        static final MethodDesc Reference_GetValue = MethodDesc.create(MethodType.Static,
                Types.Reference, "GetValue",
                Type.getMethodType(Types.Object, Types.Object, Types.ExecutionContext));

        static final MethodDesc Reference_PutValue = MethodDesc.create(MethodType.Static,
                Types.Reference, "PutValue", Type.getMethodType(Type.VOID_TYPE, Types.Object,
                        Types.Object, Types.ExecutionContext));

        // class: ScriptRuntime
        static final MethodDesc ScriptRuntime_CreateDefaultConstructor = MethodDesc.create(
                MethodType.Static, Types.ScriptRuntime, "CreateDefaultConstructor",
                Type.getMethodType(Types.RuntimeInfo$Function));

        static final MethodDesc ScriptRuntime_EvaluateConstructorMethod = MethodDesc.create(
                MethodType.Static, Types.ScriptRuntime, "EvaluateConstructorMethod", Type
                        .getMethodType(Types.OrdinaryFunction, Types.ScriptObject,
                                Types.ScriptObject, Types.RuntimeInfo$Function,
                                Types.ExecutionContext));

        static final MethodDesc ScriptRuntime_getClassProto = MethodDesc.create(MethodType.Static,
                Types.ScriptRuntime, "getClassProto",
                Type.getMethodType(Types.ScriptObject_, Types.Object, Types.ExecutionContext));

        static final MethodDesc ScriptRuntime_getDefaultClassProto = MethodDesc.create(
                MethodType.Static, Types.ScriptRuntime, "getDefaultClassProto",
                Type.getMethodType(Types.ScriptObject_, Types.ExecutionContext));

        // class: Type
        static final MethodDesc Type_isUndefinedOrNull = MethodDesc.create(MethodType.Static,
                Types._Type, "isUndefinedOrNull",
                Type.getMethodType(Type.BOOLEAN_TYPE, Types.Object));
    }

    protected final CodeGenerator codegen;

    protected DefaultCodeGenerator(CodeGenerator codegen) {
        this.codegen = codegen;
    }

    protected static final void tailCall(Expression expr, ExpressionVisitor mv) {
        while (expr instanceof CommaExpression) {
            List<Expression> list = ((CommaExpression) expr).getOperands();
            expr = list.get(list.size() - 1);
        }
        if (expr instanceof ConditionalExpression) {
            tailCall(((ConditionalExpression) expr).getThen(), mv);
            tailCall(((ConditionalExpression) expr).getOtherwise(), mv);
        } else if (expr instanceof CallExpression) {
            mv.setTailCall((CallExpression) expr);
        }
    }

    /**
     * stack: [] -> [lexEnv]
     */
    protected final void getLexicalEnvironment(ExpressionVisitor mv) {
        mv.loadExecutionContext();
        mv.invoke(Methods.ExecutionContext_getLexicalEnvironment);
    }

    /**
     * stack: [] -> [envRec]
     */
    protected final void getEnvironmentRecord(ExpressionVisitor mv) {
        mv.loadExecutionContext();
        mv.invoke(Methods.ExecutionContext_getLexicalEnvironment);
        mv.invoke(Methods.LexicalEnvironment_getEnvRec);
    }

    /**
     * stack: [obj] -> [lexEnv]
     */
    protected final void newObjectEnvironment(ExpressionVisitor mv, boolean withEnvironment) {
        mv.loadExecutionContext();
        mv.invoke(Methods.ExecutionContext_getLexicalEnvironment);
        mv.iconst(withEnvironment);
        mv.invoke(Methods.LexicalEnvironment_newObjectEnvironment);
    }

    /**
     * stack: [] -> [lexEnv]
     */
    protected final void newDeclarativeEnvironment(ExpressionVisitor mv) {
        mv.loadExecutionContext();
        mv.invoke(Methods.ExecutionContext_getLexicalEnvironment);
        mv.invoke(Methods.LexicalEnvironment_newDeclarativeEnvironment);
    }

    /**
     * stack: [lexEnv] -> []
     */
    protected final void pushLexicalEnvironment(ExpressionVisitor mv) {
        mv.loadExecutionContext();
        mv.swap();
        mv.invoke(Methods.ExecutionContext_pushLexicalEnvironment);
    }

    /**
     * stack: [] -> []
     */
    protected final void popLexicalEnvironment(ExpressionVisitor mv) {
        mv.loadExecutionContext();
        mv.invoke(Methods.ExecutionContext_popLexicalEnvironment);
    }

    /**
     * Calls <code>GetValue(o)</code> if the expression could possibly be a reference
     */
    protected final void invokeGetValue(Expression node, ExpressionVisitor mv) {
        if (node.accept(IsReference.INSTANCE, null)) {
            GetValue(mv);
        }
    }

    /**
     * stack: [object] -> [boolean]
     */
    protected final void isUndefinedOrNull(ExpressionVisitor mv) {
        mv.invoke(Methods.Type_isUndefinedOrNull);
    }

    enum ValType {
        Undefined, Null, Boolean, Number, Number_int, Number_uint, String, Object, Reference, Any;

        public int size() {
            switch (this) {
            case Number:
            case Number_uint:
                return 2;
            case Number_int:
            case Undefined:
            case Null:
            case Boolean:
            case String:
            case Object:
            case Reference:
            case Any:
            default:
                return 1;
            }
        }

        public boolean isNumeric() {
            switch (this) {
            case Number:
            case Number_int:
            case Number_uint:
                return true;
            case Undefined:
            case Null:
            case Boolean:
            case String:
            case Object:
            case Reference:
            case Any:
            default:
                return false;
            }
        }

        public boolean isPrimitive() {
            switch (this) {
            case Undefined:
            case Null:
            case Boolean:
            case Number:
            case Number_int:
            case Number_uint:
            case String:
                return true;
            case Object:
            case Reference:
            case Any:
            default:
                return false;
            }
        }
    }

    /**
     * stack: [Object] -> [Object]
     */
    protected final void GetValue(ExpressionVisitor mv) {
        mv.loadExecutionContext();
        mv.invoke(Methods.Reference_GetValue);
    }

    /**
     * stack: [Object, Object] -> []
     */
    protected final void PutValue(ExpressionVisitor mv) {
        mv.loadExecutionContext();
        mv.invoke(Methods.Reference_PutValue);
    }

    /**
     * stack: [Object] -> [boolean]
     */
    protected final ValType ToPrimitive(ValType from,
            com.github.anba.es6draft.runtime.types.Type preferredType, ExpressionVisitor mv) {
        switch (from) {
        case Number:
        case Number_int:
        case Number_uint:
        case Undefined:
        case Null:
        case Boolean:
        case String:
            return from;
        case Object:
        case Any:
        default:
            mv.loadExecutionContext();
            mv.swap();
            assert preferredType == null : "NYI"; // TODO: load enum value?
            mv.aconst(null);
            mv.invoke(Methods.AbstractOperations_ToPrimitive);
            return ValType.Any;
        }
    }

    /**
     * stack: [Object] -> [boolean]
     */
    protected final void ToBoolean(ValType from, ExpressionVisitor mv) {
        switch (from) {
        case Number:
            mv.invoke(Methods.AbstractOperations_ToBoolean_double);
            return;
        case Number_int:
            mv.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);
            mv.invoke(Methods.AbstractOperations_ToBoolean_double);
            return;
        case Number_uint:
            mv.cast(Type.LONG_TYPE, Type.DOUBLE_TYPE);
            mv.invoke(Methods.AbstractOperations_ToBoolean_double);
            return;
        case Undefined:
        case Null:
            mv.pop();
            mv.iconst(false);
            return;
        case Boolean:
            return;
        case String: {
            Label l0 = new Label(), l1 = new Label();
            mv.invoke(Methods.CharSequence_length);
            mv.ifeq(l0);
            mv.iconst(true);
            mv.goTo(l1);
            mv.mark(l0);
            mv.iconst(false);
            mv.mark(l1);
            return;
        }
        case Object:
        case Any:
        default:
            mv.invoke(Methods.AbstractOperations_ToBoolean);
            return;
        }
    }

    /**
     * stack: [Object] -> [double]
     */
    protected final void ToNumber(ValType from, ExpressionVisitor mv) {
        switch (from) {
        case Number:
            return;
        case Number_int:
            mv.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);
            return;
        case Number_uint:
            mv.cast(Type.LONG_TYPE, Type.DOUBLE_TYPE);
            return;
        case Undefined:
            mv.pop();
            mv.get(Fields.Double_NaN);
            return;
        case Null:
            mv.pop();
            mv.dconst(0);
            return;
        case Boolean:
            mv.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);
            return;
        case String:
            mv.invoke(Methods.AbstractOperations_ToNumber_CharSequence);
            return;
        case Object:
        case Any:
        default:
            mv.loadExecutionContext();
            mv.swap();
            mv.invoke(Methods.AbstractOperations_ToNumber);
            return;
        }
    }

    /**
     * stack: [Object] -> [int]
     */
    protected final void ToInt32(ValType from, ExpressionVisitor mv) {
        switch (from) {
        case Number:
            mv.invoke(Methods.AbstractOperations_ToInt32_double);
            return;
        case Number_int:
            return;
        case Number_uint:
            mv.cast(Type.LONG_TYPE, Type.INT_TYPE);
            return;
        case Undefined:
        case Null:
            mv.pop();
            mv.iconst(0);
            return;
        case Boolean:
            return;
        case String:
            mv.invoke(Methods.AbstractOperations_ToNumber_CharSequence);
            mv.invoke(Methods.AbstractOperations_ToInt32_double);
            return;
        case Object:
        case Any:
        default:
            mv.loadExecutionContext();
            mv.swap();
            mv.invoke(Methods.AbstractOperations_ToInt32);
            return;
        }
    }

    /**
     * stack: [Object] -> [long]
     */
    protected final void ToUint32(ValType from, ExpressionVisitor mv) {
        switch (from) {
        case Number:
            mv.invoke(Methods.AbstractOperations_ToUint32_double);
            return;
        case Number_int:
            mv.cast(Type.INT_TYPE, Type.LONG_TYPE);
            return;
        case Number_uint:
            return;
        case Undefined:
        case Null:
            mv.pop();
            mv.lconst(0);
            return;
        case Boolean:
            mv.cast(Type.INT_TYPE, Type.LONG_TYPE);
            return;
        case String:
            mv.invoke(Methods.AbstractOperations_ToNumber_CharSequence);
            mv.invoke(Methods.AbstractOperations_ToUint32_double);
            return;
        case Object:
        case Any:
        default:
            mv.loadExecutionContext();
            mv.swap();
            mv.invoke(Methods.AbstractOperations_ToUint32);
            return;
        }
    }

    /**
     * stack: [Object] -> [CharSequence]
     */
    protected final void ToString(ValType from, ExpressionVisitor mv) {
        switch (from) {
        case Number:
            mv.invoke(Methods.AbstractOperations_ToString_double);
            return;
        case Number_int:
            mv.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);
            mv.invoke(Methods.AbstractOperations_ToString_double);
            return;
        case Number_uint:
            mv.cast(Type.LONG_TYPE, Type.DOUBLE_TYPE);
            mv.invoke(Methods.AbstractOperations_ToString_double);
            return;
        case Undefined:
            mv.pop();
            mv.aconst("undefined");
            return;
        case Null:
            mv.pop();
            mv.aconst("null");
            return;
        case Boolean:
            mv.invoke(Methods.Boolean_toString);
            return;
        case String:
            return;
        case Object:
        case Any:
        default:
            mv.loadExecutionContext();
            mv.swap();
            mv.invoke(Methods.AbstractOperations_ToString);
            return;
        }
    }

    /**
     * stack: [Object] -> [String]
     */
    protected final void ToFlatString(ValType from, ExpressionVisitor mv) {
        switch (from) {
        case Number:
            mv.invoke(Methods.AbstractOperations_ToString_double);
            return;
        case Number_int:
            mv.cast(Type.INT_TYPE, Type.DOUBLE_TYPE);
            mv.invoke(Methods.AbstractOperations_ToString_double);
            return;
        case Number_uint:
            mv.cast(Type.LONG_TYPE, Type.DOUBLE_TYPE);
            mv.invoke(Methods.AbstractOperations_ToString_double);
            return;
        case Undefined:
            mv.pop();
            mv.aconst("undefined");
            return;
        case Null:
            mv.pop();
            mv.aconst("null");
            return;
        case Boolean:
            mv.invoke(Methods.Boolean_toString);
            return;
        case String:
            return;
        case Object:
        case Any:
        default:
            mv.loadExecutionContext();
            mv.swap();
            mv.invoke(Methods.AbstractOperations_ToFlatString);
            return;
        }
    }

    /**
     * stack: [Object] -> [ScriptObject]
     */
    protected final void ToObject(ValType from, ExpressionVisitor mv) {
        switch (from) {
        case Number:
        case Number_int:
        case Number_uint:
        case Boolean:
            mv.toBoxed(from);
            break;
        case Undefined:
        case Null:
        case String:
        case Object:
        case Any:
        default:
            break;
        }

        mv.loadExecutionContext();
        mv.swap();
        mv.invoke(Methods.AbstractOperations_ToObject);
    }

    protected void BindingInitialisation(Binding node, ExpressionVisitor mv) {
        new BindingInitialisationGenerator(codegen).generate(node, mv);
    }

    /**
     * stack: [envRec, value] -> []
     */
    protected void BindingInitialisationWithEnvironment(Binding node, ExpressionVisitor mv) {
        new BindingInitialisationGenerator(codegen).generateWithEnvironment(node, mv);
    }

    /**
     * stack: [value] -> []
     */
    protected void DestructuringAssignment(AssignmentPattern node, ExpressionVisitor mv) {
        new DestructuringAssignmentGenerator(codegen).generate(node, mv);
    }

    protected void ClassDefinitionEvaluation(ClassDefinition def, String className,
            ExpressionVisitor mv) {
        // stack: [] -> [<proto,ctor>]
        if (def.getHeritage() == null) {
            mv.loadExecutionContext();
            mv.invoke(Methods.ScriptRuntime_getDefaultClassProto);
        } else {
            // FIXME: spec bug (ClassHeritage runtime evaluation not defined) (Bug 1416)
            ValType type = codegen.expression(def.getHeritage(), mv);
            mv.toBoxed(type);
            invokeGetValue(def.getHeritage(), mv);
            mv.loadExecutionContext();
            mv.invoke(Methods.ScriptRuntime_getClassProto);
        }

        // stack: [<proto,ctor>] -> [ctor, proto]
        mv.dup();
        mv.iconst(1);
        mv.aload(Types.ScriptObject_);
        mv.swap();
        mv.iconst(0);
        mv.aload(Types.ScriptObject_);

        // steps 4-5
        if (className != null) {
            // stack: [ctor, proto] -> [ctor, proto, scope]
            // TODO: make explicit...
            // implicit: mv.enterScope(def)
            newDeclarativeEnvironment(mv);

            // stack: [ctor, proto, scope] -> [ctor, proto, scope, proto, scope]
            mv.dup2();

            // stack: [ctor, proto, scope, proto, scope] -> [ctor, proto, scope, proto, envRec]
            mv.invoke(Methods.LexicalEnvironment_getEnvRec);

            // stack: [ctor, proto, scope, proto, envRec] -> [ctor, proto, scope, proto, envRec]
            mv.dup();
            mv.aconst(className);
            mv.invoke(Methods.EnvironmentRecord_createImmutableBinding);

            // FIXME: spec bug - InitialiseBinding not called! (Bug 1416)
            // stack: [ctor, proto, scope, proto, envRec] -> [ctor, proto, scope]
            mv.swap();
            mv.aconst(className);
            mv.swap();
            mv.invoke(Methods.EnvironmentRecord_initialiseBinding);

            // stack: [ctor, proto, scope] -> [ctor, proto]
            pushLexicalEnvironment(mv);
        }

        // steps 6-12
        MethodDefinition constructor = ConstructorMethod(def);
        if (constructor != null) {
            codegen.compile(constructor);

            // Runtime Semantics: Evaluation -> MethodDefinition
            // stack: [ctor, proto] -> [proto, F]
            mv.dupX1();
            mv.invokestatic(codegen.getClassName(), codegen.methodName(constructor) + "_rti",
                    Type.getMethodDescriptor(Types.RuntimeInfo$Function));
            mv.loadExecutionContext();
            mv.invoke(Methods.ScriptRuntime_EvaluateConstructorMethod);
        } else {
            // default constructor
            // stack: [ctor, proto] -> [proto, F]
            mv.dupX1();
            mv.invoke(Methods.ScriptRuntime_CreateDefaultConstructor);
            mv.loadExecutionContext();
            mv.invoke(Methods.ScriptRuntime_EvaluateConstructorMethod);
        }

        // stack: [proto, F] -> [F, proto]
        mv.swap();

        // steps 13-14
        List<MethodDefinition> protoMethods = PrototypeMethodDefinitions(def);
        for (MethodDefinition method : protoMethods) {
            if (method == constructor) {
                // FIXME: spec bug? (not handled in draft) (Bug 1416)
                continue;
            }
            mv.dup();
            codegen.propertyDefinition(method, mv);
        }

        // stack: [F, proto] -> [F]
        mv.pop();

        // steps 15-16
        List<MethodDefinition> staticMethods = StaticMethodDefinitions(def);
        for (MethodDefinition method : staticMethods) {
            mv.dup();
            codegen.propertyDefinition(method, mv);
        }

        // step 17
        if (className != null) {
            // restore previous lexical environment
            popLexicalEnvironment(mv);
            // implicit: mv.exitScope()
        }
    }
}
