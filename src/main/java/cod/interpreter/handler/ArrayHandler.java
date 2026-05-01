package cod.interpreter.handler;

import cod.ast.node.*;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.interpreter.InterpreterVisitor;
import cod.interpreter.exception.BreakLoopException;
import cod.interpreter.exception.SkipIterationException;
import cod.math.AutoStackingNumber;
import cod.range.NaturalArray;
import cod.range.RangeObjects;

import java.util.ArrayList;
import java.util.List;

public class ArrayHandler {
    private static final class LoopVariableBinding {
        final String name;
        final int valueScopeIndex;
        final int typeScopeIndex;
        boolean typeInitialized;

        LoopVariableBinding(String name, int valueScopeIndex, int typeScopeIndex, boolean typeInitialized) {
            this.name = name;
            this.valueScopeIndex = valueScopeIndex;
            this.typeScopeIndex = typeScopeIndex;
            this.typeInitialized = typeInitialized;
        }
    }

    private final InterpreterVisitor dispatcher;
    private final TypeHandler typeSystem;
    private final ExpressionHandler exprHandler;
    private final ContextHandler ctxHandler;

    public ArrayHandler(
        InterpreterVisitor dispatcher,
        TypeHandler typeSystem,
        ExpressionHandler exprHandler,
        ContextHandler ctxHandler) {
        if (dispatcher == null) throw new InternalError("ArrayHandler dispatcher is null");
        if (typeSystem == null) throw new InternalError("ArrayHandler typeSystem is null");
        if (exprHandler == null) throw new InternalError("ArrayHandler exprHandler is null");
        if (ctxHandler == null) throw new InternalError("ArrayHandler ctxHandler is null");
        this.dispatcher = dispatcher;
        this.typeSystem = typeSystem;
        this.exprHandler = exprHandler;
        this.ctxHandler = ctxHandler;
    }

