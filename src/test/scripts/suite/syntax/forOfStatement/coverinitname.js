/*
 * Copyright (c) 2012-2014 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */

const {
} = Assert;

// CoverInitialisedName in ForOfStatement

function testSyntax() {
  for ({} of {}) ;
  for ({x = 0} of {}) ;
}
