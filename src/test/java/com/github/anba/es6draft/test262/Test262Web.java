/**
 * Copyright (c) 2012-2015 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.test262;

import static com.github.anba.es6draft.test262.Test262GlobalObject.newGlobalObjectAllocator;
import static com.github.anba.es6draft.util.Functional.intoCollection;
import static com.github.anba.es6draft.util.Functional.toStrings;
import static com.github.anba.es6draft.util.Resources.loadConfiguration;
import static com.github.anba.es6draft.util.matchers.ErrorMessageMatcher.hasErrorMessage;
import static com.github.anba.es6draft.util.matchers.PatternMatcher.matchesPattern;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.commons.configuration.Configuration;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Parameterized.UseParametersRunnerFactory;

import com.github.anba.es6draft.repl.console.ShellConsole;
import com.github.anba.es6draft.runtime.internal.ObjectAllocator;
import com.github.anba.es6draft.runtime.internal.ScriptCache;
import com.github.anba.es6draft.runtime.internal.Strings;
import com.github.anba.es6draft.util.Functional.BiFunction;
import com.github.anba.es6draft.util.Parallelized;
import com.github.anba.es6draft.util.ParameterizedRunnerFactory;
import com.github.anba.es6draft.util.Resources;
import com.github.anba.es6draft.util.TestConfiguration;
import com.github.anba.es6draft.util.TestGlobals;
import com.github.anba.es6draft.util.rules.ExceptionHandlers.ScriptExceptionHandler;
import com.github.anba.es6draft.util.rules.ExceptionHandlers.StandardErrorHandler;

/**
 * The standard test262 test suite
 */
@RunWith(Parallelized.class)
@UseParametersRunnerFactory(ParameterizedRunnerFactory.class)
@TestConfiguration(name = "test262.test.web", file = "resource:/test-configuration.properties")
public final class Test262Web {
    private static final boolean USE_SHARED_EXECUTOR = false;
    private static final Configuration configuration = loadConfiguration(Test262Web.class);
    private static final DefaultMode unmarkedDefault = DefaultMode.forName(configuration
            .getString("unmarked_default"));
    private static final Set<String> includeFeatures = intoCollection(
            toStrings(configuration.getList("include.features")), new HashSet<String>());
    private static final Set<String> excludeFeatures = intoCollection(
            toStrings(configuration.getList("exclude.features")), new HashSet<String>());

    @Parameters(name = "{0}")
    public static List<Test262Info> suiteValues() throws IOException {
        return Resources.loadTests(configuration, new BiFunction<Path, Path, Test262Info>() {
            @Override
            public Test262Info apply(Path basedir, Path file) {
                return new Test262Info(basedir, file);
            }
        });
    }

    @ClassRule
    public static TestGlobals<Test262GlobalObject, Test262Info> globals = new TestGlobals<Test262GlobalObject, Test262Info>(
            configuration) {
        final ExecutorService shared = USE_SHARED_EXECUTOR ? createDefaultSharedExecutor() : null;

        @Override
        protected ObjectAllocator<Test262GlobalObject> newAllocator(ShellConsole console,
                Test262Info test, ScriptCache scriptCache) {
            return newGlobalObjectAllocator(console, test, scriptCache);
        }

        @Override
        protected ExecutorService getExecutor() {
            return shared;
        }
    };

    @Rule
    public TestWatcher watcher = new TestWatcher() {
        @Override
        protected void starting(Description description) {
            isStrictTest = description.getAnnotation(Strict.class) != null;
        }
    };
    private boolean isStrictTest = false;

    @Rule
    public Timeout maxTime = new Timeout(120, TimeUnit.SECONDS);

    @Rule
    public StandardErrorHandler errorHandler = StandardErrorHandler.none();

    @Rule
    public ScriptExceptionHandler exceptionHandler = ScriptExceptionHandler.none();

    @Rule
    public ExpectedException expected = ExpectedException.none();

    @Parameter(0)
    public Test262Info test;

    private Test262GlobalObject global;
    private Test262Async async;
    private String sourceCode;
    private int preambleLines;

    private boolean isValidTestConfiguration() {
        return test.hasMode(isStrictTest, unmarkedDefault) && test.hasFeature(includeFeatures, excludeFeatures);
    }

    @Before
    public void setUp() throws Throwable {
        // Filter disabled tests
        assumeTrue("Test disabled", test.isEnabled());

        String fileContent = test.readFile();
        if (!isValidTestConfiguration()) {
            return;
        }

        String preamble;
        if (test.isRaw() || test.isModule()) {
            preamble = "";
            preambleLines = 0;
        } else {
            if (isStrictTest) {
                preamble = "\"use strict\";\nvar strict_mode = true;\n";
            } else {
                preamble = "//\"use strict\";\nvar strict_mode = false;\n";
            }
            preambleLines = 2;

            if (test.isEarlyError()) {
                preamble = preamble + "function Early() {} throw new Early();\n";
                preambleLines += 1;
            }
        }
        sourceCode = Strings.concat(preamble, fileContent);

        global = globals.newGlobal(new Test262Console(), test);
        exceptionHandler.setExecutionContext(global.getRealm().defaultContext());

        if (!test.isNegative()) {
            errorHandler.match(StandardErrorHandler.defaultMatcher());
            exceptionHandler.match(ScriptExceptionHandler.defaultMatcher());
        } else {
            expected.expect(Matchers.either(StandardErrorHandler.defaultMatcher())
                    .or(ScriptExceptionHandler.defaultMatcher()));
            String errorType = test.getErrorType();
            if (errorType != null) {
                expected.expect(hasErrorMessage(global.getRealm().defaultContext(),
                        matchesPattern(errorType, Pattern.CASE_INSENSITIVE)));
            }
        }

        // Load test includes
        for (String name : test.getIncludes()) {
            global.include(name);
        }

        if (test.isAsync()) {
            async = global.install(new Test262Async(), Test262Async.class);
        }
    }

    @After
    public void tearDown() {
        if (!USE_SHARED_EXECUTOR) {
            if (global != null) {
                global.getScriptLoader().getExecutor().shutdown();
            }
        }
    }

    @Test
    public void runTest() throws Throwable {
        if (!isValidTestConfiguration()) {
            return;
        }

        // Evaluate actual test-script
        if (test.isModule()) {
            global.evalModule(test.toModuleName(), sourceCode, 1 - preambleLines);
        } else {
            global.eval(test.toFile(), sourceCode, 1 - preambleLines);
        }

        // Wait for pending tasks to finish
        if (test.isAsync()) {
            assertFalse(async.doneCalled);
            global.getRealm().getWorld().runEventLoop();
            assertTrue(async.doneCalled);
        } else {
            global.getRealm().getWorld().runEventLoop();
        }
    }

    @Test
    @Strict
    public void runTestStrict() throws Throwable {
        if (!isValidTestConfiguration()) {
            return;
        }

        // Evaluate actual test-script
        if (test.isModule()) {
            global.evalModule(test.toModuleName(), sourceCode, 1 - preambleLines);
        } else {
            global.eval(test.toFile(), sourceCode, 1 - preambleLines);
        }

        // Wait for pending tasks to finish
        if (test.isAsync()) {
            assertFalse(async.doneCalled);
            global.getRealm().getWorld().runEventLoop();
            assertTrue(async.doneCalled);
        } else {
            global.getRealm().getWorld().runEventLoop();
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.METHOD })
    public @interface Strict {
    }
}
