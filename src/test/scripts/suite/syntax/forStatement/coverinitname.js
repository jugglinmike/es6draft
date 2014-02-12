/*
 * Copyright (c) 2012-2014 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */

const {
  assertSyntaxError
} = Assert;

// CoverInitialisedName in ForStatement

function testSyntax() {
  for (;;) ;
  for ({};;) ;
  for ({}, {};;) ;
}

assertSyntaxError(`for ({x = 0};;) ;`);
assertSyntaxError(`for ({}, {x = 0};;) ;`);
assertSyntaxError(`for ({x = 0}, {};;) ;`);