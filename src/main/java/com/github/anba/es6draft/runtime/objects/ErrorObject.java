/**
 * Copyright (c) 2012-2014 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.runtime.objects;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.github.anba.es6draft.runtime.Realm;
import com.github.anba.es6draft.runtime.internal.GeneratorThread;
import com.github.anba.es6draft.runtime.internal.ScriptException;
import com.github.anba.es6draft.runtime.types.Property;
import com.github.anba.es6draft.runtime.types.ScriptObject;
import com.github.anba.es6draft.runtime.types.Type;
import com.github.anba.es6draft.runtime.types.builtins.OrdinaryObject;

/**
 * <h1>19 Fundamental Objects</h1><br>
 * <h2>19.5 Error Objects</h2>
 * <ul>
 * <li>19.5.4 Properties of Error Instances
 * <li>19.5.6 NativeError Object Structure
 * <ul>
 * <li>19.5.6.4 Properties of NativeError Instances
 * </ul>
 * </ul>
 */
public final class ErrorObject extends OrdinaryObject {
    private boolean initialised = false;
    private ScriptException exception = null;
    private List<StackTraceElement[]> stackTraces;

    public ErrorObject(Realm realm) {
        super(realm);
        this.exception = new ScriptException(this);
        this.stackTraces = collectStackTraces();
    }

    private List<StackTraceElement[]> collectStackTraces() {
        List<StackTraceElement[]> stackTraces = new ArrayList<>();
        Thread thread = Thread.currentThread();
        while (thread instanceof GeneratorThread) {
            thread = ((GeneratorThread) thread).getParent();
            stackTraces.add(thread.getStackTrace());
        }
        return stackTraces;
    }

    public boolean isInitialised() {
        return initialised;
    }

    public void initialise() {
        assert !this.initialised : "ErrorObject already initialised";
        this.initialised = true;
    }

    public ScriptException getException() {
        return exception;
    }

    public List<StackTraceElement[]> getStackTraces() {
        return stackTraces;
    }

    @Override
    public String toString() {
        String name = getErrorObjectProperty(this, "name", "Error");
        String message = getErrorObjectProperty(this, "message", "");
        if (name.length() == 0) {
            return message;
        }
        if (message.length() == 0) {
            return name;
        }
        return name + ": " + message;
    }

    /**
     * Specialized property retrieval to prevent any script execution.
     * 
     * @param error
     *            the error object
     * @param propertyName
     *            the property key
     * @param defaultValue
     *            the default value
     * @return property string value
     */
    private static String getErrorObjectProperty(ErrorObject error, String propertyName,
            String defaultValue) {
        Property property = error.ordinaryGetOwnProperty(propertyName);
        if (property == null) {
            ScriptObject proto = error.getPrototype();
            if (proto instanceof ErrorPrototype) {
                property = ((ErrorPrototype) proto).getOwnProperty(propertyName);
            } else if (proto instanceof NativeErrorPrototype) {
                property = ((NativeErrorPrototype) proto).getOwnProperty(propertyName);
            }
        }
        Object value = property != null && property.isDataDescriptor() ? property.getValue() : null;
        if (value == null || Type.isUndefined(value)) {
            return defaultValue;
        }
        // Prevent possible recursion
        if (value instanceof ErrorObject) {
            return "<error>";
        }
        return Objects.toString(value);
    }
}
