/**
 * Copyright (c) 2012-2013 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.ast;

/**
 *
 */
public interface Scope {
    Scope getParent();

    ScopedNode getNode();

    boolean isDynamic();
}
