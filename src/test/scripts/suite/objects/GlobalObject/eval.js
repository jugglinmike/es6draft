/*
 * Copyright (c) 2012-2016 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
const {assertBuiltinFunction} = Assert;
const global = this;

/* 18.2.1  eval (x) */

assertBuiltinFunction(global.eval, "eval", 1);
