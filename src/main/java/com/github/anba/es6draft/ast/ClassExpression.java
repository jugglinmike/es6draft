/**
 * Copyright (c) 2012-2013 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.ast;

import java.util.List;

/**
 * <h1>13 Functions and Generators</h1>
 * <ul>
 * <li>13.5 Class Definitions
 * </ul>
 */
public class ClassExpression extends Expression implements ClassDefinition {
    private BindingIdentifier name;
    private Expression heritage;
    private List<MethodDefinition> staticMethods;
    private List<MethodDefinition> prototypeMethods;

    public ClassExpression(BindingIdentifier name, Expression heritage,
            List<MethodDefinition> staticMethods, List<MethodDefinition> prototypeMethods) {
        this.name = name;
        this.heritage = heritage;
        this.staticMethods = staticMethods;
        this.prototypeMethods = prototypeMethods;
    }

    @Override
    public BindingIdentifier getName() {
        return name;
    }

    @Override
    public Expression getHeritage() {
        return heritage;
    }

    @Override
    public List<MethodDefinition> getStaticMethods() {
        return staticMethods;
    }

    @Override
    public List<MethodDefinition> getPrototypeMethods() {
        return prototypeMethods;
    }

    @Override
    public <R, V> R accept(NodeVisitor<R, V> visitor, V value) {
        return visitor.visit(this, value);
    }
}
