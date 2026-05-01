package cod.interpreter;

import cod.ast.ASTVisitor;
import cod.ast.node.*;
import cod.debug.DebugSystem;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.math.AutoStackingNumber;
import cod.range.*;
import cod.interpreter.registry.*;
import cod.interpreter.context.*;
import cod.interpreter.exception.*;
import cod.interpreter.handler.*;
import java.util.*;
import static cod.lexer.TokenType.Keyword.*;
import cod.semantic.ConstructorResolver;
import cod.semantic.NamingValidator;

public class InterpreterVisitor extends ASTVisitor<Object> implements Evaluator {

    private static final String SELF_CALL_LAMBDA_OWNER = "self-call lambda";
    private static final double SELF_CALL_LEVEL_FLOAT_EPSILON = 1e-12d;

    // Tail-call trampolining intentionally uses this internal signal to unwind Java frames
    // without allocating wrapper result objects through every visitor return path.
    // This favors lower allocation overhead over exception cost in non-tail paths.

    enum PatternType {
        CONDITIONAL,
        SEQUENCE,
        LINEAR_RECURRENCE
    }

    class PatternResult {
        public final PatternType type;
        public final Object pattern;
        public final Expr targetArray;
        
        public PatternResult(PatternType type, Object pattern, Expr targetArray) {
            if (type == null) {
                throw new InternalError("PatternResult constructed with null type");
            }
            this.type = type;
            this.pattern = pattern;
            this.targetArray = targetArray;
        }
    }

    private final Interpreter interpreter;
    public final TypeHandler typeSystem;
    private final Stack<ExecutionContext> contextStack = new Stack<ExecutionContext>();
    private final ExpressionHandler expressionHandler;
    private final AssignmentHandler assignmentHandler;
    private final LiteralRegistry literalRegistry;
    private final ContextHandler contextHandler;
    private final LambdaHandler lambdaHandler;
    private final ArrayHandler arrHandler;
    private final PatternHandler patternHandler;
    private final LoopHandler loopHandler;
    
    // Inline Caches for Call Sites (Identity-based for O(1) AST node lookup)
    private final IdentityHashMap<MethodCall, Method> inlineMethodCache = new IdentityHashMap<MethodCall, Method>();

    public InterpreterVisitor(Interpreter interpreter, TypeHandler typeSystem, 
                              LiteralRegistry literalRegistry) {
        if (interpreter == null) {
            throw new InternalError("InterpreterVisitor constructed with null interpreter");
        }
        if (typeSystem == null) {
            throw new InternalError("InterpreterVisitor constructed with null typeSystem");
        }
        if (literalRegistry == null) {
            throw new InternalError("InterpreterVisitor constructed with null literalRegistry");
        }
        
        this.interpreter = interpreter;
        this.typeSystem = typeSystem;
        this.literalRegistry = literalRegistry;
        this.contextHandler = new ContextHandler(interpreter);
        this.expressionHandler = new ExpressionHandler(typeSystem, this);
        this.assignmentHandler = new AssignmentHandler(typeSystem, interpreter, expressionHandler, this);
        this.arrHandler =
            new ArrayHandler(this, typeSystem, expressionHandler, contextHandler);
        this.patternHandler =
            new PatternHandler(this, typeSystem, expressionHandler, arrHandler);
        this.loopHandler =
            new LoopHandler(this, typeSystem, expressionHandler, arrHandler, patternHandler);
        this.lambdaHandler = new LambdaHandler(typeSystem, this);
    }
    
    // Implement Evaluator interface
    @Override
    public Object evaluate(Expr node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("evaluate called with null node");
        }
        if (ctx == null) {
            throw new InternalError("evaluate called with null context");
        }
        