    public Object executeForLoopNormally(For node) {
        if (node == null) {
            throw new InternalError("executeForLoopNormally called with null node");
        }

        cod.interpreter.context.ExecutionContext ctx = dispatcher.getCurrentContext();
        String iter = node.iterator;

        try {
            if (node.range != null) {
                return executeRangeLoop(ctx, node, iter);
            } else if (node.arraySource != null) {
                Object arrayObj = dispatcher.dispatch(node.arraySource);
                arrayObj = typeSystem.unwrap(arrayObj);
                return executeArrayLoop(ctx, node, iter, arrayObj);
            }
            throw new ProgramError("Invalid for loop");
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Normal loop execution failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Object executeArrayLoop(
        cod.interpreter.context.ExecutionContext ctx, For node, String iter, Object arrayObj) {
        try {
            LoopVariableBinding binding = resolveLoopVariableBinding(ctx, iter);
            if (arrayObj instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) arrayObj;
                long size = natural.size();
                for (long i = 0; i < size; i++) {
                    Object currentValue = natural.get(i);
                    writeLoopVariable(ctx, binding, currentValue);
                    try {
                        executeLoopBody(ctx, node);
                    } catch (BreakLoopException e) {
                        break;
                    }
                }
            } else if (arrayObj instanceof List) {
                List<Object> list = (List<Object>) arrayObj;
                for (Object currentValue : list) {
                    writeLoopVariable(ctx, binding, currentValue);
                    try {
                        executeLoopBody(ctx, node);
                    } catch (BreakLoopException e) {
                        break;
                    }
                }
            } else {
                throw new ProgramError("Cannot iterate over: " +
                    (arrayObj != null ? arrayObj.getClass().getSimpleName() : "null"));
            }
            return null;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Array loop execution failed", e);
        }
    }

    public Object executeRangeLoop(cod.interpreter.context.ExecutionContext ctx, For node, String iter) {
        try {
            Object startObj = dispatcher.dispatch(node.range.start);
            Object endObj = dispatcher.dispatch(node.range.end);
            startObj = typeSystem.unwrap(startObj);
            endObj = typeSystem.unwrap(endObj);

            if (node.range.step != null && node.range.step instanceof BinaryOp) {
                BinaryOp binOp = (BinaryOp) node.range.step;
                if (binOp.left instanceof Identifier
                    && ((Identifier) binOp.left).name.equals(iter)
                    && (binOp.op.equals("*") || binOp.op.equals("/"))) {
                    LoopVariableBinding loopBinding = resolveLoopVariableBinding(ctx, iter);
                    Object rightObj = dispatcher.dispatch(binOp.right);
                    rightObj = typeSystem.unwrap(rightObj);
                    AutoStackingNumber factor = typeSystem.toAutoStackingNumber(rightObj);
                    validateFactor(factor, binOp.op);
                    return executeMultiplicativeLoop(ctx, node, startObj, endObj, factor, binOp.op, loopBinding);
                }
            }

            AutoStackingNumber step;
            LoopVariableBinding loopBinding = resolveLoopVariableBinding(ctx, iter);
            if (node.range.step != null) {
                Object stepObj = dispatcher.dispatch(node.range.step);
                step = typeSystem.toAutoStackingNumber(typeSystem.unwrap(stepObj));
            } else {
                AutoStackingNumber start = typeSystem.toAutoStackingNumber(startObj);
                AutoStackingNumber end = typeSystem.toAutoStackingNumber(endObj);
                step = (start.compareTo(end) > 0) ? AutoStackingNumber.minusOne(1) : AutoStackingNumber.one(1);
            }

            if (step.isZero()) {
                throw new ProgramError("Loop step cannot be zero.");
            }

            Long startLong = tryFastLongValue(startObj);
            Long endLong = tryFastLongValue(endObj);
            Long stepLong = tryFastLongValue(step);
            if (startLong != null && endLong != null && stepLong != null
                && canUsePrimitiveAdditiveLoop(startLong.longValue(), endLong.longValue(), stepLong.longValue())) {
                return executeAdditiveLoopPrimitive(
                    ctx, node, startLong.longValue(), endLong.longValue(), stepLong.longValue(), loopBinding);
            }

            return executeAdditiveLoop(ctx, node, startObj, endObj, step, loopBinding);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Range loop execution failed", e);
        }
    }

    public Object executeAdditiveLoop(
        cod.interpreter.context.ExecutionContext ctx,
        For node,
        Object startObj,
        Object endObj,
        AutoStackingNumber step,
        LoopVariableBinding loopBinding) {
        try {
            AutoStackingNumber start = typeSystem.toAutoStackingNumber(startObj);
            AutoStackingNumber end = typeSystem.toAutoStackingNumber(endObj);
            AutoStackingNumber current = start;
            boolean increasing = step.isPositive();

            while (shouldContinueAdditive(current, end, step, increasing)) {
                try {
                    executeIteration(ctx, node, current, startObj, loopBinding);
                } catch (BreakLoopException e) {
                    break;
                }
                current = current.add(step);
            }
            return null;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Additive loop execution failed", e);
        }
    }

    public Object executeMultiplicativeLoop(
        cod.interpreter.context.ExecutionContext ctx,
        For node,
        Object startObj,
        Object endObj,
        AutoStackingNumber factor,
        String operation,
        LoopVariableBinding loopBinding) {
        try {
            AutoStackingNumber start = typeSystem.toAutoStackingNumber(startObj);
            AutoStackingNumber end = typeSystem.toAutoStackingNumber(endObj);
            AutoStackingNumber current = start;

            while (shouldContinueMultiplicative(current, start, end, factor, operation)) {
                try {
                    executeIteration(ctx, node, current, startObj, loopBinding);
                } catch (BreakLoopException e) {
                    break;
                }
                if (operation.equals("*")) {
                    current = current.multiply(factor);
                } else {
                    current = current.divide(factor);
                }
            }
            return null;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Multiplicative loop execution failed", e);
        }
    }

    public void executeIteration(
        cod.interpreter.context.ExecutionContext ctx,
        For node,
        AutoStackingNumber current,
        Object startObj,
        LoopVariableBinding loopBinding) {
        try {
            Object currentValue = convertToAppropriateType(current, startObj);
            writeLoopVariable(ctx, loopBinding, currentValue);
            if (!loopBinding.typeInitialized) {
                String inferredType = (current.fitsInStacks(1) &&
                    (current.getWords()[0] & 0x7FFFFFFFFFFFFFFFL) < Long.MAX_VALUE)
                    ? cod.lexer.TokenType.Keyword.INT.toString() : cod.lexer.TokenType.Keyword.FLOAT.toString();
                ctx.setVariableTypeAtScope(loopBinding.typeScopeIndex, loopBinding.name, inferredType);
                loopBinding.typeInitialized = true;
            }
            executeLoopBody(ctx, node);
        } catch (BreakLoopException e) {
            throw e;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Loop iteration failed", e);
        }
    }

    public void executePrimitiveIteration(
        cod.interpreter.context.ExecutionContext ctx,
        For node,
        long current,
        LoopVariableBinding loopBinding) {
        try {
            Object currentValue = (current >= Integer.MIN_VALUE && current <= Integer.MAX_VALUE)
                ? Integer.valueOf((int) current)
                : Long.valueOf(current);
            writeLoopVariable(ctx, loopBinding, currentValue);
            if (!loopBinding.typeInitialized) {
                ctx.setVariableTypeAtScope(loopBinding.typeScopeIndex, loopBinding.name, cod.lexer.TokenType.Keyword.INT.toString());
                loopBinding.typeInitialized = true;
            }
            executeLoopBody(ctx, node);
        } catch (BreakLoopException e) {
            throw e;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Primitive loop iteration failed", e);
        }
    }

    public Object executeAdditiveLoopPrimitive(
        cod.interpreter.context.ExecutionContext ctx,
        For node,
        long start,
        long end,
        long step,
        LoopVariableBinding loopBinding) {
        try {
            long current = start;
            if (step > 0L) {
                while (current <= end) {
                    try {
                        executePrimitiveIteration(ctx, node, current, loopBinding);
                    } catch (BreakLoopException e) {
                        break;
                    }
                    if (current == end) {
                        break;
                    }
                    long next = current + step;
                    if (next < current) {
                        break;
                    }
                    current = next;
                }
            } else {
                while (current >= end) {
                    try {
                        executePrimitiveIteration(ctx, node, current, loopBinding);
                    } catch (BreakLoopException e) {
                        break;
                    }
                    if (current == end) {
                        break;
                    }
                    long next = current + step;
                    if (next > current) {
                        break;
                    }
                    current = next;
                }
            }
            return null;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Primitive additive loop execution failed", e);
        }
    }

    public void executeLoopBody(cod.interpreter.context.ExecutionContext ctx, For node) {
        try {
            for (Stmt s : node.body.statements) {
                try {
                    dispatcher.dispatch(s);
                } catch (SkipIterationException e) {
                    break;
                } catch (BreakLoopException e) {
                    throw e;
                }

                if (!ctx.slotsInCurrentPath.isEmpty()
                    && dispatcher.shouldReturnEarly(ctx.getSlotValues(), ctx.slotsInCurrentPath)) return;
            }
        } catch (BreakLoopException e) {
            throw e;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Loop body execution failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Object visitIndexAccess(IndexAccess node) {
        if (node == null) {
            throw new InternalError("visit(IndexAccess) called with null node");
        }

        try {
            Object arrayObj = dispatcher.dispatch(node.array);
            arrayObj = typeSystem.unwrap(arrayObj);

            if (arrayObj instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) arrayObj;
                if (natural.hasPendingUpdates()) {
                    natural.commitUpdates();
                }
            }

            Object indexObj = dispatcher.dispatch(node.index);
            indexObj = typeSystem.unwrap(indexObj);

            if (indexObj instanceof List) {
                return applyTupleIndices(arrayObj, (List<?>) indexObj);
            }

            if (RangeObjects.isRangeSpec(indexObj)) {
                if (arrayObj instanceof String) {
                    return applyStringRangeIndex((String) arrayObj, indexObj);
                }
                return applyRangeIndex(arrayObj, indexObj);
            }

            if (RangeObjects.isMultiRangeSpec(indexObj)) {
                return applyMultiRangeIndex(arrayObj, indexObj);
            }

            if (arrayObj instanceof String) {
                String text = (String) arrayObj;
                int index = exprHandler.toIntIndex(indexObj);
                index = normalizeTextIndex(index, text.length());
                if (index < 0 || index >= text.length()) {
                    throw new ProgramError(
                        "Index out of bounds: " + index + " for text of length " + text.length());
                }
                return String.valueOf(text.charAt(index));
            }

            if (arrayObj instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) arrayObj;
                long index = exprHandler.toLongIndex(indexObj);

                if (natural.needsConversion()) {
                    return natural.get(index, true);
                }
                return natural.get(index);
            }

            if (arrayObj instanceof List) {
                List<Object> list = (List<Object>) arrayObj;
                if (indexObj instanceof AutoStackingNumber) {
                    int index = (int) ((AutoStackingNumber) indexObj).longValue();
                    if (index < 0 || index >= list.size()) {
                        throw new ProgramError(
                            "Index out of bounds: " + index + " for array of size " + list.size());
                    }
                    return list.get(index);
                } else {
                    int index = exprHandler.toIntIndex(indexObj);
                    if (index < 0 || index >= list.size()) {
                        throw new ProgramError(
                            "Index out of bounds: " + index + " for array of size " + list.size());
                    }
                    return list.get(index);
                }
            }

            throw new ProgramError(
                "Invalid array access: expected NaturalArray or List, got "
                    + (arrayObj != null ? arrayObj.getClass().getSimpleName() : "null"));
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Index access failed", e);
        }
    }

    public Object visitRangeIndex(RangeIndex node) {
        if (node == null) {
            throw new InternalError("visit(RangeIndex) called with null node");
        }

        try {
            Object step = node.step != null ? dispatcher.dispatch(node.step) : null;
            Object start = dispatcher.dispatch(node.start);
            Object end = dispatcher.dispatch(node.end);

            return RangeObjects.createRangeSpec(ctxHandler.resolveInternalRangeSpecType(), step, start, end);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Range index creation failed", e);
        }
    }

    public Object visitMultiRangeIndex(MultiRangeIndex node) {
        if (node == null) {
            throw new InternalError("visit(MultiRangeIndex) called with null node");
        }

        try {
            List<Object> ranges = new ArrayList<Object>();
            for (RangeIndex rangeNode : node.ranges) {
                Object range = visitRangeIndex(rangeNode);
                if (!RangeObjects.isRangeSpec(range)) {
                    throw new InternalError("Multi-range index contains non-range value");
                }
                ranges.add(range);
            }
            return RangeObjects.createMultiRangeSpec(ctxHandler.resolveInternalMultiRangeSpecType(), ranges);
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Multi-range index creation failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Object applyRangeIndex(Object array, Object range) {
        if (array instanceof NaturalArray) {
            NaturalArray natural = (NaturalArray) array;
            return natural.getRange(range);
        } else if (array instanceof List) {
            List<Object> list = (List<Object>) array;
            return getListRange(list, range);
        }
        throw new ProgramError("Cannot apply range index to " +
            (array != null ? array.getClass().getSimpleName() : "null"));
    }

    @SuppressWarnings("unchecked")
    public Object applyMultiRangeIndex(Object array, Object multiRange) {
        if (array instanceof NaturalArray) {
            NaturalArray natural = (NaturalArray) array;
            return natural.getMultiRange(multiRange);
        } else if (array instanceof List) {
            List<Object> list = (List<Object>) array;
            return getListMultiRange(list, multiRange);
        }
        throw new ProgramError("Cannot apply multi-range index to " +
            (array != null ? array.getClass().getSimpleName() : "null"));
    }

    @SuppressWarnings("unchecked")
    public Object applyTupleIndices(Object array, List<?> indices) {
        Object current = array;
        for (Object rawIndex : indices) {
            Object indexObj = typeSystem.unwrap(rawIndex);
            if (RangeObjects.isRangeSpec(indexObj)) {
                current = applyRangeIndex(current, indexObj);
                continue;
            }
            if (RangeObjects.isMultiRangeSpec(indexObj)) {
                current = applyMultiRangeIndex(current, indexObj);
                continue;
            }
            if (current instanceof NaturalArray) {
                NaturalArray natural = (NaturalArray) current;
                long idx = exprHandler.toLongIndex(indexObj);
                current = natural.needsConversion() ? natural.get(idx, true) : natural.get(idx);
                continue;
            }
            if (current instanceof List) {
                List<Object> list = (List<Object>) current;
                int idx = exprHandler.toIntIndex(indexObj);
                if (idx < 0 || idx >= list.size()) {
                    throw new ProgramError("Index out of bounds: " + idx + " for array of size " + list.size());
                }
                current = list.get(idx);
                continue;
            }
            throw new ProgramError("Invalid array access during multidimensional indexing: expected NaturalArray or List, got "
                + (current != null ? current.getClass().getSimpleName() : "null"));
        }
        return current;
    }

    public List<Object> getListRange(List<Object> list, Object range) {
        try {
            long start, end;

            start = exprHandler.toLongIndex(RangeObjects.getStart(range));
            if (start < 0) start = list.size() + start;

            end = exprHandler.toLongIndex(RangeObjects.getEnd(range));
            if (end < 0) end = list.size() + end;

            long step = exprHandler.calculateStep(range);

            List<Object> result = new ArrayList<Object>();
            if (step > 0) {
                for (long i = start; i <= end && i < list.size(); i += step) {
                    result.add(list.get((int) i));
                }
            } else if (step < 0) {
                for (long i = start; i >= end && i >= 0; i += step) {
                    result.add(list.get((int) i));
                }
            } else {
                throw new InternalError("Step cannot be zero - should have been caught earlier");
            }
            return result;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("List range extraction failed", e);
        }
    }

    public List<Object> getListMultiRange(List<Object> list, Object multiRange) {
        try {
            List<Object> result = new ArrayList<Object>();
            for (Object range : RangeObjects.getRanges(multiRange)) {
                result.addAll(getListRange(list, range));
            }
            return result;
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("List multi-range extraction failed", e);
        }
    }

    public String applyStringRangeIndex(String text, Object range) {
        try {
            long start = exprHandler.toLongIndex(RangeObjects.getStart(range));
            long end = exprHandler.toLongIndex(RangeObjects.getEnd(range));
            long step = exprHandler.calculateStep(range);

            int length = text.length();
            start = normalizeTextIndex(start, length);
            end = normalizeTextIndex(end, length);

            if (start < 0 || start >= length) {
                throw new ProgramError("Range start index out of bounds: " + start + " for text of length " + length);
            }
            if (end < 0 || end >= length) {
                throw new ProgramError("Range end index out of bounds: " + end + " for text of length " + length);
            }
            if (step == 0) {
                throw new ProgramError("Range step cannot be zero");
            }

            StringBuilder result = new StringBuilder();
            if (step > 0) {
                for (long i = start; i <= end; i += step) {
                    result.append(text.charAt((int) i));
                }
            } else {
                for (long i = start; i >= end; i += step) {
                    result.append(text.charAt((int) i));
                }
            }
            return result.toString();
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("String range extraction failed", e);
        }
    }

    public int normalizeTextIndex(int index, int length) {
        return (int) normalizeTextIndex((long) index, length);
    }

    public long normalizeTextIndex(long index, int length) {
        if (index < 0) {
            return length + index;
        }
        return index;
    }

    public long calculateRangeStep(Range range) {
        if (range == null) {
            return 1L;
        }

        if (range.step != null) {
            Object stepObj = dispatcher.dispatch(range.step);
            return exprHandler.toLong(stepObj);
        }

        Object startObj = dispatcher.dispatch(range.start);
        Object endObj = dispatcher.dispatch(range.end);
        long start = exprHandler.toLong(startObj);
        long end = exprHandler.toLong(endObj);

        return (start < end) ? 1L : -1L;
    }

    private boolean shouldContinueAdditive(
        AutoStackingNumber current, AutoStackingNumber end, AutoStackingNumber step, boolean increasing) {
        return increasing ? current.compareTo(end) <= 0 : current.compareTo(end) >= 0;
    }

    private Long tryFastLongValue(Object value) {
        Object unwrapped = typeSystem.unwrap(value);
        if (unwrapped instanceof Integer || unwrapped instanceof Long
            || unwrapped instanceof Short || unwrapped instanceof Byte) {
            return Long.valueOf(((Number) unwrapped).longValue());
        }
        if (unwrapped instanceof IntLiteral) {
            try {
                return Long.valueOf(((IntLiteral) unwrapped).value.longValue());
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (unwrapped instanceof AutoStackingNumber) {
            AutoStackingNumber asn = (AutoStackingNumber) unwrapped;
            try {
                return Long.valueOf(asn.longValue());
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean canUsePrimitiveAdditiveLoop(long start, long end, long step) {
        if (step == 0L) {
            return false;
        }
        // Long.MIN_VALUE is excluded because it cannot be safely negated (would overflow)
        // and makes overflow-safe progression checks ambiguous.
        if (step == Long.MIN_VALUE) {
            return false;
        }
        if (step > 0L && start > end) {
            return false;
        }
        if (step < 0L && start < end) {
            return false;
        }
        return true;
    }

    private LoopVariableBinding resolveLoopVariableBinding(
        cod.interpreter.context.ExecutionContext ctx, String iter) {
        int valueScopeIndex = ctx.resolveVariableScopeIndex(iter);
        int typeScopeIndex = ctx.resolveVariableTypeScopeIndex(iter);
        String existingType = ctx.getVariableTypeAtScope(typeScopeIndex, iter);
        return new LoopVariableBinding(iter, valueScopeIndex, typeScopeIndex, existingType != null);
    }

    private void writeLoopVariable(
        cod.interpreter.context.ExecutionContext ctx, LoopVariableBinding binding, Object value) {
        ctx.setVariableAtScope(binding.valueScopeIndex, binding.name, value);
    }

    private void validateFactor(AutoStackingNumber factor, String operation) {
        if (factor.compareTo(AutoStackingNumber.zero(1)) <= 0) {
            throw new ProgramError("Factor must be positive");
        }
    }

    private boolean shouldContinueMultiplicative(
        AutoStackingNumber current, AutoStackingNumber start, AutoStackingNumber end,
        AutoStackingNumber factor, String operation) {
        int startEndComparison = start.compareTo(end);
        if (operation.equals("*")) {
            return factor.compareTo(AutoStackingNumber.one(1)) > 0
                ? (startEndComparison < 0 ? current.compareTo(end) <= 0 : current.compareTo(end) >= 0)
                : (startEndComparison > 0 ? current.compareTo(end) >= 0 : current.compareTo(end) <= 0);
        } else {
            return factor.compareTo(AutoStackingNumber.one(1)) > 0
                ? (startEndComparison > 0 ? current.compareTo(end) >= 0 : current.compareTo(end) <= 0)
                : (startEndComparison < 0 ? current.compareTo(end) <= 0 : current.compareTo(end) >= 0);
        }
    }

    private Object convertToAppropriateType(AutoStackingNumber value, Object original) {
        if ((original instanceof Integer || original instanceof Long ||
             original instanceof IntLiteral) && value.fitsInStacks(1)) {
            try {
                return (int) value.longValue();
            } catch (ArithmeticException e) {
                return value.longValue();
            }
        }
        return value;
    }
}
