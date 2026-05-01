package cod.interpreter.handler;

import cod.ast.node.*;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.math.AutoStackingNumber;
import cod.interpreter.context.ExecutionContext;
import cod.interpreter.InterpreterVisitor;
import cod.range.NaturalArray;
import cod.range.RangeObjects;

import java.util.*;

public class ExpressionHandler {
    private final TypeHandler typeSystem;
    private final InterpreterVisitor dispatcher;
    
    public ExpressionHandler(TypeHandler typeSystem, InterpreterVisitor dispatcher) {
        if (typeSystem == null) {
            throw new InternalError("ExpressionHandler constructed with null typeSystem");
        }
        if (dispatcher == null) {
            throw new InternalError("ExpressionHandler constructed with null dispatcher");
        }
        this.typeSystem = typeSystem;
        this.dispatcher = dispatcher;
    }
    
    // === Core Expression Evaluation ===
    
    public Object handleBinaryOp(BinaryOp node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("handleBinaryOp called with null node");
        }
        if (ctx == null) {
            throw new InternalError("handleBinaryOp called with null context");
        }
        
        try {
            Object left = dispatcher.dispatch(node.left);
            Object right = dispatcher.dispatch(node.right);
            Object result = null;

            switch (node.op) {
                case "+":
                case "+=":
                    if (typeSystem.unwrap(left) instanceof TypeHandler.PointerValue
                        || typeSystem.unwrap(right) instanceof TypeHandler.PointerValue) {
                        return handlePointerArithmetic(left, right, true, ctx);
                    }
                    if (left instanceof String || right instanceof String ||
                        left instanceof TextLiteral || right instanceof TextLiteral) {
                        
                        Object unwrappedLeft = typeSystem.unwrap(left);
                        Object unwrappedRight = typeSystem.unwrap(right);
                        
                        if (unwrappedLeft instanceof NaturalArray) {
                            NaturalArray arr = (NaturalArray) unwrappedLeft;
                            if (arr.hasPendingUpdates()) {
                                arr.commitUpdates();
                            }
                        }
                        
                        if (unwrappedRight instanceof NaturalArray) {
                            NaturalArray arr = (NaturalArray) unwrappedRight;
                            if (arr.hasPendingUpdates()) {
                                arr.commitUpdates();
                            }
                        }
                        
                        result = String.valueOf(unwrappedLeft) + String.valueOf(unwrappedRight);
                    } else {
                        result = typeSystem.addNumbers(left, right);
                    }
                    break;

                case "*":
                case "*=":
                    result = typeSystem.multiplyNumbers(left, right);
                    break;

                case "-":
                case "-=":
                    if (typeSystem.unwrap(left) instanceof TypeHandler.PointerValue
                        || typeSystem.unwrap(right) instanceof TypeHandler.PointerValue) {
                        return handlePointerArithmetic(left, right, false, ctx);
                    }
                    result = typeSystem.subtractNumbers(left, right);
                    break;

                case "/":
                case "/=":
                    result = typeSystem.divideNumbers(left, right);
                    break;

                case "%":
                    result = typeSystem.modulusNumbers(left, right);
                    break;

                case ">":
                    result = typeSystem.compare(left, right) > 0;
                    break;

                case "<":
                    result = typeSystem.compare(left, right) < 0;
                    break;

                case ">=":
                    result = typeSystem.compare(left, right) >= 0;
                    break;

                case "<=":
                    result = typeSystem.compare(left, right) <= 0;
                    break;

                case "=":
                    result = right;
                    break;

                case "==":
                    result = typeSystem.areEqual(left, right);
                    break;

                case "!=":
                    result = !typeSystem.areEqual(left, right);
                    break;
                    
                case "is": {
                    return handleIsOperator(left, right);
                }

                default:
                    throw new ProgramError("Unknown operator: " + node.op);
            }
            return result;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Binary operation failed: " + node.op, e);
        }
    }
    
    public Object handleUnaryOp(Unary node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("handleUnaryOp called with null node");
        }
        if (ctx == null) {
            throw new InternalError("handleUnaryOp called with null context");
        }
        
        try {
            if ("&".equals(node.op)) {
                ensureUnsafeContext(ctx, "Address-of operator '&'");
                if (!(node.operand instanceof IndexAccess)) {
                    throw new ProgramError("Address-of operator '&' requires an index expression like '&buffer[0]'");
                }
                return createPointerFromIndexAccess((IndexAccess) node.operand, ctx);
            }

            Object operand = dispatcher.dispatch(node.operand);

            switch (node.op) {
                case "-":
                    return typeSystem.negateNumber(operand);
                case "+":
                    return operand;
                case "!":
                    return !typeSystem.isTruthy(operand);
                case "*":
                    ensureUnsafeContext(ctx, "Pointer dereference '*'");
                    return dereferencePointer(operand);
                default:
                    throw new ProgramError("Unknown unary operator: " + node.op);
            }
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Unary operation failed: " + node.op, e);
        }
    }

    private void ensureUnsafeContext(ExecutionContext ctx, String featureName) {
        boolean unsafe = ctx.isUnsafeExecutionContext()
            || (ctx.currentClass != null && ctx.currentClass.isUnsafe);
        if (!unsafe) {
            throw new ProgramError(featureName + " is only available inside unsafe class/method contexts");
        }
    }

    private TypeHandler.PointerValue createPointerFromIndexAccess(IndexAccess access, ExecutionContext ctx) {
        Object container = dispatcher.dispatch(access.array);
        container = typeSystem.unwrap(container);
        Object indexObj = dispatcher.dispatch(access.index);
        indexObj = typeSystem.unwrap(indexObj);

        if (container instanceof NaturalArray) {
            long idx = toLongIndex(indexObj);
            NaturalArray arr = (NaturalArray) container;
            if (idx < 0 || idx >= arr.size()) {
                throw new ProgramError("Pointer address index out of bounds: " + idx);
            }
            int pointedMask = typeSystem.getConcreteMask(arr.get(idx));
            return new TypeHandler.PointerValue(arr, idx, pointedMask);
        }

        if (container instanceof List) {
            long idx = toLongIndex(indexObj);
            List<?> list = (List<?>) container;
            if (idx < 0 || idx >= list.size()) {
                throw new ProgramError("Pointer address index out of bounds: " + idx);
            }
            Object pointedValue = list.get((int) idx);
            int pointedMask = typeSystem.getConcreteMask(pointedValue);
            return new TypeHandler.PointerValue(list, idx, pointedMask);
        }

        throw new ProgramError("Address-of operator '&' only supports array/list index targets");
    }

    private Object dereferencePointer(Object pointerObj) {
        Object unwrapped = typeSystem.unwrap(pointerObj);
        if (!(unwrapped instanceof TypeHandler.PointerValue)) {
            throw new ProgramError("Dereference '*' expects a pointer value");
        }
        TypeHandler.PointerValue pointer = (TypeHandler.PointerValue) unwrapped;
        if (pointer.container instanceof NaturalArray) {
            return ((NaturalArray) pointer.container).get(pointer.index);
        }
        if (pointer.container instanceof List) {
            List<?> list = (List<?>) pointer.container;
            int idx = (int) pointer.index;
            if (idx < 0 || idx >= list.size()) {
                throw new ProgramError("Pointer dereference out of bounds: " + idx);
            }
            return list.get(idx);
        }
        throw new ProgramError("Unsupported pointer target");
    }

    private Object handlePointerArithmetic(Object left, Object right, boolean addition, ExecutionContext ctx) {
        ensureUnsafeContext(ctx, "Pointer arithmetic");
        Object leftUnwrapped = typeSystem.unwrap(left);
        Object rightUnwrapped = typeSystem.unwrap(right);

        TypeHandler.PointerValue pointer;
        long offset;

        if (leftUnwrapped instanceof TypeHandler.PointerValue && !(rightUnwrapped instanceof TypeHandler.PointerValue)) {
            pointer = (TypeHandler.PointerValue) leftUnwrapped;
            offset = toLongIndex(rightUnwrapped);
        } else if (rightUnwrapped instanceof TypeHandler.PointerValue && !(leftUnwrapped instanceof TypeHandler.PointerValue) && addition) {
            pointer = (TypeHandler.PointerValue) rightUnwrapped;
            offset = toLongIndex(leftUnwrapped);
        } else {
            throw new ProgramError("Pointer arithmetic expects pointer +/- integer");
        }

        long nextIndex = addition ? pointer.index + offset : pointer.index - offset;
        if (pointer.container instanceof NaturalArray) {
            NaturalArray arr = (NaturalArray) pointer.container;
            if (nextIndex < 0 || nextIndex >= arr.size()) {
                throw new ProgramError("Pointer arithmetic out of bounds: " + nextIndex);
            }
        } else if (pointer.container instanceof List) {
            int size = ((List<?>) pointer.container).size();
            if (nextIndex < 0 || nextIndex >= size) {
                throw new ProgramError("Pointer arithmetic out of bounds: " + nextIndex);
            }
        }
        return new TypeHandler.PointerValue(pointer.container, nextIndex, pointer.pointedMask);
    }
    
    public Object handleTypeCast(TypeCast node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("handleTypeCast called with null node");
        }
        if (ctx == null) {
            throw new InternalError("handleTypeCast called with null context");
        }
        
        try {
            Object val = dispatcher.dispatch(node.expression);
            if (!typeSystem.validateType(node.targetType, val)) {
                return typeSystem.convertType(val, node.targetType);
            }
            return val;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Type cast failed to " + node.targetType, e);
        }
    }
    
    public Object handleBooleanChain(BooleanChain node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("handleBooleanChain called with null node");
        }
        if (ctx == null) {
            throw new InternalError("handleBooleanChain called with null context");
        }
        
        try {
            boolean isAll = node.isAll;

            for (Expr expr : node.expressions) {
                Object result = dispatcher.dispatch(expr);
                result = typeSystem.unwrap(result);
                boolean isTruthy = typeSystem.isTruthy(result);

                if (isAll) {
                    if (!isTruthy) return false;
                } else {
                    if (isTruthy) return true;
                }
            }

            return isAll;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Boolean chain evaluation failed", e);
        }
    }
    
    public Object handleChainedComparison(ChainedComparison node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("handleChainedComparison called with null node");
        }
        if (ctx == null) {
            throw new InternalError("handleChainedComparison called with null context");
        }
        
        try {
            List<Object> values = new ArrayList<Object>();
            for (Expr expr : node.expressions) {
                Object value = dispatcher.dispatch(expr);
                values.add(typeSystem.unwrap(value));
            }
            
            for (int i = 0; i < node.operators.size(); i++) {
                String op = node.operators.get(i);
                Object left = values.get(i);
                Object right = values.get(i + 1);
                
                boolean comparisonResult;
                
                switch (op) {
                    case "==":
                        comparisonResult = typeSystem.areEqual(left, right);
                        break;
                    case "!=":
                        comparisonResult = !typeSystem.areEqual(left, right);
                        break;
                    case ">":
                        comparisonResult = typeSystem.compare(left, right) > 0;
                        break;
                    case "<":
                        comparisonResult = typeSystem.compare(left, right) < 0;
                        break;
                    case ">=":
                        comparisonResult = typeSystem.compare(left, right) >= 0;
                        break;
                    case "<=":
                        comparisonResult = typeSystem.compare(left, right) <= 0;
                        break;
                    default:
                        throw new ProgramError("Unknown comparison operator in chain: " + op);
                }
                
                if (!comparisonResult) {
                    return false;
                }
            }
            
            return true;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Chained comparison evaluation failed", e);
        }
    }
    
    public Object handleEqualityChain(EqualityChain node, ExecutionContext ctx) {
        if (node == null) {
            throw new InternalError("handleEqualityChain called with null node");
        }
        if (ctx == null) {
            throw new InternalError("handleEqualityChain called with null context");
        }
        
        try {
            Object leftValue = dispatcher.dispatch(node.left);
            leftValue = typeSystem.unwrap(leftValue);

            for (Expr arg : node.chainArguments) {
                Object rightValue = dispatcher.dispatch(arg);
                rightValue = typeSystem.unwrap(rightValue);

                boolean comparisonResult;
                switch (node.operator) {
                    case "==":
                        comparisonResult = typeSystem.areEqual(leftValue, rightValue);
                        break;
                    case "!=":
                        comparisonResult = !typeSystem.areEqual(leftValue, rightValue);
                        break;
                    case ">":
                        comparisonResult = typeSystem.compare(leftValue, rightValue) > 0;
                        break;
                    case "<":
                        comparisonResult = typeSystem.compare(leftValue, rightValue) < 0;
                        break;
                    case ">=":
                        comparisonResult = typeSystem.compare(leftValue, rightValue) >= 0;
                        break;
                    case "<=":
                        comparisonResult = typeSystem.compare(leftValue, rightValue) <= 0;
                        break;
                    default:
                        throw new ProgramError(
                            "Unknown comparison operator in equality chain: " + node.operator);
                }

                if (node.isAllChain) {
                    if (!comparisonResult) {
                        return false;
                    }
                } else {
                    if (comparisonResult) {
                        return true;
                    }
                }
            }

            return node.isAllChain;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Equality chain evaluation failed", e);
        }
    }
    
    // === FAST IS OPERATOR (BITMASK) ===
    
    private Object handleIsOperator(Object leftValue, Object rightValue) {
        leftValue = typeSystem.unwrap(leftValue);
        rightValue = typeSystem.unwrap(rightValue);
        
        // FAST PATH 1: Right is precomputed integer mask
        if (rightValue instanceof Integer) {
            int expected = ((Integer) rightValue).intValue();
            int actual = typeSystem.getConcreteMask(leftValue);
            return (actual & expected) != 0;
        }
        
        // FAST PATH 2: Right is TypeHandler.Value (type value)
        if (rightValue instanceof TypeHandler.Value) {
            TypeHandler.Value tv = (TypeHandler.Value) rightValue;
            if (tv.isTypeValue()) {
                int actual = typeSystem.getConcreteMask(leftValue);
                return (actual & tv.declaredMask) != 0;
            }
        }
        
        // FAST PATH 3: Right is type literal string
        if (rightValue instanceof String) {
            String typeStr = (String) rightValue;
            if (typeSystem.isTypeLiteral(typeStr)) {
                int expected = TypeHandler.parseTypeMask(typeStr);
                int actual = typeSystem.getConcreteMask(leftValue);
                return (actual & expected) != 0;
            }
            // Check for union/complex types
            if (typeStr.startsWith("[") || typeStr.startsWith("(") || typeStr.contains("|")) {
                int expected = TypeHandler.parseTypeMask(typeStr);
                int actual = typeSystem.getConcreteMask(leftValue);
                return (actual & expected) != 0;
            }
        }
        
        // FAST PATH 4: Right is TextLiteral
        if (rightValue instanceof TextLiteral) {
            String typeStr = ((TextLiteral) rightValue).value;
            if (typeSystem.isTypeLiteral(typeStr)) {
                int expected = TypeHandler.parseTypeMask(typeStr);
                int actual = typeSystem.getConcreteMask(leftValue);
                return (actual & expected) != 0;
            }
            if (typeStr.startsWith("[") || typeStr.startsWith("(") || typeStr.contains("|")) {
                int expected = TypeHandler.parseTypeMask(typeStr);
                int actual = typeSystem.getConcreteMask(leftValue);
                return (actual & expected) != 0;
            }
        }
        
        // FALLBACK: Legacy equality
        return typeSystem.areEqual(leftValue, rightValue);
    }
    
    // === Type/Value Conversion ===
    
    public long toLong(Object obj) {
        try {
            if (obj instanceof AutoStackingNumber) {
                return ((AutoStackingNumber) obj).longValue();
            }
            if (obj instanceof IntLiteral) {
                return ((IntLiteral) obj).value.longValue();
            }
            if (obj instanceof Integer) return ((Integer) obj).longValue();
            if (obj instanceof Long) return (Long) obj;
            throw new ProgramError("Cannot convert to long: " + 
                (obj != null ? obj.getClass().getSimpleName() + " with value " + obj : "null"));
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Long conversion failed", e);
        }
    }
    
    public long toLongIndex(Object indexObj) {
        if (indexObj == null) {
            throw new ProgramError("Array index cannot be null");
        }

        try {
            if (indexObj instanceof AutoStackingNumber) {
                AutoStackingNumber num = (AutoStackingNumber) indexObj;
                try {
                    return num.longValue();
                } catch (ArithmeticException e) {
                    throw new ProgramError(
                        "Array index must be an exact integer, got: " + num);
                }
            }

            if (indexObj instanceof IntLiteral) {
                return ((IntLiteral) indexObj).value.longValue();
            }

            if (indexObj instanceof Integer) {
                return ((Integer) indexObj).longValue();
            }

            if (indexObj instanceof Long) {
                return (Long) indexObj;
            }

            if (indexObj instanceof Double || indexObj instanceof Float) {
                double d = ((Number) indexObj).doubleValue();
                if (d != Math.floor(d)) {
                    throw new ProgramError("Array index must be integer, got: Double (" + d + ")");
                }
                return (long) d;
            }
            
            if (RangeObjects.isRangeSpec(indexObj)) {
                throw new ProgramError("Cannot use range as index for single element access");
            }
            
            if (RangeObjects.isMultiRangeSpec(indexObj)) {
                throw new ProgramError("Cannot use multi-range as index for single element access");
            }

            throw new ProgramError(
                "Array index must be integer, got: " + 
                (indexObj != null ? indexObj.getClass().getSimpleName() : "null"));
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Index conversion failed", e);
        }
    }
    
    public int toIntIndex(Object indexObj) {
        if (indexObj == null) {
            throw new ProgramError("Array index cannot be null");
        }
        
        try {
            if (indexObj instanceof AutoStackingNumber) {
                return (int) ((AutoStackingNumber) indexObj).longValue();
            }
            
            if (indexObj instanceof IntLiteral) {
                long val = ((IntLiteral) indexObj).value.longValue();
                if (val > Integer.MAX_VALUE || val < Integer.MIN_VALUE) {
                    throw new ProgramError("Index out of range for integer array: " + val);
                }
                return (int) val;
            }
            
            if (indexObj instanceof Integer) return (Integer) indexObj;
            if (indexObj instanceof Long) {
                long val = (Long) indexObj;
                if (val > Integer.MAX_VALUE || val < Integer.MIN_VALUE) {
                    throw new ProgramError("Long index " + val + " cannot fit in int");
                }
                return (int) val;
            }

            throw new ProgramError("Array index must be integer, got: " +
                (indexObj != null ? indexObj.getClass().getSimpleName() : "null"));
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Int index conversion failed", e);
        }
    }
    
    public long calculateStep(Object range) {
        if (range == null) {
            throw new InternalError("calculateStep called with null range");
        }
        
        try {
            Object step = RangeObjects.getStep(range);
            Object startObj = RangeObjects.getStart(range);
            Object endObj = RangeObjects.getEnd(range);
            if (step != null) {
                return toLongIndex(step);
            }
            long start = toLongIndex(startObj);
            long end = toLongIndex(endObj);
            if (start == end) return 1L;
            return (start < end) ? 1L : -1L;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Step calculation failed", e);
        }
    }
}