        pushContext(ctx);
        try {
            return dispatch(node);
        } finally {
            popContext();
        }
    }

    @Override
    public Object evaluate(Stmt node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("evaluate called with null node");
        }
        if (ctx == null) {
            throw new InternalError("evaluate called with null context");
        }
        
        pushContext(ctx);
        try {
            return dispatch(node);
        } finally {
            popContext();
        }
    }
    
    @Override
    public Object invokeLambda(Object callback, List<Object> arguments, ExecutionContext ctx, String ownerMethod) {
        if (ctx == null) {
            throw new InternalError("invokeLambda called with null context");
        }
        return lambdaHandler.invokeLambdaCallback(callback, arguments, ctx, ownerMethod);
    }

    public void pushContext(ExecutionContext context) {
        if (context == null) {
            throw new InternalError("pushContext called with null context");
        }
        contextStack.push(context);
        ExecutionContext.setCurrentContext(context);
    }

    public void popContext() {
        if (contextStack.isEmpty()) {
            throw new InternalError("popContext called on empty stack");
        }
        contextStack.pop();
        if (!contextStack.isEmpty()) {
            ExecutionContext.setCurrentContext(contextStack.peek());
        } else {
            ExecutionContext.clearCurrentContext();
        }
    }

    public ExecutionContext getCurrentContext() {
        if (contextStack.isEmpty()) {
            throw new InternalError("getCurrentContext called on empty stack");
        }
        return contextStack.peek();
    }
    
    public boolean isContextStackEmpty() {
        return contextStack.isEmpty();
    }

    public boolean shouldReturnEarly(Map<String, Object> slotValues, Set<String> slotsInCurrentPath) {
        return interpreter.shouldReturnEarly(slotValues, slotsInCurrentPath);
    }

    private Object createNoneValue() {
        return contextHandler.createNoneValue();
    }

    @Override
    public Object visit(Program n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Unit n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Use n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Type n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Field n) {
        if (n == null) {
            throw new InternalError("visit(Field) called with null node");
        }
        
        ExecutionContext ctx = getCurrentContext();
        Object val = n.value != null ? dispatch(n.value) : null;
        
        if (ctx.objectInstance.type != null) {
            Object existingField =
                interpreter
                    .getConstructorResolver()
                    .getFieldFromHierarchy(ctx.objectInstance.type, n.name, ctx);
            if (existingField != null) {
                throw new ProgramError("Cannot redeclare field: " + n.name);
            }
        }
        ctx.setObjectField(n.name, val);
        return val;
    }

    @Override
    public Object visit(Method n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Param n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Constructor n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(ConstructorCall node) {
        if (node == null) {
            throw new InternalError("visit(ConstructorCall) called with null node");
        }
        
        try {
            ExecutionContext ctx = getCurrentContext();
            Type targetType = interpreter.getImportResolver().findType(node.className);
            if (targetType != null
                && targetType.isUnsafe
                && !isUnsafeExecutionContext(ctx)
                && !ExecutionContext.isUnsafeCommitAllowed()) {
                throw new ProgramError(
                    "Unsafe class '" + targetType.name + "' cannot be constructed in a safe context. Use safe("
                        + targetType.name
                        + "(...)).");
            }
            return interpreter.getConstructorResolver().resolveAndCreate(node, getCurrentContext());
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Constructor resolution failed", e);
        }
    }

    @Override
    public Object visit(Policy n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(PolicyMethod n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Block node) {
        if (node == null) {
            throw new InternalError("visit(Block) called with null node");
        }
        
        ExecutionContext ctx = getCurrentContext();
        ctx.pushScope();
        
        try {
            for (Stmt stmt : node.statements) {
                dispatch(stmt);
                // Early return check: stop executing nested block statements once required
                // return slots for the current path are assigned.
                if (!ctx.slotsInCurrentPath.isEmpty()
                    && interpreter.shouldReturnEarly(ctx.getSlotValues(), ctx.slotsInCurrentPath)) {
                    break;
                }
            }
        } catch (TailCallSignal e) {
            throw e;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Block execution failed", e);
        } finally {
            ctx.popScope();
        }
        
        return null;
    }

    @Override
    public Object visit(Assignment node) {
        if (node == null) {
            throw new InternalError("visit(Assignment) called with null node");
        }
        
        try {
            return assignmentHandler.handleAssignment(node, getCurrentContext());
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Assignment failed", e);
        }
    }

@Override
public Object visit(Var node) {
    if (node == null) {
        throw new InternalError("visit(Var) called with null node");
    }
    
    try {
        ExecutionContext ctx = getCurrentContext();
        if (NamingValidator.isAllCaps(node.name)) {
            if (node.value == null) {
                throw new ProgramError("Constant '" + node.name + "' must have an initial value");
            }
            if (contextHandler.isVariableDeclaredInAnyScope(ctx, node.name)) {
                throw new ProgramError("Cannot reassign constant '" + node.name + "'");
            }
        }

        Object val = node.value != null ? dispatch(node.value) : null;
        
        // Handle array type conversion for [text] = [int range]
        if (node.explicitType != null && node.explicitType.startsWith("[") && 
            node.explicitType.endsWith("]") && val instanceof NaturalArray) {
            
            NaturalArray arr = (NaturalArray) val;
            String expectedElementType = node.explicitType.substring(1, node.explicitType.length() - 1);
            String actualElementType = arr.getElementType();
            
            // If expected is [text] but actual is not text, create a converting wrapper
            if (expectedElementType.equals("text") && !actualElementType.equals("text")) {
                // Create a new NaturalArray with conversion enabled
                Range range = contextHandler.getRangeFromArray(arr);
                if (range != null) {
                    val = new NaturalArray(range, this, ctx, node.explicitType);
                }
            }
        }
        
        ctx.setVariable(node.name, val);
        
        if (node.explicitType != null) {
            String declaredType = node.explicitType;
            String resolvedDeclaredType = resolveVariableTypeAliasIfAny(declaredType, ctx);
            int resolvedMask = TypeHandler.parseTypeMask(resolvedDeclaredType);
            ctx.setVariableType(node.name, resolvedDeclaredType);
            
            // Handle type literal assignment (e.g., x: type = int)
            if (TYPE.toString().equals(declaredType)) {
                if (val instanceof String) {
                    String typeStr = (String) val;
                    if (typeSystem.isTypeLiteral(typeStr)) {
                        int typeMask = TypeHandler.parseTypeMask(typeStr);
                        val = TypeHandler.Value.createTypeValue(typeMask);
                        ctx.setVariable(node.name, val);
                    }
                } else if (val instanceof TextLiteral) {
                    String typeStr = ((TextLiteral) val).value;
                    if (typeSystem.isTypeLiteral(typeStr)) {
                        int typeMask = TypeHandler.parseTypeMask(typeStr);
                        val = TypeHandler.Value.createTypeValue(typeMask);
                        ctx.setVariable(node.name, val);
                    }
                }
            }
            
            // Handle null with nullable type
            if (val == null && (resolvedMask & TypeHandler.MASK_NONE) != 0) {
                val = createNoneValue();
                ctx.setVariable(node.name, val);
            }
            
            // Validate type
            if (!typeSystem.validateType(resolvedMask, val)) {
                if (typeSystem.isNoneValue(val) && (resolvedMask & TypeHandler.MASK_NONE) != 0) {
                    val = createNoneValue();
                    ctx.setVariable(node.name, val);
                } else {
                    throw new ProgramError("Type mismatch for " + node.name + ". Expected " + resolvedDeclaredType);
                }
            }
            
            // Wrap union types
            if (resolvedDeclaredType != null && resolvedDeclaredType.indexOf('|') >= 0) {
                int activeMask = typeSystem.getConcreteMask(typeSystem.unwrap(val));
                val = new TypeHandler.Value(val, activeMask, resolvedMask);
                ctx.setVariable(node.name, val);
            }
        }
        
        return val;
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Variable declaration failed: " + node.name, e);
    }
}

    private String resolveVariableTypeAliasIfAny(String declaredType, ExecutionContext ctx) {
        if (declaredType == null || typeSystem.isTypeLiteral(declaredType)) {
            return declaredType;
        }
        if (ctx == null) {
            return declaredType;
        }

        AliasLookupResult alias = findTypeAliasValue(declaredType, ctx);
        if (alias == null || !alias.found) {
            return declaredType;
        }

        if (!NamingValidator.isAllCaps(declaredType)) {
            throw new ProgramError(
                "Type alias '" + declaredType + "' must be declared as a constant name (ALL_CAPS). " +
                "Declare it like: BYTE: type = u8");
        }

        Object rawAliasValue = alias.value;
        if (rawAliasValue instanceof TypeHandler.Value && ((TypeHandler.Value) rawAliasValue).isTypeValue()) {
            Object typeSig = ((TypeHandler.Value) rawAliasValue).value;
            if (typeSig instanceof String && typeSystem.isTypeLiteral((String) typeSig)) {
                return (String) typeSig;
            }
        }
        Object aliasValue = typeSystem.unwrap(rawAliasValue);
        if (aliasValue instanceof String && typeSystem.isTypeLiteral((String) aliasValue)) {
            return (String) aliasValue;
        }
        if (aliasValue instanceof TextLiteral) {
            String literal = ((TextLiteral) aliasValue).value;
            if (typeSystem.isTypeLiteral(literal)) {
                return literal;
            }
        }

        throw new ProgramError(
            "Type alias constant '" + declaredType + "' must hold a type value. " +
            "Declare it like: BYTE: type = u8");
    }

    private AliasLookupResult findTypeAliasValue(String aliasName, ExecutionContext ctx) {
        for (int i = ctx.getScopeDepth() - 1; i >= 0; i--) {
            Map<String, Object> scope = ctx.getLocalsStack().get(i);
            if (scope.containsKey(aliasName)) {
                return new AliasLookupResult(true, scope.get(aliasName));
            }
        }
        Map<String, Object> slots = ctx.getSlotValues();
        if (slots != null && slots.containsKey(aliasName)) {
            return new AliasLookupResult(true, slots.get(aliasName));
        }
        return new AliasLookupResult(false, null);
    }

    private static final class AliasLookupResult {
        private final boolean found;
        private final Object value;

        private AliasLookupResult(boolean found, Object value) {
            this.found = found;
            this.value = value;
        }
    }

    @Override
    public Object visit(StmtIf node) {
        if (node == null) {
            throw new InternalError("visit(StmtIf) called with null node");
        }
        
        try {
            Object testObj = dispatch(node.condition);
            boolean test = typeSystem.isTruthy(typeSystem.unwrap(testObj));
            
            ExecutionContext ctx = getCurrentContext();
            
            int originalDepth = ctx.getScopeDepth();
            
            try {
                ctx.pushScope();
                List<Stmt> statements = test ? node.thenBlock.statements : node.elseBlock.statements;
                for (Stmt s : statements) {
                    dispatch(s);
                    if (!ctx.slotsInCurrentPath.isEmpty()
                        && interpreter.shouldReturnEarly(ctx.getSlotValues(), ctx.slotsInCurrentPath)) break;
                }
            } finally {
                while (ctx.getScopeDepth() > originalDepth) {
                    ctx.popScope();
                }
            }
            
            return null;
        } catch (SkipIterationException e) {
            throw e;
        } catch (BreakLoopException e) {
            throw e;
        } catch (TailCallSignal e) {
            throw e;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("If statement execution failed", e);
        }
    }
    
    @Override
    public Object visit(ExprIf node) {
        if (node == null) {
            throw new InternalError("visit(ExprIf) called with null node");
        }
        
        try {
            Object condValue = dispatch(node.condition);
            if (typeSystem.isTruthy(typeSystem.unwrap(condValue))) {
                return dispatch(node.thenExpr);
            }
            return dispatch(node.elseExpr);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("If expression execution failed", e);
        }
    }

    // ========== UPDATED FOR NODE WITH SIMPLE LOOP DECISION ==========
    @Override
    public Object visit(For node) {
        return loopHandler.executeForLoop(node);
    }

    // ========== SIMPLE LOOP DECISION METHODS ==========

    @Override
    public Object visit(Skip node) {
        throw new SkipIterationException();
    }

    @Override
    public Object visit(Break node) {
        throw new BreakLoopException();
    }

    @Override
    public Object visit(Range n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Exit node) {
        throw new EarlyExitException();
    }

    @Override
    public Object visit(Tuple node) {
        if (node == null) {
            throw new InternalError("visit(Tuple) called with null node");
        }
        
        try {
            List<Object> tuple = new ArrayList<Object>();
            for (Expr elem : node.elements) {
                tuple.add(dispatch(elem));
            }
            return Collections.unmodifiableList(tuple);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Tuple creation failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object visit(ReturnSlotAssignment node) {
        if (node == null) {
            throw new InternalError("visit(ReturnSlotAssignment) called with null node");
        }
        
        ExecutionContext ctx = getCurrentContext();
        
        Map<String, Object> allLocals = new HashMap<String, Object>();
        for (int i = 0; i < ctx.getScopeDepth(); i++) {
            Map<String, Object> scope = ctx.getScope(i);
            if (scope != null) {
                allLocals.putAll(scope);
            }
        }
        
        try {
            if (node.lambda != null) {
                return evaluateLambdaAssignment(node, ctx, allLocals);
            }
            
            Object res = interpreter.evalMethodCall(node.methodCall, ctx.objectInstance, allLocals, null);

            if (res instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) res;
                Method method = null;
                if (ctx.objectInstance != null && ctx.objectInstance.type != null) {
                    method =
                        interpreter
                            .getConstructorResolver()
                            .findMethodInHierarchy(ctx.objectInstance.type, node.methodCall.name, ctx);
                }

                for (int i = 0; i < node.variableNames.size(); i++) {
                    String slot = node.methodCall.slotNames.get(i);
                    String requestedSlot = slot;
                    
                    if (!map.containsKey(requestedSlot) && method != null && method.returnSlots != null) {
                        try {
                            int index = Integer.parseInt(requestedSlot);
                            if (index >= 0 && index < method.returnSlots.size()) {
                                requestedSlot = method.returnSlots.get(index).name;
                            }
                        } catch (NumberFormatException e) {
                            // Not an index
                        }
                    }

                    if (map.containsKey(requestedSlot)) {
                        Object value = map.get(requestedSlot);
                        
                        if (value instanceof NaturalArray) {
                            NaturalArray arr = (NaturalArray) value;
                            if (arr.hasPendingUpdates()) {
                                arr.commitUpdates();
                            }
                        }
                        
                        ctx.setVariable(node.variableNames.get(i), value);
                    } else {
                        throw new ProgramError("Missing slot: " + slot + " (tried as: " + requestedSlot + ")");
                    }
                }
            } else {
                // Handle case where method returns a single value directly
                if (node.variableNames.size() == 1) {
                    ctx.setVariable(node.variableNames.get(0), res);
                } else {
                    throw new ProgramError("Method did not return slot values");
                }
            }
            return res;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Return slot assignment failed", e);
        }
    }

    private Object evaluateLambdaAssignment(
        ReturnSlotAssignment node,
        ExecutionContext parentCtx,
        Map<String, Object> allLocals) {
        
        Lambda lambda = node.lambda;
        if (lambda == null) {
            throw new ProgramError("Lambda assignment missing lambda expression");
        }
        
        List<Param> params = lambda.parameters != null ? lambda.parameters : new ArrayList<Param>();
        if (lambda.inferParameters && params.isEmpty()) {
            params = inferLambdaParamsFromPlaceholders(lambda);
            if (params.isEmpty()) {
                throw new ProgramError(
                    "Inferred lambda parameters require named placeholders like $item or $left in the body");
            }
        }
        
        List<Slot> lambdaSlots =
            lambda.returnSlots != null ? lambda.returnSlots : new ArrayList<Slot>();
        if (lambdaSlots.isEmpty()) {
            throw new ProgramError(
                "Lambda assignment requires a return contract (::) to map values to variables");
        }
        
        if (lambdaSlots.size() != node.variableNames.size()) {
            throw new ProgramError(
                "Number of assigned variables (" + node.variableNames.size()
                    + ") does not match lambda return slots (" + lambdaSlots.size() + ")");
        }

        Map<String, Object> initialLambdaLocals = new HashMap<String, Object>(allLocals);
        List<Object> activeParamValues = new ArrayList<Object>();
        for (Param param : params) {
            if (param == null || param.name == null) continue;
            
            Object boundValue = null;
            boolean found = false;
            
            if (initialLambdaLocals.containsKey(param.name)) {
                boundValue = initialLambdaLocals.get(param.name);
                found = true;
            } else if (param.hasDefaultValue && param.defaultValue != null) {
                ExecutionContext defaultCtx = new ExecutionContext(
                    parentCtx.objectInstance,
                    initialLambdaLocals,
                    null,
                    null,
                    typeSystem
                );
                pushContext(defaultCtx);
                try {
                    boundValue = visit((Base) param.defaultValue);
                    found = true;
                } finally {
                    popContext();
                }
            }
            
            if (!found) {
                throw new ProgramError(
                    "Missing value for lambda parameter '" + param.name + "'. "
                        + "Declare a local variable with that name or provide a default value.");
            }
            
            if (param.type != null && !typeSystem.validateType(param.type, boundValue)) {
                throw new ProgramError(
                    "Lambda parameter type mismatch for '" + param.name + "'. Expected "
                        + param.type + ", got: " + typeSystem.getConcreteType(boundValue));
            }
            
            initialLambdaLocals.put(param.name, boundValue);
            activeParamValues.add(boundValue);
        }

        while (true) {
            Map<String, Object> slotValues = new LinkedHashMap<String, Object>();
            Map<String, String> slotTypes = new LinkedHashMap<String, String>();
            for (Slot slot : lambdaSlots) {
                slotValues.put(slot.name, null);
                slotTypes.put(slot.name, slot.type);
            }

            Map<String, Object> lambdaLocals = new HashMap<String, Object>(allLocals);
            for (int i = 0; i < params.size(); i++) {
                Param param = params.get(i);
                if (param == null || param.name == null) continue;
                Object paramValue = i < activeParamValues.size() ? activeParamValues.get(i) : null;
                lambdaLocals.put(param.name, paramValue);
            }

            LambdaClosure lambdaClosure =
                new LambdaClosure(
                    lambda,
                    lambdaLocals,
                    parentCtx.objectInstance,
                    parentCtx.currentClass,
                    parentCtx.currentLambdaClosure,
                    Collections.<Object>emptyList());

            ExecutionContext lambdaCtx =
                new ExecutionContext(parentCtx.objectInstance, lambdaLocals, slotValues, slotTypes, typeSystem);
            lambdaCtx.currentClass = parentCtx.currentClass;
            lambdaCtx.currentMethodName = parentCtx.currentMethodName;
            lambdaCtx.currentLambdaClosure = lambdaClosure;

            List<Object> nextTailArgs = null;

            pushContext(lambdaCtx);
            try {
                if (lambda.body != null) {
                    visit((Base) lambda.body);
                }
            } catch (TailCallSignal tailCallSignal) {
                if (tailCallSignal.lambdaClosure != null && tailCallSignal.lambdaClosure == lambdaClosure) {
                    nextTailArgs = tailCallSignal.arguments;
                } else {
                    throw tailCallSignal;
                }
            } catch (EarlyExitException e) {
                // normal lambda early exit
            } finally {
                popContext();
            }

            if (nextTailArgs != null) {
                activeParamValues = new ArrayList<Object>(nextTailArgs);
                continue;
            }

            Object result = slotValues;
            for (int i = 0; i < node.variableNames.size(); i++) {
                String varName = node.variableNames.get(i);
                if ("_".equals(varName)) continue;

                String slotName = lambdaSlots.get(i).name;
                if (!slotValues.containsKey(slotName)) {
                    throw new ProgramError("Missing slot: " + slotName);
                }
                parentCtx.setVariable(varName, slotValues.get(slotName));
            }

            return result;
        }
    }

    @Override
    public Object visit(SlotAssignment node) {
        if (node == null) {
            throw new InternalError("visit(SlotAssignment) called with null node");
        }
        
        ExecutionContext ctx = getCurrentContext();
        TailCallSignal tailCallSignal = buildTailCallSignalForSlotAssignment(node, ctx);
        if (tailCallSignal != null) {
            throw tailCallSignal;
        }

        try {
            return assignmentHandler.handleSlotAssignment(node, ctx);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Slot assignment failed", e);
        }
    }

    @Override
    public Object visit(SlotDeclaration n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(MultipleSlotAssignment node) {
        if (node == null) {
            throw new InternalError("visit(MultipleSlotAssignment) called with null node");
        }
        
        try {
            return assignmentHandler.handleMultipleSlotAssignment(node, getCurrentContext());
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Multiple slot assignment failed", e);
        }
    }

    private TailCallSignal buildTailCallSignalForSlotAssignment(SlotAssignment node, ExecutionContext ctx) {
        if (node == null || ctx == null || !(node.value instanceof MethodCall)) return null;
        MethodCall methodCall = (MethodCall) node.value;
        if (!methodCall.isSelfCall) return null;

        List<Object> evaluatedArgs = evaluateMethodCallArguments(methodCall);
        Integer resolvedLevel = resolveSelfCallLevelValue(methodCall, ctx);

        if (ctx.currentLambdaClosure != null) {
            if (resolvedLevel != null && resolvedLevel.intValue() != 0) {
                // Tail-call trampoline only applies to same-closure self calls.
                // Parent/grandparent calls switch closure targets, so they are not TCO-safe here.
                return null;
            }
            return TailCallSignal.forLambda(ctx.currentLambdaClosure, evaluatedArgs);
        }

        if (resolvedLevel != null) {
            // <~N(...) levels are lambda-only; method contexts are validated in method-call resolution.
            return null;
        }
        if (ctx.currentMethodName == null || ctx.currentMethodName.isEmpty()) {
            return null;
        }
        return TailCallSignal.forMethod(ctx.currentMethodName, evaluatedArgs);
    }

    private List<Object> evaluateMethodCallArguments(MethodCall methodCall) {
        List<Object> evaluatedArgs = new ArrayList<Object>();
        if (methodCall == null || methodCall.arguments == null) {
            return evaluatedArgs;
        }
        for (Expr arg : methodCall.arguments) {
            Object argValue = dispatch(arg);
            evaluatedArgs.add(typeSystem.unwrap(argValue));
        }
        return evaluatedArgs;
    }

    private boolean isUnsafeExecutionContext(ExecutionContext ctx) {
        if (ctx == null) return false;
        if (ctx.currentClass != null && ctx.currentClass.isUnsafe) {
            return true;
        }
        Method currentMethod = resolveCurrentContextMethod(ctx);
        return currentMethod != null && currentMethod.isUnsafe;
    }

    private Method resolveCurrentContextMethod(ExecutionContext ctx) {
        if (ctx == null || ctx.currentMethodName == null || ctx.currentMethodName.isEmpty()) {
            return null;
        }
        Type searchType = ctx.currentClass;
        if (searchType == null && ctx.objectInstance != null) {
            searchType = ctx.objectInstance.type;
        }
        if (searchType == null) {
            return null;
        }
        return interpreter.getConstructorResolver().findMethodInHierarchy(searchType, ctx.currentMethodName, ctx);
    }

    private Method resolveMethodForCall(MethodCall node, ExecutionContext ctx) {
        // Fast Path: Check Inline Cache using node identity
        Method cachedMethod = inlineMethodCache.get(node);
        if (cachedMethod != null) {
            return cachedMethod;
        }

        Method method = null;
        String callName = node.name;
        String callQualifiedName = node.qualifiedName;

        if (ctx.currentClass != null) {
            method = interpreter.getConstructorResolver().findMethodInHierarchy(ctx.currentClass, callName, ctx);
        }

        if (method == null && ctx.objectInstance != null && ctx.objectInstance.type != null) {
            method = interpreter.getConstructorResolver().findMethodInHierarchy(ctx.objectInstance.type, callName, ctx);
        }

        if (method == null) {
            String qName = callQualifiedName;
            if (qName != null && qName.contains(".")) {
                String[] parts = qName.split("\\.");
                if (parts.length == 2) {
                    String receiver = parts[0];
                    String methodName = parts[1];
                    if (ctx.locals().containsKey(receiver)) {
                        Object receiverObj = ctx.locals().get(receiver);
                        if (receiverObj instanceof ObjectInstance) {
                            ObjectInstance objInst = (ObjectInstance) receiverObj;
                            if (objInst.type != null) {
                                qName = objInst.type.name + "." + methodName;
                            }
                        }
                    }
                }
            }
            if (qName == null) qName = callName;
            method = interpreter.getImportResolver().findMethod(qName);
        }

        // Cache the result before returning to ensure the next call to this 
        // specific AST node is O(1)
        if (method != null) {
            inlineMethodCache.put(node, method);
        }

        return method;
    }

    private Object executeSafeCommit(MethodCall node, ExecutionContext ctx) {
        if (isUnsafeExecutionContext(ctx)) {
            throw new ProgramError(
                "safe() is not allowed inside unsafe classes or methods; these contexts already have permission to execute unsafe code");
        }
        if (node.arguments == null || node.arguments.size() != 1) {
            throw new ProgramError("safe() expects exactly one argument");
        }

        Expr argument = node.arguments.get(0);
        boolean unsafeTarget = false;

        if (argument instanceof MethodCall) {
            Method targetMethod = resolveMethodForCall((MethodCall) argument, ctx);
            unsafeTarget = targetMethod != null && targetMethod.isUnsafe;
        } else if (argument instanceof ConstructorCall) {
            Type targetType = interpreter.getImportResolver().findType(((ConstructorCall) argument).className);
            unsafeTarget = targetType != null && targetType.isUnsafe;
        }

        if (!unsafeTarget) {
            throw new ProgramError(
                "safe() requires an unsafe method call or unsafe class constructor as its argument, but the provided expression is not marked unsafe");
        }

        ExecutionContext.enterUnsafeCommitAllowance();
        try {
            return dispatch(argument);
        } finally {
            ExecutionContext.exitUnsafeCommitAllowance();
        }
    }

    @Override
    public Object visit(Identifier node) {
        if (node == null) {
            throw new InternalError("visit(Identifier) called with null node");
        }
        
        ExecutionContext ctx = getCurrentContext();
        String name = node.name;
        
        Object val = ctx.getVariable(name);
        if (val != null) {
            return val;
        }
        
        if (ctx.getSlotValues() != null && ctx.getSlotValues().containsKey(name)) {
            return ctx.getSlotValues().get(name);
        }

        if (ctx.objectInstance != null && ctx.objectInstance.type != null) {
            Object fieldValue = interpreter.getConstructorResolver()
                .getFieldFromHierarchy(ctx.objectInstance.type, name, ctx);
            if (fieldValue != null) {
                return fieldValue;
            }
        }

        Field importedField = interpreter.getImportResolver().findField(name);
        if (importedField != null) {
            if (importedField.value != null) {
                return dispatch(importedField.value);
            }
            return null;
        }
        
        throw new ProgramError("Undefined variable: " + name);
    }

    @Override
    public Object visit(IntLiteral node) {
        if (node == null) {
            throw new InternalError("visit(IntLiteral) called with null node");
        }
        return node.value;
    }

    @Override
    public Object visit(FloatLiteral node) {
        if (node == null) {
            throw new InternalError("visit(FloatLiteral) called with null node");
        }
        return node.value;
    }

@Override
public Object visit(TextLiteral node) {
    if (node == null) {
        throw new InternalError("visit(TextLiteral) called with null node");
    }
    
    String text = node.value;
    
    if (typeSystem.isTypeLiteral(text)) {
        return typeSystem.processTypeLiteral(text);
    }
    
    return text;
}

    @Override
    public Object visit(BoolLiteral node) {
        if (node == null) {
            throw new InternalError("visit(BoolLiteral) called with null node");
        }
        return node.value;
    }

    @Override
    public Object visit(NoneLiteral node) {
        return null;
    }

    @Override
    public Object visit(This node) {
        if (node == null) {
            throw new InternalError("visit(This) called with null node");
        }
        
        ExecutionContext ctx = getCurrentContext();
        
        if (ctx.objectInstance == null) {
            throw new ProgramError("Cannot use 'this' outside of an object context");
        }
        
        if (node.className != null) {
            if (ctx.objectInstance.type == null || 
                !node.className.equals(ctx.objectInstance.type.name)) {
                throw new ProgramError(
                    "Cannot access '" + node.className + ".this' in current context. " +
                    "Current object is of type: " + 
                    (ctx.objectInstance.type != null ? ctx.objectInstance.type.name : "null")
                );
            }
        }
        
        return ctx.objectInstance;
    }

    @Override
    public Object visit(Super node) {
        if (node == null) {
            throw new InternalError("visit(Super) called with null node");
        }
        
        ExecutionContext ctx = getCurrentContext();
        
        if (ctx.objectInstance == null) {
            throw new ProgramError("Cannot use 'super' outside of an object context");
        }
        
        if (ctx.objectInstance.type == null || ctx.objectInstance.type.extendName == null) {
            throw new ProgramError("Cannot use 'super' - no parent class");
        }
        
        return ctx.objectInstance;
    }
    
    @Override
    public Object visit(PropertyAccess node) {
        if (node == null) {
            throw new InternalError("visit(PropertyAccess) called with null node");
        }
        
        ExecutionContext ctx = getCurrentContext();
        
        try {
            if (node.left instanceof Identifier && node.right instanceof Identifier) {
                String leftName = ((Identifier) node.left).name;
                String rightName = ((Identifier) node.right).name;
                Field importedField = interpreter.getImportResolver().findField(leftName + "." + rightName);
                if (importedField != null) {
                    if (importedField.value != null) {
                        return dispatch(importedField.value);
                    }
                    return null;
                }
            }

            Object leftObj = dispatch(node.left);
            leftObj = typeSystem.unwrap(leftObj);
            
            if (node.right instanceof Identifier) {
                Identifier right = (Identifier) node.right;
                String propertyName = right.name;
                
                if (literalRegistry.hasProperty(leftObj, propertyName)) {
                    return literalRegistry.handleProperty(leftObj, propertyName, ctx);
                }
            }
            
            if (node.right instanceof MethodCall) {
                MethodCall literalMethod = (MethodCall) node.right;
                String methodName = literalMethod.name;
                if (literalRegistry.hasMethod(leftObj, methodName)) {
                    List<Object> evaluatedArgs = new ArrayList<Object>();
                    if (literalMethod.arguments != null) {
                        for (Expr arg : literalMethod.arguments) {
                            Object argValue = dispatch(arg);
                            evaluatedArgs.add(typeSystem.unwrap(argValue));
                        }
                    }
                    return literalRegistry.handleMethod(leftObj, methodName, evaluatedArgs, ctx);
                }
            }
            
            if (leftObj instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) leftObj;
                if (natural.hasPendingUpdates()) {
                    natural.commitUpdates();
                }
            }
            
            if (node.left instanceof Super) {
                return handleSuperPropertyAccess(node, ctx);
            }
            
            if (node.left instanceof This) {
                return handleThisPropertyAccess(node, ctx);
            }
            
            if (leftObj instanceof ObjectInstance) {
                ObjectInstance instance = (ObjectInstance) leftObj;
                
                if (node.right instanceof Identifier) {
                    Identifier right = (Identifier) node.right;
                    String fieldName = right.name;
                    
                    Object fieldValue = interpreter.getConstructorResolver()
                        .getFieldFromHierarchy(instance.type, fieldName, ctx);
                        
                    if (fieldValue == null) {
                        throw new ProgramError("Undefined field: " + fieldName);
                    }
                    
                    return fieldValue;
                }
            }
            
            throw new ProgramError("Invalid property access");
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Property access failed", e);
        }
    }

    private Object handleSuperPropertyAccess(PropertyAccess node, ExecutionContext ctx) {
        if (ctx.objectInstance == null || ctx.objectInstance.type == null) {
            throw new ProgramError("Cannot access 'super' outside of object context");
        }
        
        if (ctx.objectInstance.type.extendName == null) {
            throw new ProgramError("Cannot access 'super' - no parent class");
        }
        
        try {
            Type parentType = interpreter.getConstructorResolver()
                .findParentType(ctx.objectInstance.type, ctx);
            
            if (parentType == null) {
                throw new ProgramError("Parent class not found");
            }
            
            if (node.right instanceof Identifier) {
                Identifier right = (Identifier) node.right;
                String fieldName = right.name;
                
                Object fieldValue = interpreter.getConstructorResolver()
                    .getFieldFromHierarchy(parentType, fieldName, ctx);
                
                if (fieldValue == null) {
                    throw new ProgramError("Undefined field in parent: " + fieldName);
                }
                
                return fieldValue;
            }
            
            throw new ProgramError("Invalid super property access");
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Super property access failed", e);
        }
    }

    private Object handleThisPropertyAccess(PropertyAccess node, ExecutionContext ctx) {
        if (ctx.objectInstance == null || ctx.objectInstance.type == null) {
            throw new ProgramError("Cannot access 'this' outside of object context");
        }
        
        try {
            if (node.left instanceof This) {
                This left = (This) node.left;
                if (left.className != null && 
                    !left.className.equals(ctx.objectInstance.type.name)) {
                    throw new ProgramError(
                        "Cannot access '" + left.className + ".this' in current context. " +
                        "Current object is of type: " + ctx.objectInstance.type.name
                    );
                }
            }
            
            if (node.right instanceof Identifier) {
                Identifier right = (Identifier) node.right;
                String fieldName = right.name;
                
                Object fieldValue = interpreter.getConstructorResolver()
                    .getFieldFromHierarchy(ctx.objectInstance.type, fieldName, ctx);
                
                if (fieldValue == null) {
                    throw new ProgramError("Undefined field: " + fieldName);
                }
                
                return fieldValue;
            }
            
            throw new ProgramError("Invalid this property access");
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("This property access failed", e);
        }
    }

    @Override
    public Object visit(BinaryOp node) {
        if (node == null) {
            throw new InternalError("visit(BinaryOp) called with null node");
        }
        
        try {
            return expressionHandler.handleBinaryOp(node, getCurrentContext());
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Binary operation failed: " + node.op, e);
        }
    }

    @Override
    public Object visit(Unary node) {
        if (node == null) {
            throw new InternalError("visit(Unary) called with null node");
        }
        
        try {
            return expressionHandler.handleUnaryOp(node, getCurrentContext());
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Unary operation failed: " + node.op, e);
        }
    }

    @Override
    public Object visit(TypeCast node) {
        if (node == null) {
            throw new InternalError("visit(TypeCast) called with null node");
        }
        
        try {
            return expressionHandler.handleTypeCast(node, getCurrentContext());
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Type cast failed to " + node.targetType, e);
        }
    }

    @SuppressWarnings("unchecked")
@Override
public Object visit(MethodCall node) {
    if (node == null) {
        throw new InternalError("visit(MethodCall) called with null node");
    }
    
    try {
        // Handle super calls first
        if (node.isSuperCall) {
            return handleSuperMethodCall(node);
        }
        
        ExecutionContext ctx = getCurrentContext();
        String callName = node.name;
        String callQualifiedName = node.qualifiedName;

        if (node.isSelfCall) {
            Integer requestedLevel = resolveSelfCallLevelValue(node, ctx);
            if (requestedLevel != null) {
                LambdaClosure targetClosure = resolveSelfCallClosure(ctx, requestedLevel.intValue());
                List<Object> evaluatedArgs = evaluateMethodCallArguments(node);
                return invokeLambdaCallback(targetClosure, evaluatedArgs, ctx, SELF_CALL_LAMBDA_OWNER);
            }
            if (ctx.currentLambdaClosure != null) {
                List<Object> evaluatedArgs = evaluateMethodCallArguments(node);
                return invokeLambdaCallback(ctx.currentLambdaClosure, evaluatedArgs, ctx, SELF_CALL_LAMBDA_OWNER);
            }
            if (ctx.currentMethodName != null && !ctx.currentMethodName.isEmpty()) {
                callName = ctx.currentMethodName;
                callQualifiedName = ctx.currentMethodName;
            } else {
                throw new ProgramError(
                    "'<~(...)' can only be used inside a method or lambda body.");
            }
        }

        if (ctx != null && callQualifiedName != null && callQualifiedName.contains(".")) {
            String[] parts = callQualifiedName.split("\\.");
            if (parts.length == 2) {
                String receiverName = parts[0];
                String methodName = parts[1];
                Object receiverValue = ctx.getVariable(receiverName);
                receiverValue = typeSystem.unwrap(receiverValue);
                if (literalRegistry.hasMethod(receiverValue, methodName)) {
                    List<Object> evaluatedArgs = evaluateMethodCallArguments(node);
                    return literalRegistry.handleMethod(receiverValue, methodName, evaluatedArgs, ctx);
                }
            }
        }

        if ("safe".equals(callName) && (callQualifiedName == null || "safe".equals(callQualifiedName))) {
            return executeSafeCommit(node, ctx);
        }
        
        // ========== FIX: Evaluate arguments with special handling for ValueExpr ==========
        List<Object> evaluatedArgs = new ArrayList<Object>();
        if (node.arguments != null) {
            for (Expr arg : node.arguments) {
                Object argValue;
                if (arg instanceof ValueExpr) {
                    // ValueExpr already contains the actual value - extract it directly
                    argValue = ((ValueExpr) arg).getValue();
                    DebugSystem.debug("METHOD_CALL", "ValueExpr argument extracted: " + argValue);
                } else {
                    argValue = dispatch(arg);
                }
                evaluatedArgs.add(typeSystem.unwrap(argValue));
            }
        }
        
        // Check global functions first
        GlobalRegistry globalRegistry = interpreter.getGlobalRegistry();
        if (globalRegistry != null && globalRegistry.isGlobal(callName)) {
            DebugSystem.debug("GLOBAL", "Executing global function: " + callName + 
                              " with args: " + evaluatedArgs);
            return globalRegistry.executeGlobal(callName, evaluatedArgs);
        }
        
        Method method = resolveMethodForCall(node, ctx);

        // If method not found after all attempts, throw error
        if (method == null) {
            throw new ProgramError("Method not found: " + callName);
        }

        if (method.isUnsafe && !isUnsafeExecutionContext(ctx) && !ExecutionContext.isUnsafeCommitAllowed()) {
            throw new ProgramError(
                "Unsafe method '" + method.methodName + "' cannot be called in a safe context. Use safe("
                    + callName
                    + "(...)).");
        }

        // Check if this is a single-slot call
        boolean hasSingleSlot = method.returnSlots != null && method.returnSlots.size() == 1;
        if (node.slotNames.isEmpty() && hasSingleSlot) {
            node.isSingleSlotCall = true;
            node.slotNames.add(method.returnSlots.get(0).name);
        }

        // Handle builtin methods
        if (method.isBuiltin) {
            MethodCall evaluatedCall = new MethodCall();
            evaluatedCall.name = callName;
            evaluatedCall.arguments = new ArrayList<Expr>();
            for (Object val : evaluatedArgs) {
                evaluatedCall.arguments.add(new ValueExpr(val));
            }
            evaluatedCall.slotNames = node.slotNames;
            evaluatedCall.qualifiedName = callQualifiedName;
            evaluatedCall.target = node.target;
            evaluatedCall.isSuperCall = node.isSuperCall;
            evaluatedCall.isSingleSlotCall = node.isSingleSlotCall;
            evaluatedCall.isSelfCall = node.isSelfCall;
            evaluatedCall.selfCallLevel = node.selfCallLevel;
            evaluatedCall.selfCallLevelConstantName = node.selfCallLevelConstantName;
            
            return interpreter.handleBuiltinMethod(method, evaluatedCall);
        }

        boolean calledMethodHasSlots = method.returnSlots != null && !method.returnSlots.isEmpty();
        List<Object> activeMethodArgs = new ArrayList<Object>(evaluatedArgs);

        while (true) {
            // Prepare method locals with parameter values
            Map<String, Object> methodLocals = new HashMap<String, Object>();
            Map<String, String> methodLocalTypes = new HashMap<String, String>();

            int argCount = activeMethodArgs.size();
            int paramCount = method.parameters != null ? method.parameters.size() : 0;

            for (int i = 0; i < paramCount; i++) {
                Param param = method.parameters.get(i);
                Object argValue = null;

                if (i < argCount) {
                    argValue = activeMethodArgs.get(i);
                } else {
                    if (param.hasDefaultValue) {
                        ExecutionContext defaultCtx = new ExecutionContext(
                            ctx.objectInstance,
                            new HashMap<String, Object>(),
                            null,
                            null,
                            typeSystem
                        );
                        defaultCtx.currentMethodName = callName;
                        pushContext(defaultCtx);
                        try {
                            argValue = dispatch(param.defaultValue);
                        } finally {
                            popContext();
                        }
                    } else {
                        throw new ProgramError(
                            "Missing argument for parameter '" + param.name
                                + "'. Expected " + paramCount + " arguments, got " + argCount);
                    }
                }

                String paramType = param.type;

                if (!typeSystem.validateType(paramType, argValue)) {
                    if (paramType.equals(TEXT.toString())) {
                        argValue = typeSystem.convertType(argValue, paramType);
                    } else {
                        throw new ProgramError(
                            "Argument type mismatch for parameter " + param.name
                                + ". Expected " + paramType + ", got: "
                                + typeSystem.getConcreteType(argValue));
                    }
                }

                if (paramType != null && paramType.indexOf('|') >= 0) {
    int activeMask = typeSystem.getConcreteMask(typeSystem.unwrap(argValue));
    int declaredMask = TypeHandler.parseTypeMask(paramType);
    argValue = new TypeHandler.Value(argValue, activeMask, declaredMask);
}

                methodLocals.put(param.name, argValue);
                methodLocalTypes.put(param.name, paramType);
            }

            if (argCount > paramCount) {
                throw new ProgramError(
                    "Too many arguments: expected " + paramCount + ", got " + argCount);
            }

            // Setup slot values for method return
            Map<String, Object> slotValues = new LinkedHashMap<String, Object>();
            Map<String, String> slotTypes = new LinkedHashMap<String, String>();
            if (method.returnSlots != null) {
                for (Slot s : method.returnSlots) {
                    slotValues.put(s.name, null);
                    slotTypes.put(s.name, s.type);
                }
            }

            // Create method execution context
            ExecutionContext methodCtx = new ExecutionContext(
                ctx.objectInstance,
                methodLocals,
                slotValues,
                slotTypes,
                typeSystem
            );

            for (Map.Entry<String, String> entry : methodLocalTypes.entrySet()) {
                methodCtx.setVariableType(entry.getKey(), entry.getValue());
            }

            methodCtx.objectInstance = ctx.objectInstance;

            if (method.associatedClass != null) {
                Type classType = findTypeByName(method.associatedClass);
                if (classType != null) {
                    methodCtx.currentClass = classType;
                }
            }

            if (ctx.objectInstance != null && ctx.objectInstance.type != null
                && methodCtx.currentClass == null) {
                Type classType = findTypeByName(ctx.objectInstance.type.name);
                if (classType != null) {
                    methodCtx.currentClass = classType;
                }
            }
            methodCtx.currentMethodName = callName;
            methodCtx.currentLambdaClosure = null;

            // Execute method body
            pushContext(methodCtx);
            Object methodResult = null;
            List<Object> nextTailArgs = null;

            try {
                if (method.body != null) {
                    for (Stmt stmt : method.body) {
                        visit(stmt);

                        if (calledMethodHasSlots
                            && interpreter.shouldReturnEarly(slotValues, methodCtx.slotsInCurrentPath)) {
                            break;
                        }
                    }
                }
            } catch (TailCallSignal tailCallSignal) {
                if (tailCallSignal.methodName != null && tailCallSignal.methodName.equals(callName)) {
                    nextTailArgs = tailCallSignal.arguments;
                } else {
                    throw tailCallSignal;
                }
            } catch (EarlyExitException e) {
                // Normal exit - method completed
            } catch (ProgramError e) {
                throw e;
            } catch (Exception e) {
                throw new InternalError("Method call execution failed: " + callName, e);
            } finally {
                popContext();
            }

            if (nextTailArgs != null) {
                activeMethodArgs = nextTailArgs;
                continue;
            }

            // Handle return value based on call type
            if (node.slotNames != null && !node.slotNames.isEmpty()) {
                if (!(methodResult instanceof Map) && calledMethodHasSlots) {
                    methodResult = slotValues;
                }

                if (methodResult instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) methodResult;
                    String requestedSlot = node.slotNames.get(0);

                    if (!map.containsKey(requestedSlot) && method != null && method.returnSlots != null) {
                        try {
                            int index = Integer.parseInt(requestedSlot);
                            if (index >= 0 && index < method.returnSlots.size()) {
                                requestedSlot = method.returnSlots.get(index).name;
                            }
                        } catch (NumberFormatException e) {
                            // Not an index, keep original slot name
                        }
                    }

                    if (map.containsKey(requestedSlot)) {
                        return map.get(requestedSlot);
                    }
                    throw new ProgramError("Undefined method slot: " + requestedSlot);
                } else if (calledMethodHasSlots) {
                    return slotValues;
                }
            }

            // Default: return whatever the method produced
            return methodResult != null ? methodResult : slotValues;
        }
        
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Method call failed: " + node.name, e);
    }
}

    private LambdaClosure resolveSelfCallClosure(ExecutionContext ctx, int level) {
        // Parser-level checks reject negative literals, but runtime validation is still required
        // for non-parser entry paths (deserialized/constructed ASTs).
        if (level < 0) {
            throw new ProgramError("Self-call level cannot be negative: " + level);
        }
        if (ctx.currentLambdaClosure == null) {
            throw new ProgramError(
                "'<~" + level + "(...)' is only valid inside lambda bodies.");
        }

        LambdaClosure closure = ctx.currentLambdaClosure;
        for (int i = 0; i < level; i++) {
            closure = closure.parentClosure;
            if (closure == null) {
                throw new ProgramError(
                    "Lambda self-call level '<~" + level + "(...)' is out of range for current nesting.");
            }
        }
        return closure;
    }

    private Integer resolveSelfCallLevelValue(MethodCall node, ExecutionContext ctx) {
        if (node == null) return null;
        if (node.selfCallLevel != null) return node.selfCallLevel;
        if (node.selfCallLevelConstantName == null) return null;

        String constantName = node.selfCallLevelConstantName;
        Object levelValue = ctx != null ? ctx.getVariable(constantName) : null;

        if (levelValue == null && ctx != null && ctx.getSlotValues() != null
            && ctx.getSlotValues().containsKey(constantName)) {
            levelValue = ctx.getSlotValues().get(constantName);
        }

        if (levelValue == null && ctx != null && ctx.objectInstance != null && ctx.objectInstance.type != null) {
            levelValue =
                interpreter
                    .getConstructorResolver()
                    .getFieldFromHierarchy(ctx.objectInstance.type, constantName, ctx);
        }

        if (levelValue == null) {
            throw new ProgramError("Undefined self-call level constant: " + constantName);
        }

        Object unwrapped = typeSystem.unwrap(levelValue);
        long levelLong;
        try {
            if (unwrapped instanceof AutoStackingNumber) {
                // Level constants are expected to be small integers in normal usage.
                // longValue() also enforces integer-only semantics (fails on fractional values).
                levelLong = ((AutoStackingNumber) unwrapped).longValue();
            } else if (unwrapped instanceof Number) {
                if (unwrapped instanceof Double || unwrapped instanceof Float) {
                    double numeric = ((Number) unwrapped).doubleValue();
                    double fractional = Math.abs(numeric % 1.0d);
                    if (fractional > SELF_CALL_LEVEL_FLOAT_EPSILON
                        && Math.abs(fractional - 1.0d) > SELF_CALL_LEVEL_FLOAT_EPSILON) {
                        throw new ProgramError(
                            "Self-call level constant '" + constantName + "' must be an integer value");
                    }
                }
                levelLong = ((Number) unwrapped).longValue();
            } else {
                throw new ProgramError(
                    "Self-call level constant '" + constantName + "' must be int, got: "
                        + typeSystem.getConcreteType(unwrapped));
            }
        } catch (ArithmeticException e) {
            throw new ProgramError(
                "Self-call level constant '" + constantName + "' must be an integer value");
        }

        if (levelLong < 0) {
            throw new ProgramError(
                "Self-call level constant '" + constantName + "' cannot be negative: " + levelLong);
        }

        if (levelLong > Integer.MAX_VALUE) {
            throw new ProgramError(
                "Self-call level constant '" + constantName + "' is out of supported range: " + levelLong);
        }

        return Integer.valueOf((int) levelLong);
    }

    private Type findTypeByName(String className) {
        Program currentProgram = interpreter.getCurrentProgram();
        if (currentProgram != null && currentProgram.unit != null && currentProgram.unit.types != null) {
            for (Type t : currentProgram.unit.types) {
                if (t.name.equals(className)) {
                    return t;
                }
            }
        }
        return null;
    }

@Override
public Object visit(Array node) {
    if (node == null) {
        throw new InternalError("visit(Array) called with null node");
    }
    
    try {
        if (node.elements.size() == 1) {
            Expr onlyElement = node.elements.get(0);
            if (onlyElement instanceof Range) {
                Range range = (Range) onlyElement;
                
                // Just create the array - type checking happens in Var
                return new NaturalArray(range, this, getCurrentContext());
            }
        }
        
        if (node.elements.size() > 1 && allElementsAreRanges(node.elements)) {
            return buildDimensionArray(node.elements, 0);
        }

        // Regular array literal handling
        List<Object> result = new ArrayList<Object>();
        for (Expr element : node.elements) {
            if (element instanceof Range) {
                result.add(new NaturalArray((Range) element, this, getCurrentContext()));
            } else {
                Object evaluated = dispatch(element);
                
                if (evaluated instanceof NaturalArray) {
                    NaturalArray arr = (NaturalArray) evaluated;
                    if (arr.hasPendingUpdates()) {
                        arr.commitUpdates();
                    }
                }
                
                // FIXED: Convert type literals using bitmask
                if (evaluated instanceof String && typeSystem.isTypeLiteral((String) evaluated)) {
                    int typeMask = TypeHandler.parseTypeMask((String) evaluated);
                    evaluated = TypeHandler.Value.createTypeValue(typeMask);
                } else if (evaluated instanceof TextLiteral) {
                    String str = ((TextLiteral) evaluated).value;
                    if (typeSystem.isTypeLiteral(str)) {
                        int typeMask = TypeHandler.parseTypeMask(str);
                        evaluated = TypeHandler.Value.createTypeValue(typeMask);
                    }
                }
                
                result.add(evaluated);
            }
        }
        return result;
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Array creation failed", e);
    }
}
    
    private boolean allElementsAreRanges(List<Expr> elements) {
        if (elements == null || elements.isEmpty()) return false;
        for (Expr element : elements) {
            if (!(element instanceof Range)) {
                return false;
            }
        }
        return true;
    }
    
    private Object buildDimensionArray(List<Expr> ranges, int dimension) {
        Range currentRange = (Range) ranges.get(dimension);
        NaturalArray currentNatural = new NaturalArray(currentRange, this, getCurrentContext());
        if (dimension == ranges.size() - 1) {
            return currentNatural;
        }
        
        long length = currentNatural.size();
        if (length > Integer.MAX_VALUE) {
            throw new ProgramError("Dimension size too large for nested ND array literal: " + length + " (max " + Integer.MAX_VALUE + ")");
        }
        
        List<Object> result = new ArrayList<Object>((int) length);
        for (int i = 0; i < (int) length; i++) {
            result.add(buildDimensionArray(ranges, dimension + 1));
        }
        return result;
    }

    @Override
    public Object visit(IndexAccess node) {
        return arrHandler.visitIndexAccess(node);
    }

    @Override
    public Object visit(RangeIndex node) {
        return arrHandler.visitRangeIndex(node);
    }

    @Override
    public Object visit(MultiRangeIndex node) {
        return arrHandler.visitMultiRangeIndex(node);
    }

    @Override
    public Object visit(EqualityChain node) {
        if (node == null) {
            throw new InternalError("visit(EqualityChain) called with null node");
        }
        
        try {
            return expressionHandler.handleEqualityChain(node, getCurrentContext());
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Equality chain evaluation failed", e);
        }
    }
    
@Override
public Object visit(ChainedComparison node) {
    if (node == null) {
        throw new InternalError("visit(ChainedComparison) called with null node");
    }
    
    try {
        return expressionHandler.handleChainedComparison(node, getCurrentContext());
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Chained comparison execution failed", e);
    }
}

    @Override
    public Object visit(BooleanChain node) {
        if (node == null) {
            throw new InternalError("visit(BooleanChain) called with null node");
        }
        
        try {
            return expressionHandler.handleBooleanChain(node, getCurrentContext());
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Boolean chain evaluation failed", e);
        }
    }

    @Override
    public Object visit(Slot n) {
        return defaultVisit(n);
    }

    @Override
    public Object visit(Lambda node) {
        return lambdaHandler.createLambdaClosure(node, getCurrentContext());
    }
    
    private Object invokeLambdaCallback(
        Object callbackObj,
        List<Object> args,
        ExecutionContext parentCtx,
        String ownerMethod) {
        return lambdaHandler.invokeLambdaCallback(callbackObj, args, parentCtx, ownerMethod);
    }

    private List<Param> inferLambdaParamsFromPlaceholders(Lambda lambda) {
        if (lambda == null) {
            return new ArrayList<Param>();
        }
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        
        if (lambda.expressionBody != null) {
            collectPlaceholderNames(lambda.expressionBody, names);
        } else if (lambda.body != null) {
            collectPlaceholderNames(lambda.body, names);
        }
        
        List<Param> params = new ArrayList<Param>();
        for (String name : names) {
            Param param = new Param();
            param.name = name;
            param.type = null;
            param.typeInferred = true;
            param.isLambdaParameter = true;
            params.add(param);
        }
        return params;
    }
    
    private void collectPlaceholderNames(Base node, LinkedHashSet<String> names) {
        if (node == null) return;
        
        if (node instanceof Identifier) {
            String name = ((Identifier) node).name;
            if (name != null && name.startsWith("$") && name.length() > 1) {
                names.add(name);
            }
            return;
        }
        
        if (node instanceof BinaryOp) {
            BinaryOp n = (BinaryOp) node;
            collectPlaceholderNames(n.left, names);
            collectPlaceholderNames(n.right, names);
            return;
        }
        if (node instanceof Unary) {
            collectPlaceholderNames(((Unary) node).operand, names);
            return;
        }
        if (node instanceof TypeCast) {
            collectPlaceholderNames(((TypeCast) node).expression, names);
            return;
        }
        if (node instanceof MethodCall) {
            MethodCall n = (MethodCall) node;
            if (n.arguments != null) {
                for (Expr arg : n.arguments) {
                    collectPlaceholderNames(arg, names);
                }
            }
            if (n.target != null) {
                collectPlaceholderNames(n.target, names);
            }
            return;
        }
        if (node instanceof PropertyAccess) {
            PropertyAccess n = (PropertyAccess) node;
            collectPlaceholderNames(n.left, names);
            collectPlaceholderNames(n.right, names);
            return;
        }
        if (node instanceof IndexAccess) {
            IndexAccess n = (IndexAccess) node;
            collectPlaceholderNames(n.array, names);
            collectPlaceholderNames(n.index, names);
            return;
        }
        if (node instanceof Array) {
            Array n = (Array) node;
            if (n.elements != null) {
                for (Expr elem : n.elements) {
                    collectPlaceholderNames(elem, names);
                }
            }
            return;
        }
        if (node instanceof Tuple) {
            Tuple n = (Tuple) node;
            if (n.elements != null) {
                for (Expr elem : n.elements) {
                    collectPlaceholderNames(elem, names);
                }
            }
            return;
        }
        if (node instanceof ExprIf) {
            ExprIf n = (ExprIf) node;
            collectPlaceholderNames(n.condition, names);
            collectPlaceholderNames(n.thenExpr, names);
            collectPlaceholderNames(n.elseExpr, names);
            return;
        }
        if (node instanceof BooleanChain) {
            BooleanChain n = (BooleanChain) node;
            if (n.expressions != null) {
                for (Expr expr : n.expressions) {
                    collectPlaceholderNames(expr, names);
                }
            }
            return;
        }
        if (node instanceof EqualityChain) {
            EqualityChain n = (EqualityChain) node;
            collectPlaceholderNames(n.left, names);
            if (n.chainArguments != null) {
                for (Expr expr : n.chainArguments) {
                    collectPlaceholderNames(expr, names);
                }
            }
            return;
        }
        if (node instanceof ChainedComparison) {
            ChainedComparison n = (ChainedComparison) node;
            if (n.expressions != null) {
                for (Expr expr : n.expressions) {
                    collectPlaceholderNames(expr, names);
                }
            }
            return;
        }
        if (node instanceof ValueExpr) {
            Object value = ((ValueExpr) node).getValue();
            if (value instanceof Base) {
                collectPlaceholderNames((Base) value, names);
            }
            return;
        }
        if (node instanceof Lambda) {
            // Nested lambdas infer their own placeholders independently.
            return;
        }
        
        if (node instanceof Block) {
            Block n = (Block) node;
            if (n.statements != null) {
                for (Stmt stmt : n.statements) {
                    collectPlaceholderNames(stmt, names);
                }
            }
            return;
        }
        if (node instanceof SlotAssignment) {
            collectPlaceholderNames(((SlotAssignment) node).value, names);
            return;
        }
        if (node instanceof MultipleSlotAssignment) {
            MultipleSlotAssignment n = (MultipleSlotAssignment) node;
            if (n.assignments != null) {
                for (SlotAssignment asg : n.assignments) {
                    collectPlaceholderNames(asg, names);
                }
            }
            return;
        }
        if (node instanceof Assignment) {
            Assignment n = (Assignment) node;
            collectPlaceholderNames(n.left, names);
            collectPlaceholderNames(n.right, names);
            return;
        }
        if (node instanceof Var) {
            collectPlaceholderNames(((Var) node).value, names);
            return;
        }
        if (node instanceof ReturnSlotAssignment) {
            ReturnSlotAssignment n = (ReturnSlotAssignment) node;
            collectPlaceholderNames(n.methodCall, names);
            collectPlaceholderNames(n.lambda, names);
            return;
        }
    }

    @SuppressWarnings("unchecked")
    private Object handleSuperMethodCall(MethodCall node) {
        ExecutionContext ctx = getCurrentContext();
        
        if (ctx.objectInstance == null || ctx.objectInstance.type == null) {
            throw new ProgramError("Cannot call 'super." + node.name + "' outside of object context");
        }
        
        if (ctx.objectInstance.type.extendName == null) {
            throw new ProgramError("Cannot call 'super." + node.name + "' - no parent class");
        }
        
        try {
            ConstructorResolver resolver = interpreter.getConstructorResolver();
            Type parentType = resolver.findParentType(ctx.objectInstance.type, ctx);
            
            if (parentType == null) {
                throw new ProgramError("Parent class not found for 'super." + node.name + "'");
            }
            
            Method method = resolver.findMethodInHierarchy(parentType, node.name, ctx);
            
            if (method == null) {
                throw new ProgramError("Method '" + node.name + "' not found in parent class");
            }
            
            if (method.isBuiltin) {
                return interpreter.handleBuiltinMethod(method, node);
            }
            
            Object result = interpreter.evalMethodCall(node, ctx.objectInstance, ctx.locals(), method);
            
            if (node.slotNames != null && !node.slotNames.isEmpty()) {
                if (!(result instanceof Map)) {
                    throw new ProgramError("Method did not return slots.");
                }

                Map<String, Object> map = (Map<String, Object>) result;
                String requestedSlot = node.slotNames.get(0);

                if (!map.containsKey(requestedSlot) && method != null && method.returnSlots != null) {
                    try {
                        int index = Integer.parseInt(requestedSlot);
                        if (index >= 0 && index < method.returnSlots.size()) {
                            requestedSlot = method.returnSlots.get(index).name;
                        }
                    } catch (NumberFormatException e) {
                        // Not an index
                    }
                }

                if (map.containsKey(requestedSlot)) {
                    return map.get(requestedSlot);
                }
                throw new ProgramError("Undefined method slot: " + requestedSlot);
            }

            return result;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Super method call failed: " + node.name, e);
        }
    }
    
}
