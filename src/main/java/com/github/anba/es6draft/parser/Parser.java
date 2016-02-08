/**
 * Copyright (c) 2012-2015 André Bargull
 * Alle Rechte vorbehalten / All Rights Reserved.  Use is subject to license terms.
 *
 * <https://github.com/anba/es6draft>
 */
package com.github.anba.es6draft.parser;

import static com.github.anba.es6draft.semantics.StaticSemantics.*;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.util.*;

import com.github.anba.es6draft.ast.AbruptNode.Abrupt;
import com.github.anba.es6draft.ast.*;
import com.github.anba.es6draft.ast.MethodDefinition.MethodAllocation;
import com.github.anba.es6draft.ast.MethodDefinition.MethodType;
import com.github.anba.es6draft.ast.scope.BlockScope;
import com.github.anba.es6draft.ast.scope.FunctionScope;
import com.github.anba.es6draft.ast.scope.ModuleScope;
import com.github.anba.es6draft.ast.scope.Name;
import com.github.anba.es6draft.ast.scope.Scope;
import com.github.anba.es6draft.ast.scope.ScriptScope;
import com.github.anba.es6draft.ast.scope.TopLevelScope;
import com.github.anba.es6draft.ast.scope.WithScope;
import com.github.anba.es6draft.parser.ParserException.ExceptionType;
import com.github.anba.es6draft.regexp.RegExpParser;
import com.github.anba.es6draft.runtime.internal.CompatibilityOption;
import com.github.anba.es6draft.runtime.internal.InlineArrayList;
import com.github.anba.es6draft.runtime.internal.Messages;
import com.github.anba.es6draft.runtime.internal.Source;

/**
 * Parser for ECMAScript6 source code
 * <ul>
 * <li>12 ECMAScript Language: Expressions
 * <li>13 ECMAScript Language: Statements and Declarations
 * <li>14 ECMAScript Language: Functions and Classes
 * <li>15 ECMAScript Language: Scripts and Modules
 * </ul>
 */
public final class Parser {
    private static final boolean DEBUG = false;

    private static final int MAX_ARGUMENTS = 0x4000;
    private static final String DEFAULT_EXPORT_BINDING_NAME = "*default*";
    private static final String DEFAULT_EXPORT_NAME = "default";
    private static final List<Binding> NO_INHERITED_BINDING = Collections.emptyList();
    private static final List<Expression> NO_DECORATORS = Collections.emptyList();
    private static final Set<String> EMPTY_LABEL_SET = Collections.emptySet();

    private final Source source;
    private final EnumSet<CompatibilityOption> options;
    private final EnumSet<Option> parserOptions;
    private TokenStream ts;
    private ParseContext context;
    private boolean moduleCode;

    private enum StrictMode {
        Unknown, Strict, NonStrict
    }

    private enum StatementType {
        Iteration, Breakable, Statement
    }

    private enum ContextKind {
        Script, Module, Function, Method, Generator, GeneratorMethod, AsyncFunction, AsyncMethod,
        ArrowFunction, GeneratorComprehension, AsyncArrowFunction;

        final boolean isScript() {
            return this == Script;
        }

        final boolean isModule() {
            return this == Module;
        }

        final boolean isFunction() {
            switch (this) {
            case ArrowFunction:
            case AsyncArrowFunction:
            case AsyncFunction:
            case AsyncMethod:
            case Function:
            case Generator:
            case GeneratorMethod:
            case GeneratorComprehension:
            case Method:
                return true;
            default:
                return false;
            }
        }

        final boolean isGenerator() {
            switch (this) {
            case Generator:
            case GeneratorMethod:
                return true;
            default:
                return false;
            }
        }

        final boolean isAsync() {
            switch (this) {
            case AsyncArrowFunction:
            case AsyncFunction:
            case AsyncMethod:
                return true;
            default:
                return false;
            }
        }

        final boolean isLexical() {
            switch (this) {
            case ArrowFunction:
            case AsyncArrowFunction:
            case GeneratorComprehension:
                return true;
            default:
                return false;
            }
        }

        public boolean isMethod() {
            switch (this) {
            case Method:
            case GeneratorMethod:
            case AsyncMethod:
                return true;
            default:
                return false;
            }
        }
    }

    private static final class ParseContext {
        final ParseContext parent;
        final ContextKind kind;

        boolean yieldAllowed = false;
        boolean awaitAllowed = false;
        boolean returnAllowed = false;
        boolean legacyGenerator = false;
        boolean explicitStrict = false;
        boolean isDerivedClassConstructor = false;

        StrictMode strictMode = StrictMode.Unknown;
        ParserException strictError;
        InlineArrayList<FunctionNode> deferred;
        ArrayDeque<ObjectLiteral> objectLiterals;
        ScopeWithNames illegalLexNames = new ScopeWithNames(null, null);

        HashMap<String, LabelContext> labelSet;
        LabelContext labels;

        ScopeContext scopeContext;
        final TopContext topContext;
        final ScriptContext scriptContext;
        final ModuleContext modContext;
        final FunctionContext funContext;

        ParseContext() {
            this.parent = null;
            this.kind = null;
            this.topContext = null;
            this.scriptContext = null;
            this.modContext = null;
            this.funContext = null;
        }

        ParseContext(ParseContext parent, ContextKind kind) {
            this.parent = parent;
            this.kind = kind;
            if (kind.isScript()) {
                this.scriptContext = new ScriptContext(parent.scopeContext);
                this.modContext = null;
                this.funContext = null;
                this.topContext = scriptContext;
            } else if (kind.isModule()) {
                this.scriptContext = null;
                this.modContext = new ModuleContext(parent.scopeContext);
                this.funContext = null;
                this.topContext = modContext;
            } else {
                assert kind.isFunction();
                this.scriptContext = null;
                this.modContext = null;
                this.funContext = new FunctionContext(parent.scopeContext, kind.isLexical());
                this.topContext = funContext;
            }
            this.scopeContext = topContext;
            this.returnAllowed = kind.isFunction();
            if (parent.strictMode == StrictMode.Strict) {
                this.strictMode = parent.strictMode;
            }
        }

        ParseContext findSuperContext() {
            for (ParseContext cx = this;; cx = cx.parent) {
                switch (cx.kind) {
                case ArrowFunction:
                case AsyncArrowFunction:
                case GeneratorComprehension:
                    continue;
                default:
                    return cx;
                }
            }
        }

        void setHasEval() {
            if (funContext != null) {
                funContext.directEval = true;
            }
            scopeContext.setHasEval();
        }

        void setNeedsSuperBinding() {
            if (funContext != null) {
                funContext.superReference = true;
            }
        }

        int countLiterals() {
            return objectLiterals != null ? objectLiterals.size() : 0;
        }

        void addLiteral(ObjectLiteral object) {
            if (objectLiterals == null) {
                objectLiterals = new ArrayDeque<>(4);
            }
            objectLiterals.push(object);
        }

        void removeLiteral(ObjectLiteral object) {
            objectLiterals.removeFirstOccurrence(object);
        }

        boolean assertLiteralsUnchecked(int expected) {
            int count = countLiterals();
            assert count == expected : String.format(
                    "%d unchecked object literals, but expected %d", count, expected);
            return count == expected;
        }

        ScopeWithNames setIllegalNames(NameSet names) {
            ScopeWithNames previous = illegalLexNames;
            illegalLexNames = new ScopeWithNames(scopeContext, names);
            return previous;
        }

        void restoreIllegalNames(ScopeWithNames illegalLexNames) {
            this.illegalLexNames = illegalLexNames;
        }

        boolean isIllegalName(Name name) {
            return illegalLexNames.scope == scopeContext && illegalLexNames.names.contains(name);
        }
    }

    private static final class ScopeWithNames {
        final ScopeContext scope;
        final NameSet names;

        ScopeWithNames(ScopeContext scope, NameSet names) {
            this.scope = scope;
            this.names = names;
        }
    }

    private static final class NameSet extends AbstractSet<Name> implements Set<Name> {
        private final LinkedHashMap<String, Name> map;

        NameSet() {
            map = new LinkedHashMap<>();
        }

        NameSet(NameSet set) {
            map = new LinkedHashMap<>(set.map);
        }

        NameSet(Collection<Name> c) {
            map = new LinkedHashMap<>(Math.max((int) (c.size() / 0.75f) + 1, 16));
            addAll(c);
        }

        void addAll(NameSet set) {
            map.putAll(set.map);
        }

        boolean remove(Name o) {
            return map.remove(o.getIdentifier()) != null;
        }

        boolean contains(Name o) {
            return map.containsKey(o.getIdentifier());
        }

        Name get(Name o) {
            return map.get(o.getIdentifier());
        }

