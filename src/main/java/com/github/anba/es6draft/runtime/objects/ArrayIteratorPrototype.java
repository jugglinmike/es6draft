/**
 * Copyright (c) 2012-2013 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.runtime.objects;

import static com.github.anba.es6draft.runtime.AbstractOperations.Get;
import static com.github.anba.es6draft.runtime.AbstractOperations.HasProperty;
import static com.github.anba.es6draft.runtime.AbstractOperations.ToString;
import static com.github.anba.es6draft.runtime.AbstractOperations.ToUint32;
import static com.github.anba.es6draft.runtime.internal.Errors.throwTypeError;
import static com.github.anba.es6draft.runtime.internal.Properties.createProperties;
import static com.github.anba.es6draft.runtime.internal.ScriptRuntime._throw;
import static com.github.anba.es6draft.runtime.types.Undefined.UNDEFINED;
import static com.github.anba.es6draft.runtime.types.builtins.ExoticArray.ArrayCreate;

import com.github.anba.es6draft.runtime.ExecutionContext;
import com.github.anba.es6draft.runtime.Realm;
import com.github.anba.es6draft.runtime.internal.Initialisable;
import com.github.anba.es6draft.runtime.internal.Messages;
import com.github.anba.es6draft.runtime.internal.Properties.Function;
import com.github.anba.es6draft.runtime.internal.Properties.Prototype;
import com.github.anba.es6draft.runtime.internal.Properties.Value;
import com.github.anba.es6draft.runtime.types.BuiltinSymbol;
import com.github.anba.es6draft.runtime.types.Intrinsics;
import com.github.anba.es6draft.runtime.types.PropertyDescriptor;
import com.github.anba.es6draft.runtime.types.ScriptObject;
import com.github.anba.es6draft.runtime.types.Type;
import com.github.anba.es6draft.runtime.types.builtins.OrdinaryObject;

/**
 * <h1>15 Standard Built-in ECMAScript Objects</h1><br>
 * <h2>15.4 Array Objects</h2>
 * <ul>
 * <li>15.4.6 Array Iterator Object Structure
 * </ul>
 */
public class ArrayIteratorPrototype extends OrdinaryObject implements Initialisable {
    public ArrayIteratorPrototype(Realm realm) {
        super(realm);
    }

    @Override
    public void initialise(ExecutionContext cx) {
        createProperties(this, cx, Properties.class);
    }

    /**
     * 15.4.6.3 Properties of Array Iterator Instances
     */
    public enum ArrayIterationKind {
        Key, Value, KeyValue, SparseKey, SparseValue, SparseKeyValue
    }

    /**
     * 15.4.6.3 Properties of Array Iterator Instances
     */
    private static class ArrayIterator extends OrdinaryObject {
        /**
         * [[IteratedObject]]
         */
        ScriptObject iteratedObject;

        /**
         * [[ArrayIteratorNextIndex]]
         */
        long nextIndex;

        /**
         * [[ArrayIterationKind]]
         */
        ArrayIterationKind kind;

        ArrayIterator(Realm realm) {
            super(realm);
        }
    }

    /**
     * 15.4.6.1 CreateArrayIterator Abstract Operation
     */
    public static OrdinaryObject CreateArrayIterator(ExecutionContext cx, ScriptObject array,
            ArrayIterationKind kind) {
        // ObjectCreate()
        ArrayIterator itr = new ArrayIterator(cx.getRealm());
        itr.setPrototype(cx, cx.getIntrinsic(Intrinsics.ArrayIteratorPrototype));
        itr.iteratedObject = array;
        itr.nextIndex = 0;
        itr.kind = kind;
        return itr;
    }

    /**
     * 15.4.6.2 The Array Iterator Prototype
     */
    public enum Properties {
        ;

        @Prototype
        public static final Intrinsics __proto__ = Intrinsics.ObjectPrototype;

        /**
         * 15.4.6.2.1 ArrayIterator.prototype.constructor FIXME: spec bug (no description)
         */
        @Value(name = "constructor")
        public static final Object constructor = UNDEFINED;

        /**
         * 15.4.6.2.2 ArrayIterator.prototype.next( )
         */
        @Function(name = "next", arity = 0)
        public static Object next(ExecutionContext cx, Object thisValue) {
            if (!Type.isObject(thisValue)) {
                throw throwTypeError(cx, Messages.Key.NotObjectType);
            }
            if (!(thisValue instanceof ArrayIterator)) {
                throw throwTypeError(cx, Messages.Key.IncompatibleObject);
            }
            ArrayIterator itr = (ArrayIterator) thisValue;
            ScriptObject array = itr.iteratedObject;
            long index = itr.nextIndex;
            ArrayIterationKind itemKind = itr.kind;
            Object lenValue = Get(cx, array, "length");
            long len = ToUint32(cx, lenValue);

            // index == +Infinity => index == -1
            if (index < 0) {
                return _throw(cx.getIntrinsic(Intrinsics.StopIteration));
            }

            if (itemKind == ArrayIterationKind.SparseKey
                    || itemKind == ArrayIterationKind.SparseValue
                    || itemKind == ArrayIterationKind.SparseKeyValue) {
                boolean found = false;
                while (!found && index < len) {
                    String elementKey = ToString(index);
                    found = HasProperty(cx, array, elementKey);
                    if (!found) {
                        index += 1;
                    }
                }
            }
            if (index >= len) {
                itr.nextIndex = -1; // actually +Infinity!
                return _throw(cx.getIntrinsic(Intrinsics.StopIteration));
            }
            String elementKey = ToString(index);
            itr.nextIndex = index + 1;
            Object elementValue = null;
            if (itemKind == ArrayIterationKind.Value || itemKind == ArrayIterationKind.KeyValue
                    || itemKind == ArrayIterationKind.SparseValue
                    || itemKind == ArrayIterationKind.SparseKeyValue) {
                elementValue = Get(cx, array, elementKey);
            }
            if (itemKind == ArrayIterationKind.KeyValue
                    || itemKind == ArrayIterationKind.SparseKeyValue) {
                assert elementValue != null;
                ScriptObject result = ArrayCreate(cx, 2);
                result.defineOwnProperty(cx, "0", new PropertyDescriptor(elementKey, true, true,
                        true));
                result.defineOwnProperty(cx, "1", new PropertyDescriptor(elementValue, true, true,
                        true));
                return result;
            } else if (itemKind == ArrayIterationKind.Key
                    || itemKind == ArrayIterationKind.SparseKey) {
                return elementKey;
            } else {
                // FIXME: spec bug (wrong assertion, itemKind is not "value" -> it's either "value"
                // or "sparse-value") (bug 1401)
                assert itemKind == ArrayIterationKind.Value
                        || itemKind == ArrayIterationKind.SparseValue;
                assert elementValue != null;
                return elementValue;
            }
        }

        /**
         * 15.4.6.2.3 ArrayIterator.prototype.@@iterator ()
         */
        @Function(name = "@@iterator", symbol = BuiltinSymbol.iterator, arity = 0)
        public static Object iterator(ExecutionContext cx, Object thisValue) {
            return thisValue;
        }

        /**
         * 15.4.6.2.4 ArrayIterator.prototype.@@toStringTag
         */
        @Value(name = "@@toStringTag", symbol = BuiltinSymbol.toStringTag)
        public static final String toStringTag = "Array Iterator";
    }
}
