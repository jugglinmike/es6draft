/**
 * Copyright (c) 2012-2014 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.runtime.internal;

/**
 * Method execution resumption point
 */
public final class ResumptionPoint {
    private final Object[] stack;
    private final Object[] locals;
    private final int offset;

    public ResumptionPoint(Object[] stack, Object[] locals, int offset) {
        assert stack != null && locals != null && offset >= 0;
        this.stack = stack;
        this.locals = locals;
        this.offset = offset;
    }

    /**
     * Returns the stored stack.
     * 
     * @return the stored stack
     */
    public Object[] getStack() {
        return stack;
    }

    /**
     * Returns the stored locals.
     * 
     * @return the stored locals
     */
    public Object[] getLocals() {
        return locals;
    }

    /**
     * Returns the stored offset.
     * 
     * @return the execution offset
     */
    public int getOffset() {
        return offset;
    }
}
