package cod.interpreter.handler;

import cod.ast.node.*;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.interpreter.InterpreterVisitor;
import cod.interpreter.TailCallSignal;
import cod.interpreter.context.ExecutionContext;
import cod.interpreter.context.LambdaClosure;

import java.util.*;

public class LambdaHandler {
    private final TypeHandler typeSystem;
    private final InterpreterVisitor dispatcher;

    public LambdaHandler(TypeHandler typeSystem, InterpreterVisitor dispatcher) {
        if (typeSystem == null) {
            throw new InternalError("LambdaHandler constructed with null typeSystem");
        }
        if (dispatcher == null) {
            throw new InternalError("LambdaHandler constructed with null dispatcher");
        }
        this.typeSystem = typeSystem;
        this.dispatcher = dispatcher;
    }

    public LambdaClosure createLambdaClosure(Lambda node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("createLambdaClosure called with null node");
        }
        if (ctx == null) {
            throw new InternalError("createLambdaClosure called with null context");
        }

        Map<String, Object> captured = new HashMap<String, Object>();
        for (int i = 0; i < ctx.getScopeDepth(); i++) {
            Map<String, Object> scope = ctx.getScope(i);
            if (scope != null) {
                captured.putAll(scope);
            }
        }

        return new LambdaClosure(
            node,
            captured,
            ctx.objectInstance,
            ctx.currentClass,
            ctx.currentLambdaClosure,
            Collections.<Object>emptyList());
    }

    public Object invokeLambdaCallback(
        Object callbackObj,
        List<Object> args,
        ExecutionContext parentCtx,
        String ownerMethod) {

        Object callback = typeSystem.unwrap(callbackObj);
        LambdaClosure closure;
        if (callback instanceof LambdaClosure) {
            closure = (LambdaClosure) callback;
        } else if (callback instanceof Lambda) {
            closure =
                new LambdaClosure(
                    (Lambda) callback,
                    parentCtx.locals(),
                    parentCtx.objectInstance,
                    parentCtx.currentClass,
                    parentCtx.currentLambdaClosure,
                    Collections.<Object>emptyList());
        } else {
            String actualType = callback == null ? "null" : callback.getClass().getSimpleName();
            throw new ProgramError(ownerMethod + " expects a lambda callback, got: " + actualType);
        }

        LambdaClosure activeClosure = closure;
        List<Object> activeIncomingValues = args != null ? args : Collections.<Object>emptyList();

        while (true) {
            Lambda lambda = activeClosure.lambda;
            List<Param> params = resolveLambdaParameters(lambda);
            List<Object> combinedValues =
                mergeBoundAndIncomingLambdaArgs(activeClosure.boundArguments, activeIncomingValues);

            if (shouldAutoCurry(params, combinedValues)) {
                return createCurriedLambdaClosure(activeClosure, combinedValues);
            }

            int parameterBindCount = Math.min(params.size(), combinedValues.size());
            List<Object> values = new ArrayList<Object>(combinedValues.subList(0, parameterBindCount));
            List<Object> leftoverValues =
                new ArrayList<Object>(combinedValues.subList(parameterBindCount, combinedValues.size()));

            Map<String, Object> lambdaLocals =
                bindLambdaArguments(params, values, activeClosure, ownerMethod);
            if (lambda.inferParameters && params.isEmpty()) {
                bindPositionalInferredPlaceholderAliases(lambdaLocals, values);
            }

            Object result;
            try {
                if (lambda.expressionBody != null) {
                    result = evaluateLambdaExpressionBody(lambda, activeClosure, lambdaLocals);
                } else {
                    result = evaluateLambdaBlockBody(lambda, activeClosure, lambdaLocals);
                }
            } catch (TailCallSignal tailCallSignal) {
                if (tailCallSignal.lambdaClosure != null
                    && tailCallSignal.lambdaClosure == activeClosure) {
                    activeClosure = tailCallSignal.lambdaClosure;
                    activeIncomingValues = tailCallSignal.arguments;
                    continue;
                }
                throw tailCallSignal;
            }

            if (!leftoverValues.isEmpty() && (result instanceof LambdaClosure || result instanceof Lambda)) {
                return invokeLambdaCallback(result, leftoverValues, parentCtx, ownerMethod);
            }
            return result;
        }
    }

    private List<Param> resolveLambdaParameters(Lambda lambda) {
        if (lambda == null) {
            return new ArrayList<Param>();
        }
        List<Param> params =
            lambda.parameters != null ? lambda.parameters : new ArrayList<Param>();
        if (!params.isEmpty()) {
            return params;
        }
        if (!lambda.inferParameters) {
            return params;
        }

        List<Param> inferred = inferLambdaParamsFromPlaceholders(lambda);
        return inferred;
    }

    private List<Object> mergeBoundAndIncomingLambdaArgs(List<Object> boundArgs, List<Object> incomingArgs) {
        if ((boundArgs == null || boundArgs.isEmpty()) && (incomingArgs == null || incomingArgs.isEmpty())) {
            return Collections.<Object>emptyList();
        }
        List<Object> combined = new ArrayList<Object>();
        if (boundArgs != null && !boundArgs.isEmpty()) {
            combined.addAll(boundArgs);
        }
        if (incomingArgs != null && !incomingArgs.isEmpty()) {
            combined.addAll(incomingArgs);
        }
        return combined;
    }

    private boolean shouldAutoCurry(List<Param> params, List<Object> values) {
        if (params == null || params.isEmpty()) return false;
        int requiredCount = 0;
        for (Param param : params) {
            if (param == null) continue;
            if (!param.hasDefaultValue) {
                requiredCount++;
            }
        }
        return values.size() < requiredCount;
    }

    private LambdaClosure createCurriedLambdaClosure(
        LambdaClosure closure,
        List<Object> boundArgs) {

        return new LambdaClosure(
            closure.lambda,
            closure.capturedLocals,
            closure.objectInstance,
            closure.currentClass,
            closure.parentClosure,
            boundArgs);
    }

    private Map<String, Object> bindLambdaArguments(
        List<Param> params,
        List<Object> values,
        LambdaClosure closure,
        String ownerMethod) {

        Map<String, Object> lambdaLocals = new HashMap<String, Object>(closure.capturedLocals);
        for (int i = 0; i < params.size(); i++) {
            Param param = params.get(i);
            if (param == null || param.name == null) continue;

            Object boundValue = resolveLambdaArgumentValue(i, param, values, closure, lambdaLocals, ownerMethod);
            validateLambdaArgumentType(param, boundValue);
            lambdaLocals.put(param.name, boundValue);
        }
        return lambdaLocals;
    }

    private Object resolveLambdaArgumentValue(
        int index,
        Param param,
        List<Object> values,
        LambdaClosure closure,
        Map<String, Object> lambdaLocals,
        String ownerMethod) {

        if (index < values.size()) {
            return values.get(index);
        }
        if (param.hasDefaultValue && param.defaultValue != null) {
            return evaluateLambdaDefaultValue(param, closure, lambdaLocals);
        }
        throw new ProgramError(
            "Missing value for lambda parameter '" + param.name + "' in " + ownerMethod + " callback");
    }

    private Object evaluateLambdaDefaultValue(
        Param param,
        LambdaClosure closure,
        Map<String, Object> lambdaLocals) {

        ExecutionContext defaultCtx =
            new ExecutionContext(closure.objectInstance, lambdaLocals, null, null, typeSystem);
        defaultCtx.currentClass = closure.currentClass;
        defaultCtx.currentLambdaClosure = closure;
        dispatcher.pushContext(defaultCtx);
        try {
            return dispatcher.visit((Base) param.defaultValue);
        } finally {
            dispatcher.popContext();
        }
    }

    private void validateLambdaArgumentType(Param param, Object boundValue) {
        if (param.type != null && !typeSystem.validateType(param.type, boundValue)) {
            throw new ProgramError(
                "Lambda parameter type mismatch for '" + param.name + "'. Expected "
                    + param.type + ", got: " + typeSystem.getConcreteType(boundValue));
        }
    }

    private Object evaluateLambdaExpressionBody(
        Lambda lambda,
        LambdaClosure closure,
        Map<String, Object> lambdaLocals) {

        ExecutionContext exprCtx =
            new ExecutionContext(closure.objectInstance, lambdaLocals, null, null, typeSystem);
        exprCtx.currentClass = closure.currentClass;
        exprCtx.currentLambdaClosure = closure;
        dispatcher.pushContext(exprCtx);
        try {
            return dispatcher.dispatch(lambda.expressionBody);
        } finally {
            dispatcher.popContext();
        }
    }

    private void bindPositionalInferredPlaceholderAliases(
        Map<String, Object> lambdaLocals,
        List<Object> values) {

        if (values == null || values.isEmpty()) return;
        Object first = values.get(0);
        putIfAbsent(lambdaLocals, "$item", first);
        putIfAbsent(lambdaLocals, "$left", first);
        putIfAbsent(lambdaLocals, "$acc", first);
        putIfAbsent(lambdaLocals, "$value", first);

        if (values.size() > 1) {
            Object second = values.get(1);
            putIfAbsent(lambdaLocals, "$index", second);
            putIfAbsent(lambdaLocals, "$right", second);
            putIfAbsent(lambdaLocals, "$next", second);
        }

        if (values.size() > 2) {
            Object third = values.get(2);
            putIfAbsent(lambdaLocals, "$index", third);
            putIfAbsent(lambdaLocals, "$position", third);
        }
    }

    private void putIfAbsent(Map<String, Object> lambdaLocals, String name, Object value) {
        if (!lambdaLocals.containsKey(name)) {
            lambdaLocals.put(name, value);
        }
    }

    private Object evaluateLambdaBlockBody(
        Lambda lambda,
        LambdaClosure closure,
        Map<String, Object> lambdaLocals) {

        List<Slot> lambdaSlots =
            lambda.returnSlots != null ? lambda.returnSlots : new ArrayList<Slot>();
        if (lambdaSlots.isEmpty()) {
            throw new ProgramError(
                "Lambda with explicit body requires a return contract (::). "
                    + "Use expression body syntax for implicit return values.");
        }

        Map<String, Object> slotValues = new LinkedHashMap<String, Object>();
        Map<String, String> slotTypes = new LinkedHashMap<String, String>();
        for (Slot slot : lambdaSlots) {
            slotValues.put(slot.name, null);
            slotTypes.put(slot.name, slot.type);
        }

        ExecutionContext lambdaCtx =
            new ExecutionContext(closure.objectInstance, lambdaLocals, slotValues, slotTypes, typeSystem);
        lambdaCtx.currentClass = closure.currentClass;
        lambdaCtx.currentLambdaClosure = closure;
        dispatcher.pushContext(lambdaCtx);
        try {
            if (lambda.body != null) {
                dispatcher.visit((Base) lambda.body);
            }
        } catch (cod.interpreter.exception.EarlyFinException e) {
            // normal lambda early exit
        } finally {
            dispatcher.popContext();
        }

        if (lambdaSlots.size() == 1) {
            return slotValues.get(lambdaSlots.get(0).name);
        }
        return slotValues;
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
        }
    }
}
