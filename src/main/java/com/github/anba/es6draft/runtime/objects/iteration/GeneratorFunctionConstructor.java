/**
 * Copyright (c) 2012-2016 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.runtime.objects.iteration;

import static com.github.anba.es6draft.runtime.internal.Properties.createProperties;
import static com.github.anba.es6draft.runtime.objects.FunctionConstructor.functionSource;
import static com.github.anba.es6draft.runtime.objects.FunctionConstructor.functionSourceText;
import static com.github.anba.es6draft.runtime.objects.FunctionConstructor.newFunctionExecutable;
import static com.github.anba.es6draft.runtime.types.builtins.OrdinaryFunction.FunctionInitialize;
import static com.github.anba.es6draft.runtime.types.builtins.OrdinaryFunction.MakeConstructor;
import static com.github.anba.es6draft.runtime.types.builtins.OrdinaryFunction.SetFunctionName;

import com.github.anba.es6draft.compiler.CompilationException;
import com.github.anba.es6draft.parser.ParserException;
import com.github.anba.es6draft.runtime.ExecutionContext;
import com.github.anba.es6draft.runtime.GlobalEnvironmentRecord;
import com.github.anba.es6draft.runtime.LexicalEnvironment;
import com.github.anba.es6draft.runtime.Realm;
import com.github.anba.es6draft.runtime.internal.CompatibilityOption;
import com.github.anba.es6draft.runtime.internal.Initializable;
import com.github.anba.es6draft.runtime.internal.Properties.Attributes;
import com.github.anba.es6draft.runtime.internal.Properties.Prototype;
import com.github.anba.es6draft.runtime.internal.Properties.Value;
import com.github.anba.es6draft.runtime.internal.RuntimeInfo;
import com.github.anba.es6draft.runtime.internal.ScriptLoader;
import com.github.anba.es6draft.runtime.internal.Source;
import com.github.anba.es6draft.runtime.objects.FunctionConstructor.SourceKind;
import com.github.anba.es6draft.runtime.types.Constructor;
import com.github.anba.es6draft.runtime.types.Intrinsics;
import com.github.anba.es6draft.runtime.types.Property;
import com.github.anba.es6draft.runtime.types.ScriptObject;
import com.github.anba.es6draft.runtime.types.builtins.BuiltinConstructor;
import com.github.anba.es6draft.runtime.types.builtins.FunctionObject;
import com.github.anba.es6draft.runtime.types.builtins.FunctionObject.FunctionKind;
import com.github.anba.es6draft.runtime.types.builtins.OrdinaryConstructorGenerator;
import com.github.anba.es6draft.runtime.types.builtins.OrdinaryGenerator;
import com.github.anba.es6draft.runtime.types.builtins.OrdinaryObject;

/**
 * <h1>25 Control Abstraction Objects</h1><br>
 * <h2>25.2 GeneratorFunction Objects</h2>
 * <ul>
 * <li>25.2.1 The GeneratorFunction Constructor
 * <li>25.2.2 Properties of the GeneratorFunction Constructor
 * </ul>
 */
public final class GeneratorFunctionConstructor extends BuiltinConstructor implements Initializable {
    /**
     * Constructs a new Generator Function constructor function.
     * 
     * @param realm
     *            the realm object
     */
    public GeneratorFunctionConstructor(Realm realm) {
        super(realm, "GeneratorFunction", 1);
    }

    @Override
    public void initialize(Realm realm) {
        createProperties(realm, this, Properties.class);
    }

    @Override
    public GeneratorFunctionConstructor clone() {
        return new GeneratorFunctionConstructor(getRealm());
    }

    /**
     * 25.2.1.1 GeneratorFunction (p1, p2, ... , pn, body)
     */
    @Override
    public FunctionObject call(ExecutionContext callerContext, Object thisValue, Object... args) {
        /* steps 1-3 */
        return CreateDynamicFunction(callerContext, calleeContext(), this, args);
    }

    /**
     * 25.2.1.1 GeneratorFunction (p1, p2, ... , pn, body)
     */
    @Override
    public FunctionObject construct(ExecutionContext callerContext, Constructor newTarget, Object... args) {
        /* steps 1-3 */
        return CreateDynamicFunction(callerContext, calleeContext(), newTarget, args);
    }

    /**
     * 19.2.1.1.1 RuntimeSemantics: CreateDynamicFunction(constructor, newTarget, kind, args)
     * 
     * @param callerContext
     *            the caller execution context
     * @param cx
     *            the execution context
     * @param newTarget
     *            the newTarget constructor function
     * @param args
     *            the function arguments
     * @return the new generator function object
     */
    private static FunctionObject CreateDynamicFunction(ExecutionContext callerContext, ExecutionContext cx,
            Constructor newTarget, Object... args) {
        if (cx.getRealm().isEnabled(CompatibilityOption.GeneratorNonConstructor)) {
            return CreateDynamicGenerator(callerContext, cx, newTarget, args);
        }
        return CreateDynamicConstructorGenerator(callerContext, cx, newTarget, args);
    }