        @Override
        public Iterator<Name> iterator() {
            return map.values().iterator();
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public boolean add(Name e) {
            return map.put(e.getIdentifier(), e) == null;
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (o == null || o.getClass() != Name.class) {
                return false;
            }
            return map.containsKey(((Name) o).getIdentifier());
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FunctionContext extends TopContext implements FunctionScope {
        FunctionNode node;
        ScopeContext variableScope = this;
        ScopeContext lexicalScope = this;
        Name arguments;
        NameSet parameterNames;
        InlineArrayList<FunctionDeclaration> blockFunctions;
        NameSet blockFunctionNames;
        final boolean isLexical;
        boolean needsArguments;
        boolean directEval;
        boolean superReference;

        FunctionContext(ScopeContext enclosing, boolean isLexical) {
            super(enclosing);
            this.isLexical = isLexical;
        }

        void addBlockFunction(FunctionDeclaration function) {
            if (blockFunctions == null) {
                blockFunctions = newList();
            }
            blockFunctions.add(function);
        }

        void setBlockFunctions(InlineArrayList<FunctionDeclaration> blockFunctions) {
            this.blockFunctions = blockFunctions;
            this.blockFunctionNames = new NameSet();
            for (FunctionDeclaration f : blockFunctions) {
                Name fname = f.getIdentifier().getName();
                Name name = variableScope.getDeclaredName(fname);
                if (name == null) {
                    // Create a new name binding, need to use clone() to create a distinct binding.
                    name = fname.clone();
                }
                blockFunctionNames.add(name);
            }
        }

        void setParameterNames(List<Name> names) {
            this.parameterNames = new NameSet(names);
        }

        void needsArguments(boolean lookupByName) {
            this.needsArguments = true;
        }

        void setNode(FunctionNode node) {
            this.node = node;
            ScopeContext varScope = variableScope, lexScope = lexicalScope;
            assert (varScope != this) == node.getParameters().containsExpression();
            if (lexScope != this) {
                // Copy all lexically declared names into the function scope.
                assert lexDeclaredNames == null && lexScopedDeclarations == null;
                if (lexScope.lexDeclaredNames != null) {
                    lexDeclaredNames = new NameSet(lexScope.lexDeclaredNames);
                }
                lexScopedDeclarations = lexScope.lexScopedDeclarations;
                lexScope.lexScopedDeclarations = null;
                ((FunctionBodyContext) lexScope).node = node;
            }
            if (varScope != this) {
                // Copy all variable declared names into varScope.
                assert lexScope != this;
                if (varDeclaredNames != null) {
                    if (varScope.lexDeclaredNames == null) {
                        varScope.lexDeclaredNames = new NameSet(varDeclaredNames);
                    } else {
                        assert varScope == lexScope;
                        varScope.lexDeclaredNames.addAll(varDeclaredNames);
                    }
                }
                ((FunctionBodyContext) varScope).node = node;
            }
            assert varScope == this || varScope.lexScopedDeclarations == null;
            assert lexScope == this || lexScope.lexScopedDeclarations == null;
            if (needsArguments()) {
                if (isLexical) {
                    propagateNeedsArguments();
                } else {
                    setImplicitArguments();
                }
            }
        }

        private void setImplicitArguments() {
            assert node.getThisMode() != FunctionNode.ThisMode.Lexical;
            Name arguments = new Name("arguments");
            if (parameterNames().contains(arguments)) {
                return;
            }
            if (!node.getParameters().containsExpression()) {
                if (lexicallyDeclaredNames().contains(arguments)) {
                    return;
                }
                if (varDeclaredNames().contains(arguments)) {
                    for (StatementListItem item : node.getStatements()) {
                        if (item instanceof HoistableDeclaration) {
                            if (((HoistableDeclaration) item).getName().equals(arguments)) {
                                return;
                            }
                        }
                    }
                }
            }
            this.arguments = arguments;
        }

        private void propagateNeedsArguments() {
            for (TopContext t = this; t instanceof FunctionContext; t = t.enclosing.top) {
                FunctionContext fc = (FunctionContext) t;
                if (!fc.isLexical) {
                    fc.needsArguments(directEval);
                    break;
                }
            }
        }

        Name blockFunctionName(Name name) {
            if (blockFunctionNames != null) {
                return blockFunctionNames.get(name);
            }
            return null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());
            sb.append("\tparams: ").append(parameterNames != null ? parameterNames : "<null>");
            return sb.toString();
        }

        @Override
        public FunctionNode getNode() {
            return node;
        }

        @Override
        public Scope variableScope() {
            return variableScope;
        }

        @Override
        public Scope lexicalScope() {
            return lexicalScope;
        }

        @Override
        public Set<Name> parameterNames() {
            return parameterNames;
        }

        @Override
        public Name arguments() {
            return arguments;
        }

        @Override
        public Set<Name> blockFunctionNames() {
            return emptyIfNull(blockFunctionNames);
        }

        @Override
        public boolean isDynamic() {
            return directEval && !IsStrict(node);
        }

        @Override
        public boolean hasEval() {
            return directEval;
        }

        @Override
        public boolean hasSuperReference() {
            return superReference;
        }

        @Override
        public boolean needsArguments() {
            return needsArguments || directEval;
        }

        @Override
        protected Name getDeclaredName(Name name) {
            if (arguments != null && arguments.equals(name)) {
                return arguments;
            }
            Name parameter = parameterNames.get(name);
            if (parameter != null) {
                return parameter;
            }
            if (variableScope == this && varDeclaredNames != null) {
                Name varName = varDeclaredNames.get(name);
                if (varName != null) {
                    return varName;
                }
            }
            if (variableScope == this) {
                Name varName = blockFunctionName(name);
                if (varName != null) {
                    return varName;
                }
            }
            if (lexicalScope == this && lexDeclaredNames != null) {
                Name lexName = lexDeclaredNames.get(name);
                if (lexName != null) {
                    return lexName;
                }
            }
            return null;
        }

        @Override
        public List<FunctionDeclaration> blockFunctions() {
            return emptyIfNull(blockFunctions);
        }
    }

    private static final class FunctionBodyContext extends ScopeContext {
        FunctionNode node;

        FunctionBodyContext(ScopeContext parent) {
            super(parent);
        }

        private FunctionContext functionContext() {
            return (FunctionContext) node.getScope();
        }

        private boolean isVarScope() {
            return functionContext().variableScope == this;
        }

        @Override
        public FunctionNode getNode() {
            return node;
        }

        @Override
        public boolean isDynamic() {
            return isVarScope() && functionContext().isDynamic();
        }

        @Override
        public boolean isPresent() {
            return super.isPresent() || isVarScope();
        }

        @Override
        protected Name getDeclaredName(Name name) {
            Name declaredName = super.getDeclaredName(name);
            if (declaredName == null && isVarScope()) {
                return functionContext().blockFunctionName(name);
            }
            return declaredName;
        }
    }

    private static final class ScriptContext extends TopContext implements ScriptScope {
        NameSet varForOfDeclaredNames;
        Script node;

        ScriptContext(ScopeContext enclosing) {
            super(enclosing);
        }

        @Override
        protected Name getDeclaredName(Name name) {
            if (node.isEvalScript() && node.isStrict() && varDeclaredNames != null) {
                Name varName = varDeclaredNames.get(name);
                if (varName != null) {
                    return varName;
                }
            }
            return super.getDeclaredName(name);
        }

        @Override
        public Script getNode() {
            return node;
        }

        @Override
        public Set<Name> varForOfDeclaredNames() {
            return emptyIfNull(varForOfDeclaredNames);
        }

        void addVarForOfDeclaredName(Name name) {
            if (varForOfDeclaredNames == null) {
                varForOfDeclaredNames = new NameSet();
            }
            varForOfDeclaredNames.add(name);
        }
    }

    private static final class ModuleContext extends TopContext implements ModuleScope {
        HashSet<String> exportNames = new HashSet<>();
        HashSet<Name> importBindings = new HashSet<>();
        LinkedHashMap<Name, Long> undeclaredExportBindings = new LinkedHashMap<>();
        Module node;

        ModuleContext(ScopeContext enclosing) {
            super(enclosing);
        }

        @Override
        protected Name getDeclaredName(Name name) {
            if (varDeclaredNames != null) {
                Name varName = varDeclaredNames.get(name);
                if (varName != null) {
                    return varName;
                }
            }
            return super.getDeclaredName(name);
        }

        @Override
        public Module getNode() {
            return node;
        }

        @Override
        public void addImplicitBinding(Name name) {
            addLexDeclaredName(name);
        }

        void addUndeclaredExportBinding(long position, Name name) {
            if (!undeclaredExportBindings.containsKey(name)) {
                undeclaredExportBindings.put(name, position);
            }
        }

        boolean addExportName(String exportName) {
            return exportNames.add(exportName);
        }

        boolean addImportBinding(Name name) {
            return importBindings.add(name);
        }
    }

    private static abstract class TopContext extends ScopeContext implements TopLevelScope {
        final ScopeContext enclosing;
        InlineArrayList<StatementListItem> varScopedDeclarations;

        TopContext(ScopeContext enclosing) {
            super();
            this.enclosing = enclosing;
        }

        final void addVarScopedDeclaration(StatementListItem decl) {
            if (varScopedDeclarations == null) {
                varScopedDeclarations = newList();
            }
            varScopedDeclarations.add(decl);
        }

        @Override
        public final ScopeContext getEnclosingScope() {
            return enclosing;
        }

        @Override
        public final Set<Name> lexicallyDeclaredNames() {
            return emptyIfNull(lexDeclaredNames);
        }

        @Override
        public final List<Declaration> lexicallyScopedDeclarations() {
            return emptyIfNull(lexScopedDeclarations);
        }

        @Override
        public final Set<Name> varDeclaredNames() {
            return emptyIfNull(varDeclaredNames);
        }

        @Override
        public final List<StatementListItem> varScopedDeclarations() {
            return emptyIfNull(varScopedDeclarations);
        }

        @Override
        public final boolean isPresent() {
            return true;
        }
    }

    private static final class BlockContext extends ScopeContext implements BlockScope {
        ScopedNode node;

        BlockContext(ScopeContext parent) {
            super(parent);
        }

        @Override
        public ScopedNode getNode() {
            return node;
        }

        @Override
        public Set<Name> lexicallyDeclaredNames() {
            return emptyIfNull(lexDeclaredNames);
        }

        @Override
        public List<Declaration> lexicallyScopedDeclarations() {
            return emptyIfNull(lexScopedDeclarations);
        }
    }

    private static final class CatchContext extends ScopeContext implements BlockScope {
        ScopedNode node;

        CatchContext(ScopeContext parent) {
            super(parent);
        }

        @Override
        public ScopedNode getNode() {
            return node;
        }

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public Set<Name> lexicallyDeclaredNames() {
            return emptyIfNull(lexDeclaredNames);
        }

        @Override
        public List<Declaration> lexicallyScopedDeclarations() {
            return emptyIfNull(lexScopedDeclarations);
        }
    }

    private static final class WithContext extends ScopeContext implements WithScope {
        WithStatement node;

        WithContext(ScopeContext parent) {
            super(parent);
        }

        @Override
        public WithStatement getNode() {
            return node;
        }

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public boolean isDynamic() {
            return true;
        }
    }

    private static final class FormalParameterContext extends ScopeContext {
        FormalParameter node;

        FormalParameterContext(ScopeContext parent) {
            super(parent);
        }

        @Override
        public FormalParameter getNode() {
            return node;
        }

        @Override
        public boolean isPresent() {
            return hasDirectEval();
        }
    }

    private static abstract class ScopeContext implements Scope {
        final ScopeContext parent;
        final TopContext top;
        private boolean directEval;

        NameSet varDeclaredNames;
        NameSet lexDeclaredNames;
        InlineArrayList<Declaration> lexScopedDeclarations;

        ScopeContext() {
            this.parent = null;
            this.top = (TopContext) this;
        }

        ScopeContext(ScopeContext parent) {
            this.parent = parent;
            this.top = parent.top;
        }

        protected Name getDeclaredName(Name name) {
            return lexDeclaredNames != null ? lexDeclaredNames.get(name) : null;
        }

        @Override
        public final Scope getParent() {
            return parent;
        }

        @Override
        public final TopLevelScope getTop() {
            return top;
        }

        @Override
        public final boolean isDeclared(Name name) {
            return getDeclaredName(name) != null;
        }

        @Override
        public final Name resolveName(Name name, boolean lookupByName) {
            Name declaredName = getDeclaredName(name);
            declaredName.resolve(this, lookupByName);
            return declaredName;
        }

        @Override
        public boolean isPresent() {
            return lexDeclaredNames != null;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public final Iterator<Scope> iterator() {
            return new ScopeIterator(this);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getClass().getSimpleName()).append('@').append(depth());
            sb.append("\tvar: ").append(varDeclaredNames != null ? varDeclaredNames : "<null>");
            sb.append("\tlex: ").append(lexDeclaredNames != null ? lexDeclaredNames : "<null>");
            return sb.toString();
        }

        private int depth() {
            int depth = 0;
            for (ScopeContext p = this.parent; p != null; p = p.parent) {
                depth += 1;
            }
            return depth;
        }

        final void setHasEval() {
            for (ScopeContext scope = this; scope != null && !scope.directEval;) {
                scope.directEval = true;
                ScopeContext parent = scope.parent;
                if (parent == null) {
                    parent = ((TopContext) scope).enclosing;
                }
                scope = parent;
            }
        }

        final boolean hasDirectEval() {
            return directEval;
        }

        final boolean allowVarDeclaredName(Name name) {
            return lexDeclaredNames == null || !lexDeclaredNames.contains(name);
        }

        final void addVarDeclaredNames(NameSet names) {
            if (varDeclaredNames == null) {
                varDeclaredNames = names;
            } else {
                varDeclaredNames.addAll(names);
            }
        }

        final boolean addVarDeclaredName(Name name) {
            if (varDeclaredNames == null) {
                varDeclaredNames = new NameSet();
            }
            varDeclaredNames.add(name);
            return lexDeclaredNames == null || !lexDeclaredNames.contains(name);
        }

        final boolean addLexDeclaredName(Name name) {
            if (lexDeclaredNames == null) {
                lexDeclaredNames = new NameSet();
            }
            return lexDeclaredNames.add(name)
                    && (varDeclaredNames == null || !varDeclaredNames.contains(name));
        }

        final void addLexScopedDeclaration(Declaration decl) {
            if (lexScopedDeclarations == null) {
                lexScopedDeclarations = newList();
            }
            lexScopedDeclarations.add(decl);
        }

        protected static final <T> Set<T> emptyIfNull(Set<T> list) {
            return list != null ? list : Collections.<T> emptySet();
        }

        protected static final <T> List<T> emptyIfNull(List<T> list) {
            return list != null ? list : Collections.<T> emptyList();
        }
    }

    private static final class ScopeIterator implements Iterator<Scope> {
        private ScopeContext scope;

        ScopeIterator(ScopeContext scope) {
            this.scope = scope;
        }

        @Override
        public boolean hasNext() {
            return scope != null;
        }

        @Override
        public Scope next() {
            ScopeContext s = scope;
            if (s == null) {
                throw new NoSuchElementException();
            }
            ScopeContext p = s.parent;
            if (p == null) {
                p = ((TopContext) s).enclosing;
            }
            scope = p;
            return s;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class LabelContext {
        final LabelContext parent;
        final StatementType type;
        final Set<String> labelSet;
        final EnumSet<Abrupt> abrupts = EnumSet.noneOf(Abrupt.class);

        LabelContext(LabelContext parent, StatementType type, Set<String> labelSet) {
            this.parent = parent;
            this.type = type;
            this.labelSet = labelSet;
        }

        void mark(Abrupt abrupt) {
            abrupts.add(abrupt);
        }
    }

    @SuppressWarnings("serial")
    private static final class RetryGenerator extends RuntimeException {
        public RetryGenerator() {
            super("RetryGenerator", null, false, false);
        }
    }

    public enum Option {
        /**
         * Strictness for source code.
         */
        Strict,

        /**
         * Source code is not global code.
         */
        FunctionCode,

        /**
         * Source code does not receive global object as this-binding.
         */
        FunctionThis,

        /**
         * Source code is not in global scope.
         */
        LocalScope,

        /**
         * Source code is direct eval code.
         */
        DirectEval,

        /**
         * Source code is eval code.
         */
        EvalScript,

        /**
         * Source code is nested in with-statement context.
         */
        EnclosedByWithStatement,

        /**
         * Source code is nested in lexical declaration context.
         */
        EnclosedByLexicalDeclaration,

        /**
         * Source is JSR-223 scripting.
         */
        Scripting,

        /**
         * Allow native call syntax.
         */
        NativeCall,

        /**
         * Parse functions as native.
         */
        NativeFunction,
    }

    public Parser(Source source, EnumSet<CompatibilityOption> options, EnumSet<Option> parserOptions) {
        this.source = source;
        this.options = EnumSet.copyOf(options);
        this.parserOptions = EnumSet.copyOf(parserOptions);
        context = new ParseContext();
        context.strictMode = this.parserOptions.contains(Option.Strict) ? StrictMode.Strict
                : StrictMode.NonStrict;

        // eval-script option must be set if one of the following options is used
        assert !(parserOptions.contains(Option.FunctionCode)
                || parserOptions.contains(Option.FunctionThis)
                || parserOptions.contains(Option.LocalScope)
                || parserOptions.contains(Option.DirectEval)
                || parserOptions.contains(Option.EnclosedByWithStatement) || parserOptions
                    .contains(Option.EnclosedByLexicalDeclaration))
                || (parserOptions.contains(Option.EvalScript)) : "Illegal option: " + parserOptions;

        // eval-script and scripting are mutually exclusive
        assert !(parserOptions.contains(Option.Scripting) && parserOptions
                .contains(Option.EvalScript)) : "Illegal option: " + parserOptions;
    }

    String getSourceName() {
        return source.getName();
    }

    int getSourceLine() {
        return source.getLine();
    }

    boolean isEnabled(CompatibilityOption option) {
        return options.contains(option);
    }

    boolean isEnabled(Option option) {
        return parserOptions.contains(option);
    }

    boolean isModule() {
        return moduleCode;
    }

    private ParseContext newContext(ContextKind kind) {
        return context = new ParseContext(context, kind);
    }

    private ParseContext restoreContext() {
        if (context.parent.strictError == null) {
            context.parent.strictError = context.strictError;
        }
        return context = context.parent;
    }

    private WithContext enterWithContext() {
        return enterScopeContext(new WithContext(context.scopeContext));
    }

    private ScopeContext exitWithContext() {
        return exitScopeContext();
    }

    private BlockContext enterBlockContext() {
        return enterScopeContext(new BlockContext(context.scopeContext));
    }

    private BlockContext enterBlockContext(Binding binding) {
        BlockContext cx = enterBlockContext();
        addLexDeclaredName(binding);
        return cx;
    }

    private BlockContext enterBlockContext(List<Binding> bindings) {
        BlockContext cx = enterBlockContext();
        addLexDeclaredNames(bindings);
        return cx;
    }

    private ScopeContext exitBlockContext() {
        return exitScopeContext();
    }

    private CatchContext enterCatchContext() {
        return enterScopeContext(new CatchContext(context.scopeContext));
    }

    private ScopeContext exitCatchContext() {
        return exitScopeContext();
    }

    private FunctionBodyContext enterFunctionBodyContext() {
        return enterScopeContext(new FunctionBodyContext(context.scopeContext));
    }

    private ScopeContext exitFunctionBodyContext() {
        return exitScopeContext();
    }

    private FormalParameterContext enterFormalParameterContext() {
        return enterScopeContext(new FormalParameterContext(context.scopeContext));
    }

    private ScopeContext exitFormalParameterContext() {
        return exitScopeContext();
    }

    private <SCOPE extends ScopeContext> SCOPE enterScopeContext(SCOPE scope) {
        context.scopeContext = scope;
        return scope;
    }

    private ScopeContext exitScopeContext() {
        ScopeContext scope = context.scopeContext;
        ScopeContext parent = scope.parent;
        assert parent != null : "exitScopeContext() on top-level";
        NameSet varDeclaredNames = scope.varDeclaredNames;
        if (varDeclaredNames != null) {
            parent.addVarDeclaredNames(varDeclaredNames);
            scope.varDeclaredNames = null;
        }
        return context.scopeContext = parent;
    }

    private void addFunctionDeclaration(FunctionDeclaration decl, boolean isNamedDefault) {
        addDeclaration(decl, isNamedDefault);
        if (isBlockScopedFunction()) {
            context.parent.funContext.addBlockFunction(decl);
        }
    }

    private void addDeclaration(HoistableDeclaration decl, boolean isNamedDefault) {
        ParseContext parentContext = context.parent;
        ScopeContext parentScope = parentContext.scopeContext;
        if (parentContext.kind.isScript() && parentScope == parentContext.scriptContext) {
            // top-level function declaration in script context
            addVarDeclaredName(decl, parentContext, BoundName(decl));
            parentContext.scriptContext.addVarScopedDeclaration(decl);
        } else if (parentContext.kind.isFunction()
                && parentScope == parentContext.funContext.lexicalScope) {
            // top-level function declaration in function context
            addVarDeclaredName(decl, parentContext, BoundName(decl));
            parentContext.funContext.addVarScopedDeclaration(decl);
        } else {
            // lexical-scoped function declaration in module/block context
            addLexDeclaredName(decl, parentContext, BoundName(decl));
            if (isNamedDefault) {
                // TODO: Better error message
                addLexDeclaredName(decl, parentContext, new Name(DEFAULT_EXPORT_BINDING_NAME));
            }
            parentScope.addLexScopedDeclaration(decl);
        }
    }

    private boolean isBlockScopedFunction() {
        ParseContext parentContext = context.parent;
        return parentContext.kind.isFunction()
                && (parentContext.funContext.lexicalScope != parentContext.scopeContext)
                && parentContext.strictMode != StrictMode.Strict
                && isEnabled(CompatibilityOption.BlockFunctionDeclaration);
    }

    private void addDeclaration(ClassDeclaration decl, boolean isNamedDefault) {
        addLexDeclaredName(decl, context, BoundName(decl));
        if (isNamedDefault) {
            // TODO: Better error message
            addLexDeclaredName(decl, context, new Name(DEFAULT_EXPORT_BINDING_NAME));
        }
        context.scopeContext.addLexScopedDeclaration(decl);
    }

    private void addDeclaration(ExportDefaultExpression defaultExpression) {
        assert context.scopeContext == context.modContext : "not in module scope";
        // TODO: Better error message
        addLexDeclaredName(defaultExpression, context, BoundName(defaultExpression.getBinding()));
        context.scopeContext.addLexScopedDeclaration(defaultExpression);
    }

    private void addLexScopedDeclaration(LexicalDeclaration decl) {
        context.scopeContext.addLexScopedDeclaration(decl);
    }

    private void addVarScopedDeclaration(VariableStatement decl) {
        context.topContext.addVarScopedDeclaration(decl);
    }
    private void addVarDeclaredName(BindingIdentifier bindingIdentifier) {
        Name name = BoundName(bindingIdentifier);
        addVarDeclaredName(bindingIdentifier, name);
    }

    private void addVarDeclaredName(BindingPattern bindingPattern) {
        for (Name name : BoundNames(bindingPattern)) {
            addVarDeclaredName(bindingPattern, name);
        }
    }

    /**
     * <strong>[13.2] Block</strong>
     * <p>
     * Static Semantics: Early Errors<br>
     * <ul>
     * <li>It is a Syntax Error if any element of the LexicallyDeclaredNames of StatementList also
     * occurs in the VarDeclaredNames of StatementList.
     * </ul>
     * 
     * @param binding
     *            the binding to add to the variable scope
     * @param name
     *            the var declared name
     */
    private void addVarDeclaredName(Binding binding, Name name) {
        addVarDeclaredName(binding, context, name);
        for (ScopeContext next = context.scopeContext.parent; next != null; next = next.parent) {
            if (!next.allowVarDeclaredName(name)) {
                if (isEnabled(CompatibilityOption.CatchVarStatement)
                        && next instanceof CatchContext) {
                    continue;
                }
                reportSyntaxError(binding, Messages.Key.VariableRedeclaration, name);
            }
        }
    }

    private void addVarDeclaredName(Node node, ParseContext context, Name name) {
        if (!context.scopeContext.addVarDeclaredName(name)) {
            reportSyntaxError(node, Messages.Key.VariableRedeclaration, name);
        }
    }

    private void checkVarDeclaredName(Binding binding) {
        if (binding instanceof BindingIdentifier) {
            checkVarDeclaredName((BindingIdentifier) binding);
        } else {
            assert binding instanceof BindingPattern;
            checkVarDeclaredName((BindingPattern) binding);
        }
    }

    private void checkVarDeclaredName(BindingIdentifier bindingIdentifier) {
        Name name = BoundName(bindingIdentifier);
        checkVarDeclaredName(bindingIdentifier, name);
    }

    private void checkVarDeclaredName(BindingPattern bindingPattern) {
        for (Name name : BoundNames(bindingPattern)) {
            checkVarDeclaredName(bindingPattern, name);
        }
    }

    private void checkVarDeclaredName(Binding binding, Name name) {
        ScopeContext scope = context.scopeContext;
        for (ScopeContext parent; (parent = scope.parent) != null; scope = parent) {
            if (!parent.allowVarDeclaredName(name)) {
                reportSyntaxError(binding, Messages.Key.VariableRedeclaration, name);
            }
        }
        if (context.kind.isScript() && isEnabled(Option.EvalScript)) {
            context.scriptContext.addVarForOfDeclaredName(name);
        }
    }

    /**
     * <strong>[13.2] Block</strong>
     * <p>
     * Static Semantics: Early Errors<br>
     * <ul>
     * <li>It is a Syntax Error if the LexicallyDeclaredNames of StatementList contains any
     * duplicate entries.
     * <li>It is a Syntax Error if any element of the LexicallyDeclaredNames of StatementList also
     * occurs in the VarDeclaredNames of StatementList.
     * </ul>
     * 
     * @param binding
     *            the binding to add to the lexical scope
     */
    private void addLexDeclaredName(Binding binding) {
        if (binding instanceof BindingIdentifier) {
            addLexDeclaredName((BindingIdentifier) binding);
        } else {
            assert binding instanceof BindingPattern;
            addLexDeclaredName((BindingPattern) binding);
        }
    }

    private void addLexDeclaredName(BindingIdentifier bindingIdentifier) {
        Name name = BoundName(bindingIdentifier);
        addLexDeclaredName(bindingIdentifier, context, name);
    }

    private void addLexDeclaredName(BindingPattern bindingPattern) {
        for (Name name : BoundNames(bindingPattern)) {
            addLexDeclaredName(bindingPattern, context, name);
        }
    }

    private void addLexDeclaredName(Node node, ParseContext context, Name name) {
        if (context.isIllegalName(name) || !context.scopeContext.addLexDeclaredName(name)) {
            reportSyntaxError(node, Messages.Key.VariableRedeclaration, name);
        }
    }

    private void addLexDeclaredNames(List<Binding> bindings) {
        for (Binding binding : bindings) {
            addLexDeclaredName(binding);
        }
    }

    private NameSet lexicalNames(List<Binding> bindings) {
        NameSet names = new NameSet();
        for (Binding binding : bindings) {
            if (binding instanceof BindingIdentifier) {
                names.add(BoundName((BindingIdentifier) binding));
            } else {
                assert binding instanceof BindingPattern;
                names.addAll(BoundNames((BindingPattern) binding));
            }
        }
        return names;
    }

    private void addExportBindings(long sourcePosition, List<Name> names) {
        for (Name name : names) {
            addExportBinding(sourcePosition, name);
        }
    }

    private void addExportBinding(long sourcePosition, Name name) {
        assert context.scopeContext == context.modContext : "not in module scope";
        if (!context.modContext.isDeclared(name)) {
            context.modContext.addUndeclaredExportBinding(sourcePosition, name);
        }
    }

    private void addExportBinding(long sourcePosition, String name) {
        addExportBinding(sourcePosition, new Name(name));
    }

    private void addExportNames(ExportClause exportClause) {
        IdentifierName defaultEntry = exportClause.getDefaultEntry();
        if (defaultEntry != null) {
            addExportName(defaultEntry.getBeginPosition(), defaultEntry.getName());
        }
        IdentifierName nameSpace = exportClause.getNameSpace();
        if (nameSpace != null) {
            addExportName(nameSpace.getBeginPosition(), nameSpace.getName());
        }
        for (ExportSpecifier export : exportClause.getExports()) {
            addExportName(export.getBeginPosition(), export.getExportName());
        }
    }

    private void addExportNames(long sourcePosition, List<Name> names) {
        for (Name name : names) {
            addExportName(sourcePosition, name);
        }
    }

    private void addExportName(long sourcePosition, Name name) {
        addExportName(sourcePosition, name.getIdentifier());
    }

    private void addExportName(long sourcePosition, String name) {
        assert context.scopeContext == context.modContext : "not in module scope";
        if (!context.modContext.addExportName(name)) {
            reportSyntaxError(sourcePosition, Messages.Key.DuplicateExport, name);
        }
    }

    private void addImportBinding(BindingIdentifier bindingIdentifier) {
        assert context.scopeContext == context.modContext : "not in module scope";
        Name name = BoundName(bindingIdentifier);
        if (!context.modContext.addImportBinding(name)) {
            reportSyntaxError(bindingIdentifier, Messages.Key.DuplicateImport, name);
        }
    }

    private void addLabel(long sourcePosition, LinkedHashSet<String> labelSet, String label) {
        if ((context.labelSet != null && context.labelSet.containsKey(label))
                || !labelSet.add(label)) {
            reportSyntaxError(sourcePosition, Messages.Key.DuplicateLabel, label);
        }
    }

    private LabelContext enterLabelled(StatementType type, Set<String> labelSet) {
        LabelContext cx = context.labels = new LabelContext(context.labels, type, labelSet);
        if (!labelSet.isEmpty() && context.labelSet == null) {
            context.labelSet = new HashMap<>();
        }
        for (String label : labelSet) {
            assert !context.labelSet.containsKey(label);
            context.labelSet.put(label, cx);
        }
        return cx;
    }

    private LabelContext exitLabelled() {
        for (String label : context.labels.labelSet) {
            context.labelSet.remove(label);
        }
        return context.labels = context.labels.parent;
    }

    private LabelContext enterIteration(Set<String> labelSet) {
        return enterLabelled(StatementType.Iteration, labelSet);
    }

    private void exitIteration() {
        exitLabelled();
    }

    private LabelContext enterBreakable(Set<String> labelSet) {
        return enterLabelled(StatementType.Breakable, labelSet);
    }

    private void exitBreakable() {
        exitLabelled();
    }

    private LabelContext findContinueTarget(String label) {
        for (LabelContext cx = context.labels; cx != null; cx = cx.parent) {
            if (label == null ? cx.type == StatementType.Iteration : cx.labelSet.contains(label)) {
                return cx;
            }
        }
        return null;
    }

    private LabelContext findBreakTarget(String label) {
        for (LabelContext cx = context.labels; cx != null; cx = cx.parent) {
            if (label == null ? cx.type != StatementType.Statement : cx.labelSet.contains(label)) {
                return cx;
            }
        }
        return null;
    }

    private static void setFunctionName(Expression expr, BindingIdentifier identifier) {
        setFunctionName(expr, identifier.getName().getIdentifier());
    }

    private static void setFunctionName(Expression expr, IdentifierReference identifier) {
        setFunctionName(expr, identifier.getName());
    }

    private static void setFunctionName(Expression expr, PropertyName propertyName) {
        assert !(propertyName instanceof ComputedPropertyName);
        setFunctionName(expr, propertyName.getName());
    }

    private static void setFunctionName(Expression expr, ComputedPropertyName propertyName) {
        setFunctionName(expr, propertyName.toString());
    }

    private static void setFunctionName(Expression expr, String name) {
        if (expr instanceof ClassExpression) {
            for (MethodDefinition def : ((ClassExpression) expr).getMethods()) {
                def.setClassName(name);
            }
        } else {
            assert expr instanceof FunctionNode : expr.getClass();
            ((FunctionNode) expr).setFunctionName(name);
        }
    }

    private static void setMethodName(Expression expr, LeftHandSideExpression lhs) {
        String name = MethodNameVisitor.toMethodName(lhs);
        if (name != null && expr instanceof FunctionNode) {
            ((FunctionNode) expr).setMethodName(name);
        }
    }

    private static <T> InlineArrayList<T> newList() {
        return new InlineArrayList<>();
    }

    private static <T> List<T> merge(List<T> list1, List<T> list2) {
        if (!(list1.isEmpty() || list2.isEmpty())) {
            List<T> merged = new ArrayList<>(list1.size() + list2.size());
            merged.addAll(list1);
            merged.addAll(list2);
            return merged;
        }
        return list1.isEmpty() ? list2 : list1;
    }

    private static int toLine(long sourcePosition) {
        return (int) sourcePosition;
    }

    private static int toColumn(long sourcePosition) {
        return (int) (sourcePosition >>> 32);
    }

    private long beginSource() {
        // make columns 1-indexed
        return ((long) 1 << 32) | getSourceLine();
    }

    private ParserException reportException(ParserException exception) {
        throw exception;
    }

    /**
     * Report mismatched token error from tokenstream's current position.
     * 
     * @param expected
     *            the expected token
     * @param actual
     *            the actual token in the token stream
     * @return the parser exception
     */
    private ParserException reportTokenMismatch(Token expected, Token actual) {
        throw reportTokenMismatch(expected.toString(), actual);
    }

    /**
     * Report mismatched token error from tokenstream's current position.
     * 
     * @param actual
     *            the actual token in the token stream
     * @return the parser exception
     */
    private ParserException reportTokenNotIdentifier(Token actual) {
        if (Token.isIdentifierName(actual)) {
            throw reportSyntaxError(Messages.Key.InvalidIdentifier, getName(actual));
        }
        throw reportTokenMismatch("<identifier>", actual);
    }

    /**
     * Report mismatched token error from tokenstream's current position.
     * 
     * @param actual
     *            the actual token in the token stream
     * @return the parser exception
     */
    private ParserException reportTokenNotIdentifierName(Token actual) {
        throw reportTokenMismatch("<identifier-name>", actual);
    }

    /**
     * Report mismatched token error from tokenstream's current position.
     * 
     * @param expected
     *            the expected token description
     * @param actual
     *            the actual token in the token stream
     * @return the parser exception
     */
    private ParserException reportTokenMismatch(String expected, Token actual) {
        if (actual == Token.EOF) {
            throw reportEofError(ts.sourcePosition(), Messages.Key.UnexpectedEndOfFile, expected);
        }
        if (actual == Token.ERROR) {
            throw reportError(ExceptionType.SyntaxError, ts.sourcePosition(),
                    Messages.Key.UnexpectedCharacter, String.valueOf(ts.lastChar()), expected);
        }
        throw reportError(ExceptionType.SyntaxError, ts.sourcePosition(),
                Messages.Key.UnexpectedToken, actual.toString(), expected);
    }

    /**
     * Report parser eof-error with the given position.
     * 
     * @param sourcePosition
     *            the source position for the error
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     * @return the parser exception
     */
    private ParserEOFException reportEofError(long sourcePosition, Messages.Key messageKey,
            String... args) {
        int line = toLine(sourcePosition), column = toColumn(sourcePosition);
        throw new ParserEOFException(getSourceName(), line, column, messageKey, args);
    }

    /**
     * Report parser error with the given type and position.
     * 
     * @param type
     *            the exception type for the error
     * @param sourcePosition
     *            the source position for the error
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     * @return the parser exception
     */
    private ParserException reportError(ExceptionType type, long sourcePosition,
            Messages.Key messageKey, String... args) {
        int line = toLine(sourcePosition), column = toColumn(sourcePosition);
        throw new ParserException(type, getSourceName(), line, column, messageKey, args);
    }

    /**
     * Report syntax error from the given position.
     * 
     * @param sourcePosition
     *            the source position for the error
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     * @return the parser exception
     */
    private ParserException reportSyntaxError(long sourcePosition, Messages.Key messageKey,
            String... args) {
        throw reportError(ExceptionType.SyntaxError, sourcePosition, messageKey, args);
    }

    /**
     * Report syntax error from the node's begin source-position.
     * 
     * @param node
     *            the node which could not be parsed without errors
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     * @return the parser exception
     */
    private ParserException reportSyntaxError(Node node, Messages.Key messageKey, String... args) {
        throw reportSyntaxError(node.getBeginPosition(), messageKey, args);
    }

    /**
     * Report syntax error from tokenstream's current position.
     * 
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     * @return the parser exception
     */
    private ParserException reportSyntaxError(Messages.Key messageKey, String... args) {
        throw reportSyntaxError(ts.sourcePosition(), messageKey, args);
    }

    /**
     * Report syntax error from the node's begin source-position.
     * 
     * @param node
     *            the node which could not be parsed without errors
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     * @return the parser exception
     */
    private ParserException reportSyntaxError(Node node, Messages.Key messageKey, Name name) {
        throw reportSyntaxError(node.getBeginPosition(), messageKey, name.getIdentifier());
    }

    /**
     * Report (or store) strict-mode parser error with the given type and position.
     * 
     * @param type
     *            the exception type for the error
     * @param sourcePosition
     *            the source position for the error
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     */
    void reportStrictModeError(ExceptionType type, long sourcePosition, Messages.Key messageKey,
            String... args) {
        if (context.strictMode == StrictMode.Unknown) {
            if (context.strictError == null) {
                int line = toLine(sourcePosition), column = toColumn(sourcePosition);
                context.strictError = new ParserException(type, getSourceName(), line, column,
                        messageKey, args);
            }
        } else if (context.strictMode == StrictMode.Strict) {
            reportError(type, sourcePosition, messageKey, args);
        }
    }

    /**
     * Report (or store) strict-mode syntax error from the given position.
     * 
     * @param sourcePosition
     *            the source position for the error
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     */
    private void reportStrictModeSyntaxError(long sourcePosition, Messages.Key messageKey,
            String... args) {
        reportStrictModeError(ExceptionType.SyntaxError, sourcePosition, messageKey, args);
    }

    /**
     * Report (or store) strict-mode syntax error from the node's source-position.
     * 
     * @param node
     *            the node which could not be parsed without errors
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     */
    private void reportStrictModeSyntaxError(Node node, Messages.Key messageKey, String... args) {
        reportStrictModeError(ExceptionType.SyntaxError, node.getBeginPosition(), messageKey, args);
    }

    /**
     * Report (or store) strict-mode syntax error from tokenstream's current position.
     * 
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     */
    private void reportStrictModeSyntaxError(Messages.Key messageKey, String... args) {
        reportStrictModeError(ExceptionType.SyntaxError, ts.sourcePosition(), messageKey, args);
    }

    /**
     * Report (or store) strict-mode syntax error from the node's source-position.
     * 
     * @param node
     *            the node which could not be parsed without errors
     * @param messageKey
     *            the error message key
     * @param args
     *            the error message arguments
     */
    private void reportStrictModeSyntaxError(Node node, Messages.Key messageKey, Name name) {
        reportStrictModeError(ExceptionType.SyntaxError, node.getBeginPosition(), messageKey,
                name.getIdentifier());
    }

    /**
     * Peeks the next token in the token-stream.
     * 
     * @return the next token
     */
    private Token peek() {
        return ts.peekToken();
    }

    /**
     * Checks whether the next token in the token-stream is equal to the input token.
     *
     * @param token
     *            the token to test
     * @return {@code true} if the next token matches
     */
    private boolean LOOKAHEAD(Token token) {
        return ts.peekToken() == token;
    }

    /**
     * Returns the current token in the token-stream.
     * 
     * @return the current token
     */
    private Token token() {
        return ts.currentToken();
    }

    /**
     * Consumes the current token in the token-stream and advances the stream to the next token.
     * 
     * @param tok
     *            the token to consume
     */
    private void consume(Token tok) {
        if (tok != token())
            reportTokenMismatch(tok, token());
        Token next = ts.nextToken();
        if (DEBUG)
            System.out.printf("consume(%s) -> %s\n", tok, next);
    }

    /**
     * Consumes the current token in the token-stream and advances the stream to the next token.
     * 
     * @param name
     *            the name string to consume
     */
    private void consume(String name) {
        long sourcePos = ts.sourcePosition();
        String string = ts.getString();
        consume(Token.NAME);
        if (!name.equals(string))
            reportSyntaxError(sourcePos, Messages.Key.UnexpectedName, string, name);
    }

    /**
     * Parses the input source as script code.
     * 
     * @param source
     *            the source string to parse
     * @return the parsed script
     * @throws ParserException
     *             if the input source could not be parsed successfully
     */
    public Script parseScript(String source) throws ParserException {
        if (ts != null)
            throw new IllegalStateException();
        ts = new TokenStream(this, new TokenStreamInput(source));
        return script();
    }

    /**
     * Parses the input source as module code.
     * 
     * @param source
     *            the source string to parse
     * @return the parsed module
     * @throws ParserException
     *             if the input source could not be parsed successfully
     */
    public Module parseModule(String source) throws ParserException {
        if (ts != null)
            throw new IllegalStateException();
        ts = new TokenStream(this, new TokenStreamInput(source));
        moduleCode = true;
        return module();
    }

    /**
     * Parses the input source as function code.
     * 
     * @param formals
     *            the function formal parameters source
     * @param bodyText
     *            the function body source text
     * @return the parsed function
     * @throws ParserException
     *             if the input source could not be parsed successfully
     */
    public FunctionDefinition parseFunction(String formals, String bodyText) throws ParserException {
        if (ts != null)
            throw new IllegalStateException();

        newContext(ContextKind.Script);
        try {
            applyStrictMode(false);

            FunctionDeclaration function;
            newContext(ContextKind.Function);
            try {
                ts = new TokenStream(this, new TokenStreamInput(formals)).initialize();
                FormalParameterList parameters = formalParameters(Token.EOF);
                if (token() != Token.EOF) {
                    reportSyntaxError(Messages.Key.InvalidFormalParameterList);
                }
                String header = formatParameters(formals, ts.position());

                ts = new TokenStream(this, new TokenStreamInput(bodyText)).initialize();
                List<StatementListItem> statements = functionBody(parameters, Token.EOF);
                if (token() != Token.EOF) {
                    reportSyntaxError(Messages.Key.InvalidFunctionBody);
                }
                String body = formatBody(bodyText);

                String functionName = "anonymous";
                BindingIdentifier identifier = new BindingIdentifier(beginSource(), beginSource(),
                        functionName);

                FunctionContext scope = context.funContext;
                function = new FunctionDeclaration(beginSource(), ts.endPosition(), scope,
                        identifier, parameters, statements, functionName, header, body);
                scope.setNode(function);

                function_EarlyErrors(function);

                function = inheritStrictness(function);
            } catch (RetryGenerator e) {
                // don't bother with legacy support here
                throw reportSyntaxError(Messages.Key.InvalidYieldExpression);
            } finally {
                restoreContext();
            }

            createScript(function);

            return function;
        } finally {
            restoreContext();
        }
    }

    /**
     * Parses the input source as generator function code.
     * 
     * @param formals
     *            the function formal parameters source
     * @param bodyText
     *            the function body source text
     * @return the parsed generator function
     * @throws ParserException
     *             if the input source could not be parsed successfully
     */
    public GeneratorDefinition parseGenerator(String formals, String bodyText)
            throws ParserException {
        if (ts != null)
            throw new IllegalStateException();

        newContext(ContextKind.Script);
        try {
            applyStrictMode(false);

            GeneratorDeclaration generator;
            newContext(ContextKind.Generator);
            try {
                ts = new TokenStream(this, new TokenStreamInput(formals)).initialize();
                FormalParameterList parameters = formalParameters(Token.EOF);
                if (token() != Token.EOF) {
                    reportSyntaxError(Messages.Key.InvalidFormalParameterList);
                }
                String header = formatParameters(formals, ts.position());

                ts = new TokenStream(this, new TokenStreamInput(bodyText)).initialize();
                List<StatementListItem> statements = functionBody(parameters, Token.EOF);
                if (token() != Token.EOF) {
                    reportSyntaxError(Messages.Key.InvalidFunctionBody);
                }
                String body = formatBody(bodyText);

                String functionName = "anonymous";
                BindingIdentifier identifier = new BindingIdentifier(beginSource(), beginSource(),
                        functionName);

                FunctionContext scope = context.funContext;
                generator = new GeneratorDeclaration(beginSource(), ts.endPosition(), scope,
                        identifier, parameters, statements, functionName, header, body);
                scope.setNode(generator);

                generator_EarlyErrors(generator);

                generator = inheritStrictness(generator);
            } finally {
                restoreContext();
            }

            createScript(generator);

            return generator;
        } finally {
            restoreContext();
        }
    }

    /**
     * Parses the input source as async function code.
     * 
     * @param formals
     *            the function formal parameters source
     * @param bodyText
     *            the function body source text
     * @return the parsed async function
     * @throws ParserException
     *             if the input source could not be parsed successfully
     */
    public AsyncFunctionDefinition parseAsyncFunction(String formals, String bodyText)
            throws ParserException {
        if (ts != null)
            throw new IllegalStateException();

        newContext(ContextKind.Script);
        try {
            applyStrictMode(false);

            AsyncFunctionDeclaration asyncFunction;
            newContext(ContextKind.AsyncFunction);
            try {
                ts = new TokenStream(this, new TokenStreamInput(formals)).initialize();
                FormalParameterList parameters = formalParameters(Token.EOF);
                if (token() != Token.EOF) {
                    reportSyntaxError(Messages.Key.InvalidFormalParameterList);
                }
                String header = formatParameters(formals, ts.position());

                ts = new TokenStream(this, new TokenStreamInput(bodyText)).initialize();
                List<StatementListItem> statements = functionBody(parameters, Token.EOF);
                if (token() != Token.EOF) {
                    reportSyntaxError(Messages.Key.InvalidFunctionBody);
                }
                String body = formatBody(bodyText);

                String functionName = "anonymous";
                BindingIdentifier identifier = new BindingIdentifier(beginSource(), beginSource(),
                        functionName);

                FunctionContext scope = context.funContext;
                asyncFunction = new AsyncFunctionDeclaration(beginSource(), ts.endPosition(), scope,
                        identifier, parameters, statements, functionName, header, body);
                scope.setNode(asyncFunction);

                asyncFunction_EarlyErrors(asyncFunction);

                asyncFunction = inheritStrictness(asyncFunction);
            } finally {
                restoreContext();
            }

            createScript(asyncFunction);

            return asyncFunction;
        } finally {
            restoreContext();
        }
    }

    private <FUNDECL extends Declaration & FunctionNode> Script createScript(FUNDECL funDeclaration) {
        List<StatementListItem> statements = singletonList((StatementListItem) funDeclaration);
        boolean strict = (context.strictMode == StrictMode.Strict);

        ScriptContext scope = context.scriptContext;
        Script script = new Script(beginSource(), ts.endPosition(), source, scope, statements,
                options, parserOptions, strict);
        scope.node = script;

        return script;
    }

    private static String formatParameters(String formals, int lastParametersTokenPosition) {
        boolean unsafeChars = hasUnsafeTrailingCharacters(formals, lastParametersTokenPosition);
        StringBuilder sb = new StringBuilder(formals.length() + 3 + (unsafeChars ? 2 : 0));
        sb.append('(');
        if (unsafeChars) {
            // More input after last token (whitespace, comments), add newlines to handle
            // last token is single-line comment case.
            sb.append('\n').append(formals).append('\n');
        } else {
            sb.append(formals);
        }
        return sb.append(')').append(' ').toString();
    }

    private static String formatBody(String body) {
        return new StringBuilder(body.length() + 2).append('\n').append(body).append('\n')
                .toString();
    }

    private static boolean hasUnsafeTrailingCharacters(String s, int start) {
        for (int i = s.length() - 1; i > start; --i) {
            char c = s.charAt(i);
            if (Characters.isLineTerminator(c)) {
                break;
            }
            if (!Characters.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    /* ***************************************************************************************** */

    /**
     * <strong>[15.1] Scripts</strong>
     * 
     * <pre>
     * Script :
     *     ScriptBody<span><sub>opt</sub></span>
     * ScriptBody :
     *     StatementList
     * </pre>
     * 
     * @return the parsed script node
     */
    private Script script() {
        newContext(ContextKind.Script);
        try {
            ts.initialize();
            List<StatementListItem> prologue = directivePrologue();
            List<StatementListItem> body = statementList(Token.EOF);
            List<StatementListItem> statements = merge(prologue, body);
            assert context.assertLiteralsUnchecked(0);
            boolean strict = (context.strictMode == StrictMode.Strict);

            ScriptContext scope = context.scriptContext;
            Script script = new Script(beginSource(), ts.endPosition(), source, scope, statements,
                    options, parserOptions, strict);
            scope.node = script;

            return script;
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[15.2] Modules</strong>
     * 
     * <pre>
     * Module :
     *     ModuleBody<span><sub>opt</sub></span>
     * ModuleBody :
     *     ModuleItemList
     * </pre>
     * 
     * @return the parsed module node
     */
    private Module module() {
        newContext(ContextKind.Module);
        context.strictMode = StrictMode.Strict;
        try {
            ts.initialize();
            List<ModuleItem> statements = moduleItemList();
            assert context.assertLiteralsUnchecked(0);

            ModuleContext scope = context.modContext;
            Module module = new Module(beginSource(), ts.endPosition(), source, scope, statements,
                    options, parserOptions);
            scope.node = module;

            module_EarlyErrors();

            return module;
        } finally {
            restoreContext();
        }
    }

    /**
     * 15.2.1.1 Static Semantics: Early Errors
     */
    private void module_EarlyErrors() {
        assert context.scopeContext == context.modContext;

        ModuleContext scope = context.modContext;
        if (!scope.undeclaredExportBindings.isEmpty()) {
            for (Map.Entry<Name, Long> export : scope.undeclaredExportBindings.entrySet()) {
                Name exportBinding = export.getKey();
                if (!scope.isDeclared(exportBinding)) {
                    reportSyntaxError(export.getValue(), Messages.Key.MissingExportBinding,
                            exportBinding.getIdentifier());
                }
            }
        }
        scope.exportNames = null;
        scope.undeclaredExportBindings = null;
        scope.importBindings = null;
    }

    /**
     * <strong>[15.2] Modules</strong>
     * 
     * <pre>
     * ModuleItemList :
     *     ModuleItem
     *     ModuleItemList ModuleItem
     * ModuleItem :
     *     ImportDeclaration
     *     ExportDeclaration
     *     StatementListItem
     * </pre>
     * 
     * @return the list of parsed module items
     */
    private List<ModuleItem> moduleItemList() {
        InlineArrayList<ModuleItem> moduleItemList = newList();
        while (token() != Token.EOF) {
            switch (token()) {
            case EXPORT:
                moduleItemList.add(exportDeclaration());
                break;
            case IMPORT:
                moduleItemList.add(importDeclaration());
                break;
            default:
                moduleItemList.add(statementListItem());
            }
        }
        return moduleItemList;
    }

    /**
     * <strong>[15.2.2] Imports</strong>
     * 
     * <pre>
     * ImportDeclaration :
     *     import ImportClause FromClause ;
     *     import ModuleSpecifier ;
     * </pre>
     * 
     * @return the parsed import declaration
     */
    private ImportDeclaration importDeclaration() {
        long begin = ts.beginPosition();
        consume(Token.IMPORT);
        ImportDeclaration decl;
        if (token() != Token.STRING) {
            ImportClause importClause = importClause();
            String moduleSpecifier = fromClause();
            semicolon();
            decl = new ImportDeclaration(begin, ts.endPosition(), importClause, moduleSpecifier);
        } else {
            String moduleSpecifier = moduleSpecifier();
            semicolon();
            decl = new ImportDeclaration(begin, ts.endPosition(), moduleSpecifier);
        }
        return decl;
    }

    /**
     * <strong>[15.2.2] Imports</strong>
     * 
     * <pre>
     * FromClause :
     *     from ModuleSpecifier
     * </pre>
     * 
     * @return the parsed from-clause's module specifier
     */
    private String fromClause() {
        consume("from");
        return moduleSpecifier();
    }

    /**
     * <strong>[15.2.2] Imports</strong>
     * 
     * <pre>
     * ImportClause :
     *     ImportedDefaultBinding
     *     NameSpaceImport
     *     NamedImports
     *     ImportedDefaultBinding , NameSpaceImport
     *     ImportedDefaultBinding , NamedImports
     * ImportedDefaultBinding :
     *     ImportedBinding
     * </pre>
     * 
     * @return the parsed import clause
     */
    private ImportClause importClause() {
        long begin = ts.beginPosition();
        BindingIdentifier defaultEntry = null;
        BindingIdentifier nameSpace = null;
        List<ImportSpecifier> namedImports = emptyList();
        if (isEnabled(CompatibilityOption.TypeAnnotation)) {
            // import typeof <ImportClause> from "module";
            // import type <ImportClause> from "module";
            if (token() == Token.TYPEOF || (isName("type") && !(peek() == Token.COMMA || isNextName("from")))) {
                consume(token());
            }
        }
        if (token() == Token.LC) {
            namedImports = namedImports();
        } else if (token() == Token.MUL) {
            nameSpace = nameSpaceImport();
            addImportBinding(nameSpace);
            addLexDeclaredName(nameSpace);
        } else {
            defaultEntry = importedBinding();
            addImportBinding(defaultEntry);
            addLexDeclaredName(defaultEntry);

            if (token() == Token.COMMA) {
                consume(Token.COMMA);
                if (token() == Token.LC) {
                    namedImports = namedImports();
                } else {
                    nameSpace = nameSpaceImport();
                    addImportBinding(nameSpace);
                    addLexDeclaredName(nameSpace);
                }
            }
        }
        return new ImportClause(begin, ts.endPosition(), defaultEntry, namedImports, nameSpace);
    }

    /**
     * <strong>[15.2.2] Imports</strong>
     * 
     * <pre>
     * NameSpaceImport :
     *     * as ImportedBinding
     * </pre>
     * 
     * @return the parsed namespace import binding
     */
    private BindingIdentifier nameSpaceImport() {
        // TODO: Add new ast node?
        consume(Token.MUL);
        consume("as");
        return importedBinding();
    }

    /**
     * <strong>[15.2.2] Imports</strong>
     * 
     * <pre>
     * NamedImports :
     *     { } 
     *     { ImportsList }
     *     { ImportsList , }
     * ImportsList :
     *     ImportSpecifier
     *     ImportsList , ImportSpecifier
     * </pre>
     * 
     * @return the list of parsed named imports
     */
    private List<ImportSpecifier> namedImports() {
        InlineArrayList<ImportSpecifier> namedImports = newList();
        consume(Token.LC);
        while (token() != Token.RC) {
            namedImports.add(importSpecifier());
            if (token() == Token.COMMA) {
                consume(Token.COMMA);
            } else {
                break;
            }
        }
        consume(Token.RC);
        return namedImports;
    }

    /**
     * <strong>[15.2.2] Imports</strong>
     * 
     * <pre>
     * ImportSpecifier :
     *     ImportedBinding
     *     IdentifierName as ImportedBinding
     * </pre>
     * 
     * @return the parsed import specifier
     */
    private ImportSpecifier importSpecifier() {
        long begin = ts.beginPosition();
        String importName;
        BindingIdentifier localName;
        if (Token.isIdentifierName(token()) && importSpecifierFollowSet(peek())) {
            BindingIdentifier binding = importedBinding();
            importName = binding.getName().getIdentifier();
            localName = binding;
        } else {
            importName = identifierName();
            consume("as");
            localName = importedBinding();
        }
        addImportBinding(localName);
        addLexDeclaredName(localName);

        return new ImportSpecifier(begin, ts.endPosition(), importName, localName);
    }

    /**
     * Returns FOLLOW(ImportSpecifier): « <tt>,</tt> , <tt>}</tt> »
     *
     * @param token
     *            the token to test
     * @return {@code true} if token is in the follow-set
     */
    private boolean importSpecifierFollowSet(Token token) {
        switch (token) {
        case COMMA:
        case RC:
            return true;
        default:
            return false;
        }
    }

    /**
     * <strong>[15.2.2] Imports</strong>
     * 
     * <pre>
     * ModuleSpecifier :
     *     StringLiteral
     * </pre>
     * 
     * @return the parsed module specifier string
     */
    private String moduleSpecifier() {
        return stringLiteral();
    }

    /**
     * <strong>[15.2.2] Imports</strong>
     * 
     * <pre>
     * ImportedDefaultBinding :
     *     ImportedBinding
     * ImportedBinding :
     *     BindingIdentifier
     * </pre>
     * 
     * @return the parsed imported binding node
     */
    private BindingIdentifier importedBinding() {
        return bindingIdentifier();
    }

    /**
     * <strong>[15.2.3] Exports</strong>
     * 
     * <pre>
     * ExportDeclaration :
     *     export * FromClause ;
     *     export ExportClause FromClause ;
     *     export ExportClause ;
     *     export VariableStatement
     *     export Declaration
     *     export default HoistableDeclaration<span><sub>[Default]</sub></span>
     *     export default ClassDeclaration<span><sub>[Default]</sub></span>
     *     export default [LA &#x2209; { <b>function</b>, <b>class</b> }] AssignmentExpression<span><sub>[In]</sub></span> ;
     * </pre>
     * 
     * @return the parsed export declaration
     */
    private ExportDeclaration exportDeclaration() {
        long begin = ts.beginPosition();
        consume(Token.EXPORT);
        switch (token()) {
        case MUL: {
            // export * FromClause ;
            // Extension: export ExportFromClause FromClause ;
            long beginExportAll = ts.beginPosition();
            consume(Token.MUL);
            if (isEnabled(CompatibilityOption.ExportFrom) && isName("as")) {
                ExportClause exportClause = exportNameSpaceFromClause(beginExportAll);
                String moduleSpecifier = fromClause();
                semicolon();

                // 15.2.3.4 Static Semantics: ExportedNames
                addExportNames(exportClause);

                return new ExportDeclaration(begin, ts.endPosition(), exportClause, moduleSpecifier);
            }
            String moduleSpecifier = fromClause();
            semicolon();
            return new ExportDeclaration(begin, ts.endPosition(), moduleSpecifier);
        }

        case LC: {
            // export ExportClause FromClause ;
            // export ExportClause ;
            ExportClause exportClause = exportClause();
            String moduleSpecifier;
            if (isName("from")) {
                moduleSpecifier = fromClause();
            } else {
                moduleSpecifier = null;

                // 15.2.3.1 Static Semantics: Early Errors
                // 15.2.3.3 Static Semantics: ExportedBindings
                // 15.2.3.4 Static Semantics: ExportedNames
                for (ExportSpecifier export : exportClause.getExports()) {
                    String sourceName = export.getSourceName();
                    if (isModuleReservedName(sourceName)) {
                        throw reportSyntaxError(Messages.Key.InvalidIdentifier, sourceName);
                    }
                    addExportBinding(export.getBeginPosition(), export.getSourceName());
                }
            }
            semicolon();

            // 15.2.3.4 Static Semantics: ExportedNames
            addExportNames(exportClause);

            return new ExportDeclaration(begin, ts.endPosition(), exportClause, moduleSpecifier);
        }

        case VAR: {
            // export VariableStatement
            VariableStatement variableStatement = variableStatement(true);

            // 15.2.3.3 Static Semantics: ExportedBindings
            // 15.2.3.4 Static Semantics: ExportedNames
            addExportBindings(begin, BoundNames(variableStatement));
            addExportNames(begin, BoundNames(variableStatement));

            return new ExportDeclaration(begin, ts.endPosition(), variableStatement);
        }

        case ASYNC:
            if (!(isEnabled(CompatibilityOption.AsyncFunction) && LOOKAHEAD(Token.FUNCTION) && noNextLineTerminator())) {
                break;
            }
            // fall-through
        case FUNCTION:
        case CLASS:
        case CONST:
        case LET:
        case AT: {
            // export Declaration
            Declaration declaration = declaration();

            // 15.2.3.3 Static Semantics: ExportedBindings
            // 15.2.3.4 Static Semantics: ExportedNames
            addExportBindings(begin, BoundNames(declaration));
            addExportNames(begin, BoundNames(declaration));

            return new ExportDeclaration(begin, ts.endPosition(), declaration);
        }

        case DEFAULT: {
            // export default HoistableDeclaration[Default]
            // export default ClassDeclaration[Default]
            // export default [LA != {function, class}] AssignmentExpression[In] ;
            long beginDefault = ts.beginPosition();
            consume(Token.DEFAULT);
            switch (token()) {
            case FUNCTION: {
                HoistableDeclaration declaration = hoistableDeclaration(true);

                // 15.2.3.2 Static Semantics: BoundNames
                // 15.2.3.3 Static Semantics: ExportedBindings
                // 15.2.3.4 Static Semantics: ExportedNames
                addExportBinding(begin, declaration.getName());
                if (declaration.getIdentifier() != null) {
                    addExportBinding(begin, DEFAULT_EXPORT_BINDING_NAME);
                }
                addExportName(begin, DEFAULT_EXPORT_NAME);

                return new ExportDeclaration(begin, ts.endPosition(), declaration);
            }
            case AT: {
                ClassDeclaration declaration = classDeclaration(true, decorators());

                // 15.2.3.2 Static Semantics: BoundNames
                // 15.2.3.3 Static Semantics: ExportedBindings
                // 15.2.3.4 Static Semantics: ExportedNames
                addExportBinding(begin, declaration.getName());
                if (declaration.getIdentifier() != null) {
                    addExportBinding(begin, DEFAULT_EXPORT_BINDING_NAME);
                }
                addExportName(begin, DEFAULT_EXPORT_NAME);

                return new ExportDeclaration(begin, ts.endPosition(), declaration);
            }
            case CLASS: {
                ClassDeclaration declaration = classDeclaration(true, NO_DECORATORS);

                // 15.2.3.2 Static Semantics: BoundNames
                // 15.2.3.3 Static Semantics: ExportedBindings
                // 15.2.3.4 Static Semantics: ExportedNames
                addExportBinding(begin, declaration.getName());
                if (declaration.getIdentifier() != null) {
                    addExportBinding(begin, DEFAULT_EXPORT_BINDING_NAME);
                }
                addExportName(begin, DEFAULT_EXPORT_NAME);

                return new ExportDeclaration(begin, ts.endPosition(), declaration);
            }
            case ASYNC:
                if (isEnabled(CompatibilityOption.AsyncFunction) && LOOKAHEAD(Token.FUNCTION)
                        && noNextLineTerminator()) {
                    HoistableDeclaration declaration = asyncFunctionDeclaration(true);

                    // 15.2.3.2 Static Semantics: BoundNames
                    // 15.2.3.3 Static Semantics: ExportedBindings
                    addExportBinding(begin, declaration.getName());
                    if (declaration.getIdentifier() != null) {
                        addExportBinding(begin, DEFAULT_EXPORT_BINDING_NAME);
                    }
                    addExportName(begin, DEFAULT_EXPORT_NAME);

                    return new ExportDeclaration(begin, ts.endPosition(), declaration);
                }
                // fall-through
            default: {
                if (isEnabled(CompatibilityOption.ExportFrom) && isName("from")
                        && LOOKAHEAD(Token.STRING) && noNextLineTerminator()) {
                    // Handle: `export default from "module-specifier" ;`
                    ExportClause exportClause = exportFromClause(beginDefault, true);
                    String moduleSpecifier = fromClause();
                    semicolon();

                    // 15.2.3.4 Static Semantics: ExportedNames
                    addExportNames(exportClause);

                    return new ExportDeclaration(begin, ts.endPosition(), exportClause,
                            moduleSpecifier);
                }
                ExportDefaultExpression defaultExpression = defaultExpression();

                addExportBinding(begin, DEFAULT_EXPORT_BINDING_NAME);
                addExportName(begin, DEFAULT_EXPORT_NAME);

                return new ExportDeclaration(begin, ts.endPosition(), defaultExpression);
            }
            }
        }
        default:
        }
        if (isEnabled(CompatibilityOption.ExportFrom)) {
            ExportClause exportClause = exportFromClause(ts.beginPosition(), false);
            String moduleSpecifier = fromClause();
            semicolon();

            // 15.2.3.4 Static Semantics: ExportedNames
            addExportNames(exportClause);

            return new ExportDeclaration(begin, ts.endPosition(), exportClause, moduleSpecifier);
        }
        throw reportTokenMismatch(Token.DEFAULT, token());
    }

    private ExportDefaultExpression defaultExpression() {
        long begin = ts.beginPosition();
        BindingIdentifier binding = new BindingIdentifier(begin, ts.endPosition(),
                DEFAULT_EXPORT_BINDING_NAME);
        Expression expression = assignmentExpression(true);
        semicolon();

        ExportDefaultExpression defaultExpression = new ExportDefaultExpression(begin,
                ts.endPosition(), binding, expression);
        addDeclaration(defaultExpression);
        return defaultExpression;
    }

    private static boolean isModuleReservedName(String name) {
        Token token = TokenStream.readReservedWord(name);
        return Token.isReservedWord(token) || Token.isStrictReservedWord(token)
                || token == Token.AWAIT;
    }

    /**
     * <strong>[15.2.3] Exports</strong>
     * 
     * <pre>
     * ExportClause :
     *     { } 
     *     { ExportsList }
     *     { ExportsList , }
     * ExportsList :
     *     ExportSpecifier
     *     ExportsList , ExportSpecifier
     * </pre>
     * 
     * @return the parsed exports clause
     */
    private ExportClause exportClause() {
        long begin = ts.beginPosition();
        List<ExportSpecifier> exports = namedExports();
        return new ExportClause(begin, ts.endPosition(), exports);
    }

    /**
     * <strong>[15.2.3] Exports</strong>
     * 
     * <pre>
     * ExportSpecifier :
     *     IdentifierName
     *     IdentifierName as IdentifierName
     * </pre>
     *
     * @return the parsed export specifier
     */
    private ExportSpecifier exportSpecifier() {
        long begin = ts.beginPosition();
        String sourceName = identifierName();
        String exportName;
        if (isName("as")) {
            consume("as");
            exportName = identifierName();
        } else {
            exportName = sourceName;
        }
        return new ExportSpecifier(begin, ts.endPosition(), sourceName, exportName);
    }

    /**
     * <strong>[Extension] Exports</strong>
     * 
     * <pre>
     * ExportFromClause :
     *     *
     *     ExportedDefaultBinding
     *     NameSpaceExport
     *     NamedExports
     *     ExportedDefaultBinding , NameSpaceExport
     *     ExportedDefaultBinding , NamedExports
     * </pre>
     *
     * @param begin
     *            the begin position
     * @param isDefault
     *            {@code true} if the exported default binding is "default"
     * @return the parsed export from clause
     */
    private ExportClause exportFromClause(long begin, boolean isDefault) {
        assert token() != Token.MUL && token() != Token.LC;
        IdentifierName defaultEntry;
        if (isDefault) {
            defaultEntry = new IdentifierName(begin, ts.endPosition(), "default");
        } else {
            defaultEntry = exportedDefaultBinding();
        }
        IdentifierName nameSpace = null;
        List<ExportSpecifier> namedExports = emptyList();
        if (token() == Token.COMMA) {
            consume(Token.COMMA);
            if (token() == Token.MUL) {
                consume(Token.MUL);
                nameSpace = nameSpaceExport();
            } else {
                namedExports = namedExports();
            }
        }
        return new ExportClause(begin, ts.endPosition(), defaultEntry, namedExports, nameSpace);
    }

    private ExportClause exportNameSpaceFromClause(long begin) {
        IdentifierName defaultEntry = null;
        IdentifierName nameSpace = nameSpaceExport();
        List<ExportSpecifier> namedExports = emptyList();
        return new ExportClause(begin, ts.endPosition(), defaultEntry, namedExports, nameSpace);
    }

    /**
     * <strong>[Extension] Exports</strong>
     * 
     * <pre>
     * NameSpaceExport :
     *     * as IdentifierName
     * </pre>
     *
     * @return the parsed namespace export
     */
    private IdentifierName nameSpaceExport() {
        // Token.MUL already consumed in caller.
        consume("as");
        long begin = ts.beginPosition();
        String name = identifierName();
        return new IdentifierName(begin, ts.endPosition(), name);
    }

    /**
     * <strong>[Extension] Exports</strong>
     * 
     * <pre>
     * NamedExports :
     *     { } 
     *     { ExportsList }
     *     { ExportsList , }
     * </pre>
     *
     * @return the parsed export from clause
     */
    private List<ExportSpecifier> namedExports() {
        InlineArrayList<ExportSpecifier> namedExports = newList();
        consume(Token.LC);
        while (token() != Token.RC) {
            namedExports.add(exportSpecifier());
            if (token() == Token.COMMA) {
                consume(Token.COMMA);
            } else {
                break;
            }
        }
        consume(Token.RC);
        return namedExports;
    }

    /**
     * <strong>[Extension] Exports</strong>
     * 
     * <pre>
     * ExportedDefaultBinding :
     *     IdentifierName
     * </pre>
     *
     * @return the parsed exported default binding
     */
    private IdentifierName exportedDefaultBinding() {
        long begin = ts.beginPosition();
        String name = identifierName();
        return new IdentifierName(begin, ts.endPosition(), name);
    }

    /**
     * <strong>[14.1.1] Directive Prologues and the Use Strict Directive</strong>
     * 
     * <pre>
     * DirectivePrologue :
     *   Directive<span><sub>opt</sub></span>
     * Directive:
     *   StringLiteral ;
     *   Directive StringLiteral ;
     * </pre>
     * 
     * @return the parsed list of directives
     */
    private List<StatementListItem> directivePrologue() {
        if (token() != Token.STRING) {
            applyStrictMode(false);
            return emptyList();
        }
        InlineArrayList<StatementListItem> statements = newList();
        boolean strict = false;
        directive: do {
            long begin = ts.beginPosition();
            boolean hasEscape = ts.hasEscape(); // peek() clears hasEscape if next token is string
            Token next = peek();
            switch (next) {
            case SEMI:
            case RC:
            case EOF:
                break;
            default:
                if (noNextLineTerminator() || stringLiteralFollowSetNextLine(next)) {
                    break directive;
                }
                break;
            }
            // found a directive
            String string = stringLiteral();
            if (!hasEscape && "use strict".equals(string)) {
                strict = true;
            }
            StringLiteral stringLiteral = new StringLiteral(begin, ts.endPosition(), string);
            semicolon();
            statements.add(new ExpressionStatement(begin, ts.endPosition(), stringLiteral));
        } while (token() == Token.STRING);
        applyStrictMode(strict);
        return statements;
    }

    private static boolean stringLiteralFollowSetNextLine(Token token) {
        switch (token) {
        case DOT:
        case LB:
        case LP:
        case TEMPLATE:
        case COMMA:
        case HOOK:
            return true;
        default:
            return Token.isBinaryOperator(token) || Token.isAssignmentOperator(token);
        }
    }

    private void applyStrictMode(boolean strict) {
        if (strict) {
            context.strictMode = StrictMode.Strict;
            context.explicitStrict = true;
            if (context.strictError != null) {
                reportException(context.strictError);
            }
        } else {
            if (context.strictMode == StrictMode.Unknown) {
                context.strictMode = context.parent.strictMode;
            }
        }
    }

    /* ***************************************************************************************** */

    private static FunctionNode.StrictMode toFunctionStrictness(boolean strict, boolean explicit) {
        if (!strict) {
            return FunctionNode.StrictMode.NonStrict;
        }
        if (explicit) {
            return FunctionNode.StrictMode.ExplicitStrict;
        }
        return FunctionNode.StrictMode.ImplicitStrict;
    }

    private <FUNCTION extends FunctionNode> FUNCTION inheritStrictness(FUNCTION function) {
        ParseContext context = this.context;
        if (context.strictMode != StrictMode.Unknown) {
            boolean strict = (context.strictMode == StrictMode.Strict);
            assert !strict || context.funContext.blockFunctions == null;
            function.setStrictMode(toFunctionStrictness(strict, context.explicitStrict));
            if (context.deferred != null) {
                deferredInheritStrictness(strict);
            }
        } else {
            // This case only applies for functions in default parameter expressions.
            deferInheritStrictness(function);
        }
        return function;
    }

    private void deferredInheritStrictness(boolean strict) {
        ParseContext context = this.context;
        for (FunctionNode func : context.deferred) {
            func.setStrictMode(toFunctionStrictness(strict, false));
            if (strict) {
                FunctionContext fc = (FunctionContext) func.getScope();
                InlineArrayList<FunctionDeclaration> blockFunctions = fc.blockFunctions;
                if (blockFunctions != null) {
                    for (FunctionDeclaration blockFunction : blockFunctions) {
                        blockFunction.setLegacyBlockScoped(false);
                    }
                    fc.blockFunctions = null;
                    fc.blockFunctionNames = null;
                }
            }
        }
        context.deferred = null;
    }

    private <FUNCTION extends FunctionNode> void deferInheritStrictness(FUNCTION function) {
        ParseContext context = this.context;
        assert context.parent.strictMode == StrictMode.Unknown;
        ParseContext parent = context.parent;
        if (parent.deferred == null) {
            parent.deferred = newList();
        }
        parent.deferred.add(function);
        if (context.deferred != null) {
            parent.deferred.addAll(context.deferred);
            context.deferred = null;
        }
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * FunctionDeclaration<span><sub>[Yield, Default]</sub></span> :
     *     function BindingIdentifier<span><sub>[?Yield]</sub></span> ( FormalParameters ) { FunctionBody }
     *     <span><sub>[+Default]</sub></span> function ( FormalParameters ) { FunctionBody }
     * </pre>
     * 
     * @param isDefault
     *            the flag to select whether or not the declaration is part of a default export
     * @return the parsed function declaration
     */
    private FunctionDeclaration functionDeclaration(boolean isDefault) {
        newContext(ContextKind.Function);
        try {
            long begin = ts.beginPosition();
            consume(Token.FUNCTION);
            boolean hasName = !isDefault || token() != Token.LP;
            BindingIdentifier identifier;
            String functionName;
            if (hasName) {
                identifier = bindingIdentifierFunctionName(true);
                functionName = identifier.getName().getIdentifier();
            } else {
                identifier = null;
                functionName = DEFAULT_EXPORT_NAME;
            }
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = formalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            String header, body;
            List<StatementListItem> statements;
            if (token() != Token.LC && isEnabled(CompatibilityOption.ExpressionClosure)) {
                int startBody = ts.position();
                statements = expressionClosureBody(parameters);
                int endFunction = ts.position();

                header = ts.range(startFunction, startBody);
                body = "return " + ts.range(startBody, endFunction);
            } else {
                consume(Token.LC);
                int startBody = ts.position();
                statements = functionBody(parameters, Token.RC);
                consume(Token.RC);
                int endFunction = ts.position() - 1;

                header = ts.range(startFunction, startBody - 1);
                body = ts.range(startBody, endFunction);
            }

            FunctionContext scope = context.funContext;
            FunctionDeclaration function = new FunctionDeclaration(begin, ts.endPosition(), scope,
                    identifier, parameters, statements, functionName, header, body);
            scope.setNode(function);

            function_EarlyErrors(function);
            addFunctionDeclaration(function, hasName && isDefault);

            return inheritStrictness(function);
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * FunctionExpression :
     *     function BindingIdentifier<span><sub>opt</sub></span> ( FormalParameters ) { FunctionBody }
     * </pre>
     * 
     * @return the parsed function expression
     */
    private FunctionExpression functionExpression() {
        long begin = ts.beginPosition();
        consume(Token.FUNCTION);
        boolean hasName = token() != Token.LP;
        if (hasName) {
            enterBlockContext();
        }

        newContext(ContextKind.Function);
        try {
            BindingIdentifier identifier = null;
            if (hasName) {
                identifier = bindingIdentifierFunctionName(false);
                addLexDeclaredName(identifier, context.parent, BoundName(identifier));
            }
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = formalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            String header, body;
            List<StatementListItem> statements;
            if (token() != Token.LC && isEnabled(CompatibilityOption.ExpressionClosure)) {
                int startBody = ts.position();
                statements = expressionClosureBody(parameters);
                int endFunction = ts.position();

                header = ts.range(startFunction, startBody);
                body = "return " + ts.range(startBody, endFunction);
            } else {
                consume(Token.LC);
                int startBody = ts.position();
                statements = functionBody(parameters, Token.RC);
                consume(Token.RC);
                int endFunction = ts.position() - 1;

                header = ts.range(startFunction, startBody - 1);
                body = ts.range(startBody, endFunction);
            }

            FunctionContext scope = context.funContext;
            FunctionExpression function = new FunctionExpression(begin, ts.endPosition(), scope,
                    identifier, parameters, statements, header, body);
            scope.setNode(function);

            function_EarlyErrors(function);

            return inheritStrictness(function);
        } finally {
            restoreContext();
            if (hasName) {
                exitBlockContext();
            }
        }
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * StrictFormalParameters<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     FormalParameters<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * </pre>
     * 
     * @param end
     *            the end token
     * @return the parsed formal parameters list
     */
    private FormalParameterList strictFormalParameters(Token end) {
        return formalParameters(end);
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * FormalParameters<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     [empty]
     *     FormalParameterList<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * </pre>
     * 
     * @param end
     *            the end token
     * @return the parsed formal parameters list
     */
    private FormalParameterList formalParameters(Token end) {
        if (token() == end) {
            return emptyFormalParameterList();
        }
        return formalParameterList(end);
    }

    private FormalParameterList emptyFormalParameterList() {
        context.funContext.setParameterNames(Collections.<Name> emptyList());
        return new FormalParameterList(ts.beginPosition(), ts.endPosition(),
                Collections.<FormalParameter> emptyList());
    }

    private FormalParameterList arrowFormalParameterList(BindingIdentifier identifier) {
        long begin = identifier.getBeginPosition();
        BindingElement element = new BindingElement(begin, ts.endPosition(), identifier, null);
        context.funContext.setParameterNames(BoundNames(identifier));
        FormalParameter parameter = new FormalParameter(begin, ts.endPosition(), element, null);
        return new FormalParameterList(begin, ts.endPosition(), singletonList(parameter));
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * FormalParameterList<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     FunctionRestParameter<span><sub>[?Yield]</sub></span>
     *     FormalsList<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     *     FormalsList<span><sub>[?Yield, ?GeneratorParameter]</sub></span>, FunctionRestParameter<span><sub>[?Yield]</sub></span>
     * FormalsList<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     FormalParameter<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     *     FormalsList<span><sub>[?Yield, ?GeneratorParameter]</sub></span>, FormalParameter<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * </pre>
     * 
     * @param end
     *            the end token
     * @return the parsed formal parameters list
     */
    private FormalParameterList formalParameterList(Token end) {
        long begin = ts.beginPosition();
        InlineArrayList<FormalParameter> formals = newList();
        for (;;) {
            if (token() == Token.TRIPLE_DOT) {
                formals.add(functionRestParameter());
                break;
            } else {
                formals.add(formalParameter());
                if (token() == Token.COMMA) {
                    consume(Token.COMMA);
                    if (token() == end && isEnabled(CompatibilityOption.FunctionCallTrailingComma)) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        context.funContext.setParameterNames(BoundNames(formals));
        return new FormalParameterList(begin, ts.endPosition(), formals);
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * FunctionRestParameter<span><sub>[Yield]</sub></span> :
     *     BindingRestElement<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed formal parameter
     */
    private FormalParameter functionRestParameter() {
        long begin = ts.beginPosition();
        BindingRestElement restElement = bindingRestElement(true);
        return new FormalParameter(begin, ts.endPosition(), restElement);
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * FormalParameter<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     BindingElement<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * </pre>
     * 
     * @return the parsed formal parameter
     */
    private FormalParameter formalParameter() {
        boolean nonSimple = token() == Token.LB || token() == Token.LC
                || !(isBindingIdentifier(token()) && !LOOKAHEAD(Token.ASSIGN));
        FormalParameterContext scope = null;
        if (nonSimple) {
            scope = enterFormalParameterContext();
        }
        long begin = ts.beginPosition();
        BindingElement element = bindingElement(true, true);
        FormalParameter parameter = new FormalParameter(begin, ts.endPosition(), element, scope);
        if (nonSimple) {
            scope.node = parameter;
            exitFormalParameterContext();
        }
        return parameter;
    }

    private static Name containsAny(List<Name> list, NameSet set) {
        for (Name element : list) {
            if (set.contains(element)) {
                return element;
            }
        }
        return null;
    }

    private void checkFormalParameterRedeclaration(FunctionNode node, List<Name> boundNames,
            NameSet declaredNames) {
        if (!(declaredNames == null || declaredNames.isEmpty())) {
            Name redeclared = containsAny(boundNames, declaredNames);
            if (redeclared != null) {
                BindingIdentifier parameter = FindParameter.find(node, redeclared);
                reportSyntaxError(parameter, Messages.Key.FormalParameterRedeclaration, redeclared);
            }
        }
    }

    private static Name findDuplicate(NameSet set, List<Name> list) {
        assert list.size() > set.size();
        NameSet copy = new NameSet(set);
        for (Name element : list) {
            if (!copy.remove(element)) {
                return element;
            }
        }
        throw new AssertionError(String.format("no duplicate: %s - %s", set, list));
    }

    private void checkFormalParameterDuplication(FunctionNode node, List<Name> boundNames,
            NameSet names) {
        boolean hasDuplicates = (boundNames.size() != names.size());
        if (hasDuplicates) {
            Name duplicate = findDuplicate(names, boundNames);
            BindingIdentifier parameter = FindParameter.find(node, duplicate);
            reportSyntaxError(parameter, Messages.Key.DuplicateFormalParameter, duplicate);
        }
    }

    private void checkFormalParameterDuplicationStrict(FunctionNode node, List<Name> boundNames,
            NameSet names) {
        boolean hasDuplicates = (boundNames.size() != names.size());
        if (hasDuplicates) {
            Name duplicate = findDuplicate(names, boundNames);
            BindingIdentifier parameter = FindParameter.find(node, duplicate);
            reportStrictModeSyntaxError(parameter, Messages.Key.DuplicateFormalParameter, duplicate);
        }
    }

    /**
     * 14.1.2 Static Semantics: Early Errors
     * 
     * @param function
     *            the function definition to validate
     */
    private void function_EarlyErrors(FunctionDefinition function) {
        assert context.scopeContext == context.funContext;
        FunctionContext scope = context.funContext;
        FormalParameterList parameters = function.getParameters();
        List<Name> boundNames = BoundNames(parameters);
        if (!IsSimpleParameterList(parameters)) {
            checkFormalParameterDuplication(function, boundNames, scope.parameterNames);
        } else if (context.strictMode != StrictMode.NonStrict) {
            checkFormalParameterDuplicationStrict(function, boundNames, scope.parameterNames);
        }
        checkFormalParameterRedeclaration(function, boundNames, scope.lexDeclaredNames);
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * FunctionBody<span><sub>[Yield]</sub></span> :
     *     FunctionStatementList<span><sub>[?Yield]</sub></span>
     * FunctionStatementList<span><sub>[Yield]</sub></span> :
     *     StatementList<span><sub>[?Yield, Return]opt</sub></span>
     * </pre>
     * 
     * @param parameters
     *            the function parameters
     * @param end
     *            the end token
     * @return the list of parsed statement list items
     */
    private List<StatementListItem> functionBody(FormalParameterList parameters, Token end) {
        // enable 'yield' if in generator
        context.yieldAllowed = context.kind.isGenerator();
        // enable 'await' if in async function
        context.awaitAllowed = context.kind.isAsync();
        List<StatementListItem> prologue = directivePrologue();
        if (parameters.containsExpression()) {
            context.funContext.variableScope = enterFunctionBodyContext();
            context.funContext.lexicalScope = context.funContext.variableScope;
        }
        if (context.strictMode != StrictMode.Strict) {
            context.funContext.lexicalScope = enterFunctionBodyContext();
        }
        List<StatementListItem> body = statementList(end);
        if (context.strictMode != StrictMode.Strict) {
            exitFunctionBodyContext();
        }
        if (parameters.containsExpression()) {
            exitFunctionBodyContext();
        }
        assert context.assertLiteralsUnchecked(0);
        computeBlockFunctions();
        return merge(prologue, body);
    }

    private void computeBlockFunctions() {
        assert context.kind.isFunction();
        if (!isEnabled(CompatibilityOption.BlockFunctionDeclaration)) {
            return;
        }
        FunctionContext funScope = context.funContext;
        ScopeContext topScope = funScope.lexicalScope;
        InlineArrayList<FunctionDeclaration> functions = funScope.blockFunctions;
        if (functions == null) {
            return;
        }
        assert context.strictMode != StrictMode.Strict : "block functions in strict mode";
        InlineArrayList<FunctionDeclaration> blockFunctions = new InlineArrayList<>();
        outer: for (FunctionDeclaration function : functions) {
            Name name = function.getIdentifier().getName();
            ScopeContext enclosingScope = (ScopeContext) function.getScope().getEnclosingScope();
            // Top-level function declarations are not applicable for legacy semantics.
            assert enclosingScope != topScope : "top-level function declaration";
            assert enclosingScope.isDeclared(name) : "undeclared block scoped function: " + name;
            for (ScopeContext scope = enclosingScope; (scope = scope.parent) != topScope;) {
                // See 13.2.1 Static Semantics: Early Errors
                // See 13.12.1 Static Semantics: Early Errors
                if (scope.isDeclared(name)) {
                    // Found a block scoped, lexical declaration - cannot declare function as var.
                    if (isEnabled(CompatibilityOption.CatchVarStatement)
                            && scope instanceof CatchContext) {
                        // Unless "B.3.5 VariableStatements in Catch blocks" applies.
                        continue;
                    }
                    continue outer;
                }
            }
            // See 14.1.2 Static Semantics: Early Errors
            if (topScope.allowVarDeclaredName(name) && !funScope.parameterNames.contains(name)) {
                // Function declaration is applicable for legacy semantics, iff
                // (1) Adding a VariableStatement with the same name would not produce an error.
                // (2) The name is not an element of BoundNames of FormalParameters.
                function.setLegacyBlockScoped(true);
                blockFunctions.add(function);
            }
        }
        funScope.setBlockFunctions(blockFunctions);
    }

    /**
     * <strong>[14.1] Function Definitions</strong>
     * 
     * <pre>
     * ExpressionClosureBody<span><sub>[Yield]</sub></span> :
     *     AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     * </pre>
     * 
     * @return the list of parsed statement list items
     */
    private List<StatementListItem> expressionClosureBody(FormalParameterList parameters) {
        assert !(context.kind.isGenerator() || context.kind.isAsync());

        // Necessary to call applyStrictMode() manually b/c directivePrologue() is not used.
        applyStrictMode(false);
        if (parameters.containsExpression()) {
            context.funContext.variableScope = enterFunctionBodyContext();
            context.funContext.lexicalScope = context.funContext.variableScope;
        }
        if (context.strictMode != StrictMode.Strict) {
            context.funContext.lexicalScope = enterFunctionBodyContext();
        }
        Expression expr = assignmentExpression(true);
        if (context.strictMode != StrictMode.Strict) {
            exitFunctionBodyContext();
        }
        if (parameters.containsExpression()) {
            exitFunctionBodyContext();
        }
        assert context.assertLiteralsUnchecked(0);
        return Collections.<StatementListItem> singletonList(new ReturnStatement(
                ts.beginPosition(), ts.endPosition(), expr));
    }

    /**
     * <strong>[14.2] Arrow Function Definitions</strong>
     * 
     * <pre>
     * ArrowFunction<span><sub>[In, Yield]</sub></span> :
     *     ArrowParameters<span><sub>[?Yield]</sub></span> [no <i>LineTerminator</i> here] {@literal =>} ConciseBody<span><sub>[?In]</sub></span>
     * ArrowParameters<span><sub>[Yield]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield]</sub></span>
     *     CoverParenthesizedExpressionAndArrowParameterList<span><sub>[?Yield]</sub></span>
     * ConciseBody<span><sub>[In]</sub></span> :
     *     [LA &#x2209; { <b>{</b> }] AssignmentExpression<span><sub>[?In]</sub></span>
     *     { FunctionBody }
     * </pre>
     * 
     * <h2>Supplemental Syntax</h2>
     * 
     * <pre>
     * ArrowFormalParameters<span><sub>[Yield]</sub></span> :
     *     ( StrictFormalParameters<span><sub>[?Yield]</sub></span> )
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed arrow function
     */
    private ArrowFunction arrowFunction(boolean allowIn) {
        newContext(ContextKind.ArrowFunction);
        try {
            long begin = ts.beginPosition();

            int startFunction;
            StringBuilder source = new StringBuilder();
            FormalParameterList parameters;
            if (token() == Token.LP) {
                consume(Token.LP);
                startFunction = ts.position() - 1;
                context.yieldAllowed = context.parent.yieldAllowed;
                context.awaitAllowed = context.parent.awaitAllowed;
                parameters = strictFormalParameters(Token.RP);
                context.yieldAllowed = false;
                context.awaitAllowed = false;
                consume(Token.RP);
            } else {
                // Don't need to set {await,yield}Allowed for single parameter case.
                BindingIdentifier identifier = bindingIdentifier();
                parameters = arrowFormalParameterList(identifier);

                startFunction = ts.position();
                source.append(identifier.getName().getIdentifier());
            }
            if (!noLineTerminator()) {
                reportSyntaxError(Messages.Key.UnexpectedEndOfLine);
            }
            consume(Token.ARROW);
            if (token() == Token.LC) {
                consume(Token.LC);
                int startBody = ts.position();
                List<StatementListItem> statements = functionBody(parameters, Token.RC);
                consume(Token.RC);
                int endFunction = ts.position() - 1;

                String header = source.append(ts.range(startFunction, startBody - 1)).toString();
                String body = ts.range(startBody, endFunction);

                FunctionContext scope = context.funContext;
                ArrowFunction function = new ArrowFunction(begin, ts.endPosition(), scope,
                        parameters, statements, header, body);
                scope.setNode(function);

                arrowFunction_EarlyErrors(function);

                return inheritStrictness(function);
            } else {
                int startBody = ts.position();
                Expression expression = arrowFunctionExpressionBody(parameters, allowIn);
                int endFunction = ts.position();

                String header = source.append(ts.range(startFunction, startBody)).toString();
                String body = ts.range(startBody, endFunction);

                FunctionContext scope = context.funContext;
                ArrowFunction function = new ArrowFunction(begin, ts.endPosition(), scope,
                        parameters, expression, header, body);
                scope.setNode(function);

                arrowFunction_EarlyErrors(function);

                return inheritStrictness(function);
            }
        } finally {
            restoreContext();
        }
    }

    private Expression arrowFunctionExpressionBody(FormalParameterList parameters, boolean allowIn) {
        // enable 'yield' if in generator
        context.yieldAllowed = context.kind.isGenerator();
        // enable 'await' if in async function
        context.awaitAllowed = context.kind.isAsync();

        // Necessary to call applyStrictMode() manually b/c directivePrologue() is not used.
        applyStrictMode(false);
        if (parameters.containsExpression()) {
            context.funContext.variableScope = enterFunctionBodyContext();
            context.funContext.lexicalScope = context.funContext.variableScope;
        }
        if (context.strictMode != StrictMode.Strict) {
            context.funContext.lexicalScope = enterFunctionBodyContext();
        }
        Expression expression = assignmentExpression(allowIn);
        if (context.strictMode != StrictMode.Strict) {
            exitFunctionBodyContext();
        }
        if (parameters.containsExpression()) {
            exitFunctionBodyContext();
        }
        assert context.assertLiteralsUnchecked(0);
        return expression;
    }

    /**
     * 14.2.1 Static Semantics: Early Errors
     * 
     * @param function
     *            the arrow function node to validate
     */
    private void arrowFunction_EarlyErrors(ArrowFunction function) {
        assert context.scopeContext == context.funContext;
        FunctionContext scope = context.funContext;
        List<Name> boundNames = BoundNames(function.getParameters());
        checkFormalParameterDuplication(function, boundNames, scope.parameterNames);
        checkFormalParameterRedeclaration(function, boundNames, scope.lexDeclaredNames);
    }

    /**
     * <strong>[14.3] Method Definitions</strong>
     * 
     * <pre>
     * MethodDefinition<span><sub>[Yield]</sub></span> :
     *     PropertyName<span><sub>[?Yield]</sub></span> ( StrictFormalParameters ) { FunctionBody }
     *     GeneratorMethod<span><sub>[?Yield]</sub></span>
     *     get PropertyName<span><sub>[?Yield]</sub></span> ( ) { FunctionBody }
     *     set PropertyName<span><sub>[?Yield]</sub></span> ( PropertySetParameterList ) { FunctionBody }
     * </pre>
     * 
     * @param allocation
     *            the method allocation kind
     * @param hasExtends
     *            {@code true} if the ClassHeritage expression is present
     * @param decorators
     *            the list of method decorators
     * @return the parsed method definition
     */
    private PropertyDefinition methodDefinition(MethodAllocation allocation, boolean hasExtends,
            List<Expression> decorators) {
        switch (methodType()) {
        case AsyncFunction:
            return asyncMethod(allocation, decorators);
        case Generator:
            return generatorMethod(allocation, decorators);
        case Getter:
            return getterMethod(allocation, decorators);
        case Setter:
            return setterMethod(allocation, decorators);
        default:
            return normalMethod(allocation, hasExtends, decorators);
        }
    }

    /**
     * <strong>[14.3] Method Definitions</strong>
     * 
     * <pre>
     * MethodDefinition<span><sub>[Yield]</sub></span> :
     *     PropertyName<span><sub>[?Yield]</sub></span> ( StrictFormalParameters ) { FunctionBody }
     * </pre>
     * 
     * @param allocation
     *            the method allocation kind
     * @param hasExtends
     *            {@code true} if the ClassHeritage expression is present
     * @param decorators
     *            the list of method decorators
     * @return the parsed method definition
     */
    private PropertyDefinition normalMethod(MethodAllocation allocation, boolean hasExtends,
            List<Expression> decorators) {
        long begin = ts.beginPosition();
        PropertyName propertyName = propertyName();
        if (token() == Token.ASSIGN && allocation == MethodAllocation.Class && decorators.isEmpty()
                && isEnabled(CompatibilityOption.StaticClassProperties)) {
            consume(Token.ASSIGN);
            Expression propertyValue = assignmentExpression(true);
            return new PropertyValueDefinition(begin, ts.endPosition(), propertyName, propertyValue);
        }
        return normalMethod(allocation, hasExtends, decorators, begin, propertyName);
    }

    private MethodDefinition normalMethod(MethodAllocation allocation, boolean hasExtends,
            List<Expression> decorators, long begin, PropertyName propertyName) {
        newContext(ContextKind.Method);
        try {
            MethodType type;
            if (allocation == MethodAllocation.Prototype
                    && "constructor".equals(propertyName.getName())) {
                context.isDerivedClassConstructor = hasExtends;
                type = hasExtends ? MethodType.DerivedConstructor : MethodType.BaseConstructor;
            } else {
                type = MethodType.Function;
            }
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = strictFormalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            consume(Token.LC);
            int startBody = ts.position();
            List<StatementListItem> statements = functionBody(parameters, Token.RC);
            consume(Token.RC);
            int endFunction = ts.position() - 1;

            String header = ts.range(startFunction, startBody - 1);
            String body = ts.range(startBody, endFunction);

            FunctionContext scope = context.funContext;
            MethodDefinition method = new MethodDefinition(begin, ts.endPosition(), scope, type,
                    allocation, decorators, propertyName, parameters, statements, header, body);
            scope.setNode(method);

            methodDefinition_EarlyErrors(method);

            return inheritStrictness(method);
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[14.3] Method Definitions</strong>
     * 
     * <pre>
     * MethodDefinition<span><sub>[Yield]</sub></span> :
     *     get PropertyName<span><sub>[?Yield]</sub></span> ( ) { FunctionBody }
     * </pre>
     * 
     * @param allocation
     *            the method allocation kind
     * @param decorators
     *            the list of method decorators
     * @return the parsed getter method definition
     */
    private MethodDefinition getterMethod(MethodAllocation allocation, List<Expression> decorators) {
        long begin = ts.beginPosition();

        consume(Token.NAME); // "get"
        PropertyName propertyName = propertyName();

        newContext(ContextKind.Method);
        try {
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = emptyFormalParameterList();
            consume(Token.RP);

            List<StatementListItem> statements;
            String header, body;
            if (token() != Token.LC && isEnabled(CompatibilityOption.ExpressionClosure)) {
                int startBody = ts.position();
                statements = expressionClosureBody(parameters);
                int endFunction = ts.position();

                header = ts.range(startFunction, startBody);
                body = "return " + ts.range(startBody, endFunction);
            } else {
                consume(Token.LC);
                int startBody = ts.position();
                statements = functionBody(parameters, Token.RC);
                consume(Token.RC);
                int endFunction = ts.position() - 1;

                header = ts.range(startFunction, startBody - 1);
                body = ts.range(startBody, endFunction);
            }

            FunctionContext scope = context.funContext;
            MethodType type = MethodType.Getter;
            MethodDefinition method = new MethodDefinition(begin, ts.endPosition(), scope, type,
                    allocation, decorators, propertyName, parameters, statements, header, body);
            scope.setNode(method);

            methodDefinition_EarlyErrors(method);

            return inheritStrictness(method);
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[14.3] Method Definitions</strong>
     * 
     * <pre>
     * MethodDefinition<span><sub>[Yield]</sub></span> :
     *     set PropertyName<span><sub>[?Yield]</sub></span> ( PropertySetParameterList ) { FunctionBody }
     * </pre>
     * 
     * @param allocation
     *            the method allocation kind
     * @param decorators
     *            the list of method decorators
     * @return the parsed setter method definition
     */
    private MethodDefinition setterMethod(MethodAllocation allocation, List<Expression> decorators) {
        long begin = ts.beginPosition();

        consume(Token.NAME); // "set"
        PropertyName propertyName = propertyName();

        newContext(ContextKind.Method);
        try {
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = propertySetParameterList();
            consume(Token.RP);

            List<StatementListItem> statements;
            String header, body;
            if (token() != Token.LC && isEnabled(CompatibilityOption.ExpressionClosure)) {
                int startBody = ts.position();
                statements = expressionClosureBody(parameters);
                int endFunction = ts.position();

                header = ts.range(startFunction, startBody);
                body = "return " + ts.range(startBody, endFunction);
            } else {
                consume(Token.LC);
                int startBody = ts.position();
                statements = functionBody(parameters, Token.RC);
                consume(Token.RC);
                int endFunction = ts.position() - 1;

                header = ts.range(startFunction, startBody - 1);
                body = ts.range(startBody, endFunction);
            }

            FunctionContext scope = context.funContext;
            MethodType type = MethodType.Setter;
            MethodDefinition method = new MethodDefinition(begin, ts.endPosition(), scope, type,
                    allocation, decorators, propertyName, parameters, statements, header, body);
            scope.setNode(method);

            methodDefinition_EarlyErrors(method);

            return inheritStrictness(method);
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[14.3] Method Definitions</strong>
     * 
     * <pre>
     * PropertySetParameterList :
     *     FormalParameter
     * </pre>
     * 
     * @return the parsed formal parameters list
     */
    private FormalParameterList propertySetParameterList() {
        long begin = ts.beginPosition();
        FormalParameter setParameter = formalParameter();
        context.funContext.setParameterNames(BoundNames(setParameter));
        return new FormalParameterList(begin, ts.endPosition(), singletonList(setParameter));
    }

    private MethodType methodType() {
        if (token() == Token.MUL) {
            return MethodType.Generator;
        }
        if (token() == Token.NAME) {
            String name = getName(Token.NAME);
            if (("get".equals(name) || "set".equals(name)) && isPropertyName(peek())) {
                return "get".equals(name) ? MethodType.Getter : MethodType.Setter;
            }
        }
        if (token() == Token.ASYNC) {
            if (isEnabled(CompatibilityOption.AsyncFunction) && isPropertyName(peek()) && noNextLineTerminator()) {
                return MethodType.AsyncFunction;
            }
        }
        return MethodType.Function; // or Constructor, or Property
    }

    private static boolean isPropertyName(Token token) {
        switch (token) {
        case STRING:
        case NUMBER:
        case LB:
            return true;
        default:
            return Token.isIdentifierName(token);
        }
    }

    /**
     * 14.3.1 Static Semantics: Early Errors
     * 
     * @param method
     *            the method definition node to validate
     */
    private void methodDefinition_EarlyErrors(MethodDefinition method) {
        assert context.scopeContext == context.funContext;
        FunctionContext scope = context.funContext;
        List<Name> boundNames = BoundNames(method.getParameters());
        checkFormalParameterDuplication(method, boundNames, scope.parameterNames);
        checkFormalParameterRedeclaration(method, boundNames, scope.lexDeclaredNames);
    }

    /**
     * <strong>[14.4] Generator Function Definitions</strong>
     * 
     * <pre>
     * GeneratorMethod<span><sub>[Yield]</sub></span> :
     *     * PropertyName<span><sub>[?Yield]</sub></span> ( StrictFormalParameters<span><sub>[Yield, GeneratorParameter]</sub></span> ) { FunctionBody<span><sub>[Yield]</sub></span> }
     * </pre>
     * 
     * @param allocation
     *            the method allocation kind
     * @param decorators
     *            the list of method decorators
     * @return the parsed generator method definition
     */
    private MethodDefinition generatorMethod(MethodAllocation allocation,
            List<Expression> decorators) {
        long begin = ts.beginPosition();
        consume(Token.MUL);
        PropertyName propertyName = propertyName();

        newContext(ContextKind.GeneratorMethod);
        try {
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = strictFormalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            consume(Token.LC);
            int startBody = ts.position();
            List<StatementListItem> statements = functionBody(parameters, Token.RC);
            consume(Token.RC);
            int endFunction = ts.position() - 1;

            String header = ts.range(startFunction, startBody - 1);
            String body = ts.range(startBody, endFunction);

            FunctionContext scope = context.funContext;
            MethodType type = MethodType.Generator;
            MethodDefinition method = new MethodDefinition(begin, ts.endPosition(), scope, type,
                    allocation, decorators, propertyName, parameters, statements, header, body);
            scope.setNode(method);

            methodDefinition_EarlyErrors(method);

            return inheritStrictness(method);
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[14.4] Generator Function Definitions</strong>
     * 
     * <pre>
     * GeneratorDeclaration<span><sub>[Yield, Default]</sub></span> :
     *     function * BindingIdentifier<span><sub>[?Yield]</sub></span> ( FormalParameters<span><sub>[Yield, GeneratorParameter]</sub></span> ) { GeneratorBody<span><sub>[Yield]</sub></span> }
     *     <span><sub>[+Default]</sub></span> function * ( FormalParameters<span><sub>[Yield, GeneratorParameter]</sub></span> ) { GeneratorBody<span><sub>[Yield]</sub></span> }
     * GeneratorBody<span><sub>[Yield]</sub></span> :
     *     FunctionBody<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @param isDefault
     *            the flag to select whether or not the declaration is part of a default export
     * @param isLegacy
     *            the flag to mark this generator as a legacy, star-less generator
     * @return the parsed generator declaration
     */
    private GeneratorDeclaration generatorDeclaration(boolean isDefault, boolean isLegacy) {
        newContext(ContextKind.Generator);
        try {
            context.legacyGenerator = isLegacy;

            long begin = ts.beginPosition();
            consume(Token.FUNCTION);
            if (!isLegacy) {
                consume(Token.MUL);
            }
            boolean hasName = !isDefault || token() != Token.LP;
            BindingIdentifier identifier;
            String functionName;
            if (hasName) {
                identifier = bindingIdentifierFunctionName(true);
                functionName = identifier.getName().getIdentifier();
            } else {
                identifier = null;
                functionName = DEFAULT_EXPORT_NAME;
            }
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = formalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            consume(Token.LC);
            int startBody = ts.position();
            List<StatementListItem> statements = functionBody(parameters, Token.RC);
            consume(Token.RC);
            int endFunction = ts.position() - 1;

            String header = ts.range(startFunction, startBody - 1);
            String body = ts.range(startBody, endFunction);

            FunctionContext scope = context.funContext;
            GeneratorDeclaration generator;
            if (!isLegacy) {
                generator = new GeneratorDeclaration(begin, ts.endPosition(), scope, identifier,
                        parameters, statements, functionName, header, body);
            } else {
                generator = new LegacyGeneratorDeclaration(begin, ts.endPosition(), scope,
                        identifier, parameters, statements, functionName, header, body);
            }
            scope.setNode(generator);

            generator_EarlyErrors(generator);
            addDeclaration(generator, hasName && isDefault);

            return inheritStrictness(generator);
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[14.4] Generator Function Definitions</strong>
     * 
     * <pre>
     * GeneratorExpression :
     *     function * BindingIdentifier<span><sub>[Yield]opt</sub></span> ( FormalParameters<span><sub>[Yield, GeneratorParameter]</sub></span> ) { FunctionBody<span><sub>[Yield]</sub></span> }
     * </pre>
     * 
     * @param isLegacy
     *            the flag to mark this generator as a legacy, star-less generator
     * @return the parsed generator expression declaration
     */
    private GeneratorExpression generatorExpression(boolean isLegacy) {
        long begin = ts.beginPosition();
        consume(Token.FUNCTION);
        if (!isLegacy) {
            consume(Token.MUL);
        }
        boolean hasName = token() != Token.LP;
        if (hasName) {
            enterBlockContext();
        }

        newContext(ContextKind.Generator);
        try {
            context.legacyGenerator = isLegacy;

            BindingIdentifier identifier = null;
            if (hasName) {
                identifier = bindingIdentifierFunctionName(false);
                addLexDeclaredName(identifier, context.parent, BoundName(identifier));
            }
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = formalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            consume(Token.LC);
            int startBody = ts.position();
            List<StatementListItem> statements = functionBody(parameters, Token.RC);
            consume(Token.RC);
            int endFunction = ts.position() - 1;

            String header = ts.range(startFunction, startBody - 1);
            String body = ts.range(startBody, endFunction);

            FunctionContext scope = context.funContext;
            GeneratorExpression generator;
            if (!isLegacy) {
                generator = new GeneratorExpression(begin, ts.endPosition(), scope, identifier,
                        parameters, statements, header, body);
            } else {
                generator = new LegacyGeneratorExpression(begin, ts.endPosition(), scope,
                        identifier, parameters, statements, header, body);
            }
            scope.setNode(generator);

            generator_EarlyErrors(generator);

            return inheritStrictness(generator);
        } finally {
            restoreContext();
            if (hasName) {
                exitBlockContext();
            }
        }
    }

    /**
     * 14.4.1 Static Semantics: Early Errors
     * 
     * @param generator
     *            the generator to validate
     */
    private void generator_EarlyErrors(GeneratorDefinition generator) {
        assert context.scopeContext == context.funContext;
        FunctionContext scope = context.funContext;
        FormalParameterList parameters = generator.getParameters();
        List<Name> boundNames = BoundNames(parameters);
        if (!IsSimpleParameterList(parameters)) {
            checkFormalParameterDuplication(generator, boundNames, scope.parameterNames);
        } else if (context.strictMode != StrictMode.NonStrict) {
            checkFormalParameterDuplicationStrict(generator, boundNames, scope.parameterNames);
        }
        checkFormalParameterRedeclaration(generator, boundNames, scope.lexDeclaredNames);
    }

    /**
     * <strong>[14.4] Generator Function Definitions</strong>
     * 
     * <pre>
     * YieldExpression<span><sub>[In]</sub></span> :
     *     yield
     *     yield [no <i>LineTerminator</i> here] AssignmentExpression<span><sub>[?In, Yield]</sub></span>
     *     yield [no <i>LineTerminator</i> here] * AssignmentExpression<span><sub>[?In, Yield]</sub></span>
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed yield expression
     */
    private YieldExpression yieldExpression(boolean allowIn) {
        assert context.kind.isGenerator() && context.yieldAllowed;
        long begin = ts.beginPosition();
        consume(Token.YIELD);
        boolean delegatedYield = false;
        if (token() == Token.MUL) {
            if (!noLineTerminator()) {
                reportSyntaxError(Messages.Key.UnexpectedEndOfLine);
            }
            consume(Token.MUL);
            delegatedYield = true;
        }
        Expression expr;
        if (delegatedYield) {
            expr = assignmentExpression(allowIn);
        } else if (!context.legacyGenerator) {
            if (noLineTerminator() && assignmentExpressionFirstSet(token())) {
                expr = assignmentExpression(allowIn);
            } else {
                expr = null;
            }
        } else {
            // slightly different rules for optional AssignmentExpression in legacy generators
            if (noLineTerminator() && !assignmentExpressionFollowSet(token())) {
                expr = assignmentExpression(allowIn);
            } else {
                expr = null;
            }
        }
        return new YieldExpression(begin, ts.endPosition(), delegatedYield, expr);
    }

    private boolean assignmentExpressionFirstSet(Token token) {
        // returns FIRST(AssignmentExpression)
        switch (token) {
        case YIELD:
            // FIRST(YieldExpression)
            return true;
        case DELETE:
        case VOID:
        case TYPEOF:
        case INC:
        case DEC:
        case ADD:
        case SUB:
        case BITNOT:
        case NOT:
            // FIRST(UnaryExpression)
            return true;
        case SUPER:
        case NEW:
            // FIRST(LeftHandSideExpression)
            return true;
        case THIS:
        case NULL:
        case FALSE:
        case TRUE:
        case NUMBER:
        case STRING:
        case LB:
        case LC:
        case LP:
        case FUNCTION:
        case CLASS:
        case AT:
        case TEMPLATE:
            // FIRST(PrimaryExpression)
            return true;
        case DIV:
        case ASSIGN_DIV:
            // FIRST(RegularExpressionLiteral)
            return true;
        case ASYNC:
        case AWAIT:
        case LET:
        case IMPLEMENTS:
        case INTERFACE:
        case PACKAGE:
        case PRIVATE:
        case PROTECTED:
        case PUBLIC:
        case STATIC:
        case NAME:
        case ESCAPED_NAME:
        case ESCAPED_RESERVED_WORD:
        case ESCAPED_STRICT_RESERVED_WORD:
        case ESCAPED_YIELD:
        case ESCAPED_ASYNC:
        case ESCAPED_AWAIT:
        case ESCAPED_LET:
            // FIRST(Identifier)
            return isIdentifierReference(token);
        default:
            return false;
        }
    }

    private boolean assignmentExpressionFollowSet(Token token) {
        // returns FOLLOW(AssignmentExpression) without { "of", "in", "for", "{" }
        // NB: not the exact follow set, consider `a = let(x=0)x++ ++`, but not relevant here
        switch (token) {
        case COLON:
        case COMMA:
        case RB:
        case RC:
        case RP:
        case SEMI:
        case EOF:
            return true;
        default:
            return false;
        }
    }

    /**
     * <strong>[14.5] Class Definitions</strong>
     * 
     * <pre>
     * ClassDeclaration<span><sub>[Yield, Default]</sub></span> :
     *     class BindingIdentifier<span><sub>[?Yield]</sub></span> ClassTail<span><sub>[?Yield]</sub></span>
     *     [+Default] class ClassTail[?Yield]
     * ClassTail<span><sub>[Yield]</sub></span> :
     *     ClassHeritage<span><sub>[?Yield]opt</sub></span> { ClassBody<span><sub>[?Yield]opt</sub></span> }
     * </pre>
     * 
     * @param isDefault
     *            the flag to select whether or not the declaration is part of a default export
     * @param decorators
     *            the list of class decorators
     * @return the parsed class declaration
     */
    private ClassDeclaration classDeclaration(boolean isDefault, List<Expression> decorators) {
        StrictMode strictMode = context.strictMode;
        try {
            // 10.2.1 - ClassDeclaration and ClassExpression is always strict code
            context.strictMode = StrictMode.Strict;
            long begin = ts.beginPosition();
            consume(Token.CLASS);
            boolean hasName = !isDefault || (token() != Token.EXTENDS && token() != Token.LC);
            BindingIdentifier identifier;
            String className;
            if (hasName) {
                identifier = bindingIdentifierClassName();
                className = identifier.getName().getIdentifier();
            } else {
                identifier = null;
                className = DEFAULT_EXPORT_NAME;
            }
            BlockContext scope = null;
            if (hasName) {
                scope = enterBlockContext();
                // Create a second inner class binding (NB: distinct Name instance required).
                addLexDeclaredName(identifier, context, BoundName(identifier).clone());
            }
            Expression heritage = null;
            if (token() == Token.EXTENDS) {
                heritage = classHeritage();
            }
            consume(Token.LC);
            InlineArrayList<MethodDefinition> methods = newList();
            List<PropertyDefinition> properties = classBody(identifier, heritage != null, methods);
            if (hasName) {
                exitBlockContext();
            }
            consume(Token.RC);

            ClassDeclaration decl = new ClassDeclaration(begin, ts.endPosition(), scope,
                    decorators, identifier, heritage, methods, properties, className);
            if (hasName) {
                scope.node = decl;
            }

            addDeclaration(decl, isDefault && hasName);

            return decl;
        } finally {
            context.strictMode = strictMode;
        }
    }

    /**
     * <strong>[14.5] Class Definitions</strong>
     * 
     * <pre>
     * ClassExpression<span><sub>[Yield]</sub></span> :
     *     class BindingIdentifier<span><sub>[?Yield]opt</sub></span> ClassTail<span><sub>[?Yield]</sub></span>
     * ClassTail<span><sub>[Yield]</sub></span> :
     *     ClassHeritage<span><sub>[?Yield]opt</sub></span> { ClassBody<span><sub>[?Yield]opt</sub></span> }
     * </pre>
     * 
     * @param decorators
     *            the list of class decorators
     * @return the parsed class expression
     */
    private ClassExpression classExpression(List<Expression> decorators) {
        StrictMode strictMode = context.strictMode;
        try {
            // 10.2.1 - ClassDeclaration and ClassExpression is always strict code
            context.strictMode = StrictMode.Strict;
            long begin = ts.beginPosition();
            consume(Token.CLASS);
            BindingIdentifier name = null;
            if (token() != Token.EXTENDS && token() != Token.LC) {
                name = bindingIdentifierClassName();
            }
            BlockContext scope = null;
            if (name != null) {
                scope = enterBlockContext(name);
            }
            Expression heritage = null;
            if (token() == Token.EXTENDS) {
                heritage = classHeritage();
            }
            consume(Token.LC);
            InlineArrayList<MethodDefinition> methods = newList();
            List<PropertyDefinition> properties = classBody(name, heritage != null, methods);
            if (name != null) {
                exitBlockContext();
            }
            consume(Token.RC);

            ClassExpression expr = new ClassExpression(begin, ts.endPosition(), scope, decorators,
                    name, heritage, methods, properties);
            if (name != null) {
                scope.node = expr;
            }
            return expr;
        } finally {
            context.strictMode = strictMode;
        }
    }

    /**
     * <strong>[14.5] Class Definitions</strong>
     * 
     * <pre>
     * ClassHeritage<span><sub>[Yield]</sub></span> :
     *     extends LeftHandSideExpression<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed class heritage expression
     */
    private Expression classHeritage() {
        consume(Token.EXTENDS);
        return leftHandSideExpressionWithValidation(true);
    }

    /**
     * <strong>[14.5] Class Definitions</strong>
     * 
     * <pre>
     * ClassBody<span><sub>[Yield]</sub></span> :
     *     ClassElementList<span><sub>[?Yield]</sub></span>
     * ClassElementList<span><sub>[Yield]</sub></span> :
     *     ClassElement<span><sub>[?Yield]</sub></span>
     *     ClassElementList<span><sub>[?Yield]</sub></span> ClassElement<span><sub>[?Yield]</sub></span>
     * ClassElement<span><sub>[Yield]</sub></span> :
     *     MethodDefinition<span><sub>[?Yield]</sub></span>
     *     static MethodDefinition<span><sub>[?Yield]</sub></span>
     *     ;
     * </pre>
     * 
     * @param className
     *            the class name if present, otherwise {@code null}
     * @param hasExtends
     *            {@code true} if the ClassHeritage expression is present
     * @return the class methods in source order
     */
    private List<PropertyDefinition> classBody(BindingIdentifier className, boolean hasExtends,
            InlineArrayList<MethodDefinition> methods) {
        int beginLine = ts.getLine();
        InlineArrayList<PropertyDefinition> properties = newList();
        InlineArrayList<MethodDefinition> staticMethods = newList();
        InlineArrayList<MethodDefinition> prototypeMethods = newList();
        while (token() != Token.RC) {
            if (token() == Token.SEMI) {
                consume(Token.SEMI);
                continue;
            }
            List<Expression> decorators = token() == Token.AT ? decorators() : NO_DECORATORS;
            PropertyDefinition property;
            MethodDefinition method = null;
            if (token() == Token.STATIC && !LOOKAHEAD(Token.LP)) {
                // TODO: Add "static" start position to node?
                consume(Token.STATIC);
                property = methodDefinition(MethodAllocation.Class, hasExtends, decorators);
                if (property instanceof MethodDefinition) {
                    method = (MethodDefinition) property;
                    staticMethods.add(method);
                    methods.add(method);
                }
            } else {
                property = methodDefinition(MethodAllocation.Prototype, hasExtends, decorators);
                method = (MethodDefinition) property;
                prototypeMethods.add(method);
                methods.add(method);
            }
            if (className != null && method != null) {
                method.setClassName(className.getName().getIdentifier());
            }
            properties.add(property);
        }

        classBody_EarlyErrors(staticMethods, true);
        classBody_EarlyErrors(prototypeMethods, false);

        if (ConstructorMethod(prototypeMethods) == null) {
            MethodDefinition constructor = createSyntheticClassConstructor(beginLine, hasExtends);
            if (className != null) {
                constructor.setClassName(className.getName().getIdentifier());
            }
            methods.add(constructor);
            properties.add(constructor);
        }

        return properties;
    }

    private MethodDefinition createSyntheticClassConstructor(int beginLine, boolean hasExtends) {
        String sourceText;
        if (hasExtends) {
            sourceText = "constructor(...args){super(...args);}";
        } else {
            sourceText = "constructor(){}";
        }
        TokenStream tokenStream = ts;
        TokenStream syntheticStream = new TokenStream(this, new TokenStreamInput(sourceText));
        try {
            ts = syntheticStream.initialize(beginLine);
            return (MethodDefinition) methodDefinition(MethodAllocation.Prototype, hasExtends,
                    NO_DECORATORS);
        } finally {
            ts = tokenStream;
        }
    }

    /**
     * 14.5.1 Static Semantics: Early Errors
     * 
     * @param defs
     *            the method definitions to validate
     * @param isStatic
     *            the flag to select whether to perform static or prototype method error detection
     */
    private void classBody_EarlyErrors(List<MethodDefinition> defs, boolean isStatic) {
        boolean hasConstructor = false;
        HashMap<String, Integer> values = null;
        if (isEnabled(CompatibilityOption.DuplicateProperties)) {
            values = new HashMap<>();
        }
        for (MethodDefinition def : defs) {
            String key = def.getPropertyName().getName();
            if (key == null) {
                assert def.getPropertyName() instanceof ComputedPropertyName;
                continue;
            }
            if (isStatic) {
                if ("prototype".equals(key)) {
                    reportSyntaxError(def, Messages.Key.InvalidPrototypeMethod);
                }
            } else {
                if ("constructor".equals(key)) {
                    if (hasConstructor) {
                        reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
                    } else if (SpecialMethod(def)) {
                        reportSyntaxError(def, Messages.Key.InvalidConstructorMethod);
                    }
                    hasConstructor = true;
                }
            }
            if (values != null) {
                checkDuplicateMethod(def, values);
            }
        }
    }

    private void checkDuplicateMethod(MethodDefinition def, HashMap<String, Integer> values) {
        final int VALUE = 0, GETTER = 1, SETTER = 2;
        MethodDefinition.MethodType type = def.getType();
        final int kind = type == MethodType.Getter ? GETTER : type == MethodType.Setter ? SETTER
                : VALUE;
        String key = def.getPropertyName().getName();
        if (values.containsKey(key)) {
            int prev = values.get(key);
            if (kind == VALUE) {
                reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
            }
            if (kind == GETTER && prev != SETTER) {
                reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
            }
            if (kind == SETTER && prev != GETTER) {
                reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
            }
            values.put(key, prev | kind);
        } else {
            values.put(key, kind);
        }
    }

    /**
     * <strong>[Extension] <code>async</code> Function Definitions</strong>
     * 
     * <pre>
     * AsyncFunctionDeclaration<span><sub>[Yield, Await, Default]</sub></span> :
     *     async [no <i>LineTerminator</i> here] function BindingIdentifier<span><sub>[?Yield, ?Await]</sub></span> ( FormalParameters<span><sub>[Await]</sub></span> ) { AsyncFunctionBody }
     *     <span><sub>[+Default]</sub></span> async [no <i>LineTerminator</i> here] function ( FormalParameters<span><sub>[Await]</sub></span> ) { AsyncFunctionBody }
     * AsyncFunctionBody :
     *     FunctionBody<span><sub>[Await]</sub></span>
     * </pre>
     * 
     * @param isDefault
     *            the flag to select whether or not the declaration is part of a default export
     * @return the parsed async function declaration
     */
    private AsyncFunctionDeclaration asyncFunctionDeclaration(boolean isDefault) {
        newContext(ContextKind.AsyncFunction);
        try {
            long begin = ts.beginPosition();
            consume(Token.ASYNC);
            if (!noLineTerminator()) {
                reportSyntaxError(Messages.Key.UnexpectedEndOfLine);
            }
            consume(Token.FUNCTION);
            boolean hasName = !isDefault || token() != Token.LP;
            BindingIdentifier identifier;
            String functionName;
            if (hasName) {
                identifier = bindingIdentifierFunctionName(true);
                functionName = identifier.getName().getIdentifier();
            } else {
                identifier = null;
                functionName = DEFAULT_EXPORT_NAME;
            }
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = formalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            consume(Token.LC);
            int startBody = ts.position();
            List<StatementListItem> statements = functionBody(parameters, Token.RC);
            consume(Token.RC);
            int endFunction = ts.position() - 1;

            String header = ts.range(startFunction, startBody - 1);
            String body = ts.range(startBody, endFunction);

            FunctionContext scope = context.funContext;
            AsyncFunctionDeclaration function = new AsyncFunctionDeclaration(begin,
                    ts.endPosition(), scope, identifier, parameters, statements, functionName,
                    header, body);
            scope.setNode(function);

            asyncFunction_EarlyErrors(function);
            addDeclaration(function, hasName && isDefault);

            return inheritStrictness(function);
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[Extension] <code>async</code> Function Definitions</strong>
     * 
     * <pre>
     * AsyncFunctionExpression :
     *     async [no <i>LineTerminator</i> here] function BindingIdentifier<span><sub>opt</sub></span> ( FormalParameters<span><sub>[Await]</sub></span> ) { AsyncFunctionBody }
     * </pre>
     * 
     * @return the parsed async function expression
     */
    private AsyncFunctionExpression asyncFunctionExpression() {
        long begin = ts.beginPosition();
        consume(Token.ASYNC);
        if (!noLineTerminator()) {
            reportSyntaxError(Messages.Key.UnexpectedEndOfLine);
        }
        consume(Token.FUNCTION);
        boolean hasName = token() != Token.LP;
        if (hasName) {
            enterBlockContext();
        }

        newContext(ContextKind.AsyncFunction);
        try {
            BindingIdentifier identifier = null;
            if (hasName) {
                identifier = bindingIdentifierFunctionName(false);
                addLexDeclaredName(identifier, context.parent, BoundName(identifier));
            }
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = formalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            consume(Token.LC);
            int startBody = ts.position();
            List<StatementListItem> statements = functionBody(parameters, Token.RC);
            consume(Token.RC);
            int endFunction = ts.position() - 1;

            String header = ts.range(startFunction, startBody - 1);
            String body = ts.range(startBody, endFunction);

            FunctionContext scope = context.funContext;
            AsyncFunctionExpression function = new AsyncFunctionExpression(begin, ts.endPosition(),
                    scope, identifier, parameters, statements, header, body);
            scope.setNode(function);

            asyncFunction_EarlyErrors(function);

            return inheritStrictness(function);
        } finally {
            restoreContext();
            if (hasName) {
                exitBlockContext();
            }
        }
    }

    private void asyncFunction_EarlyErrors(AsyncFunctionDefinition function) {
        assert context.scopeContext == context.funContext;
        FunctionContext scope = context.funContext;
        FormalParameterList parameters = function.getParameters();
        List<Name> boundNames = BoundNames(parameters);
        if (!IsSimpleParameterList(parameters)) {
            checkFormalParameterDuplication(function, boundNames, scope.parameterNames);
        } else if (context.strictMode != StrictMode.NonStrict) {
            checkFormalParameterDuplicationStrict(function, boundNames, scope.parameterNames);
        }
        checkFormalParameterRedeclaration(function, boundNames, scope.lexDeclaredNames);
    }

    /**
     * <strong>[Extension] <code>async</code> Function Definitions</strong>
     * 
     * <pre>
     * AsyncArrowFunction<span><sub>[In, Yield]</sub></span> :
     *     async [no <i>LineTerminator</i> here] AsyncArrowBindingIdentifier<span><sub>[?Yield]</sub></span> [no <i>LineTerminator</i> here] {@literal =>} AsyncConciseBody<span><sub>[?In]</sub></span>
     *     CoverCallExpressionAndAsyncArrowHead<span><sub>[?Yield, ?Await]</sub></span> [no <i>LineTerminator</i> here] {@literal =>} AsyncConciseBody<span><sub>[?In]</sub></span>
     * AsyncConciseBody<span><sub>[In]</sub></span> :
     *     [lookahead &#x2260; <b>{</b>] AssignmentExpression<span><sub>[?In]</sub></span>
     *     { AsyncFunctionBody }
     * AsyncArrowBindingIdentifier<span><sub>[Yield]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield, Await]</sub></span>
     * CoverCallExpressionAndAsyncArrowHead<span><sub>[Yield, Await]</sub></span> :
     *     MemberExpression<span><sub>[?Yield, ?Await]</sub></span> Arguments<span><sub>[?Yield, ?Await]</sub></span>
     * AsyncArrowHead<span><sub>[Yield]</sub></span> :
     *     async [no <i>LineTerminator</i> here] ArrowFormalParameters<span><sub>[?Yield, Await]</sub></span>
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed async arrow function
     */
    private AsyncArrowFunction asyncArrowFunction(boolean allowIn) {
        newContext(ContextKind.AsyncArrowFunction);
        try {
            long begin = ts.beginPosition();
            consume(Token.ASYNC);
            if (!noLineTerminator()) {
                reportSyntaxError(Messages.Key.UnexpectedEndOfLine);
            }

            int startFunction;
            StringBuilder source = new StringBuilder();
            FormalParameterList parameters;
            if (token() == Token.LP) {
                consume(Token.LP);
                startFunction = ts.position() - 1;
                context.yieldAllowed = context.parent.yieldAllowed;
                context.awaitAllowed = false;
                parameters = strictFormalParameters(Token.RP);
                context.yieldAllowed = false;
                consume(Token.RP);
            } else {
                // Don't need to set {await,yield}Allowed for single parameter case.
                BindingIdentifier identifier = bindingIdentifier();
                parameters = arrowFormalParameterList(identifier);

                startFunction = ts.position();
                source.append(identifier.getName().getIdentifier());
            }
            if (!noLineTerminator()) {
                reportSyntaxError(Messages.Key.UnexpectedEndOfLine);
            }
            consume(Token.ARROW);
            if (token() == Token.LC) {
                consume(Token.LC);
                int startBody = ts.position();
                List<StatementListItem> statements = functionBody(parameters, Token.RC);
                consume(Token.RC);
                int endFunction = ts.position() - 1;

                String header = source.append(ts.range(startFunction, startBody - 1)).toString();
                String body = ts.range(startBody, endFunction);

                FunctionContext scope = context.funContext;
                AsyncArrowFunction function = new AsyncArrowFunction(begin, ts.endPosition(),
                        scope, parameters, statements, header, body);
                scope.setNode(function);

                asyncArrowFunction_EarlyErrors(function);

                return inheritStrictness(function);
            } else {
                int startBody = ts.position();
                Expression expression = arrowFunctionExpressionBody(parameters, allowIn);
                int endFunction = ts.position();

                String header = source.append(ts.range(startFunction, startBody - 1)).toString();
                String body = ts.range(startBody, endFunction);

                FunctionContext scope = context.funContext;
                AsyncArrowFunction function = new AsyncArrowFunction(begin, ts.endPosition(),
                        scope, parameters, expression, header, body);
                scope.setNode(function);

                asyncArrowFunction_EarlyErrors(function);

                return inheritStrictness(function);
            }
        } finally {
            restoreContext();
        }
    }

    /**
     * Static Semantics: Early Errors
     * 
     * @param function
     *            the async arrow function node to validate
     */
    private void asyncArrowFunction_EarlyErrors(AsyncArrowFunction function) {
        assert context.scopeContext == context.funContext;
        FunctionContext scope = context.funContext;
        List<Name> boundNames = BoundNames(function.getParameters());
        checkFormalParameterDuplication(function, boundNames, scope.parameterNames);
        checkFormalParameterRedeclaration(function, boundNames, scope.lexDeclaredNames);
    }

    /**
     * <strong>[Extension] <code>async</code> Function Definitions</strong>
     * 
     * <pre>
     * AsyncMethod<span><sub>[Yield]</sub></span> :
     *     async [no <i>LineTerminator</i> here] PropertyName<span><sub>[?Yield]</sub></span> ( StrictFormalParameters<span><sub>[Await]</sub></span> ) { AsyncFunctionBody }
     * </pre>
     * 
     * @param allocation
     *            the method allocation kind
     * @param decorators
     *            the list of method decorators
     * @return the parsed async method
     */
    private MethodDefinition asyncMethod(MethodAllocation allocation, List<Expression> decorators) {
        long begin = ts.beginPosition();

        consume(Token.ASYNC);
        if (!noLineTerminator()) {
            reportSyntaxError(Messages.Key.UnexpectedEndOfLine);
        }
        PropertyName propertyName = propertyName();

        newContext(ContextKind.AsyncMethod);
        try {
            consume(Token.LP);
            int startFunction = ts.position() - 1;
            FormalParameterList parameters = strictFormalParameters(Token.RP);
            consume(Token.RP);

            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                returnTypeAnnotation();
            }

            consume(Token.LC);
            int startBody = ts.position();
            List<StatementListItem> statements = functionBody(parameters, Token.RC);
            consume(Token.RC);
            int endFunction = ts.position() - 1;

            String header = ts.range(startFunction, startBody - 1);
            String body = ts.range(startBody, endFunction);

            FunctionContext scope = context.funContext;
            MethodType type = MethodType.AsyncFunction;
            MethodDefinition method = new MethodDefinition(begin, ts.endPosition(), scope, type,
                    allocation, decorators, propertyName, parameters, statements, header, body);
            scope.setNode(method);

            methodDefinition_EarlyErrors(method);

            return inheritStrictness(method);
        } finally {
            restoreContext();
        }
    }

    /**
     * <strong>[Extension] <code>async</code> Function Definitions</strong>
     * 
     * <pre>
     * AwaitExpression<span><sub>[Yield]</sub></span> :
     *     await UnaryExpression<span><sub>[?Yield, Await]</sub></span>
     * </pre>
     * 
     * @return the parsed await expression
     */
    private AwaitExpression awaitExpression() {
        assert context.kind.isAsync() && context.awaitAllowed;
        long begin = ts.beginPosition();
        consume(Token.AWAIT);
        Expression expr = unaryExpression();
        return new AwaitExpression(begin, ts.endPosition(), expr);
    }

    /**
     * <strong>[Extension] Decorators</strong>
     */
    private List<Expression> decorators() {
        InlineArrayList<Expression> decorators = newList();
        do {
            // FIXME: bug in spec proposal, AssignmentExpression not valid, cf. `{ @ F * G () {} }`.
            // And: `{ @ D ["m"] () {} }`.
            // https://github.com/wycats/javascript-decorators/issues/10
            consume(Token.AT);
            decorators.add(leftHandSideExpressionWithValidation(false));
        } while (token() == Token.AT);
        return decorators;
    }

    private void parameterTypeAnnotation() {
        // NB: Parse and ignore type annotations.
        if (token() == Token.HOOK) {
            consume(Token.HOOK);
        }
        if (token() == Token.COLON) {
            typeAnnotation();
        }
    }

    private void returnTypeAnnotation() {
        // NB: Parse and ignore type annotations.
        typeAnnotation();
    }

    private void typeAnnotation() {
        // NB: Parse and ignore type annotations.
        consume(Token.COLON);
        if (token() == Token.HOOK) {
            consume(Token.HOOK);
        }
        for (;;) {
            if (token() == Token.LC) {
                objectTypeAnnotation();
            } else if (token() == Token.STRING) {
                stringLiteral();
            } else {
                identifier();
                if (token() == Token.LT) {
                    consume(Token.LT);
                    identifier();
                    consume(Token.GT);
                }
            }
            if (token() == Token.BITOR) {
                consume(Token.BITOR);
                continue;
            }
            break;
        }
    }

    private void objectTypeAnnotation() {
        consume(Token.LC);
        while (token() != Token.RC) {
            identifier();
            if (token() == Token.HOOK) {
                consume(Token.HOOK);
            }
            typeAnnotation();
            if (token() == Token.SEMI) {
                consume(Token.SEMI);
            } else {
                break;
            }
        }
        consume(Token.RC);
    }

    /* ***************************************************************************************** */

    /**
     * <strong>[13] ECMAScript Language: Statements and Declarations</strong>
     * 
     * <pre>
     * Statement<span><sub>[Yield, Return]</sub></span> :
     *     BlockStatement<span><sub>[?Yield, ?Return]</sub></span>
     *     VariableStatement<span><sub>[?Yield]</sub></span>
     *     EmptyStatement
     *     ExpressionStatement<span><sub>[?Yield]</sub></span>
     *     IfStatement<span><sub>[?Yield, ?Return]</sub></span>
     *     BreakableStatement<span><sub>[?Yield, ?Return]</sub></span>
     *     ContinueStatement<span><sub>[?Yield]</sub></span>
     *     BreakStatement<span><sub>[?Yield]</sub></span>
     *     <span><sub>[+Return]</sub></span>ReturnStatement<span><sub>[?Yield]</sub></span>
     *     WithStatement<span><sub>[?Yield, ?Return]</sub></span>
     *     LabelledStatement<span><sub>[?Yield, ?Return]</sub></span>
     *     ThrowStatement<span><sub>[?Yield]</sub></span>
     *     TryStatement<span><sub>[?Yield, ?Return]</sub></span>
     *     DebuggerStatement
     * 
     * BreakableStatement<span><sub>[Yield, Return]</sub></span> :
     *     IterationStatement<span><sub>[?Yield, ?Return]</sub></span>
     *     SwitchStatement<span><sub>[?Yield, ?Return]</sub></span>
     * </pre>
     * 
     * @param allowFunction
     *            {@code true} if labelled function statements are allowed
     * @return the parsed statement node
     */
    private Statement statement(boolean allowFunction) {
        switch (token()) {
        case LC:
            return block(NO_INHERITED_BINDING);
        case VAR:
            return variableStatement(true);
        case SEMI:
            return emptyStatement();
        case IF:
            return ifStatement();
        case FOR:
            return forStatementOrForInOfStatement(EMPTY_LABEL_SET);
        case WHILE:
            return whileStatement(EMPTY_LABEL_SET);
        case DO:
            return doWhileStatement(EMPTY_LABEL_SET);
        case CONTINUE:
            return continueStatement();
        case BREAK:
            return breakStatement();
        case RETURN:
            return returnStatement();
        case WITH:
            return withStatement();
        case SWITCH:
            return switchStatement(EMPTY_LABEL_SET);
        case THROW:
            return throwStatement();
        case TRY:
            return tryStatement();
        case DEBUGGER:
            return debuggerStatement();
        case LET:
            if (isEnabled(CompatibilityOption.LetStatement)
                    || isEnabled(CompatibilityOption.LetExpression)) {
                return letStatement();
            }
            // fall-through
        case ASYNC:
        case AWAIT:
        case YIELD:
        case IMPLEMENTS:
        case INTERFACE:
        case PACKAGE:
        case PRIVATE:
        case PROTECTED:
        case PUBLIC:
        case STATIC:
        case NAME:
        case ESCAPED_NAME:
        case ESCAPED_RESERVED_WORD:
        case ESCAPED_STRICT_RESERVED_WORD:
        case ESCAPED_YIELD:
        case ESCAPED_ASYNC:
        case ESCAPED_AWAIT:
        case ESCAPED_LET:
            if (LOOKAHEAD(Token.COLON)) {
                return labelledStatement(allowFunction);
            }
        default:
        }
        return expressionStatement();
    }

    /**
     * <strong>[13.2] Block</strong>
     * 
     * <pre>
     * BlockStatement<span><sub>[Yield, Return]</sub></span> :
     *     Block<span><sub>[?Yield, ?Return]</sub></span>
     * Block<span><sub>[Yield, Return]</sub></span> :
     *     { StatementList<span><sub>[?Yield, ?Return]opt</sub></span> }
     * </pre>
     * 
     * @param inherited
     *            the list of inherited lexical bindings
     * @return the parsed block statement
     */
    private BlockStatement block(List<Binding> inherited) {
        long begin = ts.beginPosition();
        consume(Token.LC);
        BlockContext scope = enterBlockContext();
        ScopeWithNames previous = null;
        if (!inherited.isEmpty()) {
            previous = context.setIllegalNames(lexicalNames(inherited));
        }
        List<StatementListItem> list = statementList(Token.RC);
        if (!inherited.isEmpty()) {
            context.restoreIllegalNames(previous);
        }
        exitBlockContext();
        consume(Token.RC);

        BlockStatement block = new BlockStatement(begin, ts.endPosition(), scope, list);
        scope.node = block;
        return block;
    }

    /**
     * <strong>[13.2] Block</strong>
     * 
     * <pre>
     * StatementList<span><sub>[Yield, Return]</sub></span> :
     *     StatementItem<span><sub>[?Yield, ?Return]</sub></span>
     *     StatementList<span><sub>[?Yield, ?Return]</sub></span> StatementListItem<span><sub>[?Yield, ?Return]</sub></span>
     * </pre>
     * 
     * @param end
     *            the end marker token
     * @return the list of parsed statement list items
     */
    private List<StatementListItem> statementList(Token end) {
        if (token() == end) {
            return Collections.emptyList();
        }
        InlineArrayList<StatementListItem> list = newList();
        do {
            list.add(statementListItem());
        } while (token() != end);
        return list;
    }

    /**
     * <strong>[13.2] Block</strong>
     * 
     * <pre>
     * StatementListItem<span><sub>[Yield, Return]</sub></span> :
     *     Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     Declaration<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed statement list item
     */
    private StatementListItem statementListItem() {
        switch (token()) {
        case FUNCTION:
        case CLASS:
        case CONST:
        case AT:
            return declaration();
        case ASYNC:
            if (isEnabled(CompatibilityOption.AsyncFunction) && LOOKAHEAD(Token.FUNCTION)
                    && noNextLineTerminator()) {
                return declaration();
            }
            break;
        case LET:
            if (lexicalBindingFirstSet(peek())) {
                return declaration();
            }
            // 'let' as identifier, e.g. `let + 1`
            break;
        default:
        }
        return statement(true);
    }

    /**
     * <strong>[13] ECMAScript Language: Statements and Declarations</strong>
     * 
     * <pre>
     * Declaration<span><sub>[Yield]</sub></span> :
     *     HoistableDeclaration<span><sub>[?Yield]</sub></span>
     *     ClassDeclaration<span><sub>[?Yield]</sub></span>
     *     LexicalDeclaration<span><sub>[In, ?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed declaration node
     */
    private Declaration declaration() {
        switch (token()) {
        case FUNCTION:
            return hoistableDeclaration(false);
        case AT:
            return classDeclaration(false, decorators());
        case CLASS:
            return classDeclaration(false, NO_DECORATORS);
        case LET:
        case CONST:
            return lexicalDeclaration(true);
        case ASYNC:
            if (isEnabled(CompatibilityOption.AsyncFunction)) {
                return asyncFunctionDeclaration(false);
            }
            // fall-through
        default:
            throw reportSyntaxError(Messages.Key.InvalidToken, token().toString());
        }
    }

    /**
     * <strong>[13] ECMAScript Language: Statements and Declarations</strong>
     * 
     * <pre>
     * HoistableDeclaration<span><sub>[Yield, Default]</sub></span> :
     *     FunctionDeclaration<span><sub>[?Yield, ?Default]</sub></span>
     *     GeneratorDeclaration<span><sub>[?Yield, ?Default]</sub></span>
     * </pre>
     * 
     * @param isDefault
     *            the flag to select whether or not the declaration is part of a default export
     * @return the parsed declaration node
     */
    private HoistableDeclaration hoistableDeclaration(boolean isDefault) {
        if (LOOKAHEAD(Token.MUL)) {
            return generatorDeclaration(isDefault, false);
        }
        return functionDeclarationWithRetry(isDefault);
    }

    private HoistableDeclaration functionDeclarationWithRetry(boolean isDefault) {
        long position = ts.position(), lineinfo = ts.lineinfo();
        try {
            return functionDeclaration(isDefault);
        } catch (RetryGenerator e) {
            ts.reset(position, lineinfo);
            return generatorDeclaration(isDefault, true);
        }
    }

    /**
     * <strong>[13.3.1] Let and Const Declarations</strong>
     * 
     * <pre>
     * LexicalDeclaration<span><sub>[In, Yield]</sub></span> :
     *     LetOrConst BindingList<span><sub>[?In, ?Yield]</sub></span> ;
     * LetOrConst :
     *     let
     *     const
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed lexical declaration
     */
    private LexicalDeclaration lexicalDeclaration(boolean allowIn) {
        long begin = ts.beginPosition();
        LexicalDeclaration.Type type;
        if (token() == Token.LET) {
            consume(Token.LET);
            type = LexicalDeclaration.Type.Let;
        } else {
            consume(Token.CONST);
            type = LexicalDeclaration.Type.Const;
        }
        List<LexicalBinding> list = bindingList((type == LexicalDeclaration.Type.Const), allowIn);
        if (allowIn) {
            // semicolon() not called if "in" not allowed, cf. forStatement()
            semicolon();
        }
        LexicalDeclaration decl = new LexicalDeclaration(begin, ts.endPosition(), type, list);
        addLexScopedDeclaration(decl);
        return decl;
    }

    /**
     * <strong>[13.3.1] Let and Const Declarations</strong>
     * 
     * <pre>
     * BindingList<span><sub>[In, Yield]</sub></span> :
     *     LexicalBinding<span><sub>[?In, ?Yield]</sub></span>
     *     BindingList<span><sub>[?In, ?Yield]</sub></span>, LexicalBinding<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param isConst
     *            the flag for const lexical bindings
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the list of parsed lexical bindings
     */
    private List<LexicalBinding> bindingList(boolean isConst, boolean allowIn) {
        InlineArrayList<LexicalBinding> list = newList();
        list.add(lexicalBinding(isConst, allowIn));
        while (token() == Token.COMMA) {
            consume(Token.COMMA);
            list.add(lexicalBinding(isConst, allowIn));
        }
        return list;
    }

    /**
     * <strong>[13.3.1] Let and Const Declarations</strong>
     * 
     * <pre>
     * LexicalBinding<span><sub>[In, Yield]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield]</sub></span> Initializer<span><sub>[?In, ?Yield]opt</sub></span>
     *     BindingPattern<span><sub>[?Yield]</sub></span> Initializer<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param isConst
     *            the flag for const lexical bindings
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed lexical binding
     */
    private LexicalBinding lexicalBinding(boolean isConst, boolean allowIn) {
        long begin = ts.beginPosition();
        Binding binding;
        Expression initializer = null;
        if (token() == Token.LC || token() == Token.LB) {
            BindingPattern bindingPattern = bindingPattern(false);
            addLexDeclaredName(bindingPattern);
            if (token() == Token.ASSIGN || allowIn) {
                // make initializer optional if `allowIn == false`, cf. forStatement()
                initializer = initializer(allowIn);
            }
            binding = bindingPattern;
        } else {
            BindingIdentifier bindingIdentifier = bindingIdentifier(false);
            addLexDeclaredName(bindingIdentifier);
            if (token() == Token.ASSIGN) {
                initializer = initializer(allowIn);
                if (IsAnonymousFunctionDefinition(initializer)) {
                    setFunctionName(initializer, bindingIdentifier);
                }
            } else if (isConst && allowIn) {
                // `allowIn == false` indicates for-loop, cf. forStatement()
                reportSyntaxError(bindingIdentifier, Messages.Key.ConstMissingInitializer);
            }
            binding = bindingIdentifier;
        }
        return new LexicalBinding(begin, ts.endPosition(), binding, initializer);
    }

    /**
     * Returns {@code true} iff {@code token} is in the first-set of LexicalBinding.
     * 
     * @param token
     *            the token to inspect
     * @return {@code true} if the token is in the first-set
     */
    private boolean lexicalBindingFirstSet(Token token) {
        switch (token) {
        case LB:
        case LC:
            return true;
        case YIELD:
            return !context.yieldAllowed;
        case AWAIT:
            return !(moduleCode || context.awaitAllowed);
        default:
            return isBindingIdentifier(token);
        }
    }

    /**
     * <strong>[12.2.6] Object Initializer</strong>
     * 
     * <pre>
     * Initializer<span><sub>[In, Yield]</sub></span> :
     *     = AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed initializer expression
     */
    private Expression initializer(boolean allowIn) {
        consume(Token.ASSIGN);
        return assignmentExpression(allowIn);
    }

    /**
     * <strong>[13.3.2] Variable Statement</strong>
     * 
     * <pre>
     * VariableStatement<span><sub>[Yield]</sub></span> :
     *     var VariableDeclarationList<span><sub>[In, ?Yield]</sub></span> ;
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed variable statement
     */
    private VariableStatement variableStatement(boolean allowIn) {
        long begin = ts.beginPosition();
        consume(Token.VAR);
        List<VariableDeclaration> decls = variableDeclarationList(allowIn);
        if (allowIn) {
            semicolon();
        }
        VariableStatement varStmt = new VariableStatement(begin, ts.endPosition(), decls);
        addVarScopedDeclaration(varStmt);
        return varStmt;
    }

    /**
     * <strong>[13.3.2] Variable Statement</strong>
     * 
     * <pre>
     * VariableDeclarationList<span><sub>[In, Yield]</sub></span> :
     *     VariableDeclaration<span><sub>[?In, ?Yield]</sub></span>
     *     VariableDeclarationList<span><sub>[?In, ?Yield]</sub></span> , VariableDeclaration<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed list of variable declarations
     */
    private List<VariableDeclaration> variableDeclarationList(boolean allowIn) {
        InlineArrayList<VariableDeclaration> list = newList();
        list.add(variableDeclaration(allowIn));
        while (token() == Token.COMMA) {
            consume(Token.COMMA);
            list.add(variableDeclaration(allowIn));
        }
        return list;
    }

    /**
     * <strong>[13.3.2] Variable Statement</strong>
     * 
     * <pre>
     * VariableDeclaration<span><sub>[In, Yield]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield]</sub></span> Initializer<span><sub>[?In, ?Yield]opt</sub></span>
     *     BindingPattern<span><sub>[Yield]</sub></span> Initializer<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed variable declaration
     */
    private VariableDeclaration variableDeclaration(boolean allowIn) {
        Binding binding;
        Expression initializer = null;
        if (token() == Token.LC || token() == Token.LB) {
            BindingPattern bindingPattern = bindingPattern(true);
            addVarDeclaredName(bindingPattern);
            if (allowIn) {
                initializer = initializer(allowIn);
            } else if (token() == Token.ASSIGN) {
                // make initializer optional if `allowIn == false`, cf. forStatement()
                initializer = initializer(allowIn);
            }
            binding = bindingPattern;
        } else {
            BindingIdentifier bindingIdentifier = bindingIdentifier();
            addVarDeclaredName(bindingIdentifier);
            if (token() == Token.ASSIGN) {
                initializer = initializer(allowIn);
                if (IsAnonymousFunctionDefinition(initializer)) {
                    setFunctionName(initializer, bindingIdentifier);
                }
            }
            binding = bindingIdentifier;
        }
        return new VariableDeclaration(binding, initializer);
    }

    /**
     * <strong>[13.3.3] Destructuring Binding Patterns</strong>
     * 
     * <pre>
     * BindingPattern<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     ObjectBindingPattern<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     *     ArrayBindingPattern<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @return the parsed binding pattern
     */
    private BindingPattern bindingPattern(boolean allowLet) {
        if (token() == Token.LC) {
            return objectBindingPattern(allowLet);
        } else {
            return arrayBindingPattern(allowLet);
        }
    }

    /**
     * <strong>[13.3.3] Destructuring Binding Patterns</strong>
     * 
     * <pre>
     * ObjectBindingPattern<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     { }
     *     { BindingPropertyList<span><sub>[?Yield, ?GeneratorParameter]</sub></span> }
     *     { BindingPropertyList<span><sub>[?Yield, ?GeneratorParameter]</sub></span> , }
     * BindingPropertyList<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     BindingProperty<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     *     BindingPropertyList<span><sub>[?Yield, ?GeneratorParameter]</sub></span> , BindingProperty<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @return the parsed object binding pattern
     */
    private ObjectBindingPattern objectBindingPattern(boolean allowLet) {
        long begin = ts.beginPosition();
        InlineArrayList<BindingProperty> list = newList();
        BindingRestProperty rest = null;
        consume(Token.LC);
        for (Token tok; (tok = token()) != Token.RC;) {
            if (tok == Token.TRIPLE_DOT && isEnabled(CompatibilityOption.ObjectRestDestructuring)) {
                rest = bindingRestProperty(allowLet);
                break;
            } else {
                list.add(bindingProperty(allowLet));
                if (token() == Token.COMMA) {
                    consume(Token.COMMA);
                } else {
                    break;
                }
            }
        }
        consume(Token.RC);
        return new ObjectBindingPattern(begin, ts.endPosition(), list, rest);
    }

    /**
     * <strong>[13.3.3] Destructuring Binding Patterns</strong>
     * 
     * <pre>
     * BindingProperty<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     SingleNameBinding<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     *     PropertyName<span><sub>[?Yield, ?GeneratorParameter]</sub></span> : BindingElement<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * SingleNameBinding<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     <span><sub>[+GeneratorParameter]</sub></span>BindingIdentifier<span><sub>[Yield]</sub></span> Initializer<span><sub>[In]opt</sub></span>
     *     <span><sub>[~GeneratorParameter]</sub></span>BindingIdentifier<span><sub>[?Yield]</sub></span> Initializer<span><sub>[In, ?Yield]opt</sub></span>
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @return the parsed binding property
     */
    private BindingProperty bindingProperty(boolean allowLet) {
        if (token() == Token.LB || (isPropertyName(token()) && LOOKAHEAD(Token.COLON))) {
            PropertyName propertyName = propertyName();
            consume(Token.COLON);
            Binding binding = binding(allowLet);
            Expression initializer = null;
            if (token() == Token.ASSIGN) {
                initializer = initializer(true);
                if (binding instanceof BindingIdentifier
                        && IsAnonymousFunctionDefinition(initializer)) {
                    setFunctionName(initializer, (BindingIdentifier) binding);
                }
            }
            return new BindingProperty(propertyName, binding, initializer);
        } else {
            BindingIdentifier binding = bindingIdentifier(allowLet);
            Expression initializer = null;
            if (token() == Token.ASSIGN) {
                initializer = initializer(true);
                if (IsAnonymousFunctionDefinition(initializer)) {
                    setFunctionName(initializer, (BindingIdentifier) binding);
                }
            }
            return new BindingProperty(binding, initializer);
        }
    }

    private BindingRestProperty bindingRestProperty(boolean allowLet) {
        long begin = ts.beginPosition();
        consume(Token.TRIPLE_DOT);
        BindingIdentifier bindingIdentifier = bindingIdentifier(allowLet);
        return new BindingRestProperty(begin, ts.endPosition(), bindingIdentifier);
    }

    /**
     * <strong>[13.3.3] Destructuring Binding Patterns</strong>
     * 
     * <pre>
     * ArrayBindingPattern<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     [ Elision<span><sub>opt</sub></span> BindingRestElement<span><sub>[?Yield, ?GeneratorParameter]opt</sub></span> ]
     *     [ BindingElementList<span><sub>[?Yield, ?GeneratorParameter]</sub></span> ]
     *     [ BindingElementList<span><sub>[?Yield, ?GeneratorParameter]</sub></span> , Elision<span><sub>opt</sub></span> BindingRestElement<span><sub>[?Yield, ?GeneratorParameter]opt</sub></span> ]
     * BindingElementList<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     BindingElisionElement<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     *     BindingElementList<span><sub>[?Yield, ?GeneratorParameter]</sub></span> , BindingElisionElement<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * BindingElisionElement<span><sub>[Yield, GeneratorParameter]</sub></span>:
     *     Elision<span><sub>opt</sub></span> BindingElement<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @return the parsed array binding pattern
     */
    private ArrayBindingPattern arrayBindingPattern(boolean allowLet) {
        long begin = ts.beginPosition();
        InlineArrayList<BindingElementItem> list = newList();
        consume(Token.LB);
        boolean needComma = false;
        for (Token tok; (tok = token()) != Token.RB;) {
            if (needComma) {
                consume(Token.COMMA);
                needComma = false;
            } else if (tok == Token.COMMA) {
                consume(Token.COMMA);
                list.add(new BindingElision(0, 0));
            } else if (tok == Token.TRIPLE_DOT) {
                list.add(bindingRestElement(allowLet));
                break;
            } else {
                list.add(bindingElement(allowLet, false));
                needComma = true;
            }
        }
        consume(Token.RB);
        return new ArrayBindingPattern(begin, ts.endPosition(), list);
    }

    /**
     * <pre>
     * Binding<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     *     BindingPattern<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @return the parsed binding node
     */
    private Binding binding(boolean allowLet) {
        switch (token()) {
        case LC:
            return objectBindingPattern(allowLet);
        case LB:
            return arrayBindingPattern(allowLet);
        default:
            return bindingIdentifier(allowLet);
        }
    }

    /**
     * <strong>[13.3.3] Destructuring Binding Patterns</strong>
     * 
     * <pre>
     * BindingElement<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     SingleNameBinding<span><sub>[?Yield, ?GeneratorParameter]</sub></span>
     *     <span><sub>[+GeneratorParameter]</sub></span>BindingPattern<span><sub>[?Yield, GeneratorParameter]</sub></span> Initializer<span><sub>[In]opt</sub></span>
     *     <span><sub>[~GeneratorParameter]</sub></span>BindingPattern<span><sub>[?Yield]</sub></span> Initializer<span><sub>[In, ?Yield]opt</sub></span>
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @param allowType
     *            the flag to select if type annotations are allowed
     * @return the parsed binding element
     */
    private BindingElement bindingElement(boolean allowLet, boolean allowType) {
        long begin = ts.beginPosition();
        Binding binding = binding(allowLet);
        if (allowType && binding instanceof BindingIdentifier
                && (token() == Token.COLON || token() == Token.HOOK)
                && isEnabled(CompatibilityOption.TypeAnnotation)) {
            parameterTypeAnnotation();
        }
        Expression initializer = null;
        if (token() == Token.ASSIGN) {
            initializer = initializer(true);
            if (binding instanceof BindingIdentifier && IsAnonymousFunctionDefinition(initializer)) {
                setFunctionName(initializer, (BindingIdentifier) binding);
            }
        }
        return new BindingElement(begin, ts.endPosition(), binding, initializer);
    }

    /**
     * <strong>[13.3.3] Destructuring Binding Patterns</strong>
     * 
     * <pre>
     * BindingRestElement<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *     <span><sub>[+GeneratorParameter]</sub></span>... BindingIdentifier<span><sub>[Yield]</sub></span>
     *     <span><sub>[~GeneratorParameter]</sub></span>... BindingIdentifier<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @return the parsed binding rest element
     */
    private BindingRestElement bindingRestElement(boolean allowLet) {
        long begin = ts.beginPosition();
        consume(Token.TRIPLE_DOT);
        Binding binding = binding(allowLet);
        return new BindingRestElement(begin, ts.endPosition(), binding);
    }

    /**
     * <strong>[13.4] Empty Statement</strong>
     * 
     * <pre>
     * EmptyStatement:
     * ;
     * </pre>
     * 
     * @return the parsed empty statement
     */
    private EmptyStatement emptyStatement() {
        long begin = ts.beginPosition();
        consume(Token.SEMI);
        return new EmptyStatement(begin, ts.endPosition());
    }

    /**
     * <strong>[13.5] Expression Statement</strong>
     * 
     * <pre>
     * ExpressionStatement<span><sub>[Yield]</sub></span> :
     *     [LA &#x2209; { <b>{, function, class, let [</b> }] Expression<span><sub>[In, ?Yield]</sub></span> ;
     * </pre>
     * 
     * @return the parsed expression statement
     */
    private ExpressionStatement expressionStatement() {
        switch (token()) {
        case LC:
        case FUNCTION:
        case CLASS:
        case AT:
            throw reportSyntaxError(Messages.Key.InvalidToken, token().toString());
        case LET:
            if (LOOKAHEAD(Token.LB)) {
                throw reportSyntaxError(Messages.Key.InvalidToken, token().toString());
            }
            break;
        case ASYNC:
            if (isEnabled(CompatibilityOption.AsyncFunction) && LOOKAHEAD(Token.FUNCTION) && noNextLineTerminator()) {
                throw reportSyntaxError(Messages.Key.InvalidToken, token().toString());
            }
            break;
        default:
            break;
        }
        long begin = ts.beginPosition();
        Expression expr = expression(true);
        semicolon();
        return new ExpressionStatement(begin, ts.endPosition(), expr);
    }

    /**
     * <strong>[13.6] The <code>if</code> Statement</strong>
     * 
     * <pre>
     * IfStatement<span><sub>[Yield, Return]</sub></span> :
     *     if ( Expression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span> else Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     if ( Expression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     * </pre>
     * 
     * <strong>[B.3.4] FunctionDeclarations in IfStatement Statement Clauses</strong>
     * 
     * <pre>
     * IfStatement<span><sub>[Yield, Return]</sub></span> :
     *     if ( Expression<span><sub>[In, ?Yield]</sub></span> ) FunctionDeclaration<span><sub>[?Yield]</sub></span> else Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     if ( Expression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span> else FunctionDeclaration<span><sub>[?Yield]</sub></span>
     *     if ( Expression<span><sub>[In, ?Yield]</sub></span> ) FunctionDeclaration<span><sub>[?Yield]</sub></span> else FunctionDeclaration<span><sub>[?Yield]</sub></span>
     *     if ( Expression<span><sub>[In, ?Yield]</sub></span> ) FunctionDeclaration<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed if-statement
     */
    private IfStatement ifStatement() {
        long begin = ts.beginPosition();
        consume(Token.IF);
        consume(Token.LP);
        Expression test = expression(true);
        consume(Token.RP);
        Statement then = statementOrFunctionDeclaration();
        Statement otherwise = null;
        if (token() == Token.ELSE) {
            consume(Token.ELSE);
            otherwise = statementOrFunctionDeclaration();
        }
        return new IfStatement(begin, ts.endPosition(), test, then, otherwise);
    }

    private Statement statementOrFunctionDeclaration() {
        if (token() == Token.FUNCTION
                && isEnabled(CompatibilityOption.IfStatementFunctionDeclaration)) {
            return ifStatementFunctionDeclaration();
        }
        return statement(false);
    }

    private BlockStatement ifStatementFunctionDeclaration() {
        if (context.strictMode != StrictMode.NonStrict) {
            reportStrictModeSyntaxError(Messages.Key.InvalidToken, token().toString());
        }
        long begin = ts.beginPosition();
        BlockContext scope = enterBlockContext();
        Declaration declaration = functionDeclarationWithRetry(false);
        List<StatementListItem> list = singletonList((StatementListItem) declaration);
        exitBlockContext();

        BlockStatement block = new BlockStatement(begin, ts.endPosition(), scope, list);
        scope.node = block;
        return block;
    }

    /**
     * <strong>[13.7.2] The <code>do-while</code> Statement</strong>
     * 
     * <pre>
     * IterationStatement<span><sub>[Yield, Return]</sub></span> :
     *     do Statement<span><sub>[?Yield, ?Return]</sub></span> while ( Expression<span><sub>[In, ?Yield]</sub></span> ) ;<span><sub>opt</sub></span>
     * </pre>
     * 
     * @param labelSet
     *            the label set
     * @return the parsed do-while statement
     */
    private DoWhileStatement doWhileStatement(Set<String> labelSet) {
        long begin = ts.beginPosition();
        consume(Token.DO);

        LabelContext labelCx = enterIteration(labelSet);
        Statement stmt = statement(false);
        exitIteration();

        consume(Token.WHILE);
        consume(Token.LP);
        Expression test = expression(true);
        consume(Token.RP);
        if (token() == Token.SEMI) {
            consume(Token.SEMI);
        }

        return new DoWhileStatement(begin, ts.endPosition(), labelCx.abrupts, labelCx.labelSet,
                test, stmt);
    }

    /**
     * <strong>[13.7.3] The <code>while</code> Statement</strong>
     * 
     * <pre>
     * IterationStatement<span><sub>[Yield, Return]</sub></span> :
     *     while ( Expression<span><sub>[In, ?Yield]</sub></span> ) StatementStatement<span><sub>[?Yield, ?Return]</sub></span>
     * </pre>
     * 
     * @param labelSet
     *            the label set
     * @return the parsed while statement
     */
    private WhileStatement whileStatement(Set<String> labelSet) {
        long begin = ts.beginPosition();
        consume(Token.WHILE);
        consume(Token.LP);
        Expression test = expression(true);
        consume(Token.RP);

        LabelContext labelCx = enterIteration(labelSet);
        Statement stmt = statement(false);
        exitIteration();

        return new WhileStatement(begin, ts.endPosition(), labelCx.abrupts, labelCx.labelSet, test,
                stmt);
    }

    private enum ForType {
        Each, In, Of
    }

    /**
     * <strong>[13.7.4] The <code>for</code> Statement</strong> <br>
     * <strong>[13.7.5] The <code>for-in</code> and <code>for-of</code> Statements</strong>
     * 
     * <pre>
     * IterationStatement<span><sub>[Yield, Return]</sub></span> :
     *     for ( [LA &#x2209; { <b>let [</b> }] Expression<span><sub>[?Yield]opt</sub></span> ; Expression<span><sub>[In, ?Yield]opt</sub></span> ; Expression<span><sub>[In, ?Yield]opt</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     for ( var VariableDeclarationList<span><sub>[?Yield]</sub></span> ; Expression<span><sub>[In, ?Yield]opt</sub></span> ; Expression<span><sub>[In, ?Yield]opt</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     for ( LexicalDeclaration<span><sub>[?Yield]</sub></span> Expression<span><sub>[In, ?Yield]opt</sub></span> ; Expression<span><sub>[In, ?Yield]opt</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     for ( [LA &#x2209; { <b>let [</b> }] LeftHandSideExpression<span><sub>[?Yield]</sub></span> in Expression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     for ( var ForBinding<span><sub>[?Yield]</sub></span> in Expression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     for ( ForDeclaration<span><sub>[?Yield]</sub></span> in Expression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     for ( [LA &#x2209; { <b>let</b> }] LeftHandSideExpression<span><sub>[?Yield]</sub></span> of AssignmentExpression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     for ( var ForBinding<span><sub>[?Yield]</sub></span> of AssignmentExpression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     for ( ForDeclaration<span><sub>[?Yield]</sub></span> of AssignmentExpression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     * ForDeclaration<span><sub>[Yield]</sub></span> :
     *     LetOrConst ForBinding<span><sub>[?Yield]</sub></span>
     * ForBinding<span><sub>[Yield]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield]</sub></span>
     *     BindingPattern<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @param labelSet
     *            the label set
     * @return the parsed for-loop or for-in/of statement
     */
    private IterationStatement forStatementOrForInOfStatement(Set<String> labelSet) {
        long begin = ts.beginPosition();
        boolean forEach = false;

        consume(Token.FOR);
        if (token() != Token.LP && isName("each")
                && isEnabled(CompatibilityOption.ForEachStatement)) {
            consume("each");
            forEach = true;
        }
        consume(Token.LP);

        // NB: This code needs to be able to parse ForStatement and ForIn/OfStatement
        boolean letIdentifier = false;
        BlockContext lexBlockContext = null;
        Node head;
        switch (token()) {
        case VAR:
            head = variableStatement(false);
            break;
        case SEMI:
            head = null;
            break;
        case CONST:
            lexBlockContext = enterBlockContext();
            head = lexicalDeclaration(false);
            break;
        case LET:
            if (lexicalBindingFirstSet(peek())) {
                lexBlockContext = enterBlockContext();
                head = lexicalDeclaration(false);
                break;
            }
            // 'let' as identifier, e.g. `for (let ;;) {}`
            // 'let' as identifier, e.g. `for (let in "") {}` or `for (let.prop in "") {}`
            letIdentifier = true;
            // fall-through
        default:
            int count = context.countLiterals();
            Expression expr = assignmentExpressionNoValidation(false);
            if (token() == Token.SEMI || token() == Token.COMMA) {
                // ForStatement, apply early error checks for object literals
                objectLiteral_EarlyErrors(count);
                // Proceed to parse expression tail, if any
                if (token() == Token.COMMA) {
                    head = commaExpression(expr, false);
                } else {
                    head = expr;
                }
            } else {
                // ForInStatement or ForOfStatement, check assignment target
                head = validateAssignment(expr, ExceptionType.SyntaxError,
                        Messages.Key.InvalidAssignmentTarget);
                // Apply early error checks for remaining object literals
                objectLiteral_EarlyErrors(count);
            }
            break;
        }

        if (forEach) {
            return forInOfStatement(labelSet, begin, head, lexBlockContext, ForType.Each);
        } else if (token() == Token.IN) {
            return forInOfStatement(labelSet, begin, head, lexBlockContext, ForType.In);
        } else if (token() == Token.NAME && !letIdentifier && isName("of")) {
            return forInOfStatement(labelSet, begin, head, lexBlockContext, ForType.Of);
        } else {
            return forStatement(labelSet, begin, head, lexBlockContext);
        }
    }

    private IterationStatement forInOfStatement(Set<String> labelSet, long begin, Node head,
            BlockContext lexBlockContext, ForType type) {
        // Only allow single binding without initializer in for-in/of head.
        if (head == null) {
            // for-each loop without head: `for each (;`
            assert type == ForType.Each;
            reportSyntaxError(begin, Messages.Key.InvalidForEachStatement);
        } else if (head instanceof VariableStatement) {
            VariableStatement varStmt = (VariableStatement) head;
            if (varStmt.getElements().size() != 1) {
                reportSyntaxError(varStmt, Messages.Key.InvalidForStatementLeftHandSide);
            }
            VariableDeclaration varDecl = varStmt.getElements().get(0);
            if (varDecl.getInitializer() != null) {
                if (type == ForType.Of || varDecl.getBinding() instanceof BindingPattern) {
                    reportSyntaxError(varDecl, Messages.Key.InvalidForStatementLeftHandSide);
                }
                if (!isEnabled(CompatibilityOption.ForInVarInitializer)) {
                    reportSyntaxError(varDecl, Messages.Key.InvalidForStatementLeftHandSide);
                }
            }
            if (type == ForType.Of) {
                checkVarDeclaredName(varDecl.getBinding());
            }
        } else if (head instanceof LexicalDeclaration) {
            // Forbid initializer for BindingPattern and const declarations
            LexicalDeclaration lexDecl = (LexicalDeclaration) head;
            if (lexDecl.getElements().size() != 1) {
                reportSyntaxError(lexDecl, Messages.Key.InvalidForStatementLeftHandSide);
            }
            LexicalBinding lexBinding = lexDecl.getElements().get(0);
            if (lexBinding.getInitializer() != null) {
                reportSyntaxError(lexBinding, Messages.Key.InvalidForStatementLeftHandSide);
            }
        } else if (!(head instanceof LeftHandSideExpression)) {
            // Handle: `for (a, b in ...` and `for each (false; ...`
            assert head instanceof CommaExpression || type == ForType.Each;
            reportSyntaxError(head, Messages.Key.InvalidForStatementLeftHandSide);
        }

        Expression expr;
        if (type != ForType.Of) {
            consume(Token.IN);
            expr = expression(true);
        } else {
            consume("of");
            expr = assignmentExpression(true);
        }
        consume(Token.RP);

        LabelContext labelCx = enterIteration(labelSet);
        Statement stmt = statement(false);
        exitIteration();

        if (lexBlockContext != null) {
            exitBlockContext();
        }

        if (type == ForType.Each) {
            ForEachStatement iteration = new ForEachStatement(begin, ts.endPosition(),
                    lexBlockContext, labelCx.abrupts, labelCx.labelSet, head, expr, stmt);
            if (lexBlockContext != null) {
                lexBlockContext.node = iteration;
            }
            return iteration;
        } else if (type == ForType.In) {
            ForInStatement iteration = new ForInStatement(begin, ts.endPosition(), lexBlockContext,
                    labelCx.abrupts, labelCx.labelSet, head, expr, stmt);
            if (lexBlockContext != null) {
                lexBlockContext.node = iteration;
            }
            return iteration;
        } else {
            ForOfStatement iteration = new ForOfStatement(begin, ts.endPosition(), lexBlockContext,
                    labelCx.abrupts, labelCx.labelSet, head, expr, stmt);
            if (lexBlockContext != null) {
                lexBlockContext.node = iteration;
            }
            return iteration;
        }
    }

    private ForStatement forStatement(Set<String> labelSet, long begin, Node head,
            BlockContext lexBlockContext) {
        if (head instanceof VariableStatement) {
            // Enforce initializer for BindingPattern
            VariableStatement varStmt = (VariableStatement) head;
            for (VariableDeclaration decl : varStmt.getElements()) {
                if (decl.getBinding() instanceof BindingPattern && decl.getInitializer() == null) {
                    reportSyntaxError(varStmt, Messages.Key.DestructuringMissingInitializer);
                }
            }
        } else if (head instanceof LexicalDeclaration) {
            // Enforce initializer for BindingPattern and const declarations
            LexicalDeclaration lexDecl = (LexicalDeclaration) head;
            boolean isConst = lexDecl.isConstDeclaration();
            for (LexicalBinding decl : lexDecl.getElements()) {
                if (decl.getBinding() instanceof BindingPattern && decl.getInitializer() == null) {
                    reportSyntaxError(lexDecl, Messages.Key.DestructuringMissingInitializer);
                }
                if (isConst && decl.getInitializer() == null) {
                    reportSyntaxError(lexDecl, Messages.Key.ConstMissingInitializer);
                }
            }
        }

        consume(Token.SEMI);
        Expression test = null;
        if (token() != Token.SEMI) {
            test = expression(true);
        }
        consume(Token.SEMI);
        Expression step = null;
        if (token() != Token.RP) {
            step = expression(true);
        }
        consume(Token.RP);

        LabelContext labelCx = enterIteration(labelSet);
        Statement stmt = statement(false);
        exitIteration();

        if (lexBlockContext != null) {
            exitBlockContext();
        }

        ForStatement iteration = new ForStatement(begin, ts.endPosition(), lexBlockContext,
                labelCx.abrupts, labelCx.labelSet, head, test, step, stmt);
        if (lexBlockContext != null) {
            lexBlockContext.node = iteration;
        }
        return iteration;
    }

    /**
     * <strong>[13.8] The <code>continue</code> Statement</strong>
     * 
     * <pre>
     * ContinueStatement<span><sub>[Yield]</sub></span> :
     *     continue ;
     *     continue [no <i>LineTerminator</i> here] LabelIdentifier<span><sub>[?Yield]</sub></span> ;
     * </pre>
     * 
     * @return the parsed continue statement
     */
    private ContinueStatement continueStatement() {
        long begin = ts.beginPosition();
        String label;
        consume(Token.CONTINUE);
        if (noLineTerminator() && isLabelIdentifier(token())) {
            label = labelIdentifier();
        } else {
            label = null;
        }
        semicolon();

        LabelContext target = findContinueTarget(label);
        if (target == null) {
            if (label == null) {
                reportSyntaxError(begin, Messages.Key.InvalidContinueTarget);
            } else {
                reportSyntaxError(begin, Messages.Key.LabelTargetNotFound, label);
            }
        }
        if (target.type != StatementType.Iteration) {
            reportSyntaxError(begin, Messages.Key.InvalidContinueTarget);
        }
        target.mark(Abrupt.Continue);

        return new ContinueStatement(begin, ts.endPosition(), label);
    }

    /**
     * <strong>[13.9] The <code>break</code> Statement</strong>
     * 
     * <pre>
     * BreakStatement<span><sub>[Yield]</sub></span> :
     *     break ;
     *     break [no <i>LineTerminator</i> here] LabelIdentifier<span><sub>[?Yield]</sub></span> ;
     * </pre>
     * 
     * @return the parsed break statement
     */
    private BreakStatement breakStatement() {
        long begin = ts.beginPosition();
        String label;
        consume(Token.BREAK);
        if (noLineTerminator() && isLabelIdentifier(token())) {
            label = labelIdentifier();
        } else {
            label = null;
        }
        semicolon();

        LabelContext target = findBreakTarget(label);
        if (target == null) {
            if (label == null) {
                reportSyntaxError(begin, Messages.Key.InvalidBreakTarget);
            } else {
                reportSyntaxError(begin, Messages.Key.LabelTargetNotFound, label);
            }
        }
        target.mark(Abrupt.Break);

        return new BreakStatement(begin, ts.endPosition(), label);
    }

    /**
     * <strong>[13.10] The <code>return</code> Statement</strong>
     * 
     * <pre>
     * ReturnStatement<span><sub>[Yield]</sub></span> :
     *     return ;
     *     return [no <i>LineTerminator</i> here] Expression<span><sub>[In, ?Yield]</sub></span> ;
     * </pre>
     * 
     * @return the parsed return statement
     */
    private ReturnStatement returnStatement() {
        if (!context.returnAllowed) {
            reportSyntaxError(Messages.Key.InvalidReturnStatement);
        }
        long begin = ts.beginPosition();
        Expression expr = null;
        consume(Token.RETURN);
        if (noLineTerminator()
                && !(token() == Token.SEMI || token() == Token.RC || token() == Token.EOF)) {
            expr = expression(true);
        }
        semicolon();
        return new ReturnStatement(begin, ts.endPosition(), expr);
    }

    /**
     * <strong>[13.11] The <code>with</code> Statement</strong>
     * 
     * <pre>
     * WithStatement<span><sub>[Yield, Return]</sub></span> :
     *     with ( Expression<span><sub>[In, ?Yield]</sub></span> ) Statement<span><sub>[?Yield, ?Return]</sub></span>
     * </pre>
     * 
     * @return the parsed with statement
     */
    private WithStatement withStatement() {
        long begin = ts.beginPosition();
        reportStrictModeSyntaxError(begin, Messages.Key.StrictModeWithStatement);

        consume(Token.WITH);
        consume(Token.LP);
        Expression expr = expression(true);
        consume(Token.RP);

        WithContext scope = enterWithContext();
        Statement stmt = statement(false);
        exitWithContext();

        WithStatement withStatement = new WithStatement(begin, ts.endPosition(), scope, expr, stmt);
        scope.node = withStatement;
        return withStatement;
    }

    /**
     * <strong>[13.12] The <code>switch</code> Statement</strong>
     * 
     * <pre>
     * SwitchStatement<span><sub>[Yield, Return]</sub></span> :
     *     switch ( Expression<span><sub>[In, ?Yield]</sub></span> ) CaseBlock<span><sub>[?Yield, ?Return]</sub></span>
     * CaseBlock<span><sub>[Yield, Return]</sub></span> :
     *     { CaseClauses<span><sub>[?Yield, ?Return]opt</sub></span> }
     *     { CaseClauses<span><sub>[?Yield, ?Return]opt</sub></span> DefaultClause<span><sub>[?Yield, ?Return]</sub></span> CaseClauses<span><sub>[?Yield, ?Return]opt</sub></span> }
     * CaseClauses<span><sub>[Yield, Return]</sub></span> :
     *     CaseClause<span><sub>[?Yield, ?Return]</sub></span>
     *     CaseClauses<span><sub>[?Yield, ?Return]</sub></span> CaseClause<span><sub>[?Yield, ?Return]</sub></span>
     * CaseClause<span><sub>[Yield, Return]</sub></span> :
     *     case Expression<span><sub>[In, ?Yield]</sub></span> : StatementList<span><sub>[?Yield, ?Return]opt</sub></span>
     * DefaultClause :
     *     default : StatementList<span><sub>[?Yield, ?Return]opt</sub></span>
     * </pre>
     * 
     * @param labelSet
     *            the label set
     * @return the parsed switch statement
     */
    private SwitchStatement switchStatement(Set<String> labelSet) {
        InlineArrayList<SwitchClause> clauses = newList();
        long begin = ts.beginPosition();
        consume(Token.SWITCH);
        consume(Token.LP);
        Expression expr = expression(true);
        consume(Token.RP);

        consume(Token.LC);
        BlockContext scope = enterBlockContext();
        LabelContext labelCx = enterBreakable(labelSet);
        boolean hasDefault = false;
        for (;;) {
            long beginClause = ts.beginPosition();
            Expression caseExpr;
            Token tok = token();
            if (tok == Token.CASE) {
                consume(Token.CASE);
                caseExpr = expression(true);
                consume(Token.COLON);
            } else if (tok == Token.DEFAULT && !hasDefault) {
                hasDefault = true;
                consume(Token.DEFAULT);
                consume(Token.COLON);
                caseExpr = null;
            } else {
                break;
            }
            Token next = token();
            if (next == Token.CASE || next == Token.DEFAULT) {
                // empty case clause
                List<StatementListItem> list = emptyList();
                clauses.add(new SwitchClause(beginClause, ts.endPosition(), caseExpr, list));
                continue;
            }
            InlineArrayList<StatementListItem> list = newList();
            statementlist: for (;;) {
                switch (token()) {
                case CASE:
                case DEFAULT:
                case RC:
                    break statementlist;
                default:
                    list.add(statementListItem());
                }
            }
            clauses.add(new SwitchClause(beginClause, ts.endPosition(), caseExpr, list));
        }
        exitBlockContext();
        exitBreakable();
        consume(Token.RC);

        SwitchStatement switchStatement = new SwitchStatement(begin, ts.endPosition(), scope,
                labelCx.abrupts, labelCx.labelSet, expr, clauses);
        scope.node = switchStatement;
        return switchStatement;
    }

    /**
     * <strong>[13.13] Labelled Statements</strong>
     * 
     * <pre>
     * LabelledStatement<span><sub>[Yield, Return]</sub></span> :
     *     LabelIdentifier<span><sub>[?Yield]</sub></span> : LabelledItem<span><sub>[?Yield, ?Return]</sub></span>
     * LabelledItem<span><sub>[Yield, Return]</sub></span> :
     *     Statement<span><sub>[?Yield, ?Return]</sub></span>
     *     FunctionDeclaration<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @param allowFunction
     *            {@code true} if labelled function statements are allowed
     * @return the parsed labelled statement
     */
    private Statement labelledStatement(boolean allowFunction) {
        long begin = ts.beginPosition();
        LinkedHashSet<String> labelSet = new LinkedHashSet<>(4);
        labels: for (;;) {
            switch (token()) {
            case FOR:
                assert !labelSet.isEmpty();
                return forStatementOrForInOfStatement(labelSet);
            case WHILE:
                assert !labelSet.isEmpty();
                return whileStatement(labelSet);
            case DO:
                assert !labelSet.isEmpty();
                return doWhileStatement(labelSet);
            case SWITCH:
                assert !labelSet.isEmpty();
                return switchStatement(labelSet);
            case FUNCTION:
                if (isEnabled(CompatibilityOption.LabelledFunctionDeclaration)) {
                    assert !labelSet.isEmpty();
                    return labelledFunctionStatement(labelSet, allowFunction);
                }
                break labels;
            case LET:
                if (isEnabled(CompatibilityOption.LetStatement)
                        || isEnabled(CompatibilityOption.LetExpression)) {
                    break labels;
                }
                // fall-through
            case ASYNC:
            case AWAIT:
            case YIELD:
            case IMPLEMENTS:
            case INTERFACE:
            case PACKAGE:
            case PRIVATE:
            case PROTECTED:
            case PUBLIC:
            case STATIC:
            case NAME:
            case ESCAPED_NAME:
            case ESCAPED_RESERVED_WORD:
            case ESCAPED_STRICT_RESERVED_WORD:
            case ESCAPED_YIELD:
            case ESCAPED_ASYNC:
            case ESCAPED_AWAIT:
            case ESCAPED_LET:
                if (LOOKAHEAD(Token.COLON)) {
                    break;
                }
            default:
                break labels;
            }
            long beginLabel = ts.beginPosition();
            String name = labelIdentifier();
            consume(Token.COLON);
            addLabel(beginLabel, labelSet, name);
        }

        assert !labelSet.isEmpty();

        LabelContext labelCx = enterLabelled(StatementType.Statement, labelSet);
        Statement stmt = statement(false);
        exitLabelled();

        return new LabelledStatement(begin, ts.endPosition(), labelCx.abrupts, labelCx.labelSet,
                stmt);
    }

    /**
     * B.3.2 Labelled Function Declarations
     * 
     * @param allowFunction
     *            {@code true} if labelled function statements are allowed
     * @return the labelled function statement
     */
    private LabelledFunctionStatement labelledFunctionStatement(Set<String> labelSet,
            boolean allowFunction) {
        if (!allowFunction) {
            reportSyntaxError(Messages.Key.InvalidToken, token().toString());
        } else if (context.strictMode != StrictMode.NonStrict) {
            reportStrictModeSyntaxError(Messages.Key.InvalidToken, token().toString());
        }
        long begin = ts.beginPosition();
        Declaration function = functionDeclarationWithRetry(false);
        return new LabelledFunctionStatement(begin, ts.endPosition(), labelSet, function);
    }

    /**
     * <strong>[13.14] The <code>throw</code> Statement</strong>
     * 
     * <pre>
     * ThrowStatement<span><sub>[Yield]</sub></span> :
     *     throw [no <i>LineTerminator</i> here] Expression<span><sub>[In, ?Yield]</sub></span> ;
     * </pre>
     * 
     * @return the parsed throw statement
     */
    private ThrowStatement throwStatement() {
        long begin = ts.beginPosition();
        consume(Token.THROW);
        if (!noLineTerminator()) {
            reportSyntaxError(Messages.Key.UnexpectedEndOfLine);
        }
        Expression expr = expression(true);
        semicolon();
        return new ThrowStatement(begin, ts.endPosition(), expr);
    }

    /**
     * <strong>[13.15] The <code>try</code> Statement</strong>
     * 
     * <pre>
     * TryStatement<span><sub>[Yield, Return]</sub></span> :
     *     try Block<span><sub>[?Yield, ?Return]</sub></span> Catch<span><sub>[?Yield, ?Return]</sub></span>
     *     try Block<span><sub>[?Yield, ?Return]</sub></span> Finally<span><sub>[?Yield, ?Return]</sub></span>
     *     try Block<span><sub>[?Yield, ?Return]</sub></span> Catch<span><sub>[?Yield, ?Return]</sub></span> Finally<span><sub>[?Yield, ?Return]</sub></span>
     * Catch<span><sub>[Yield, Return]</sub></span> :
     *     catch ( CatchParameter<span><sub>[?Yield]</sub></span> ) Block<span><sub>[?Yield, ?Return]</sub></span>
     * Finally<span><sub>[Yield, Return]</sub></span> :
     *     finally Block<span><sub>[?Yield, ?Return]</sub></span>
     * CatchParameter<span><sub>[Yield]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield]</sub></span>
     *     BindingPattern<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed try-statement node
     */
    private TryStatement tryStatement() {
        BlockStatement tryBlock, finallyBlock = null;
        CatchNode catchNode = null;
        List<GuardedCatchNode> guardedCatchNodes = emptyList();
        long begin = ts.beginPosition();
        consume(Token.TRY);
        tryBlock = block(NO_INHERITED_BINDING);
        Token tok = token();
        if (tok == Token.CATCH) {
            if (isEnabled(CompatibilityOption.GuardedCatch)) {
                guardedCatchNodes = newList();
                while (token() == Token.CATCH && catchNode == null) {
                    long beginCatch = ts.beginPosition();
                    consume(Token.CATCH);
                    consume(Token.LP);
                    CatchContext catchScope = enterCatchContext();
                    Binding catchParameter = binding(true);
                    addLexDeclaredName(catchParameter);

                    Expression guard;
                    if (token() == Token.IF) {
                        consume(Token.IF);
                        guard = expression(true);
                    } else {
                        guard = null;
                    }

                    consume(Token.RP);

                    // CatchBlock receives a list of non-available lexical declarable names to
                    // fulfill the early error restriction that the BoundNames of CatchParameter
                    // must not also occur in either the LexicallyDeclaredNames or the
                    // VarDeclaredNames of CatchBlock.
                    BlockStatement catchBlock = block(singletonList(catchParameter));

                    exitCatchContext();
                    if (guard != null) {
                        GuardedCatchNode guardedCatchNode = new GuardedCatchNode(beginCatch,
                                ts.endPosition(), catchScope, catchParameter, guard, catchBlock);
                        catchScope.node = guardedCatchNode;
                        guardedCatchNodes.add(guardedCatchNode);
                    } else {
                        catchNode = new CatchNode(beginCatch, ts.endPosition(), catchScope,
                                catchParameter, catchBlock);
                        catchScope.node = catchNode;
                    }
                }
            } else {
                long beginCatch = ts.beginPosition();
                consume(Token.CATCH);
                consume(Token.LP);
                CatchContext catchScope = enterCatchContext();
                Binding catchParameter = binding(true);
                addLexDeclaredName(catchParameter);
                consume(Token.RP);

                // CatchBlock receives a list of non-available lexical declarable names to
                // fulfill the early error restriction that the BoundNames of CatchParameter
                // must not also occur in either the LexicallyDeclaredNames or the
                // VarDeclaredNames of CatchBlock.
                BlockStatement catchBlock = block(singletonList(catchParameter));

                exitCatchContext();
                catchNode = new CatchNode(beginCatch, ts.endPosition(), catchScope, catchParameter,
                        catchBlock);
                catchScope.node = catchNode;
            }

            if (token() == Token.FINALLY) {
                consume(Token.FINALLY);
                finallyBlock = block(NO_INHERITED_BINDING);
            }
        } else {
            consume(Token.FINALLY);
            finallyBlock = block(NO_INHERITED_BINDING);
        }
        return new TryStatement(begin, ts.endPosition(), tryBlock, catchNode, guardedCatchNodes,
                finallyBlock);
    }

    /**
     * <strong>[13.16] The <code>debugger</code> Statement</strong>
     * 
     * <pre>
     * DebuggerStatement :
     *     debugger ;
     * </pre>
     * 
     * @return the parsed debugger statement
     */
    private DebuggerStatement debuggerStatement() {
        long begin = ts.beginPosition();
        consume(Token.DEBUGGER);
        semicolon();
        return new DebuggerStatement(begin, ts.endPosition());
    }

    /**
     * <strong>[Extension] The <code>let</code> Statement</strong>
     * 
     * <pre>
     * LetStatement<span><sub>[Yield, Return]</sub></span> :
     *     let ( BindingList<span><sub>[In, ?Yield]</sub></span> ) BlockStatement<span><sub>[?Yield, ?Return]</sub></span>
     * </pre>
     * 
     * @return the parsed let-statement
     */
    private Statement letStatement() {
        long begin = ts.beginPosition();
        consume(Token.LET);

        consume(Token.LP);
        List<LexicalBinding> lexicalBindings = letBindingList();
        List<Binding> bindings = toBindings(lexicalBindings);
        consume(Token.RP);

        if (token() != Token.LC && isEnabled(CompatibilityOption.LetExpression)) {
            // let expression disguised as let statement - also error in strict mode(!)
            reportStrictModeSyntaxError(begin, Messages.Key.UnexpectedToken, token().toString());

            BlockContext scope = enterBlockContext(bindings);
            Expression expression = assignmentExpression(true);
            exitBlockContext();

            LetExpression letExpression = new LetExpression(begin, ts.endPosition(), scope,
                    lexicalBindings, expression);
            scope.node = letExpression;
            return new ExpressionStatement(begin, ts.endPosition(), letExpression);
        } else {
            BlockContext scope = enterBlockContext(bindings);
            BlockStatement letBlock = block(bindings);
            exitBlockContext();

            LetStatement block = new LetStatement(begin, ts.endPosition(), scope, lexicalBindings,
                    letBlock);
            scope.node = block;
            return block;
        }
    }

    private List<LexicalBinding> letBindingList() {
        InlineArrayList<LexicalBinding> list = newList();
        list.add(letBinding());
        while (token() == Token.COMMA) {
            consume(Token.COMMA);
            list.add(letBinding());
        }
        return list;
    }

    private LexicalBinding letBinding() {
        long begin = ts.beginPosition();
        Binding binding;
        Expression initializer = null;
        if (token() == Token.LC || token() == Token.LB) {
            binding = bindingPattern(false);
            initializer = initializer(true);
        } else {
            binding = bindingIdentifier(false);
            if (token() == Token.ASSIGN) {
                initializer = initializer(true);
                if (IsAnonymousFunctionDefinition(initializer)) {
                    setFunctionName(initializer, (BindingIdentifier) binding);
                }
            }
        }
        return new LexicalBinding(begin, ts.endPosition(), binding, initializer);
    }

    private List<Binding> toBindings(List<LexicalBinding> lexicalBindings) {
        ArrayList<Binding> bindings = new ArrayList<>(lexicalBindings.size());
        for (LexicalBinding lexicalBinding : lexicalBindings) {
            bindings.add(lexicalBinding.getBinding());
        }
        return bindings;
    }

    /* ***************************************************************************************** */

    /**
     * <strong>[12.1] Identifiers</strong>
     * 
     * <pre>
     * IdentifierReference<span><sub>[Yield]</sub></span> :
     *     Identifier
     *     [~Yield] yield
     * </pre>
     * 
     * @return the parsed identifier reference
     */
    private IdentifierReference identifierReference() {
        long begin = ts.beginPosition();
        String identifier = identifier();
        if ("arguments".equals(identifier) && context.kind.isFunction()) {
            context.funContext.needsArguments(false);
        }
        return new IdentifierReference(begin, ts.endPosition(), identifier);
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * 
     * <pre>
     * BindingIdentifier<span><sub>[Yield]</sub></span> :
     *     Identifier
     *     <span><sub>[~Yield]</sub></span> yield
     * </pre>
     * 
     * @return the parsed binding identifier
     */
    private BindingIdentifier bindingIdentifier() {
        return bindingIdentifier(true);
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * 
     * <pre>
     * BindingIdentifier<span><sub>[Yield]</sub></span> :
     *     Identifier
     *     <span><sub>[~Yield]</sub></span> yield
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @return the parsed binding identifier
     */
    private BindingIdentifier bindingIdentifier(boolean allowLet) {
        long begin = ts.beginPosition();
        if (!allowLet) {
            Token tok = token();
            if (tok == Token.LET || tok == Token.ESCAPED_LET) {
                reportTokenNotIdentifier(Token.LET);
            }
        }
        String identifier = identifier();
        if (context.strictMode != StrictMode.NonStrict) {
            if ("arguments".equals(identifier) || "eval".equals(identifier)) {
                reportStrictModeSyntaxError(begin, Messages.Key.StrictModeRestrictedIdentifier);
            }
        }
        return new BindingIdentifier(begin, ts.endPosition(), identifier);
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * <p>
     * Difference when compared to {@link #bindingIdentifier()}:<br>
     * Neither "arguments" nor "eval" nor "let" is allowed.
     * 
     * <pre>
     * BindingIdentifier<span><sub>[Yield]</sub></span> :
     *     Identifier
     *     <span><sub>[~Yield]</sub></span> yield
     * </pre>
     * 
     * @return the parsed binding identifier
     */
    private BindingIdentifier bindingIdentifierClassName() {
        assert context.strictMode == StrictMode.Strict;
        return bindingIdentifier(true);
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * <p>
     * Special case for {@link Token#YIELD} as {@link BindingIdentifier} in functions and generators
     * 
     * <pre>
     * BindingIdentifier<span><sub>[Yield]</sub></span> :
     *     Identifier
     *     <span><sub>[~Yield]</sub></span> yield
     * </pre>
     * 
     * @param isDeclaration
     *            {@code true} if the function is declaration node
     * @return the parsed binding identifier
     */
    private BindingIdentifier bindingIdentifierFunctionName(boolean isDeclaration) {
        Token tok = token();
        switch (tok) {
        case YIELD:
        case ESCAPED_YIELD: {
            // function declarations inherit the yield mode from the parent context
            long begin = ts.beginPosition();
            if (!isYieldName(isDeclaration ? context.parent : context)) {
                reportTokenNotIdentifier(Token.YIELD);
            }
            consume(tok);
            return new BindingIdentifier(begin, ts.endPosition(), getName(Token.YIELD));
        }
        case AWAIT:
        case ESCAPED_AWAIT: {
            // function declarations inherit the await mode from the parent context
            long begin = ts.beginPosition();
            if (!isAwaitName(isDeclaration ? context.parent : context)) {
                reportTokenNotIdentifier(Token.AWAIT);
            }
            consume(tok);
            return new BindingIdentifier(begin, ts.endPosition(), getName(Token.AWAIT));
        }
        default:
            return bindingIdentifier(true);
        }
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * 
     * <pre>
     * LabelIdentifier<span><sub>[Yield]</sub></span> :
     *     Identifier
     *     <span><sub>[~Yield]</sub></span> yield
     * </pre>
     * 
     * @return the parsed label identifier
     */
    private String labelIdentifier() {
        return identifier();
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * 
     * <pre>
     * Identifier :
     *     IdentifierName <strong>but not</strong> ReservedWord
     * </pre>
     * 
     * @return the parsed identifier
     */
    private String identifier() {
        Token tok = token();
        if (!isIdentifier(tok)) {
            reportTokenNotIdentifier(tok);
        }
        String name = getName(tok);
        consume(tok);
        return name;
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * <p>
     * 12.1.1 Static Semantics: Early Errors
     * 
     * @param tok
     *            the token to inspect
     * @return {@code true} if the token is valid binding identifier
     */
    private boolean isBindingIdentifier(Token tok) {
        return isIdentifier(tok);
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * <p>
     * 12.1.1 Static Semantics: Early Errors
     * 
     * @param tok
     *            the token to inspect
     * @return {@code true} if the token is valid label identifier
     */
    private boolean isLabelIdentifier(Token tok) {
        return isIdentifier(tok);
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * <p>
     * 12.1.1 Static Semantics: Early Errors
     * 
     * @param tok
     *            the token to inspect
     * @return {@code true} if the token is valid identifier reference
     */
    private boolean isIdentifierReference(Token tok) {
        return isIdentifier(tok);
    }

    /**
     * <strong>[12.1] Identifiers</strong>
     * <p>
     * 12.1.1 Static Semantics: Early Errors
     * 
     * @param tok
     *            the token to inspect
     * @return {@code true} if the token is valid identifier
     */
    private boolean isIdentifier(Token tok) {
        switch (tok) {
        case NAME:
        case ASYNC:
        case ESCAPED_NAME:
        case ESCAPED_ASYNC:
            return true;
        case ESCAPED_RESERVED_WORD:
            throw reportSyntaxError(Messages.Key.InvalidIdentifier, getName(tok));
        case AWAIT:
        case ESCAPED_AWAIT:
            return isAwaitName(context);
        case YIELD:
        case ESCAPED_YIELD:
            return isYieldName(context);
        case LET:
        case ESCAPED_LET:
        case IMPLEMENTS:
        case INTERFACE:
        case PACKAGE:
        case PRIVATE:
        case PROTECTED:
        case PUBLIC:
        case STATIC:
        case ESCAPED_STRICT_RESERVED_WORD:
            // Strict mode reserved words
            if (context.strictMode != StrictMode.NonStrict) {
                reportStrictModeSyntaxError(Messages.Key.StrictModeInvalidIdentifier, getName(tok));
            }
            return true;
        default:
            return false;
        }
    }

    /**
     * Returns <code>true</code> if {@link Token#YIELD} should be treated as {@link Token#NAME} in
     * the supplied context.
     * 
     * @param yieldContext
     *            the context to use
     * @return {@code true} if 'yield' is a valid name in the parse context
     */
    private boolean isYieldName(ParseContext yieldContext) {
        switch (yieldContext.kind) {
        case Generator:
        case GeneratorMethod:
            // 'yield' is always a keyword in generator functions
            reportSyntaxError(Messages.Key.InvalidIdentifier, getName(Token.YIELD));
            break;
        case ArrowFunction:
        case AsyncArrowFunction:
        case GeneratorComprehension:
            if (yieldContext.yieldAllowed) {
                // 'yield' in arrow function parameters embedded in generator or generator compr.
                // 'yield' in generator comprehension, embedded in generator
                reportSyntaxError(Messages.Key.InvalidIdentifier, getName(Token.YIELD));
            }
            break;
        default:
            assert !yieldContext.yieldAllowed : String.format(
                    "unexpected context kind '%s' with yield allowed", yieldContext.kind);
        }

        // 'yield' is always a keyword in strict-mode (independent of `yieldContext`)
        if (context.strictMode != StrictMode.NonStrict) {
            reportStrictModeSyntaxError(Messages.Key.StrictModeInvalidIdentifier,
                    getName(Token.YIELD));
        }
        return true;
    }

    /**
     * Returns <code>true</code> if {@link Token#AWAIT} should be treated as {@link Token#NAME} in
     * the supplied context.
     * 
     * @param awaitContext
     *            the context to use
     * @return {@code true} if 'await' is a valid name in the parse context
     */
    private boolean isAwaitName(ParseContext awaitContext) {
        switch (awaitContext.kind) {
        case AsyncArrowFunction:
        case AsyncFunction:
        case AsyncMethod:
            // 'await' is always a keyword in async functions
            reportSyntaxError(Messages.Key.InvalidIdentifier, getName(Token.AWAIT));
            break;
        case ArrowFunction:
        case GeneratorComprehension:
            if (awaitContext.awaitAllowed) {
                // 'await' in arrow function parameters, embedded in async function
                // 'await' in generator comprehension, embedded in async function
                reportSyntaxError(Messages.Key.InvalidIdentifier, getName(Token.AWAIT));
            }
            break;
        default:
            assert !awaitContext.awaitAllowed;
        }

        // 'await' is always a keyword in module-mode (independent of `awaitContext`)
        if (moduleCode) {
            reportSyntaxError(Messages.Key.InvalidIdentifier, getName(Token.AWAIT));
        }
        return true;
    }

    /**
     * <strong>[12.2] Primary Expression</strong>
     * 
     * <pre>
     * PrimaryExpresion<span><sub>[Yield]</sub></span> :
     *     this
     *     IdentifierReference<span><sub>[?Yield]</sub></span>
     *     Literal
     *     ArrayLiteral<span><sub>[?Yield]</sub></span>
     *     ArrayComprehension<span><sub>[?Yield]</sub></span>
     *     ObjectLiteral<span><sub>[?Yield]</sub></span>
     *     FunctionExpression
     *     ClassExpression
     *     GeneratorExpression
     *     GeneratorComprehension<span><sub>[?Yield]</sub></span>
     *     RegularExpressionLiteral
     *     TemplateLiteral<span><sub>[?Yield]</sub></span>
     *     CoverParenthesizedExpressionAndArrowParameterList<span><sub>[?Yield]</sub></span>
     * Literal :
     *     NullLiteral
     *     BooleanLiteral
     *     NumericLiteral
     *     StringLiteral
     * </pre>
     * 
     * @return the parsed primary expression node
     */
    private Expression primaryExpression() {
        long begin = ts.beginPosition();
        Token tok = token();
        switch (tok) {
        case THIS:
            consume(tok);
            return new ThisExpression(begin, ts.endPosition());
        case NULL:
            consume(tok);
            return new NullLiteral(begin, ts.endPosition());
        case FALSE:
        case TRUE:
            consume(tok);
            return new BooleanLiteral(begin, ts.endPosition(), tok == Token.TRUE);
        case NUMBER:
            double number = numericLiteral();
            return new NumericLiteral(begin, ts.endPosition(), number);
        case STRING:
            String string = stringLiteral();
            return new StringLiteral(begin, ts.endPosition(), string);
        case DIV:
        case ASSIGN_DIV:
            return regularExpressionLiteral(tok);
        case LB:
            return arrayInitializer();
        case LC:
            return objectLiteral();
        case FUNCTION:
            return functionOrGeneratorExpression();
        case AT:
            return classExpression(decorators());
        case CLASS:
            return classExpression(NO_DECORATORS);
        case LP:
            if (LOOKAHEAD(Token.FOR) && isEnabled(CompatibilityOption.Comprehension)) {
                return generatorComprehension();
            } else {
                return coverParenthesizedExpressionAndArrowParameterList();
            }
        case TEMPLATE:
            return templateLiteral(false);
        case MOD:
            if (isEnabled(Option.NativeCall)) {
                return nativeCallExpression();
            }
            break;
        case LET:
            if (isEnabled(CompatibilityOption.LetExpression)) {
                return letExpression();
            }
            break;
        case ASYNC:
            if (isEnabled(CompatibilityOption.AsyncFunction) && LOOKAHEAD(Token.FUNCTION)
                    && noNextLineTerminator()) {
                return asyncFunctionExpression();
            }
            break;
        default:
        }
        return identifierReference();
    }

    private Expression functionOrGeneratorExpression() {
        if (LOOKAHEAD(Token.MUL)) {
            return generatorExpression(false);
        }
        long position = ts.position(), lineinfo = ts.lineinfo();
        try {
            return functionExpression();
        } catch (RetryGenerator e) {
            ts.reset(position, lineinfo);
            return generatorExpression(true);
        }
    }

    /**
     * <strong>[12.2] Primary Expression</strong>
     * 
     * <pre>
     * CoverParenthesizedExpressionAndArrowParameterList<span><sub>[Yield]</sub></span> :
     *     ( Expression<span><sub>[In, ?Yield]</sub></span> )
     *     ( )
     *     ( ... BindingIdentifier<span><sub>[?Yield]</sub></span> )
     *     ( Expression<span><sub>[In, ?Yield]</sub></span> , ... BindingIdentifier<span><sub>[?Yield]</sub></span>)
     * </pre>
     * 
     * @return the parsed expression node
     */
    private Expression coverParenthesizedExpressionAndArrowParameterList() {
        long position = ts.position(), lineinfo = ts.lineinfo();
        consume(Token.LP);
        Expression expr;
        if (token() == Token.RP) {
            expr = arrowFunctionEmptyParameters();
        } else if (token() == Token.TRIPLE_DOT) {
            expr = arrowFunctionRestParameter();
        } else {
            // Inlined `expression(true)`, all calls to assignmentExpression() are replaced with
            // assignmentExpressionNoValidation() to support cover-init-name and duplicate property
            // names in case this production is an ArrowParameterList.
            expr = assignmentExpressionNoValidation(true);
            if (token() == Token.FOR && isEnabled(CompatibilityOption.LegacyComprehension)) {
                // NB: It is not necessary to remove unchecked object literals from
                // assignmentExpressionNoValidation(), because any early errors will reappear
                // in legacyGeneratorComprehension().
                ts.reset(position, lineinfo);
                return legacyGeneratorComprehension();
            }
            if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                typeAnnotation();
            }
            if (token() == Token.COMMA) {
                InlineArrayList<Expression> list = newList();
                list.add(expr);
                do {
                    consume(Token.COMMA);
                    if (token() == Token.TRIPLE_DOT) {
                        list.add(arrowFunctionRestParameter());
                        break;
                    }
                    if (token() == Token.RP && LOOKAHEAD(Token.ARROW)
                            && isEnabled(CompatibilityOption.FunctionCallTrailingComma)) {
                        break;
                    }
                    expr = assignmentExpressionNoValidation(true);
                    if (token() == Token.COLON && isEnabled(CompatibilityOption.TypeAnnotation)) {
                        typeAnnotation();
                    }
                    list.add(expr);
                } while (token() == Token.COMMA);
                expr = new CommaExpression(list);
            }
        }
        expr.addParentheses();
        consume(Token.RP);
        return expr;
    }

    private EmptyExpression arrowFunctionEmptyParameters() {
        if (!(token() == Token.RP && LOOKAHEAD(Token.ARROW))) {
            reportSyntaxError(Messages.Key.EmptyParenthesizedExpression);
        }
        return new EmptyExpression(0, 0);
    }

    private SpreadElement arrowFunctionRestParameter() {
        long begin = ts.beginPosition();
        consume(Token.TRIPLE_DOT);
        String ident = bindingIdentifier().getName().getIdentifier();
        IdentifierReference identifier = new IdentifierReference(ts.beginPosition(),
                ts.endPosition(), ident);
        SpreadElement spread = new SpreadElement(begin, ts.endPosition(), identifier);
        if (!(token() == Token.RP && LOOKAHEAD(Token.ARROW))) {
            reportSyntaxError(spread, Messages.Key.InvalidSpreadExpression);
        }
        return spread;
    }

    /**
     * <strong>[12.2.5] Array Initializer</strong>
     * 
     * <pre>
     * ArrayInitializer<span><sub>[Yield]</sub></span> :
     *     ArrayLiteral<span><sub>[?Yield]</sub></span>
     *     ArrayComprehension<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed array initializer
     */
    private ArrayInitializer arrayInitializer() {
        if (LOOKAHEAD(Token.FOR) && isEnabled(CompatibilityOption.Comprehension)) {
            return arrayComprehension();
        }
        long begin = ts.beginPosition();
        if (isEnabled(CompatibilityOption.LegacyComprehension)) {
            switch (peek()) {
            case RB:
            case COMMA:
            case TRIPLE_DOT:
                break;
            default:
                // TODO: report eclipse formatter bug
                long position = ts.position(),
                lineinfo = ts.lineinfo();
                consume(Token.LB);
                Expression expression = assignmentExpressionNoValidation(true);
                if (token() == Token.FOR) {
                    // NB: It is not necessary to remove unchecked object literals from
                    // assignmentExpressionNoValidation(), because any early errors will
                    // reappear in legacyArrayComprehension()
                    ts.reset(position, lineinfo);
                    return legacyArrayComprehension();
                }
                return arrayLiteral(begin, expression);
            }
        }
        return arrayLiteral(begin, null);
    }

    /**
     * <strong>[12.2.5] Array Initializer</strong>
     * 
     * <pre>
     * ArrayLiteral<span><sub>[Yield]</sub></span> :
     *     [ Elision<span><sub>opt</sub></span> ]
     *     [ ElementList<span><sub>[?Yield]</sub></span> ]
     *     [ ElementList<span><sub>[?Yield]</sub></span> , Elision<span><sub>opt</sub></span> ]
     * ElementList<span><sub>[Yield]</sub></span> :
     *     Elision<span><sub>opt</sub></span> AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     *     Elision<span><sub>opt</sub></span> SpreadElement<span><sub>[?Yield]</sub></span>
     *     ElementList<span><sub>[?Yield]</sub></span> , Elision<span><sub>opt</sub></span> AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     *     ElementList<span><sub>[?Yield]</sub></span> , Elision<span><sub>opt</sub></span> SpreadElement<span><sub>[?Yield]</sub></span>
     * Elision :
     *     ,
     *     Elision ,
     * SpreadElement<span><sub>[Yield]</sub></span> :
     *     ... AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param begin
     *            the begin source position
     * @param expr
     *            the first array element or {@code null} if not yet parsed
     * @return the parsed array literal
     */
    private ArrayLiteral arrayLiteral(long begin, Expression expr) {
        InlineArrayList<Expression> list = newList();
        boolean needComma = false;
        if (expr == null) {
            consume(Token.LB);
        } else {
            list.add(expr);
            needComma = true;
        }
        for (Token tok; (tok = token()) != Token.RB;) {
            if (needComma) {
                consume(Token.COMMA);
                needComma = false;
            } else if (tok == Token.COMMA) {
                consume(Token.COMMA);
                list.add(new Elision(0, 0));
            } else if (tok == Token.TRIPLE_DOT) {
                long beginSpread = ts.beginPosition();
                consume(Token.TRIPLE_DOT);
                Expression expression = assignmentExpressionNoValidation(true);
                list.add(new SpreadElement(beginSpread, ts.endPosition(), expression));
                needComma = true;
            } else {
                list.add(assignmentExpressionNoValidation(true));
                needComma = true;
            }
        }
        consume(Token.RB);
        return new ArrayLiteral(begin, ts.endPosition(), list, !needComma && !list.isEmpty());
    }

    /**
     * <strong>Array Comprehension</strong>
     * 
     * <pre>
     * ArrayComprehension<span><sub>[Yield]</sub></span> :
     *     [ Comprehension<span><sub>[?Yield]</sub></span> ]
     * </pre>
     * 
     * @return the parsed array comprehension
     */
    private ArrayComprehension arrayComprehension() {
        long begin = ts.beginPosition();
        consume(Token.LB);
        Comprehension comprehension = comprehension();
        consume(Token.RB);
        return new ArrayComprehension(begin, ts.endPosition(), comprehension);
    }

    /**
     * <strong>Array Comprehension</strong>
     * 
     * <pre>
     * Comprehension<span><sub>[Yield]</sub></span> :
     *     ComprehensionFor<span><sub>[?Yield]</sub></span> ComprehensionTail<span><sub>[?Yield]</sub></span>
     * ComprehensionTail<span><sub>[Yield]</sub></span> :
     *     AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     *     ComprehensionFor<span><sub>[?Yield]</sub></span> ComprehensionTail<span><sub>[?Yield]</sub></span>
     *     ComprehensionIf<span><sub>[?Yield]</sub></span> ComprehensionTail<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed comprehension node
     */
    private Comprehension comprehension() {
        assert token() == Token.FOR;
        InlineArrayList<ComprehensionQualifier> list = newList();
        int scopes = 0;
        for (;;) {
            ComprehensionQualifier qualifier;
            if (token() == Token.FOR) {
                scopes += 1;
                qualifier = comprehensionFor();
            } else if (token() == Token.IF) {
                qualifier = comprehensionIf();
            } else {
                break;
            }
            list.add(qualifier);
        }
        Expression expression = assignmentExpression(true);
        while (scopes-- > 0) {
            exitBlockContext();
        }
        return new Comprehension(list, expression);
    }

    /**
     * <strong>Array Comprehension</strong>
     * 
     * <pre>
     * ComprehensionFor<span><sub>[Yield]</sub></span> :
     *     for ( ForBinding<span><sub>[?Yield]</sub></span> of AssignmentExpression<span><sub>[In, ?Yield]</sub></span> )
     * ForBinding<span><sub>[Yield]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield]</sub></span>
     *     BindingPattern<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed comprehension-for node
     */
    private ComprehensionFor comprehensionFor() {
        long begin = ts.beginPosition();
        consume(Token.FOR);
        consume(Token.LP);
        Binding b = forBinding(false);
        consume("of");
        Expression expression = assignmentExpression(true);
        consume(Token.RP);
        BlockContext scope = enterBlockContext(b);
        ComprehensionFor node = new ComprehensionFor(begin, ts.endPosition(), scope, b, expression);
        scope.node = node;
        return node;
    }

    /**
     * <strong>Array Comprehension</strong>
     * 
     * <pre>
     * ForBinding<span><sub>[Yield]</sub></span> :
     *     BindingIdentifier<span><sub>[?Yield]</sub></span>
     *     BindingPattern<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @param allowLet
     *            the flag to select if 'let' is allowed as a binding name
     * @return the parsed for-binding
     */
    private Binding forBinding(boolean allowLet) {
        return binding(allowLet);
    }

    /**
     * <strong>Array Comprehension</strong>
     * 
     * <pre>
     * ComprehensionIf<span><sub>[Yield]</sub></span> :
     *     if ( AssignmentExpression<span><sub>[In, ?Yield]</sub></span> )
     * </pre>
     * 
     * @return the parsed comprehension-if node
     */
    private ComprehensionIf comprehensionIf() {
        long begin = ts.beginPosition();
        consume(Token.IF);
        consume(Token.LP);
        Expression expression = assignmentExpression(true);
        consume(Token.RP);
        return new ComprehensionIf(begin, ts.endPosition(), expression);
    }

    /**
     * <strong>Array Comprehension</strong>
     * 
     * <pre>
     * LegacyArrayComprehension<span><sub>[Yield]</sub></span> :
     *     [ LegacyComprehension<span><sub>[?Yield]</sub></span> ]
     * </pre>
     * 
     * @return the parsed legacy array comprehension
     */
    private ArrayComprehension legacyArrayComprehension() {
        long begin = ts.beginPosition();
        consume(Token.LB);
        LegacyComprehension comprehension = legacyComprehension();
        consume(Token.RB);
        return new ArrayComprehension(begin, ts.endPosition(), comprehension);
    }

    /**
     * <strong>Array Comprehension</strong>
     * 
     * <pre>
     * LegacyComprehension<span><sub>[Yield]</sub></span> :
     *     AssignmentExpression<span><sub>[In, ?Yield]</sub></span> LegacyComprehensionForList<span><sub>[?Yield]</sub></span> LegacyComprehensionIf<span><sub>[?Yield]opt</sub></span>
     * LegacyComprehensionForList<span><sub>[Yield]</sub></span> :
     *     LegacyComprehensionFor<span><sub>[?Yield]</sub></span> LegacyComprehensionForList<span><sub>[?Yield]opt</sub></span>
     * LegacyComprehensionFor<span><sub>[Yield]</sub></span> :
     *     for ( ForBinding<span><sub>[?Yield]</sub></span> of Expression<span><sub>[In, ?Yield]</sub></span> )
     *     for ( ForBinding<span><sub>[?Yield]</sub></span> in Expression<span><sub>[In, ?Yield]</sub></span> )
     *     for each ( ForBinding<span><sub>[?Yield]</sub></span> in Expression<span><sub>[In, ?Yield]</sub></span> )
     * LegacyComprehensionIf<span><sub>[Yield]</sub></span> :
     *     if ( Expression<span><sub>[In, ?Yield]</sub></span> )
     * </pre>
     * 
     * @return the parsed legacy comprehension
     */
    private LegacyComprehension legacyComprehension() {
        BlockContext scope = enterBlockContext();
        Expression expr = assignmentExpression(true);

        InlineArrayList<ComprehensionQualifier> list = newList();
        do {
            long begin = ts.beginPosition();
            consume(Token.FOR);
            boolean each = false;
            if (token() != Token.LP && isName("each")) {
                consume("each");
                each = true;
            }
            consume(Token.LP);
            Binding b = forBinding(false);
            addLexDeclaredName(b);

            LegacyComprehensionFor.IterationKind iterationKind;
            if (each) {
                consume(Token.IN);
                iterationKind = LegacyComprehensionFor.IterationKind.EnumerateValues;
            } else if (token() == Token.IN) {
                consume(Token.IN);
                iterationKind = LegacyComprehensionFor.IterationKind.Enumerate;
            } else {
                consume("of");
                iterationKind = LegacyComprehensionFor.IterationKind.Iterate;
            }
            Expression expression = expression(true);
            consume(Token.RP);

            list.add(new LegacyComprehensionFor(begin, ts.endPosition(), iterationKind, b,
                    expression));
        } while (token() == Token.FOR);

        if (token() == Token.IF) {
            long begin = ts.beginPosition();
            consume(Token.IF);
            consume(Token.LP);
            Expression expression = expression(true);
            consume(Token.RP);
            list.add(new ComprehensionIf(begin, ts.endPosition(), expression));
        }

        exitBlockContext();

        LegacyComprehension comprehension = new LegacyComprehension(scope, list, expr);
        scope.node = comprehension;
        return comprehension;
    }

    /**
     * <strong>[12.2.6] Object Initializer</strong>
     * 
     * <pre>
     * ObjectLiteral<span><sub>[Yield]</sub></span> :
     *     { }
     *     { PropertyDefinitionList<span><sub>[?Yield]</sub></span> }
     *     { PropertyDefinitionList<span><sub>[?Yield]</sub></span> , }
     * PropertyDefinitionList<span><sub>[Yield]</sub></span> :
     *     PropertyDefinition<span><sub>[?Yield]</sub></span>
     *     PropertyDefinitionList<span><sub>[?Yield]</sub></span> , PropertyDefinition<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed object literal
     */
    private ObjectLiteral objectLiteral() {
        long begin = ts.beginPosition();
        consume(Token.LC);
        List<PropertyDefinition> defs;
        if (token() == Token.RC) {
            defs = Collections.emptyList();
        } else {
            InlineArrayList<PropertyDefinition> list = newList();
            do {
                list.add(propertyDefinition());
                if (token() == Token.COMMA) {
                    consume(Token.COMMA);
                } else {
                    break;
                }
            } while (token() != Token.RC);
            defs = list;
        }
        consume(Token.RC);
        ObjectLiteral object = new ObjectLiteral(begin, ts.endPosition(), defs);
        context.addLiteral(object);
        return object;
    }

    private void discardUncheckedObjectLiterals(int oldCount) {
        ArrayDeque<ObjectLiteral> literals = context.objectLiterals;
        if (literals != null) {
            for (int i = oldCount, newCount = literals.size(); i < newCount; ++i) {
                literals.pop();
            }
        }
    }

    /**
     * 12.2.6.1 Static Semantics: Early Errors
     * 
     * @param oldCount
     *            the previous count of object literals
     */
    private void objectLiteral_EarlyErrors(int oldCount) {
        ArrayDeque<ObjectLiteral> literals = context.objectLiterals;
        if (literals != null) {
            for (int i = oldCount, newCount = literals.size(); i < newCount; ++i) {
                objectLiteral_EarlyErrors(literals.pop());
            }
        }
    }

    /**
     * 12.2.6.1 Static Semantics: Early Errors
     * 
     * @param object
     *            the object literal to check for early errors
     */
    private void objectLiteral_EarlyErrors(ObjectLiteral object) {
        boolean hasProto = false, checkProto = isEnabled(CompatibilityOption.ProtoInitializer);
        HashMap<String, Integer> values = null;
        if (isEnabled(CompatibilityOption.DuplicateProperties)) {
            values = new HashMap<String, Integer>();
        }
        for (PropertyDefinition def : object.getProperties()) {
            if (def instanceof CoverInitializedName) {
                // Always throw a Syntax Error if this production is present
                String key = def.getPropertyName().getName();
                throw reportSyntaxError(def, Messages.Key.MissingColonAfterPropertyId, key);
            }
            if (def instanceof SpreadProperty
                    && !isEnabled(CompatibilityOption.ObjectSpreadInitializer)) {
                reportSyntaxError(def, Messages.Key.InvalidDestructuring);
            }
            if (checkProto) {
                hasProto = checkDuplicateProtoInitializer(def, hasProto);
            }
            if (values != null) {
                checkDuplicateProperty(def, values);
            }
        }
    }

    private boolean checkDuplicateProtoInitializer(PropertyDefinition def, boolean hasProto) {
        if (!(def instanceof PropertyValueDefinition)) {
            return hasProto;
        }
        PropertyName propertyName = def.getPropertyName();
        String key = propertyName.getName();
        if (key == null) {
            assert propertyName instanceof ComputedPropertyName;
            return hasProto;
        }
        if (!"__proto__".equals(key)) {
            return hasProto;
        }
        if (hasProto) {
            reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
        }
        return true;
    }

    private void checkDuplicateProperty(PropertyDefinition def, HashMap<String, Integer> values) {
        final int VALUE = 0, GETTER = 1, SETTER = 2, SPECIAL = 4;
        PropertyName propertyName = def.getPropertyName();
        String key = propertyName.getName();
        if (key == null) {
            assert propertyName instanceof ComputedPropertyName;
            return;
        }
        final int kind;
        if (def instanceof PropertyValueDefinition) {
            kind = VALUE;
        } else if (def instanceof PropertyNameDefinition) {
            kind = SPECIAL;
        } else {
            assert def instanceof MethodDefinition;
            MethodDefinition.MethodType type = ((MethodDefinition) def).getType();
            kind = type == MethodType.Getter ? GETTER : type == MethodType.Setter ? SETTER
                    : SPECIAL;
        }
        // It is a Syntax Error if PropertyNameList of PropertyDefinitionList contains any
        // duplicate entries [...]
        if (values.containsKey(key)) {
            int prev = values.get(key);
            if (kind == VALUE && prev != VALUE) {
                reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
            }
            if (kind == VALUE && prev == VALUE) {
                reportStrictModeSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
            }
            if (kind == GETTER && prev != SETTER) {
                reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
            }
            if (kind == SETTER && prev != GETTER) {
                reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
            }
            if (kind == SPECIAL) {
                reportSyntaxError(def, Messages.Key.DuplicatePropertyDefinition, key);
            }
            values.put(key, prev | kind);
        } else {
            values.put(key, kind);
        }
    }

    /**
     * <strong>[12.2.6] Object Initializer</strong>
     * 
     * <pre>
     * PropertyDefinition<span><sub>[Yield]</sub></span> :
     *     IdentifierReference<span><sub>[?Yield]</sub></span>
     *     CoverInitializedName<span><sub>[?Yield]</sub></span>
     *     PropertyName<span><sub>[?Yield]</sub></span> : AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     *     MethodDefinition<span><sub>[?Yield]</sub></span>
     * CoverInitializedName<span><sub>[Yield]</sub></span> :
     *     IdentifierReference Initializer<span><sub>[In, ?Yield]</sub></span>
     * Initializer<span><sub>[In, Yield]</sub></span> :
     *     = AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed property definition
     */
    private PropertyDefinition propertyDefinition() {
        long begin = ts.beginPosition();
        if (token() == Token.LB) {
            // either `PropertyName : AssignmentExpression` or MethodDefinition (normal)
            ComputedPropertyName propertyName = computedPropertyName();
            if (token() == Token.COLON) {
                consume(Token.COLON);
                Expression propertyValue = assignmentExpressionNoValidation(true);
                if (IsAnonymousFunctionDefinition(propertyValue)) {
                    setFunctionName(propertyValue, propertyName);
                }
                return new PropertyValueDefinition(begin, ts.endPosition(), propertyName,
                        propertyValue);
            }
            return normalMethod(MethodAllocation.Object, false, NO_DECORATORS, begin, propertyName);
        }
        if (token() == Token.TRIPLE_DOT
                && (isEnabled(CompatibilityOption.ObjectSpreadInitializer) || isEnabled(CompatibilityOption.ObjectRestDestructuring))) {
            consume(Token.TRIPLE_DOT);
            Expression expression = assignmentExpression(true);
            return new SpreadProperty(begin, ts.endPosition(), expression);
        }
        if (isPropertyName(token()) && LOOKAHEAD(Token.COLON)) {
            PropertyName propertyName = literalPropertyName();
            consume(Token.COLON);
            Expression propertyValue = assignmentExpressionNoValidation(true);
            if (IsAnonymousFunctionDefinition(propertyValue)) {
                setFunctionName(propertyValue, propertyName);
            }
            return new PropertyValueDefinition(begin, ts.endPosition(), propertyName, propertyValue);
        }
        if (Token.isIdentifierName(token()) && (LOOKAHEAD(Token.COMMA) || LOOKAHEAD(Token.RC))) {
            IdentifierReference identifier = identifierReference();
            return new PropertyNameDefinition(begin, ts.endPosition(), identifier);
        }
        if (Token.isIdentifierName(token()) && LOOKAHEAD(Token.ASSIGN)) {
            IdentifierReference identifier = identifierReference();
            consume(Token.ASSIGN);
            Expression initializer = assignmentExpression(true);
            if (IsAnonymousFunctionDefinition(initializer)) {
                setFunctionName(initializer, identifier);
            }
            return new CoverInitializedName(begin, ts.endPosition(), identifier, initializer);
        }
        List<Expression> decorators = token() == Token.AT ? decorators() : NO_DECORATORS;
        return methodDefinition(MethodAllocation.Object, false, decorators);
    }

    /**
     * <strong>[12.2.6] Object Initializer</strong>
     * 
     * <pre>
     * PropertyName<span><sub>[Yield, GeneratorParameter]</sub></span> :
     *   LiteralPropertyName
     *   <span><sub>[+GeneratorParameter]</sub></span>ComputedPropertyName
     *   <span><sub>[~GeneratorParameter]</sub></span>ComputedPropertyName<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed property name
     */
    private PropertyName propertyName() {
        if (token() != Token.LB) {
            return literalPropertyName();
        } else {
            return computedPropertyName();
        }
    }

    /**
     * <strong>[12.2.6] Object Initializer</strong>
     * 
     * <pre>
     * LiteralPropertyName :
     *     IdentifierName
     *     StringLiteral
     *     NumericLiteral
     * </pre>
     * 
     * @return the parsed literal property name
     */
    private PropertyName literalPropertyName() {
        long begin = ts.beginPosition();
        switch (token()) {
        case STRING:
            String string = stringLiteral();
            return new StringLiteral(begin, ts.endPosition(), string);
        case NUMBER:
            double number = numericLiteral();
            return new NumericLiteral(begin, ts.endPosition(), number);
        default:
            String ident = identifierName();
            return new IdentifierName(begin, ts.endPosition(), ident);
        }
    }

    /**
     * <strong>[12.2.6] Object Initializer</strong>
     * 
     * <pre>
     * ComputedPropertyName<span><sub>[Yield]</sub></span> :
     *     [ AssignmentExpression<span><sub>[In, ?Yield]</sub></span> ]
     * </pre>
     * 
     * @return the parsed computed property name
     */
    private ComputedPropertyName computedPropertyName() {
        long begin = ts.beginPosition();
        consume(Token.LB);
        Expression expression = assignmentExpression(true);
        consume(Token.RB);
        return new ComputedPropertyName(begin, ts.endPosition(), expression);
    }

    /**
     * <strong>Generator Comprehensions</strong>
     * 
     * <pre>
     * GeneratorComprehension<span><sub>[Yield]</sub></span> :
     *     ( Comprehension<span><sub>[?Yield]</sub></span> )
     * </pre>
     * 
     * @return the parsed generator comprehension
     */
    private GeneratorComprehension generatorComprehension() {
        newContext(ContextKind.GeneratorComprehension);
        try {
            long begin = ts.beginPosition();
            // generator comprehensions have no named parameters
            FormalParameterList parameters = emptyFormalParameterList();
            Comprehension comprehension = generatorComprehensionBody();

            FunctionContext scope = context.funContext;
            GeneratorComprehension generator = new GeneratorComprehension(begin, ts.endPosition(),
                    scope, parameters, comprehension);
            scope.setNode(generator);

            return inheritStrictness(generator);
        } finally {
            restoreContext();
        }
    }

    private Comprehension generatorComprehensionBody() {
        // propagate the outer context's 'yield' state
        context.yieldAllowed = context.parent.yieldAllowed;
        // propagate the outer context's 'await' state
        context.awaitAllowed = context.parent.awaitAllowed;

        // Necessary to call applyStrictMode() manually b/c directivePrologue() is not used.
        applyStrictMode(false);
        if (context.strictMode != StrictMode.Strict) {
            context.funContext.lexicalScope = enterFunctionBodyContext();
        }
        consume(Token.LP);
        Comprehension comprehension = comprehension();
        consume(Token.RP);
        if (context.strictMode != StrictMode.Strict) {
            exitFunctionBodyContext();
        }
        assert context.assertLiteralsUnchecked(0);
        return comprehension;
    }

    /**
     * <strong>Generator Comprehensions</strong>
     * 
     * <pre>
     * LegacyGeneratorComprehension<span><sub>[Yield]</sub></span> :
     *     ( LegacyComprehension<span><sub>[?Yield]</sub></span> )
     * </pre>
     * 
     * @return the parsed legacy generator comprehension
     */
    private GeneratorComprehension legacyGeneratorComprehension() {
        newContext(ContextKind.GeneratorComprehension);
        try {
            long begin = ts.beginPosition();
            // generator comprehensions have no named parameters
            FormalParameterList parameters = emptyFormalParameterList();
            LegacyComprehension comprehension = legacyGeneratorComprehensionBody();

            FunctionContext scope = context.funContext;
            GeneratorComprehension generator = new GeneratorComprehension(begin, ts.endPosition(),
                    scope, parameters, comprehension);
            scope.setNode(generator);

            return inheritStrictness(generator);
        } finally {
            restoreContext();
        }
    }

    private LegacyComprehension legacyGeneratorComprehensionBody() {
        // propagate the outer context's 'yield' state
        context.yieldAllowed = context.parent.yieldAllowed;
        // propagate the outer context's 'await' state
        context.awaitAllowed = context.parent.awaitAllowed;

        // Necessary to call applyStrictMode() manually b/c directivePrologue() is not used.
        applyStrictMode(false);
        if (context.strictMode != StrictMode.Strict) {
            context.funContext.lexicalScope = enterFunctionBodyContext();
        }
        consume(Token.LP);
        LegacyComprehension comprehension = legacyComprehension();
        consume(Token.RP);
        if (context.strictMode != StrictMode.Strict) {
            exitFunctionBodyContext();
        }
        assert context.assertLiteralsUnchecked(0);
        return comprehension;
    }

    /**
     * <strong>[12.2.8] Regular Expression Literals</strong>
     * 
     * <pre>
     * RegularExpressionLiteral ::
     *     / RegularExpressionBody / RegularExpressionFlags
     * </pre>
     * 
     * @param tok
     *            the start token of the regular expression, must be either {@link Token#DIV} or
     *            {@link Token#ASSIGN_DIV}
     * @return the parsed regular expression literal
     */
    private RegularExpressionLiteral regularExpressionLiteral(Token tok) {
        long begin = ts.beginPosition();
        String pattern = ts.readRegularExpression(tok);
        String flags = ts.readRegularExpressionFlags();
        regularExpressionLiteral_EarlyErrors(begin, pattern, flags);
        consume(tok);
        return new RegularExpressionLiteral(begin, ts.endPosition(), pattern, flags);
    }

    /**
     * 12.2.8.1 Static Semantics: Early Errors
     * 
     * @param sourcePos
     *            the source position where the regular expression literal starts
     * @param pattern
     *            the regular expression literal's pattern part
     * @param flags
     *            the regular expression literal's flags part
     */
    private void regularExpressionLiteral_EarlyErrors(long sourcePos, String pattern, String flags) {
        // parse to validate regular expression
        RegExpParser.syntaxParse(pattern, flags, getSourceName(), toLine(sourcePos),
                toColumn(sourcePos), isEnabled(CompatibilityOption.WebRegularExpressions));
    }

    /**
     * <strong>[12.2.9] Template Literals</strong>
     * 
     * <pre>
     * TemplateLiteral<span><sub>[Yield]</sub></span> :
     *     NoSubstitutionTemplate
     *     TemplateHead Expression<span><sub>[In, ?Yield]</sub></span> TemplateSpans<span><sub>[?Yield]</sub></span>
     * TemplateSpans<span><sub>[Yield]</sub></span> :
     *     TemplateTail
     *     TemplateMiddleList<span><sub>[?Yield]</sub></span> TemplateTail
     * TemplateMiddleList<span><sub>[Yield]</sub></span> :
     *     TemplateMiddle Expression<span><sub>[In, ?Yield]</sub></span>
     *     TemplateMiddleList<span><sub>[?Yield]</sub></span> TemplateMiddle Expression<span><sub>[In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param tagged
     *            the flag for tagged template literals
     * @return the parsed template literal
     */
    private TemplateLiteral templateLiteral(boolean tagged) {
        long begin = ts.beginPosition();
        InlineArrayList<Expression> elements = newList();
        elements.add(templateCharacters(Token.TEMPLATE));
        while (token() == Token.LC) {
            consume(Token.LC);
            elements.add(expression(true));
            if (token() != Token.RC) {
                reportTokenMismatch(Token.RC, token());
            }
            elements.add(templateCharacters(Token.RC));
        }
        consume(Token.TEMPLATE);

        if (tagged && (elements.size() / 2) + 1 > MAX_ARGUMENTS) {
            reportSyntaxError(Messages.Key.FunctionTooManyArguments);
        }
        return new TemplateLiteral(begin, ts.endPosition(), tagged, elements);
    }

    private TemplateCharacters templateCharacters(Token start) {
        long begin = ts.beginPosition();
        String[] values = ts.readTemplateLiteral(start);
        return new TemplateCharacters(begin, ts.rawEndPosition(), values[0], values[1]);
    }

    /**
     * <strong>[Extension] Native call expression</strong>
     * 
     * <pre>
     * NativeCallExpression :
     *     % Identifier ( Arguments )
     * </pre>
     */
    private NativeCallExpression nativeCallExpression() {
        long begin = ts.beginPosition();
        consume(Token.MOD);
        IdentifierReference name = identifierReference();
        List<Expression> args = arguments();
        return new NativeCallExpression(begin, ts.endPosition(), name, args);
    }

    /**
     * <strong>[Extension] The <code>let</code> Expression</strong>
     * 
     * <pre>
     * LetExpression<span><sub>[Yield]</sub></span> :
     *     let ( BindingList<span><sub>[In, ?Yield]</sub></span> ) AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed let expression
     */
    private LetExpression letExpression() {
        long begin = ts.beginPosition();
        consume(Token.LET);

        consume(Token.LP);
        List<LexicalBinding> lexicalBindings = letBindingList();
        List<Binding> bindings = toBindings(lexicalBindings);
        consume(Token.RP);

        BlockContext scope = enterBlockContext(bindings);
        Expression expression = assignmentExpression(true);
        exitBlockContext();

        LetExpression letExpression = new LetExpression(begin, ts.endPosition(), scope,
                lexicalBindings, expression);
        scope.node = letExpression;
        return letExpression;
    }

    /**
     * <strong>[12.3] Left-Hand-Side Expressions</strong>
     * 
     * <pre>
     * MemberExpression<span><sub>[Yield]</sub></span> :
     *     PrimaryExpression<span><sub>[?Yield]</sub></span>
     *     MemberExpression<span><sub>[?Yield]</sub></span> [ Expression<span><sub>[In, ?Yield]</sub></span> ]
     *     MemberExpression<span><sub>[?Yield]</sub></span> . IdentifierName
     *     MemberExpression<span><sub>[?Yield]</sub></span> TemplateLiteral<span><sub>[?Yield]</sub></span>
     *     SuperProperty<span><sub>[?Yield]</sub></span>
     *     NewSuper Arguments<span><sub>[?Yield]</sub></span>
     *     MetaProperty
     *     new MemberExpression<span><sub>[?Yield]</sub></span> Arguments<span><sub>[?Yield]</sub></span>
     * SuperProperty<span><sub>[Yield]</sub></span> :
     *     super [ Expression<span><sub>[In, ?Yield]</sub></span> ]
     *     super . IdentifierName
     * MetaProperty :
     *     NewTarget
     * NewTarget :
     *     new . target
     * NewExpression<span><sub>[Yield]</sub></span> :
     *     MemberExpression<span><sub>[?Yield]</sub></span>
     *     new NewExpression<span><sub>[?Yield]</sub></span>
     *     NewSuper
     * NewSuper :
     *     new super
     * CallExpression<span><sub>[Yield]</sub></span> :
     *     MemberExpression<span><sub>[?Yield]</sub></span> Arguments<span><sub>[?Yield]</sub></span>
     *     SuperCall<span><sub>[?Yield]</sub></span>
     *     CallExpression<span><sub>[?Yield]</sub></span> Arguments<span><sub>[?Yield]</sub></span>
     *     CallExpression<span><sub>[?Yield]</sub></span> [ Expression<span><sub>[In, ?Yield]</sub></span> ]
     *     CallExpression<span><sub>[?Yield]</sub></span> . IdentifierName
     *     CallExpression<span><sub>[?Yield]</sub></span> TemplateLiteral<span><sub>[?Yield]</sub></span>
     * SuperCall<span><sub>[Yield]</sub></span> :
     *     super Arguments<span><sub>[?Yield]</sub></span>
     * LeftHandSideExpression<span><sub>[Yield]</sub></span> :
     *     NewExpression<span><sub>[?Yield]</sub></span>
     *     CallExpression<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @param allowCall
     *            the flag to select whether or not call expressions are allowed
     * @param allowElement
     *            the flag to select whether or not element member expressions are allowed
     * @return the parsed left-hand side expression
     */
    private Expression leftHandSideExpression(boolean allowCall, boolean allowElement) {
        long begin = ts.beginPosition();
        Expression lhs;
        if (token() == Token.NEW) {
            consume(Token.NEW);
            if (token() == Token.DOT) {
                newTarget();
                consume(Token.DOT);
                consume("target");
                lhs = new NewTarget(begin, ts.endPosition());
            } else if (token() == Token.SUPER && !(LOOKAHEAD(Token.DOT) || LOOKAHEAD(Token.LB))
                    && isEnabled(CompatibilityOption.NewSuper)) {
                newSuper();
                consume(Token.SUPER);
                if (token() == Token.LP) {
                    List<Expression> args = arguments();
                    lhs = new SuperNewExpression(begin, ts.endPosition(), args);
                } else {
                    return new SuperNewExpression(begin, ts.endPosition(),
                            Collections.<Expression> emptyList());
                }
            } else {
                Expression expr = leftHandSideExpression(false, allowElement);
                if (token() == Token.LP) {
                    List<Expression> args = arguments();
                    lhs = new NewExpression(begin, ts.endPosition(), expr, args);
                } else {
                    return new NewExpression(begin, ts.endPosition(), expr,
                            Collections.<Expression> emptyList());
                }
            }
        } else if (token() == Token.SUPER) {
            consume(Token.SUPER);
            switch (token()) {
            case DOT:
                superPropertyAccess();
                consume(Token.DOT);
                String name = identifierName();
                lhs = new SuperPropertyAccessor(begin, ts.endPosition(), name);
                break;
            case LB:
                superPropertyAccess();
                consume(Token.LB);
                Expression expr = expression(true);
                consume(Token.RB);
                lhs = new SuperElementAccessor(begin, ts.endPosition(), expr);
                break;
            case LP:
                if (allowCall) {
                    superCall();
                    List<Expression> args = arguments();
                    lhs = new SuperCallExpression(begin, ts.endPosition(), args);
                    break;
                }
            case TEMPLATE:
            default:
                throw reportSyntaxError(Messages.Key.InvalidToken, token().toString());
            }
        } else {
            lhs = primaryExpression();
        }

        for (;;) {
            switch (token()) {
            case DOT:
                consume(Token.DOT);
                String name = identifierName();
                lhs = new PropertyAccessor(begin, ts.endPosition(), lhs, name);
                break;
            case LB:
                if (!allowElement) {
                    return lhs;
                }
                consume(Token.LB);
                Expression expr = expression(true);
                consume(Token.RB);
                lhs = new ElementAccessor(begin, ts.endPosition(), lhs, expr);
                break;
            case LP:
                if (!allowCall) {
                    return lhs;
                }
                if (lhs instanceof IdentifierReference
                        && "eval".equals(((IdentifierReference) lhs).getName())) {
                    context.setHasEval();
                }
                List<Expression> args = arguments();
                lhs = new CallExpression(begin, ts.endPosition(), lhs, args);
                break;
            case TEMPLATE:
                TemplateLiteral templ = templateLiteral(true);
                lhs = new TemplateCallExpression(begin, ts.endPosition(), lhs, templ);
                break;
            default:
                return lhs;
            }
        }
    }

    private void superPropertyAccess() {
        ParseContext superContext = context.findSuperContext();
        // 14.1.2 Static Semantics: Early Errors
        // 15.1.1 Static Semantics: Early Errors
        // 15.2.1.1 Static Semantics: Early Errors
        if ((superContext.kind == ContextKind.Script && !isEnabled(Option.FunctionThis))
                || superContext.kind == ContextKind.Module
                || superContext.kind == ContextKind.Function
                || superContext.kind == ContextKind.Generator
                || superContext.kind == ContextKind.AsyncFunction) {
            reportSyntaxError(Messages.Key.InvalidSuperExpression);
        }
        superContext.setNeedsSuperBinding();
    }

    private void superCall() {
        ParseContext superContext = context.findSuperContext();
        // 12.2.6.1 Static Semantics: Early Errors
        // 14.1.2 Static Semantics: Early Errors
        // 14.4.1 Static Semantics: Early Errors
        // 14.5.1 Static Semantics: Early Errors
        // 15.1.1 Static Semantics: Early Errors
        // 15.2.1.1 Static Semantics: Early Errors
        if ((superContext.kind == ContextKind.Script && !isEnabled(Option.FunctionThis))
                || superContext.kind == ContextKind.Module
                || superContext.kind == ContextKind.Function
                || superContext.kind == ContextKind.Generator
                || superContext.kind == ContextKind.AsyncFunction
                || (superContext.kind.isMethod() && !superContext.isDerivedClassConstructor)) {
            reportSyntaxError(Messages.Key.InvalidSuperCallExpression);
        }
    }

    private void newSuper() {
        ParseContext superContext = context.findSuperContext();
        // 12.2.6.1 Static Semantics: Early Errors
        // 14.1.2 Static Semantics: Early Errors
        // 14.4.1 Static Semantics: Early Errors
        // 14.5.1 Static Semantics: Early Errors
        // 15.1.1 Static Semantics: Early Errors
        // 15.2.1.1 Static Semantics: Early Errors
        if ((superContext.kind == ContextKind.Script && !isEnabled(Option.FunctionThis))
                || superContext.kind == ContextKind.Module
                || superContext.kind == ContextKind.Function
                || superContext.kind == ContextKind.Generator
                || superContext.kind == ContextKind.AsyncFunction
                || (superContext.kind.isMethod() && !superContext.isDerivedClassConstructor)) {
            reportSyntaxError(Messages.Key.InvalidNewSuperExpression);
        }
    }

    private void newTarget() {
        ParseContext superContext = context.findSuperContext();
        // 15.1.1 Static Semantics: Early Errors
        // 15.2.1.1 Static Semantics: Early Errors
        if ((superContext.kind == ContextKind.Script && !isEnabled(Option.FunctionThis))
                || superContext.kind == ContextKind.Module) {
            reportSyntaxError(Messages.Key.InvalidNewTarget);
        }
    }

    /**
     * Entry point for {@link #leftHandSideExpression(boolean, boolean)} which additionally performs
     * object literal early error checks.
     * 
     * @param allowElement
     *            the flag to select whether or not element member expressions are allowed
     * @return the parsed left-hand side expression
     */
    private Expression leftHandSideExpressionWithValidation(boolean allowElement) {
        int count = context.countLiterals();
        Expression lhs = leftHandSideExpression(true, allowElement);
        objectLiteral_EarlyErrors(count);
        return lhs;
    }

    /**
     * <strong>[12.3] Left-Hand-Side Expressions</strong>
     * 
     * <pre>
     * Arguments<span><sub>[Yield]</sub></span> :
     *     ()
     *     ( ArgumentList<span><sub>[?Yield]</sub></span> )
     * ArgumentList<span><sub>[Yield]</sub></span> :
     *     AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     *     ... AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     *     ArgumentList<span><sub>[?Yield]</sub></span> , AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     *     ArgumentList<span><sub>[?Yield]</sub></span> , ... AssignmentExpression<span><sub>[In, ?Yield]</sub></span>
     * </pre>
     * 
     * @return the list of parsed function call arguments
     */
    private List<Expression> arguments() {
        long position = ts.position(), lineinfo = ts.lineinfo();
        consume(Token.LP);
        if (token() == Token.RP) {
            consume(Token.RP);
            return Collections.emptyList();
        }
        InlineArrayList<Expression> args = newList();
        if (token() != Token.TRIPLE_DOT && isEnabled(CompatibilityOption.LegacyComprehension)) {
            Expression expr = assignmentExpression(true);
            if (token() == Token.FOR) {
                ts.reset(position, lineinfo);
                args.add(legacyGeneratorComprehension());
                return args;
            }
            args.add(expr);
            if (token() == Token.COMMA) {
                consume(Token.COMMA);
                if (token() == Token.RP && isEnabled(CompatibilityOption.FunctionCallTrailingComma)) {
                    consume(Token.RP);
                    return args;
                }
            } else {
                consume(Token.RP);
                return args;
            }
        }
        for (;;) {
            Expression expr;
            if (token() == Token.TRIPLE_DOT) {
                long begin = ts.beginPosition();
                consume(Token.TRIPLE_DOT);
                Expression e = assignmentExpression(true);
                expr = new CallSpreadElement(begin, ts.endPosition(), e);
            } else {
                expr = assignmentExpression(true);
            }
            args.add(expr);
            if (token() == Token.COMMA) {
                consume(Token.COMMA);
                if (token() == Token.RP && isEnabled(CompatibilityOption.FunctionCallTrailingComma)) {
                    break;
                }
            } else {
                break;
            }
        }
        if (args.size() > MAX_ARGUMENTS) {
            reportSyntaxError(Messages.Key.FunctionTooManyArguments);
        }
        consume(Token.RP);
        return args;
    }

    /**
     * <strong>[12.4] Postfix Expressions</strong><br>
     * <strong>[12.5] Unary Operators</strong>
     * 
     * <pre>
     * PostfixExpression<span><sub>[Yield]</sub></span> :
     *     LeftHandSideExpression<span><sub>[?Yield]</sub></span>
     *     LeftHandSideExpression<span><sub>[?Yield]</sub></span> [no <i>LineTerminator</i> here] ++
     *     LeftHandSideExpression<span><sub>[?Yield]</sub></span> [no <i>LineTerminator</i> here] --
     * UnaryExpression<span><sub>[Yield]</sub></span> :
     *     PostfixExpression<span><sub>[?Yield]</sub></span>
     *     delete UnaryExpression<span><sub>[?Yield]</sub></span>
     *     void UnaryExpression<span><sub>[?Yield]</sub></span>
     *     typeof UnaryExpression<span><sub>[?Yield]</sub></span>
     *     ++ UnaryExpression<span><sub>[?Yield]</sub></span>
     *     -- UnaryExpression<span><sub>[?Yield]</sub></span>
     *     + UnaryExpression<span><sub>[?Yield]</sub></span>
     *     - UnaryExpression<span><sub>[?Yield]</sub></span>
     *     ~ UnaryExpression<span><sub>[?Yield]</sub></span>
     *     ! UnaryExpression<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @return the parsed unary expression
     */
    private Expression unaryExpression() {
        long begin = ts.beginPosition();
        Token tok = token();
        switch (tok) {
        case DELETE:
        case VOID:
        case TYPEOF:
        case INC:
        case DEC:
        case ADD:
        case SUB:
        case BITNOT:
        case NOT: {
            consume(tok);
            Expression operand = unaryExpression();
            UnaryExpression unary = new UnaryExpression(begin, ts.endPosition(),
                    unaryOp(tok, false), operand);
            if (tok == Token.INC || tok == Token.DEC) {
                // 12.5.1 Static Semantics: Early Errors
                validateSimpleAssignment(operand, ExceptionType.ReferenceError,
                        Messages.Key.InvalidIncDecTarget);
            }
            if (tok == Token.DELETE) {
                // 12.5.4.1 Static Semantics: Early Errors
                if (operand instanceof IdentifierReference) {
                    reportStrictModeSyntaxError(unary, Messages.Key.StrictModeInvalidDeleteOperand);
                }
            }
            return unary;
        }
        case AWAIT:
            if (isEnabled(CompatibilityOption.AsyncFunction)) {
                switch (context.kind) {
                case AsyncArrowFunction:
                case AsyncFunction:
                case AsyncMethod:
                    if (!context.awaitAllowed) {
                        // await in async function parameters
                        reportSyntaxError(Messages.Key.InvalidAwaitExpression);
                    }
                    return awaitExpression();
                case ArrowFunction:
                case GeneratorComprehension:
                    if (context.awaitAllowed) {
                        // One of:
                        // - await in arrow function parameters, nested in async function
                        // - await in generator comprehension, nested in async function
                        reportSyntaxError(Messages.Key.InvalidAwaitExpression);
                    }
                    break;
                default:
                    assert !context.awaitAllowed;
                }
            }
            // fall-through
        default: {
            Expression lhs = leftHandSideExpression(true, true);
            if (noLineTerminator()) {
                tok = token();
                if (tok == Token.INC || tok == Token.DEC) {
                    // 12.4.1 Static Semantics: Early Errors
                    validateSimpleAssignment(lhs, ExceptionType.ReferenceError,
                            Messages.Key.InvalidIncDecTarget);
                    consume(tok);
                    return new UnaryExpression(begin, ts.endPosition(), unaryOp(tok, true), lhs);
                }
            }
            return lhs;
        }
        }
    }

    private static UnaryExpression.Operator unaryOp(Token tok, boolean postfix) {
        switch (tok) {
        case DELETE:
            return UnaryExpression.Operator.DELETE;
        case VOID:
            return UnaryExpression.Operator.VOID;
        case TYPEOF:
            return UnaryExpression.Operator.TYPEOF;
        case INC:
            return postfix ? UnaryExpression.Operator.POST_INC : UnaryExpression.Operator.PRE_INC;
        case DEC:
            return postfix ? UnaryExpression.Operator.POST_DEC : UnaryExpression.Operator.PRE_DEC;
        case ADD:
            return UnaryExpression.Operator.POS;
        case SUB:
            return UnaryExpression.Operator.NEG;
        case BITNOT:
            return UnaryExpression.Operator.BITNOT;
        case NOT:
            return UnaryExpression.Operator.NOT;
        default:
            throw new AssertionError();
        }
    }

    /**
     * <strong>[12.6] Multiplicative Operators</strong><br>
     * <strong>[12.7] Additive Operators</strong><br>
     * <strong>[12.8] Bitwise Shift Operators</strong><br>
     * <strong>[12.9] Relational Operators</strong><br>
     * <strong>[12.10] Equality Operators</strong><br>
     * <strong>[12.11] Binary Bitwise Operators</strong><br>
     * <strong>[12.12] Binary Logical Operators</strong><br>
     * 
     * <pre>
     * MultiplicativeExpression<span><sub>[Yield]</sub></span> :
     *     UnaryExpression<span><sub>[?Yield]</sub></span>
     *     MultiplicativeExpression<span><sub>[?Yield]</sub></span> * UnaryExpression<span><sub>[?Yield]</sub></span>
     *     MultiplicativeExpression<span><sub>[?Yield]</sub></span> / UnaryExpression<span><sub>[?Yield]</sub></span>
     *     MultiplicativeExpression<span><sub>[?Yield]</sub></span> % UnaryExpression<span><sub>[?Yield]</sub></span>
     * AdditiveExpression<span><sub>[Yield]</sub></span> :
     *     MultiplicativeExpression<span><sub>[?Yield]</sub></span>
     *     AdditiveExpression<span><sub>[?Yield]</sub></span> + MultiplicativeExpression<span><sub>[?Yield]</sub></span>
     *     AdditiveExpression<span><sub>[?Yield]</sub></span> - MultiplicativeExpression<span><sub>[?Yield]</sub></span>
     * ShiftExpression<span><sub>[Yield]</sub></span> :
     *     AdditiveExpression<span><sub>[?Yield]</sub></span>
     *     ShiftExpression<span><sub>[?Yield]</sub></span> {@literal <<} AdditiveExpression<span><sub>[?Yield]</sub></span>
     *     ShiftExpression<span><sub>[?Yield]</sub></span> {@literal >>} AdditiveExpression<span><sub>[?Yield]</sub></span>
     *     ShiftExpression<span><sub>[?Yield]</sub></span> {@literal >>>} AdditiveExpression<span><sub>[?Yield]</sub></span>
     * RelationalExpression<span><sub>[In, Yield]</sub></span> :
     *     ShiftExpression<span><sub>[?Yield]</sub></span>
     *     RelationalExpression<span><sub>[?in, ?Yield]</sub></span> {@literal <} ShiftExpression<span><sub>[?Yield]</sub></span>
     *     RelationalExpression<span><sub>[?in, ?Yield]</sub></span> {@literal >} ShiftExpression<span><sub>[?Yield]</sub></span>
     *     RelationalExpression<span><sub>[?in, ?Yield]</sub></span> {@literal <=} ShiftExpression<span><sub>[?Yield]</sub></span>
     *     RelationalExpression<span><sub>[?in, ?Yield]</sub></span> {@literal >=} ShiftExpression<span><sub>[?Yield]</sub></span>
     *     RelationalExpression<span><sub>[?in, ?Yield]</sub></span> instanceof ShiftExpression<span><sub>[?Yield]</sub></span>
     *     <span><sub>[+In]</sub></span> RelationalExpression<span><sub>[In, ?Yield]</sub></span> in ShiftExpression<span><sub>[?Yield]</sub></span>
     * EqualityExpression<span><sub>[In, Yield]</sub></span> :
     *     RelationalExpression<span><sub>[?In, ?Yield]</sub></span>
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span> == RelationalExpression<span><sub>[?In, ?Yield]</sub></span>
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span> != RelationalExpression<span><sub>[?In, ?Yield]</sub></span>
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span> === RelationalExpression<span><sub>[?In, ?Yield]</sub></span>
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span> !== RelationalExpression<span><sub>[?In, ?Yield]</sub></span>
     * BitwiseANDExpression<span><sub>[In, Yield]</sub></span> :
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     *     BitwiseANDExpression<span><sub>[?In, ?Yield]</sub></span> {@literal &} EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     * BitwiseXORExpression<span><sub>[In, Yield]</sub></span> :
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     *     BitwiseXORExpression<span><sub>[?In, ?Yield]</sub></span> ^ EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     * BitwiseORExpression<span><sub>[In, Yield]</sub></span> :
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     *     BitwiseORExpression<span><sub>[?In, ?Yield]</sub></span> | EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     * LogicalANDExpression<span><sub>[In, Yield]</sub></span> :
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     *     LogicalANDExpression<span><sub>[?In, ?Yield]</sub></span> {@literal &&} EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     * LogicalORExpression<span><sub>[In, Yield]</sub></span> :
     *     EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     *     LogicalORExpression<span><sub>[?In, ?Yield]</sub></span> || EqualityExpression<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed binary expression
     */
    private Expression binaryExpression(boolean allowIn) {
        Expression lhs = unaryExpression();
        return binaryExpression(allowIn, lhs, BinaryExpression.Operator.OR.getPrecedence());
    }

    private Expression binaryExpression(boolean allowIn, Expression lhs, int minpred) {
        // Recursive-descent parsers require multiple levels of recursion to parse binary
        // expressions, to avoid this we're using precedence climbing.
        for (;;) {
            Token tok = token();
            if (tok == Token.IN && !allowIn) {
                break;
            }
            BinaryExpression.Operator op = binaryOp(tok);
            int pred = (op != null ? op.getPrecedence() : -1);
            if (pred < minpred) {
                break;
            }
            consume(tok);
            Expression rhs = unaryExpression();
            for (BinaryExpression.Operator op2; (op2 = binaryOp(token())) != null;) {
                int pred2 = op2.getPrecedence();
                if (pred2 < pred || pred2 == pred && !op2.isRightAssociative()) {
                    break;
                }
                rhs = binaryExpression(allowIn, rhs, pred2);
            }
            lhs = new BinaryExpression(op, lhs, rhs);
        }
        return lhs;
    }

    private static BinaryExpression.Operator binaryOp(Token token) {
        switch (token) {
        case OR:
            return BinaryExpression.Operator.OR;
        case AND:
            return BinaryExpression.Operator.AND;
        case BITOR:
            return BinaryExpression.Operator.BITOR;
        case BITXOR:
            return BinaryExpression.Operator.BITXOR;
        case BITAND:
            return BinaryExpression.Operator.BITAND;
        case EQ:
            return BinaryExpression.Operator.EQ;
        case NE:
            return BinaryExpression.Operator.NE;
        case SHEQ:
            return BinaryExpression.Operator.SHEQ;
        case SHNE:
            return BinaryExpression.Operator.SHNE;
        case LT:
            return BinaryExpression.Operator.LT;
        case LE:
            return BinaryExpression.Operator.LE;
        case GT:
            return BinaryExpression.Operator.GT;
        case GE:
            return BinaryExpression.Operator.GE;
        case IN:
            return BinaryExpression.Operator.IN;
        case INSTANCEOF:
            return BinaryExpression.Operator.INSTANCEOF;
        case SHL:
            return BinaryExpression.Operator.SHL;
        case SHR:
            return BinaryExpression.Operator.SHR;
        case USHR:
            return BinaryExpression.Operator.USHR;
        case ADD:
            return BinaryExpression.Operator.ADD;
        case SUB:
            return BinaryExpression.Operator.SUB;
        case MUL:
            return BinaryExpression.Operator.MUL;
        case DIV:
            return BinaryExpression.Operator.DIV;
        case MOD:
            return BinaryExpression.Operator.MOD;
        case EXP:
            return BinaryExpression.Operator.EXP;
        default:
            return null;
        }
    }

    /**
     * <strong>[12.13] Conditional Operator</strong><br>
     * <strong>[12.14] Assignment Operators</strong>
     * 
     * <pre>
     * ConditionalExpression<span><sub>[In, Yield]</sub></span> :
     *     LogicalORExpression<span><sub>[?In, ?Yield]</sub></span>
     *     LogicalORExpression<span><sub>[?In, ?Yield]</sub></span> ? AssignmentExpression<span><sub>[In, ?Yield]</sub></span> : AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     * AssignmentExpression<span><sub>[In, Yield]</sub></span> :
     *     ConditionalExpression<span><sub>[?In, ?Yield]</sub></span>
     *     <span><sub>[+Yield]</sub></span> YieldExpression<span><sub>[?In]</sub></span>
     *     ArrowFunction<span><sub>[?In]</sub></span>
     *     LeftHandSideExpression<span><sub>[?Yield]</sub></span> = AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     *     LeftHandSideExpression<span><sub>[?Yield]</sub></span> AssignmentOperator AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed assignment expression
     */
    private Expression assignmentExpression(boolean allowIn) {
        int count = context.countLiterals();
        Expression expr = assignmentExpression(allowIn, count);
        objectLiteral_EarlyErrors(count);
        return expr;
    }

    /**
     * Same as {@link #assignmentExpression(boolean)} except object literal early errors are not
     * checked. This method needs to be used if the AssignmentExpression is in a possible
     * destructuring assignment position.
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed assignment expression
     */
    private Expression assignmentExpressionNoValidation(boolean allowIn) {
        return assignmentExpression(allowIn, context.countLiterals());
    }

    private Expression assignmentExpression(boolean allowIn, int oldCount) {
        if (token() == Token.YIELD) {
            switch (context.kind) {
            case Generator:
            case GeneratorMethod:
                if (!context.yieldAllowed) {
                    // Static Semantics: Early Errors for GeneratorDeclaration/GeneratorExpression
                    // - It is a Syntax Error if FormalParameters Contains YieldExpression.
                    // Static Semantics: Early Errors for GeneratorMethod
                    // - It is a Syntax Error if StrictFormalParameters Contains YieldExpression.
                    reportSyntaxError(Messages.Key.InvalidYieldExpression);
                }
                return yieldExpression(allowIn);
            case ArrowFunction:
            case AsyncArrowFunction:
            case GeneratorComprehension:
                if (context.yieldAllowed) {
                    // Static Semantics: Early Errors for ArrowFunction
                    // - It is a Syntax Error if ArrowParameters Contains YieldExpression.
                    // Static Semantics: Early Errors for GeneratorComprehension
                    // - `yield` nested in generator comprehension, nested in generator.
                    reportSyntaxError(Messages.Key.InvalidYieldExpression);
                }
                break;
            case Function:
                if (isEnabled(CompatibilityOption.LegacyGenerator)) {
                    throw new RetryGenerator();
                }
                // fall-through
            default:
                assert !context.yieldAllowed;
            }
        }
        long position = ts.position(), lineinfo = ts.lineinfo();
        boolean asyncArrow = false;
        if (token() == Token.ASYNC && isEnabled(CompatibilityOption.AsyncFunction)) {
            Token next = peek();
            if (noNextLineTerminator()) {
                if (isBindingIdentifier(next)) {
                    // Production: `async AsyncArrowBindingIdentifier => AsyncConciseBody`
                    consume(Token.ASYNC);
                    // Parse in this context, but ignore result (escaped yield)
                    bindingIdentifier();
                    assert context.countLiterals() == oldCount;
                    Token tok = token();
                    // Reset token stream and parse again
                    ts.reset(position, lineinfo);
                    if (tok == Token.ARROW) {
                        return asyncArrowFunction(allowIn);
                    }
                    // Invalid async arrow function, fall through
                } else if (next == Token.LP) {
                    // Production: `CoverCallExpressionAndAsyncArrowHead => AsyncConciseBody`
                    asyncArrow = true;
                }
            }
        }
        Expression left = binaryExpression(allowIn);
        Token tok = token();
        if (tok == Token.HOOK) {
            consume(Token.HOOK);
            Expression then = assignmentExpression(true);
            consume(Token.COLON);
            Expression otherwise = assignmentExpression(allowIn);
            return new ConditionalExpression(left, then, otherwise);
        } else if (tok == Token.ARROW && isCoveredArrowParameters(left, asyncArrow)) {
            // Discard parsed object literals.
            discardUncheckedObjectLiterals(oldCount);
            ts.reset(position, lineinfo);
            if (asyncArrow) {
                return asyncArrowFunction(allowIn);
            }
            return arrowFunction(allowIn);
        } else if (tok == Token.ASSIGN) {
            LeftHandSideExpression lhs = validateAssignment(left, ExceptionType.ReferenceError,
                    Messages.Key.InvalidAssignmentTarget);
            consume(Token.ASSIGN);
            Expression right = assignmentExpression(allowIn);
            if (IsIdentifierRef(lhs) && IsAnonymousFunctionDefinition(right)) {
                setFunctionName(right, (IdentifierReference) lhs);
            } else if ((lhs instanceof ElementAccessor || lhs instanceof PropertyAccessor)
                    && IsAnonymousFunctionDefinition(right)) {
                setMethodName(right, lhs);
            }
            return new AssignmentExpression(assignmentOp(tok), lhs, right);
        } else if (isAssignmentOperator(tok)) {
            LeftHandSideExpression lhs = validateSimpleAssignment(left,
                    ExceptionType.ReferenceError, Messages.Key.InvalidAssignmentTarget);
            consume(tok);
            Expression right = assignmentExpression(allowIn);
            return new AssignmentExpression(assignmentOp(tok), lhs, right);
        } else {
            return left;
        }
    }

    private static boolean isCoveredArrowParameters(Expression expr, boolean async) {
        if (async) {
            return expr instanceof CallExpression
                    && ((CallExpression) expr).getBase() instanceof IdentifierReference
                    && !expr.isParenthesized();
        }
        return expr instanceof IdentifierReference || expr.isParenthesized();
    }

    private static AssignmentExpression.Operator assignmentOp(Token token) {
        switch (token) {
        case ASSIGN:
            return AssignmentExpression.Operator.ASSIGN;
        case ASSIGN_ADD:
            return AssignmentExpression.Operator.ASSIGN_ADD;
        case ASSIGN_SUB:
            return AssignmentExpression.Operator.ASSIGN_SUB;
        case ASSIGN_MUL:
            return AssignmentExpression.Operator.ASSIGN_MUL;
        case ASSIGN_DIV:
            return AssignmentExpression.Operator.ASSIGN_DIV;
        case ASSIGN_MOD:
            return AssignmentExpression.Operator.ASSIGN_MOD;
        case ASSIGN_SHL:
            return AssignmentExpression.Operator.ASSIGN_SHL;
        case ASSIGN_SHR:
            return AssignmentExpression.Operator.ASSIGN_SHR;
        case ASSIGN_USHR:
            return AssignmentExpression.Operator.ASSIGN_USHR;
        case ASSIGN_BITAND:
            return AssignmentExpression.Operator.ASSIGN_BITAND;
        case ASSIGN_BITOR:
            return AssignmentExpression.Operator.ASSIGN_BITOR;
        case ASSIGN_BITXOR:
            return AssignmentExpression.Operator.ASSIGN_BITXOR;
        case ASSIGN_EXP:
            return AssignmentExpression.Operator.ASSIGN_EXP;
        default:
            throw new AssertionError();
        }
    }

    /**
     * <strong>[12.14] Assignment Operators</strong>
     * 
     * <pre>
     * AssignmentOperator : <b>one of</b>
     *     {@literal *=  /=  %=  +=  -=  <<=  >>=  >>>=  &=  ^=  |=}
     * </pre>
     * 
     * @param tok
     *            the token to inspect
     * @return {@code true} if the token is a compound assignment token
     */
    private static boolean isAssignmentOperator(Token tok) {
        switch (tok) {
        case ASSIGN_ADD:
        case ASSIGN_BITAND:
        case ASSIGN_BITOR:
        case ASSIGN_BITXOR:
        case ASSIGN_DIV:
        case ASSIGN_MOD:
        case ASSIGN_MUL:
        case ASSIGN_SHL:
        case ASSIGN_SHR:
        case ASSIGN_SUB:
        case ASSIGN_USHR:
        case ASSIGN_EXP:
            return true;
        default:
            return false;
        }
    }

    /**
     * Static Semantics: IsValidSimpleAssignmentTarget
     * <ul>
     * <li>12.1.3 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.2.1.5 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.2.10.3 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.3.1.5 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.4.3 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.5.3 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.6.2 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.7.2 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.8.2 Semantics: IsValidSimpleAssignmentTarget
     * <li>12.9.2 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.10.2 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.11.2 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.12.2 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.13.2 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.14.3 Static Semantics: IsValidSimpleAssignmentTarget
     * <li>12.15.2 Static Semantics: IsValidSimpleAssignmentTarget
     * </ul>
     * 
     * @param lhs
     *            the left-hand side expression to validate
     * @param type
     *            the exception type for errors
     * @param messageKey
     *            the message key for errors
     * @return the {@code lhs} parameter as a left-hand side expression node
     */
    private LeftHandSideExpression validateSimpleAssignment(Expression lhs, ExceptionType type,
            Messages.Key messageKey) {
        if (lhs instanceof IdentifierReference) {
            IdentifierReference ident = (IdentifierReference) lhs;
            if (context.strictMode != StrictMode.NonStrict) {
                String name = ident.getName();
                if ("eval".equals(name) || "arguments".equals(name)) {
                    // FIXME: spec issue - early SyntaxError in ES5, but ReferenceError in ES6.
                    // https://bugs.ecmascript.org/show_bug.cgi?id=4375
                    reportStrictModeSyntaxError(ident,
                            Messages.Key.StrictModeInvalidAssignmentTarget);
                }
            }
            return ident;
        } else if (lhs instanceof ElementAccessor || lhs instanceof PropertyAccessor
                || lhs instanceof SuperElementAccessor || lhs instanceof SuperPropertyAccessor) {
            return (LeftHandSideExpression) lhs;
        }
        // everything else => invalid lhs
        throw reportError(type, lhs.getBeginPosition(), messageKey);
    }

    /**
     * <strong>[12.14.5] Destructuring Assignment</strong>
     * 
     * <ul>
     * <li>12.14.1 Static Semantics: Early Errors
     * <li>12.14.5.1 Static Semantics: Early Errors
     * <li>13.7.5.1 Static Semantics: Early Errors
     * </ul>
     * 
     * @param lhs
     *            the left-hand side expression to validate
     * @param type
     *            the exception type for errors
     * @param messageKey
     *            the message key for errors
     * @return the {@code lhs} parameter as a left-hand side expression node
     */
    private LeftHandSideExpression validateAssignment(Expression lhs, ExceptionType type,
            Messages.Key messageKey) {
        // rewrite object/array literal to destructuring form
        if (lhs instanceof ObjectLiteral) {
            if (!lhs.isParenthesized() || isEnabled(CompatibilityOption.ParenthesizedDestructuring)) {
                return toDestructuring((ObjectLiteral) lhs);
            }
        } else if (lhs instanceof ArrayLiteral) {
            if (!lhs.isParenthesized() || isEnabled(CompatibilityOption.ParenthesizedDestructuring)) {
                return toDestructuring((ArrayLiteral) lhs);
            }
        }
        return validateSimpleAssignment(lhs, type, messageKey);
    }

    /**
     * <strong>[12.14.5] Destructuring Assignment</strong>
     * 
     * <pre>
     * ObjectAssignmentPattern<span><sub>[Yield]</sub></span> :
     *     { }
     *     { AssignmentPropertyList<span><sub>[?Yield]</sub></span> }
     *     { AssignmentPropertyList<span><sub>[?Yield]</sub></span> , }
     * AssignmentPropertyList<span><sub>[Yield]</sub></span> :
     *     AssignmentProperty<span><sub>[?Yield]</sub></span>
     *     AssignmentPropertyList<span><sub>[?Yield]</sub></span> , AssignmentProperty<span><sub>[?Yield]</sub></span>
     * AssignmentProperty<span><sub>[Yield]</sub></span> :
     *     IdentifierReference<span><sub>[?Yield]</sub></span> Initializer<span><sub>[In, ?Yield]opt</sub></span>
     *     PropertyName : AssignmentElement<span><sub>[?Yield]</sub></span>
     * AssignmentElement<span><sub>[Yield]</sub></span> :
     *     DestructuringAssignmentTarget<span><sub>[?Yield]</sub></span> Initializer<span><sub>[In, ?Yield]opt</sub></span>
     * DestructuringAssignmentTarget<span><sub>[Yield]</sub></span> :
     *     LeftHandSideExpression<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @param object
     *            the object literal to convert
     * @return the object assignment pattern for the object literal
     */
    private ObjectAssignmentPattern toDestructuring(ObjectLiteral object) {
        InlineArrayList<AssignmentProperty> list = newList();
        AssignmentRestProperty rest = null;
        for (Iterator<PropertyDefinition> iterator = object.getProperties().iterator(); iterator
                .hasNext();) {
            PropertyDefinition p = iterator.next();
            AssignmentProperty property;
            if (p instanceof PropertyValueDefinition) {
                // AssignmentProperty : PropertyName ':' AssignmentElement
                PropertyValueDefinition def = (PropertyValueDefinition) p;
                PropertyName propertyName = def.getPropertyName();
                Expression propertyValue = def.getPropertyValue();
                LeftHandSideExpression target;
                Expression initializer;
                if (propertyValue instanceof AssignmentExpression) {
                    // AssignmentElement : DestructuringAssignmentTarget Initializer
                    AssignmentExpression assignment = (AssignmentExpression) propertyValue;
                    if (assignment.getOperator() != AssignmentExpression.Operator.ASSIGN
                            || assignment.isParenthesized()) {
                        reportSyntaxError(p, Messages.Key.InvalidDestructuring);
                    }
                    target = destructuringAssignmentTarget_EarlyErrors(assignment.getLeft());
                    initializer = assignment.getRight();
                } else {
                    // AssignmentElement : DestructuringAssignmentTarget
                    target = destructuringAssignmentTarget_EarlyErrors(propertyValue);
                    initializer = null;
                }
                property = new AssignmentProperty(p.getBeginPosition(), p.getEndPosition(),
                        propertyName, target, initializer);
            } else if (p instanceof PropertyNameDefinition) {
                // AssignmentProperty : IdentifierReference
                PropertyNameDefinition def = (PropertyNameDefinition) p;
                assignmentProperty_EarlyErrors(def.getPropertyName());
                property = new AssignmentProperty(p.getBeginPosition(), p.getEndPosition(),
                        def.getPropertyName(), null);
            } else if (p instanceof CoverInitializedName) {
                // AssignmentProperty : IdentifierReference Initializer
                CoverInitializedName def = (CoverInitializedName) p;
                assignmentProperty_EarlyErrors(def.getPropertyName());
                property = new AssignmentProperty(p.getBeginPosition(), p.getEndPosition(),
                        def.getPropertyName(), def.getInitializer());
            } else if (p instanceof SpreadProperty) {
                // ... IdentifierReference
                if (!isEnabled(CompatibilityOption.ObjectRestDestructuring)) {
                    throw reportSyntaxError(p, Messages.Key.InvalidDestructuring);
                }
                SpreadProperty spread = (SpreadProperty) p;
                Expression expression = spread.getExpression();
                if (!(expression instanceof IdentifierReference) || expression.isParenthesized()) {
                    throw reportSyntaxError(p, Messages.Key.InvalidDestructuring);
                }
                // no further elements after AssignmentRestElement allowed
                if (iterator.hasNext()) {
                    throw reportSyntaxError(p, Messages.Key.InvalidDestructuring);
                }
                rest = new AssignmentRestProperty(p.getBeginPosition(), p.getEndPosition(),
                        (IdentifierReference) expression);
                continue;
            } else {
                assert p instanceof MethodDefinition;
                throw reportSyntaxError(p, Messages.Key.InvalidDestructuring);
            }
            list.add(property);
        }
        context.removeLiteral(object);
        ObjectAssignmentPattern pattern = new ObjectAssignmentPattern(object.getBeginPosition(),
                object.getEndPosition(), list, rest);
        if (object.isParenthesized()) {
            pattern.addParentheses();
        }
        return pattern;
    }

    /**
     * <strong>[12.14.5] Destructuring Assignment</strong>
     * 
     * <pre>
     * ArrayAssignmentPattern<span><sub>[Yield]</sub></span> :
     *     [ Elision<span><sub>opt</sub></span> AssignmentRestElement<span><sub>[?Yield]opt</sub></span> ]
     *     [ AssignmentElementList<span><sub>[?Yield]</sub></span>  ]
     *     [ AssignmentElementList<span><sub>[?Yield]</sub></span> , Elision<span><sub>opt</sub></span> AssignmentRestElement<span><sub>[?Yield]opt</sub></span> ]
     * AssignmentElementList<span><sub>[Yield]</sub></span> :
     *     AssignmentElisionElement<span><sub>[?Yield]</sub></span>
     *     AssignmentElementList<span><sub>[?Yield]</sub></span> , AssignmentElisionElement<span><sub>[?Yield]</sub></span>
     * AssignmentElisionElement<span><sub>[Yield]</sub></span> :
     *     Elision<span><sub>opt</sub></span>  AssignmentElement<span><sub>[?Yield]</sub></span>
     * AssignmentElement<span><sub>[Yield]</sub></span> :
     *     DestructuringAssignmentTarget<span><sub>[?Yield]</sub></span> Initializer<span><sub>[In, ?Yield]opt</sub></span>
     * AssignmentRestElement<span><sub>[Yield]</sub></span> : 
     *     ... DestructuringAssignmentTarget<span><sub>[?Yield]</sub></span>
     * DestructuringAssignmentTarget<span><sub>[Yield]</sub></span> :
     *     LeftHandSideExpression<span><sub>[?Yield]</sub></span>
     * </pre>
     * 
     * @param array
     *            the array literal to convert
     * @return the array assignment pattern for the array literal
     */
    private ArrayAssignmentPattern toDestructuring(ArrayLiteral array) {
        InlineArrayList<AssignmentElementItem> list = newList();
        for (Iterator<Expression> iterator = array.getElements().iterator(); iterator.hasNext();) {
            Expression e = iterator.next();
            AssignmentElementItem element;
            if (e instanceof Elision) {
                // Elision
                element = (Elision) e;
            } else if (e instanceof SpreadElement) {
                // AssignmentRestElement : ... DestructuringAssignmentTarget
                Expression expression = ((SpreadElement) e).getExpression();
                LeftHandSideExpression target = destructuringAssignmentTarget_EarlyErrors(expression);
                element = new AssignmentRestElement(e.getBeginPosition(), e.getEndPosition(),
                        target);
                // no further elements after AssignmentRestElement allowed
                if (iterator.hasNext()) {
                    reportSyntaxError(iterator.next(), Messages.Key.InvalidDestructuring);
                }
                // no trailing comma allowed
                if (array.hasTrailingComma()) {
                    reportSyntaxError(expression, Messages.Key.InvalidDestructuring);
                }
            } else {
                LeftHandSideExpression target;
                Expression initializer;
                if (e instanceof AssignmentExpression) {
                    // AssignmentElement : DestructuringAssignmentTarget Initializer
                    AssignmentExpression assignment = (AssignmentExpression) e;
                    if (assignment.getOperator() != AssignmentExpression.Operator.ASSIGN
                            || assignment.isParenthesized()) {
                        reportSyntaxError(e, Messages.Key.InvalidDestructuring);
                    }
                    target = destructuringAssignmentTarget_EarlyErrors(assignment.getLeft());
                    initializer = assignment.getRight();
                } else {
                    // AssignmentElement : DestructuringAssignmentTarget
                    target = destructuringAssignmentTarget_EarlyErrors(e);
                    initializer = null;
                }
                element = new AssignmentElement(e.getBeginPosition(), e.getEndPosition(), target,
                        initializer);
            }
            list.add(element);
        }
        ArrayAssignmentPattern pattern = new ArrayAssignmentPattern(array.getBeginPosition(),
                array.getEndPosition(), list);
        if (array.isParenthesized()) {
            pattern.addParentheses();
        }
        return pattern;
    }

    /**
     * 12.14.5.1 Static Semantics: Early Errors
     * 
     * @param lhs
     *            the left-hand side expression to check
     * @return the {@code lhs} parameter as a left-hand side expression node
     */
    private LeftHandSideExpression destructuringAssignmentTarget_EarlyErrors(Expression lhs) {
        if (lhs instanceof ObjectAssignmentPattern) {
            return (ObjectAssignmentPattern) lhs;
        } else if (lhs instanceof ArrayAssignmentPattern) {
            return (ArrayAssignmentPattern) lhs;
        }
        return validateAssignment(lhs, ExceptionType.SyntaxError, Messages.Key.InvalidDestructuring);
    }

    /**
     * 12.14.5.1 Static Semantics: Early Errors
     * 
     * @param identifier
     *            the identifier to check
     */
    private void assignmentProperty_EarlyErrors(IdentifierReference identifier) {
        validateSimpleAssignment(identifier, ExceptionType.SyntaxError,
                Messages.Key.InvalidDestructuring);
    }

    /**
     * <strong>[12.15] Comma Operator</strong>
     * 
     * <pre>
     * Expression<span><sub>[In, Yield]</sub></span> :
     *     AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     *     Expression<span><sub>[?In, ?Yield]</sub></span> , AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed expression
     */
    private Expression expression(boolean allowIn) {
        Expression expr = assignmentExpression(allowIn);
        if (token() == Token.COMMA) {
            return commaExpression(expr, allowIn);
        }
        return expr;
    }

    /**
     * <strong>[12.15] Comma Operator</strong>
     * 
     * <pre>
     * Expression<span><sub>[In, Yield]</sub></span> :
     *     AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     *     Expression<span><sub>[?In, ?Yield]</sub></span> , AssignmentExpression<span><sub>[?In, ?Yield]</sub></span>
     * </pre>
     * 
     * @param expr
     *            the first expression
     * @param allowIn
     *            the flag to select whether or not the in-operator is allowed
     * @return the parsed comma expression
     */
    private CommaExpression commaExpression(Expression expr, boolean allowIn) {
        InlineArrayList<Expression> list = newList();
        list.add(expr);
        do {
            consume(Token.COMMA);
            expr = assignmentExpression(allowIn);
            list.add(expr);
        } while (token() == Token.COMMA);
        return new CommaExpression(list);
    }

    /* ***************************************************************************************** */

    /**
     * <strong>[11.9] Automatic Semicolon Insertion</strong>
     */
    private void semicolon() {
        switch (token()) {
        case SEMI:
            consume(Token.SEMI);
            // fall-through
        case RC:
        case EOF:
            break;
        default:
            if (noLineTerminator()) {
                reportSyntaxError(Messages.Key.MissingSemicolon);
            }
        }
    }

    /**
     * Returns {@code true} if the last and the current token are not separated from each other by a
     * line-terminator.
     * 
     * @return {@code true} if there is no line separator
     */
    private boolean noLineTerminator() {
        return !ts.hasCurrentLineTerminator();
    }

    /**
     * Returns {@code true} if the current and the next token are not separated from each other by a
     * line-terminator.
     * 
     * @return {@code true} if there is no line separator
     */
    private boolean noNextLineTerminator() {
        return !ts.hasNextLineTerminator();
    }

    /**
     * Returns true if the current token is of type {@link Token#NAME} and its name is {@code name}.
     * 
     * @param name
     *            the name to test
     * @return {@code true} if the current token is the requested name
     */
    private boolean isName(String name) {
        Token tok = token();
        return tok == Token.NAME && name.equals(getName(tok));
    }

    /**
     * Returns true if the next token is of type {@link Token#NAME} and its name is {@code name}.
     * 
     * @param name
     *            the name to test
     * @return {@code true} if the next token is the requested name
     */
    private boolean isNextName(String name) {
        Token tok = peek();
        return tok == Token.NAME && name.equals(getNextName(tok));
    }

    /**
     * Returns the token's name.
     * 
     * @param tok
     *            the token to inspect
     * @return the token name
     */
    private String getName(Token tok) {
        switch (tok) {
        case NAME:
        case ESCAPED_NAME:
        case ESCAPED_RESERVED_WORD:
        case ESCAPED_STRICT_RESERVED_WORD:
        case ESCAPED_YIELD:
        case ESCAPED_ASYNC:
        case ESCAPED_AWAIT:
        case ESCAPED_LET:
            return ts.getString();
        default:
            return tok.getName();
        }
    }

    /**
     * Returns the token's name.
     * 
     * @param tok
     *            the token to inspect
     * @return the token name
     */
    private String getNextName(Token tok) {
        switch (tok) {
        case NAME:
        case ESCAPED_NAME:
        case ESCAPED_RESERVED_WORD:
        case ESCAPED_STRICT_RESERVED_WORD:
        case ESCAPED_YIELD:
        case ESCAPED_ASYNC:
        case ESCAPED_AWAIT:
        case ESCAPED_LET:
            return ts.getNextString();
        default:
            return tok.getName();
        }
    }

    /**
     * <strong>[11.6] Names and Keywords</strong>
     * 
     * @return the parsed identifier name
     */
    private String identifierName() {
        Token tok = token();
        if (!Token.isIdentifierName(tok)) {
            reportTokenNotIdentifierName(tok);
        }
        String name = getName(tok);
        consume(tok);
        return name;
    }

    /**
     * <strong>[11.8.3] Numeric Literals</strong>
     * 
     * @return the parsed number literal
     */
    private double numericLiteral() {
        double number = ts.getNumber();
        consume(Token.NUMBER);
        return number;
    }

    /**
     * <strong>[11.8.4] String Literals</strong>
     * 
     * @return the parsed string literal
     */
    private String stringLiteral() {
        String string = ts.getString();
        consume(Token.STRING);
        return string;
    }
}
