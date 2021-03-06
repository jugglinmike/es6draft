/*
 * Copyright (c) 2012-2016 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
function test(start, end) {
  for (let cp = start; cp <= end;) {
    let source = "var obj = {};\n";
    for (let i = 0; cp <= end && i < 1000; ++cp, ++i) {
      source += `obj.A${String.fromCodePoint(cp)};\n`;
    }
    eval(source);
  }
}

// Delta compared to Unicode 6.0
// Same as Unicode 6.2
test(0x08a0, 0x08a0);
test(0x08a2, 0x08ac);
test(0x08e4, 0x08fe);
test(0x0edc, 0x0edf);
test(0x10c7, 0x10c7);
test(0x10cd, 0x10cd);
test(0x10fd, 0x1248);
test(0x17b4, 0x17b5);
test(0x1bab, 0x1bab);
test(0x1bac, 0x1bad);
test(0x1bba, 0x1be5);
test(0x1cf2, 0x1cf3);
test(0x1cf4, 0x1cf4);
test(0x1cf5, 0x1cf6);
test(0x1d2c, 0x1d6a);
test(0x1d6b, 0x1d77);
test(0x2c60, 0x2c7b);
test(0x2c7c, 0x2c7d);
test(0x2cf2, 0x2cf3);
test(0x2d27, 0x2d27);
test(0x2d2d, 0x2d2d);
test(0x2d30, 0x2d67);
test(0x302a, 0x302d);
test(0x302e, 0x302f);
test(0x4e00, 0x9fcc);
test(0xa674, 0xa67d);
test(0xa69f, 0xa69f);
test(0xa790, 0xa793);
test(0xa7a0, 0xa7aa);
test(0xa7f8, 0xa7f9);
test(0xaae0, 0xaaea);
test(0xaaeb, 0xaaeb);
test(0xaaec, 0xaaed);
test(0xaaee, 0xaaef);
test(0xaaf2, 0xaaf2);
test(0xaaf3, 0xaaf4);
test(0xaaf5, 0xaaf5);
test(0xaaf6, 0xaaf6);
test(0xf900, 0xfa6d);
test(0x10980, 0x109b7);
test(0x109be, 0x109bf);
test(0x110d0, 0x110e8);
test(0x110f0, 0x110f9);
test(0x11100, 0x11102);
test(0x11103, 0x11126);
test(0x11127, 0x1112b);
test(0x1112c, 0x1112c);
test(0x1112d, 0x11134);
test(0x11136, 0x1113f);
test(0x11180, 0x11181);
test(0x11182, 0x11182);
test(0x11183, 0x111b2);
test(0x111b3, 0x111b5);
test(0x111b6, 0x111be);
test(0x111bf, 0x111c0);
test(0x111c1, 0x111c4);
test(0x111d0, 0x111d9);
test(0x11680, 0x116aa);
test(0x116ab, 0x116ab);
test(0x116ac, 0x116ac);
test(0x116ad, 0x116ad);
test(0x116ae, 0x116af);
test(0x116b0, 0x116b5);
test(0x116b6, 0x116b6);
test(0x116b7, 0x116b7);
test(0x116c0, 0x116c9);
test(0x16f00, 0x16f44);
test(0x16f50, 0x16f50);
test(0x16f51, 0x16f7e);
test(0x16f8f, 0x16f92);
test(0x16f93, 0x16f9f);
test(0x1ee00, 0x1ee03);
test(0x1ee05, 0x1ee1f);
test(0x1ee21, 0x1ee22);
test(0x1ee24, 0x1ee24);
test(0x1ee27, 0x1ee27);
test(0x1ee29, 0x1ee32);
test(0x1ee34, 0x1ee37);
test(0x1ee39, 0x1ee39);
test(0x1ee3b, 0x1ee3b);
test(0x1ee42, 0x1ee42);
test(0x1ee47, 0x1ee47);
test(0x1ee49, 0x1ee49);
test(0x1ee4b, 0x1ee4b);
test(0x1ee4d, 0x1ee4f);
test(0x1ee51, 0x1ee52);
test(0x1ee54, 0x1ee54);
test(0x1ee57, 0x1ee57);
test(0x1ee59, 0x1ee59);
test(0x1ee5b, 0x1ee5b);
test(0x1ee5d, 0x1ee5d);
test(0x1ee5f, 0x1ee5f);
test(0x1ee61, 0x1ee62);
test(0x1ee64, 0x1ee64);
test(0x1ee67, 0x1ee6a);
test(0x1ee6c, 0x1ee72);
test(0x1ee74, 0x1ee77);
test(0x1ee79, 0x1ee7c);
test(0x1ee7e, 0x1ee7e);
test(0x1ee80, 0x1ee89);
test(0x1ee8b, 0x1ee9b);
test(0x1eea1, 0x1eea3);
test(0x1eea5, 0x1eea9);
test(0x1eeab, 0x1eebb);