    private static OrdinaryConstructorGenerator CreateDynamicConstructorGenerator(ExecutionContext callerContext,
            ExecutionContext cx, Constructor newTarget, Object... args) {
        /* step 1 (not applicable) */
        /* step 2 (not applicable) */
        /* step 3 */
        Intrinsics fallbackProto = Intrinsics.Generator;

        /* steps 4-10 */
        String[] sourceText = functionSourceText(cx, args);
        String parameters = sourceText[0], bodyText = sourceText[1];

        /* steps 11, 13-20 */
        Source source = functionSource(SourceKind.Generator, cx.getRealm(), callerContext);
        RuntimeInfo.Function function;
        try {
            ScriptLoader scriptLoader = cx.getRealm().getScriptLoader();
            function = scriptLoader.generator(source, parameters, bodyText).getFunction();
        } catch (ParserException | CompilationException e) {
            throw e.toScriptException(cx);
        }

        /* step 12 */
        boolean strict = function.isStrict();
        /* steps 21-22 */
        ScriptObject proto = GetPrototypeFromConstructor(cx, newTarget, fallbackProto);
        /* step 23 */
        OrdinaryConstructorGenerator f = OrdinaryConstructorGenerator.FunctionAllocate(cx, proto, strict,
                FunctionKind.Normal);
        /* steps 24-25 */
        LexicalEnvironment<GlobalEnvironmentRecord> scope = f.getRealm().getGlobalEnv();
        /* step 26 */
        FunctionInitialize(f, FunctionKind.Normal, function, scope, newFunctionExecutable(source));
        /* step 27 */
        OrdinaryObject prototype = ObjectCreate(cx, Intrinsics.GeneratorPrototype);
        MakeConstructor(f, true, prototype);
        /* step 28 (not applicable) */
        /* step 29 */
        SetFunctionName(f, "anonymous");
        /* step 30 */
        return f;
    }

    private static OrdinaryGenerator CreateDynamicGenerator(ExecutionContext callerContext, ExecutionContext cx,
            Constructor newTarget, Object... args) {
        /* step 1 (not applicable) */
        /* step 2 (not applicable) */
        /* step 3 */
        Intrinsics fallbackProto = Intrinsics.Generator;

        /* steps 4-10 */
        String[] sourceText = functionSourceText(cx, args);
        String parameters = sourceText[0], bodyText = sourceText[1];

        /* steps 11, 13-20 */
        Source source = functionSource(SourceKind.Generator, cx.getRealm(), callerContext);
        RuntimeInfo.Function function;
        try {
            ScriptLoader scriptLoader = cx.getRealm().getScriptLoader();
            function = scriptLoader.generator(source, parameters, bodyText).getFunction();
        } catch (ParserException | CompilationException e) {
            throw e.toScriptException(cx);
        }

        /* step 12 */
        boolean strict = function.isStrict();
        /* steps 21-22 */
        ScriptObject proto = GetPrototypeFromConstructor(cx, newTarget, fallbackProto);
        /* step 23 */
        OrdinaryGenerator f = OrdinaryGenerator.FunctionAllocate(cx, proto, strict, FunctionKind.Normal);
        /* steps 24-25 */
        LexicalEnvironment<GlobalEnvironmentRecord> scope = f.getRealm().getGlobalEnv();
        /* step 26 */
        FunctionInitialize(f, FunctionKind.Normal, function, scope, newFunctionExecutable(source));
        /* step 27 */
        OrdinaryObject prototype = ObjectCreate(cx, Intrinsics.GeneratorPrototype);
        f.infallibleDefineOwnProperty("prototype", new Property(prototype, true, false, false));
        /* step 28 (not applicable) */
        /* step 29 */
        SetFunctionName(f, "anonymous");
        /* step 30 */
        return f;
    }

    /**
     * 25.2.2 Properties of the GeneratorFunction Constructor
     */
    public enum Properties {
        ;

        @Prototype
        public static final Intrinsics __proto__ = Intrinsics.Function;

        /**
         * 25.2.2.2 GeneratorFunction.prototype
         */
        @Value(name = "prototype", attributes = @Attributes(writable = false, enumerable = false,
                configurable = false))
        public static final Intrinsics prototype = Intrinsics.Generator;

        /**
         * 25.2.2.1 GeneratorFunction.length
         */
        @Value(name = "length", attributes = @Attributes(writable = false, enumerable = false,
                configurable = true))
        public static final int length = 1;

        @Value(name = "name", attributes = @Attributes(writable = false, enumerable = false,
                configurable = true))
        public static final String name = "GeneratorFunction";
    }
}
