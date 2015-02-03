/*
 * Copyright (c) 2012-2015 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
const {
  assertThrows
} = Assert;

function SuperConstructor() { }

// 14.1 FunctionDeclaration
function fdecl() {
  eval("new super()");
}
Object.setPrototypeOf(fdecl, SuperConstructor);
assertThrows(TypeError, () => fdecl());
new fdecl();

// 14.1 FunctionExpression
var fexpr = function() {
  eval("new super()");
};
Object.setPrototypeOf(fexpr, SuperConstructor);
assertThrows(TypeError, () => fexpr());
new fexpr();

// 14.3 Method Definitions [Method]
var obj = {
  m() {
    eval("new super()");
  }
};
Object.setPrototypeOf(obj.m, SuperConstructor);
assertThrows(TypeError, () => obj.m());
assertThrows(TypeError, () => new obj.m());

var obj = class {
  m() {
    eval("new super()");
  }
};
Object.setPrototypeOf(obj.prototype.m, SuperConstructor);
assertThrows(TypeError, () => obj.prototype.m());
assertThrows(TypeError, () => new obj.prototype.m());

var obj = class {
  static m() {
    eval("new super()");
  }
};
Object.setPrototypeOf(obj.m, SuperConstructor);
assertThrows(TypeError, () => obj.m());
assertThrows(TypeError, () => new obj.m());

// 14.3 Method Definitions [ConstructorMethod]
var obj = class {
  constructor() {
    eval("new super()");
  }
};
Object.setPrototypeOf(obj, SuperConstructor);
assertThrows(TypeError, () => obj());
new obj();

var obj = class extends class {} {
  constructor() {
    super();
    eval("new super()");
  }
};
assertThrows(TypeError, () => obj());
new obj();

// 14.3 Method Definitions [Getter]
var obj = {
  get x() {
    eval("new super()");
  }
};
Object.setPrototypeOf(Object.getOwnPropertyDescriptor(obj, "x").get, SuperConstructor);
assertThrows(TypeError, () => Object.getOwnPropertyDescriptor(obj, "x").get());
assertThrows(TypeError, () => new (Object.getOwnPropertyDescriptor(obj, "x").get)());

var obj = class {
  get x() {
    eval("new super()");
  }
};
Object.setPrototypeOf(Object.getOwnPropertyDescriptor(obj.prototype, "x").get, SuperConstructor);
assertThrows(TypeError, () => Object.getOwnPropertyDescriptor(obj.prototype, "x").get());
assertThrows(TypeError, () => new (Object.getOwnPropertyDescriptor(obj.prototype, "x").get)());

var obj = class {
  static get x() {
    eval("new super()");
  }
};
Object.setPrototypeOf(Object.getOwnPropertyDescriptor(obj, "x").get, SuperConstructor);
assertThrows(TypeError, () => Object.getOwnPropertyDescriptor(obj, "x").get());
assertThrows(TypeError, () => new (Object.getOwnPropertyDescriptor(obj, "x").get)());

// 14.3 Method Definitions [Setter]
var obj = {
  set x(_) {
    eval("new super()");
  }
};
Object.setPrototypeOf(Object.getOwnPropertyDescriptor(obj, "x").set, SuperConstructor);
assertThrows(TypeError, () => Object.getOwnPropertyDescriptor(obj, "x").set());
assertThrows(TypeError, () => new (Object.getOwnPropertyDescriptor(obj, "x").set)());

var obj = class {
  set x(_) {
    eval("new super()");
  }
};
Object.setPrototypeOf(Object.getOwnPropertyDescriptor(obj.prototype, "x").set, SuperConstructor);
assertThrows(TypeError, () => Object.getOwnPropertyDescriptor(obj.prototype, "x").set());
assertThrows(TypeError, () => new (Object.getOwnPropertyDescriptor(obj.prototype, "x").set)());

var obj = class {
  static set x(_) {
    eval("new super()");
  }
};
Object.setPrototypeOf(Object.getOwnPropertyDescriptor(obj, "x").set, SuperConstructor);
assertThrows(TypeError, () => Object.getOwnPropertyDescriptor(obj, "x").set());
assertThrows(TypeError, () => new (Object.getOwnPropertyDescriptor(obj, "x").set)());

// 14.4 GeneratorDeclaration
function* gdecl() {
  eval("new super()");
}
Object.setPrototypeOf(gdecl, SuperConstructor);
assertThrows(TypeError, () => gdecl().next());
new gdecl().next();

// 14.4 GeneratorExpression
var gexpr = function*() {
  eval("new super()");
};
Object.setPrototypeOf(gexpr, SuperConstructor);
assertThrows(TypeError, () => gexpr().next());
new gexpr().next();

// 14.4 GeneratorMethod
var obj = {
  *m() {
    eval("new super()");
  }
};
Object.setPrototypeOf(obj.m, SuperConstructor);
assertThrows(TypeError, () => obj.m().next());
new obj.m().next();

var obj = class {
  *m() {
    eval("new super()");
  }
};
Object.setPrototypeOf(obj.prototype.m, SuperConstructor);
assertThrows(TypeError, () => obj.prototype.m().next());
new obj.prototype.m().next();

var obj = class {
  static *m() {
    eval("new super()");
  }
};
Object.setPrototypeOf(obj.m, SuperConstructor);
assertThrows(TypeError, () => obj.m().next());
new obj.m().next();
