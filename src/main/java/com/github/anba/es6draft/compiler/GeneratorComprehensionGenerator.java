/**
 * Copyright (c) 2012-2013 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.compiler;

import static com.github.anba.es6draft.semantics.StaticSemantics.BoundNames;

import java.util.Iterator;

import org.objectweb.asm.Label;

import com.github.anba.es6draft.ast.ComprehensionFor;
import com.github.anba.es6draft.ast.Expression;
import com.github.anba.es6draft.ast.GeneratorComprehension;
import com.github.anba.es6draft.ast.Node;
import com.github.anba.es6draft.compiler.DefaultCodeGenerator.ValType;
import com.github.anba.es6draft.compiler.MethodGenerator.Register;

/**
 * TODO: current draft [rev. 13] does not specify the runtime semantics for
 * generator-comprehensions, therefore the translation from
 * http://wiki.ecmascript.org/doku.php?id=harmony:generator_expressions is used
 */
final class GeneratorComprehensionGenerator extends DefaultCodeGenerator<ValType, MethodGenerator> {
    GeneratorComprehensionGenerator(CodeGenerator codegen) {
        super(codegen);
    }

    @Override
    protected ValType visit(Node node, MethodGenerator mv) {
        throw new IllegalStateException(String.format("node-class: %s", node.getClass()));
    }

    @Override
    public ValType visit(GeneratorComprehension node, MethodGenerator mv) {
        visitGeneratorComprehension(node, node.getList().iterator(), mv);
        return ValType.Object;
    }

    private ValType expression(Expression node, MethodGenerator mv) {
        return codegen.expression(node, mv);
    }

    private void visitGeneratorComprehension(GeneratorComprehension node, MethodGenerator mv) {
        Label l0 = null;
        if (node.getTest() != null) {
            l0 = new Label();
            ValType type = expression(node.getTest(), mv);
            invokeGetValue(node.getTest(), mv);
            ToBoolean(type, mv);
            mv.ifeq(l0);
        }

        ValType type = expression(node.getExpression(), mv);
        mv.toBoxed(type);
        invokeGetValue(node.getExpression(), mv);
        mv.load(Register.ExecutionContext);
        mv.lineInfo(node);
        mv.invoke(Methods.ScriptRuntime_yield);
        mv.pop();

        if (node.getTest() != null) {
            mv.mark(l0);
        }
    }

    private void visitGeneratorComprehension(GeneratorComprehension comprehension,
            Iterator<ComprehensionFor> iterator, MethodGenerator mv) {
        Label lblContinue = new Label(), lblBreak = new Label();
        Label loopstart = new Label();

        assert iterator.hasNext();
        ComprehensionFor comprehensionFor = iterator.next();

        expression(comprehensionFor.getExpression(), mv);
        invokeGetValue(comprehensionFor.getExpression(), mv);

        // FIXME: translation into for-of per
        // http://wiki.ecmascript.org/doku.php?id=harmony:generator_expressions means adding
        // additional isUndefinedOrNull() check, but Spidermonkey reports an error in this case!

        mv.dup();
        isUndefinedOrNull(mv);
        mv.ifeq(loopstart);
        mv.pop();
        mv.goTo(lblBreak);
        mv.mark(loopstart);
        mv.load(Register.Realm);
        mv.invoke(Methods.ScriptRuntime_iterate);

        int var = mv.newVariable(Types.Iterator);
        mv.store(var, Types.Iterator);

        mv.mark(lblContinue);
        mv.load(var, Types.Iterator);
        mv.invoke(Methods.Iterator_hasNext);
        mv.ifeq(lblBreak);
        mv.load(var, Types.Iterator);
        mv.invoke(Methods.Iterator_next);

        // FIXME: translation into for-of per
        // http://wiki.ecmascript.org/doku.php?id=harmony:generator_expressions means using a fresh
        // lexical/declarative environment for each inner loop, but Spidermonkey creates a single
        // environment for the whole generator comprehension

        // create new declarative lexical environment
        // stack: [nextValue] -> [nextValue, iterEnv]
        mv.enterScope(comprehensionFor);
        newDeclarativeEnvironment(mv);
        {
            // stack: [nextValue, iterEnv] -> [iterEnv, nextValue, envRec]
            mv.dupX1();
            mv.invoke(Methods.LexicalEnvironment_getEnvRec);

            // stack: [iterEnv, nextValue, envRec] -> [iterEnv, envRec, nextValue]
            for (String name : BoundNames(comprehensionFor.getBinding())) {
                mv.dup();
                mv.aconst(name);
                mv.iconst(false);
                mv.invoke(Methods.EnvironmentRecord_createMutableBinding);
            }
            mv.swap();

            // FIXME: spec bug (missing ToObject() call?)

            // stack: [iterEnv, envRec, nextValue] -> [iterEnv]
            BindingInitialisationWithEnvironment(comprehensionFor.getBinding(), mv);
        }
        // stack: [iterEnv] -> []
        pushLexicalEnvironment(mv);

        if (iterator.hasNext()) {
            visitGeneratorComprehension(comprehension, iterator, mv);
        } else {
            visitGeneratorComprehension(comprehension, mv);
        }

        // restore previous lexical environment
        popLexicalEnvironment(mv);
        mv.exitScope();

        mv.goTo(lblContinue);
        mv.mark(lblBreak);
        mv.freeVariable(var);
    }
}
