package cod.interpreter.handler;

import cod.ast.ASTFactory;
import cod.ast.node.*;
import cod.debug.DebugSystem;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.interpreter.InterpreterVisitor;
import cod.interpreter.TailCallSignal;
import cod.interpreter.context.ExecutionContext;
import cod.math.AutoStackingNumber;
import cod.range.ArrayTracker;
import cod.range.NaturalArray;
import cod.range.pattern.ConditionalPattern;
import cod.range.pattern.OutputAwarePattern;
import cod.range.pattern.SequencePattern;
import cod.range.pattern.AccumulationPattern;
import cod.range.formula.AccumulationFormula;
import cod.range.formula.ConditionalFormula;
import cod.range.formula.SequenceFormula;

import java.util.*;

public class LoopHandler {
    private static final int LAZY_THRESHOLD = 10;
    private static final int MAX_SUPPORTED_LAG = 64;
    private static final int MIN_VECTOR_SEQUENCES = 2;
    private static final int MAX_VECTOR_SEQUENCES = 64;
    private static final AutoStackingNumber ZERO = AutoStackingNumber.fromLong(0);
    private static final AutoStackingNumber ONE = AutoStackingNumber.fromLong(1);
    
    private final InterpreterVisitor dispatcher;
    private final TypeHandler typeSystem;
    private final ExpressionHandler exprHandler;
    private final ArrayHandler arrHandler;
    private final PatternHandler patternHandler;

    public LoopHandler(
        InterpreterVisitor dispatcher,
        TypeHandler typeSystem,
        ExpressionHandler exprHandler,
        ArrayHandler arrHandler,
        PatternHandler patternHandler) {
        if (dispatcher == null) throw new InternalError("LoopHandler dispatcher is null");
        if (typeSystem == null) throw new InternalError("LoopHandler typeSystem is null");
        if (exprHandler == null) throw new InternalError("LoopHandler exprHandler is null");
        if (arrHandler == null) throw new InternalError("LoopHandler arrHandler is null");
        if (patternHandler == null) throw new InternalError("LoopHandler patternHandler is null");
        this.dispatcher = dispatcher;
        this.typeSystem = typeSystem;
        this.exprHandler = exprHandler;
        this.arrHandler = arrHandler;
        this.patternHandler = patternHandler;
    }

    public Object executeForLoop(For node) {
        if (node == null) {
            throw new InternalError("visit(For) called with null node");
        }

        ExecutionContext ctx = dispatcher.getCurrentContext();
        int originalDepth = ctx.getScopeDepth();

        long loopSize = estimateLoopSize(node, ctx);
        boolean hasSideEffects = hasSideEffects(node.body);

        boolean useLazyExecution = shouldUseLazyExecution(loopSize, hasSideEffects);

        int loopId = ArrayTracker.beginLoop(node);

        ArrayTracker.setLoopSize(loopId, loopSize);
        ArrayTracker.setSideEffects(loopId, hasSideEffects);

        try {
            ctx.pushScope();

            if (useLazyExecution) {
                Object result = tryOptimizedExecution(node, loopId);
                if (result != null) {
                    return result;
                }
            } else {
                DebugSystem.debug("LOOP",
                    String.format("Skipping optimization: size=%d, sideEffects=%s",
                        loopSize, hasSideEffects));
            }

            ArrayTracker.incrementIteration();

            if (node.range != null) {
                return arrHandler.executeRangeLoop(ctx, node, node.iterator);
            } else if (node.arraySource != null) {
                Object arrayObj = dispatcher.dispatch(node.arraySource);
                arrayObj = typeSystem.unwrap(arrayObj);
                return arrHandler.executeArrayLoop(ctx, node, node.iterator, arrayObj);
            }
            throw new ProgramError("Invalid for loop: neither range nor array source specified");

        } catch (ProgramError e) {
            throw e;
        } catch (TailCallSignal e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("For loop execution failed", e);
        } finally {
            ArrayTracker.LoopStats stats = ArrayTracker.endLoop();
            if (stats != null) {
                DebugSystem.debug("LOOP", stats.toString());
            }

            while (ctx.getScopeDepth() > originalDepth) {
                ctx.popScope();
            }
        }
    }

    public boolean shouldUseLazyExecution(long loopSize, boolean hasSideEffects) {
        if (loopSize < 0) {
            return false;
        }

        if (loopSize < LAZY_THRESHOLD) {
            return !hasSideEffects;
        }

        return true;
    }

    public long estimateLoopSize(For node, ExecutionContext ctx) {
        try {
            if (node.range != null) {
                Object startObj = dispatcher.dispatch(node.range.start);
                Object endObj = dispatcher.dispatch(node.range.end);

                startObj = typeSystem.unwrap(startObj);
                endObj = typeSystem.unwrap(endObj);

                AutoStackingNumber start = typeSystem.toAutoStackingNumber(startObj);
                AutoStackingNumber end = typeSystem.toAutoStackingNumber(endObj);

                AutoStackingNumber step;
                if (node.range.step != null) {
                    Object stepObj = dispatcher.dispatch(node.range.step);
                    step = typeSystem.toAutoStackingNumber(typeSystem.unwrap(stepObj));
                } else {
                    step = (start.compareTo(end) > 0) ?
                        AutoStackingNumber.minusOne(1) : AutoStackingNumber.one(1);
                }

                if (step.isZero()) return 0;

                AutoStackingNumber diff = end.subtract(start);
                AutoStackingNumber steps = diff.divide(step);
                AutoStackingNumber size = steps.add(AutoStackingNumber.one(1));

                return size.longValue();

            } else if (node.arraySource != null) {
                Object arrayObj = dispatcher.dispatch(node.arraySource);
                arrayObj = typeSystem.unwrap(arrayObj);

                if (arrayObj instanceof NaturalArray) {
                    NaturalArray arr = (NaturalArray) arrayObj;
                    if (arr.hasPendingUpdates()) {
                        arr.commitUpdates();
                    }
                    return arr.size();
                } else if (arrayObj instanceof List) {
                    return ((List<?>) arrayObj).size();
                }
            }
        } catch (Exception e) {
            DebugSystem.debug("LOOP", "Failed to estimate size: " + e.getMessage());
        }

        return -1;
    }

    public boolean hasSideEffects(Block body) {
        if (body == null || body.statements == null) return false;

        for (Stmt stmt : body.statements) {
            if (stmt instanceof MethodCall) {
                MethodCall call = (MethodCall) stmt;
                if ("out".equals(call.name) || "outs".equals(call.name) || "in".equals(call.name)) {
                    return true;
                }
                return true;
            }

            if (stmt instanceof StmtIf) {
                StmtIf ifStmt = (StmtIf) stmt;
                if (hasSideEffects(ifStmt.thenBlock) || hasSideEffects(ifStmt.elseBlock)) {
                    return true;
                }
            }

            if (stmt instanceof For) {
                return true;
            }

            if (stmt instanceof Assignment) {
                Assignment assign = (Assignment) stmt;
                if (assign.left instanceof PropertyAccess) {
                    return true;
                }
            }
        }

        return false;
    }

    public Object tryOptimizedExecution(For node, int loopId) {
        OutputAwarePattern.OutputPattern outputPattern =
            OutputAwarePattern.extract(node, node.iterator);

        if (outputPattern.isOptimizable) {
            try {
                Object result = executeOutputAwareLoop(node, outputPattern);
                ArrayTracker.markLoopOptimized(loopId);
                return result;
            } catch (Exception e) {
                DebugSystem.debug("OPTIMIZER", "Output pattern failed: " + e.getMessage());
            }
        }

        List<PatternHandler.PatternResult> vectorRecurrencePatterns = extractVectorLinearRecurrencePatterns(node);
        if (!vectorRecurrencePatterns.isEmpty()) {
            try {
                Object result = patternHandler.applyPatterns(node, vectorRecurrencePatterns);
                ArrayTracker.markLoopOptimized(loopId);
                return result;
            } catch (Exception e) {
                DebugSystem.debug("OPTIMIZER", "Vector recurrence pattern failed: " + e.getMessage());
            }
        }

        List<PatternHandler.PatternResult> multiArrayPatterns = extractMultiArraySequencePatterns(node);
        if (!multiArrayPatterns.isEmpty()) {
            try {
                Object result = patternHandler.applyPatterns(node, multiArrayPatterns);
                ArrayTracker.markLoopOptimized(loopId);
                return result;
            } catch (Exception e) {
                DebugSystem.debug("OPTIMIZER", "Multi-array pattern failed: " + e.getMessage());
            }
        }

        // Extract linear recurrence as AccumulationPattern (FREC type)
        AccumulationPattern recurrencePattern = extractLinearRecurrencePattern(node);
        if (recurrencePattern != null && recurrencePattern.isOptimizable) {
            try {
                List<PatternHandler.PatternResult> patterns = new ArrayList<PatternHandler.PatternResult>();
                patterns.add(new PatternHandler.PatternResult(
                    PatternHandler.PatternType.ARRAY_RECURRENCE, 
                    recurrencePattern, 
                    recurrencePattern.stateVars.length > 0 ? null : null
                ));
                Object result = patternHandler.applyPatterns(node, patterns);
                ArrayTracker.markLoopOptimized(loopId);
                return result;
            } catch (Exception e) {
                DebugSystem.debug("OPTIMIZER", "Linear recurrence pattern failed: " + e.getMessage());
            }
        }

        SequencePattern.Pattern seqPattern =
            SequencePattern.extract(node.body.statements, node.iterator);
        if (seqPattern != null && seqPattern.isOptimizable()) {
            try {
                List<PatternHandler.PatternResult> patterns = new ArrayList<PatternHandler.PatternResult>();
                patterns.add(new PatternHandler.PatternResult(PatternHandler.PatternType.SEQUENCE, seqPattern, seqPattern.targetArray));
                Object result = patternHandler.applyPatterns(node, patterns);
                ArrayTracker.markLoopOptimized(loopId);
                return result;
            } catch (Exception e) {
                DebugSystem.debug("OPTIMIZER", "Sequence pattern failed: " + e.getMessage());
            }
        }

        List<PatternHandler.PatternResult> allPatterns = new ArrayList<PatternHandler.PatternResult>();
        for (Stmt stmt : node.body.statements) {
            if (stmt instanceof StmtIf) {
                StmtIf ifStmt = (StmtIf) stmt;
                List<ConditionalPattern> patterns = extractConditionalPatterns(ifStmt, node.iterator);
                for (ConditionalPattern pattern : patterns) {
                    if (pattern != null && pattern.isOptimizable()) {
                        allPatterns.add(new PatternHandler.PatternResult(PatternHandler.PatternType.CONDITIONAL, pattern, pattern.array));
                    }
                }
            }
        }

        if (!allPatterns.isEmpty()) {
            try {
                Object result = patternHandler.applyPatterns(node, allPatterns);
                ArrayTracker.markLoopOptimized(loopId);
                return result;
            } catch (Exception e) {
                DebugSystem.debug("OPTIMIZER", "Conditional pattern failed: " + e.getMessage());
            }
        }
        AccumulationPattern accPattern = AccumulationPattern.extract(node);

        if (accPattern != null && accPattern.isOptimizable) {
            try {
                Object result = executeAccumulation(node, accPattern, dispatcher.getCurrentContext());
                return result;
            } catch (Exception e) {
            }
        }        
        return null;
    }

    // ========== SCALAR ACCUMULATION METHODS ==========

    private AutoStackingNumber evaluateBoundAsAutoStacking(Expr bound, ExecutionContext ctx) {
        if (bound == null) {
            return ZERO;
        }
        try {
            Object val = dispatcher.dispatch(bound);
            val = typeSystem.unwrap(val);
            return typeSystem.toAutoStackingNumber(val);
        } catch (Exception e) {
            DebugSystem.debug("OPTIMIZER", "Failed to evaluate bound: " + e.getMessage());
            return ZERO;
        }
    }

    private AutoStackingNumber evaluateStepAsAutoStacking(Expr step, ExecutionContext ctx) {
        if (step == null) {
            return ONE;
        }
        try {
            Object val = dispatcher.dispatch(step);
            val = typeSystem.unwrap(val);
            return typeSystem.toAutoStackingNumber(val);
        } catch (Exception e) {
            return ONE;
        }
    }

    private AutoStackingNumber calculateStepFromNodeAsAutoStacking(For node, ExecutionContext ctx) {
        if (node.range != null && node.range.step != null) {
            return evaluateStepAsAutoStacking(node.range.step, ctx);
        }
        if (node.range != null) {
            AutoStackingNumber start = evaluateBoundAsAutoStacking(node.range.start, ctx);
            AutoStackingNumber end = evaluateBoundAsAutoStacking(node.range.end, ctx);
            return start.compareTo(end) <= 0 ? ONE : AutoStackingNumber.minusOne(1);
        }
        return ONE;
    }

    private Object convertToOriginalType(AutoStackingNumber value, Object original) {
        if (original instanceof Integer) {
            return Integer.valueOf((int) value.longValue());
        }
        if (original instanceof Long) {
            return Long.valueOf(value.longValue());
        }
        if (original instanceof Double) {
            return Double.valueOf(value.doubleValue());
        }
        if (original instanceof Float) {
            return Float.valueOf((float) value.doubleValue());
        }
        return value;
    }

    private Object executeAccumulation(For node, AccumulationPattern pattern, ExecutionContext ctx) {
        // Evaluate bounds at runtime (works with method parameters!)
        AutoStackingNumber start, end, step;
        
        if (node.range != null) {
            start = evaluateBoundAsAutoStacking(node.range.start, ctx);
            end = evaluateBoundAsAutoStacking(node.range.end, ctx);
            step = calculateStepFromNodeAsAutoStacking(node, ctx);
        } else if (node.arraySource != null) {
            Object source = dispatcher.dispatch(node.arraySource);
            source = typeSystem.unwrap(source);
            if (source instanceof NaturalArray) {
                start = ZERO;
                end = AutoStackingNumber.fromLong(((NaturalArray) source).size() - 1);
                step = ONE;
            } else if (source instanceof List) {
                start = ZERO;
                end = AutoStackingNumber.fromLong(((List<?>) source).size() - 1);
                step = ONE;
            } else {
                return null;
            }
        } else {
            return null;
        }
        
        // Check step direction - we only support forward iteration for now
        if (step.compareTo(ZERO) <= 0) {
            return null;
        }
        
        // Get initial values
        AutoStackingNumber[] initialValues = new AutoStackingNumber[pattern.stateVars.length];
        for (int i = 0; i < pattern.stateVars.length; i++) {
            Object val = ctx.getVariable(pattern.stateVars[i]);
            if (val == null) {
                return null;
            }
            initialValues[i] = typeSystem.toAutoStackingNumber(val);
        }
        
        AutoStackingNumber result;
        
        switch (pattern.type) {
            case FSUM:
                if (pattern.constantExpr != null) {
                    // Loop-invariant expression - evaluate once, then multiply by iterations
                    AutoStackingNumber constValue = typeSystem.toAutoStackingNumber(
                        dispatcher.dispatch(pattern.constantExpr)
                    );
                    AutoStackingNumber iterations = end.subtract(start).divide(step).add(ONE);
                    result = initialValues[0].add(constValue.multiply(iterations));
                } else {
                    AccumulationFormula sumFormula = new AccumulationFormula(
                        pattern.polynomialCoeffs, initialValues[0], start, end, step
                    );
                    result = sumFormula.evaluate();
                }
                break;
                
            case FREC:
                // Calculate iterations: ((end - start) / step)
                AutoStackingNumber diff = end.subtract(start);
                AutoStackingNumber steps = diff.divide(step);
                AutoStackingNumber iterations = steps;
                
                AccumulationFormula recFormula = new AccumulationFormula(
                    pattern.recurrenceCoeffs, initialValues
                );
                result = recFormula.evaluate(iterations);
                break;
                
            case NSUM:
                List<AutoStackingNumber> bounds = new ArrayList<AutoStackingNumber>();
                for (Expr boundExpr : pattern.nestedBounds) {
                    bounds.add(evaluateBoundAsAutoStacking(boundExpr, ctx));
                }
                
                AccumulationFormula nestedFormula = new AccumulationFormula(
                    bounds, pattern.nestedFactorCoeffs, pattern.isProductOfSums, initialValues[0]
                );
                result = nestedFormula.evaluate();
                break;
                
            case NREC:
                AutoStackingNumber[] dimBounds = new AutoStackingNumber[pattern.dimensions];
                for (int i = 0; i < pattern.dimensions; i++) {
                    dimBounds[i] = evaluateBoundAsAutoStacking(pattern.dimBounds.get(i), ctx);
                }
                
                AutoStackingNumber[][][] ndCoeffs = pattern.ndCoeffs;
                AutoStackingNumber[] ndInitialState = new AutoStackingNumber[pattern.ndInitialState.length];
                for (int i = 0; i < ndInitialState.length; i++) {
                    ndInitialState[i] = pattern.ndInitialState[i];
                }
                
                AccumulationFormula ndFormula = new AccumulationFormula(
                    pattern.dimensions, dimBounds, ndCoeffs, ndInitialState
                );
                result = ndFormula.evaluate();
                break;
                
            case VEC:
                AutoStackingNumber iterationsVec = end.subtract(start).divide(step).add(ONE);
                
                AutoStackingNumber[][][] vecCoeffs = pattern.vectorCoeffs;
                AutoStackingNumber[] vecConstants = pattern.vectorConstants;
                AutoStackingNumber[][] seedValues = pattern.vectorSeedValues;
                
                AccumulationFormula vecFormula = new AccumulationFormula(
                    pattern.vectorDim, pattern.vectorOrder, vecCoeffs, vecConstants, seedValues
                );
                result = vecFormula.evaluate(iterationsVec);
                break;
                
            default:
                return null;
        }
        
        // Update variable
        Object finalResult = convertToOriginalType(result, ctx.getVariable(pattern.stateVars[0]));
        ctx.setVariable(pattern.stateVars[0], finalResult);
        
        // Update slot if exists (for return contracts like :: value: int)
        if (ctx.getSlotCount() > 0 && ctx.hasSlot(pattern.stateVars[0])) {
            ctx.setSlotValue(pattern.stateVars[0], finalResult);
            ctx.markSlotAssigned(pattern.stateVars[0]);
        }
        
        return finalResult;
    }

    // ========== VECTOR RECURRENCE METHODS ==========

    public List<PatternHandler.PatternResult> extractVectorLinearRecurrencePatterns(For node) {
        List<PatternHandler.PatternResult> results = new ArrayList<PatternHandler.PatternResult>();
        if (node == null || node.body == null || node.body.statements == null) {
            return results;
        }

        List<Assignment> assignments = collectVectorAssignments(node);
        if (assignments.size() < MIN_VECTOR_SEQUENCES || assignments.size() > MAX_VECTOR_SEQUENCES) {
            return results;
        }

        List<String> orderedTargets = new ArrayList<String>();
        List<Expr> targetExprs = new ArrayList<Expr>();
        List<NaturalArray> targetArrays = new ArrayList<NaturalArray>();
        Map<String, Integer> targetIndexByName = new LinkedHashMap<String, Integer>();

        for (Assignment assignment : assignments) {
            IndexAccess leftAccess = (IndexAccess) assignment.left;
            Identifier targetId = (Identifier) leftAccess.array;
            String targetName = targetId.name;
            if (targetIndexByName.containsKey(targetName)) {
                return new ArrayList<PatternHandler.PatternResult>();
            }
            Object resolved = dispatcher.dispatch(targetId);
            resolved = typeSystem.unwrap(resolved);
            if (!(resolved instanceof NaturalArray)) {
                return new ArrayList<PatternHandler.PatternResult>();
            }
            orderedTargets.add(targetName);
            targetExprs.add(targetId);
            targetArrays.add((NaturalArray) resolved);
            targetIndexByName.put(targetName, targetIndexByName.size());
        }

        long expectedSize = targetArrays.get(0).size();
        for (int i = 1; i < targetArrays.size(); i++) {
            if (targetArrays.get(i).size() != expectedSize) {
                return new ArrayList<PatternHandler.PatternResult>();
            }
        }

        int dimension = assignments.size();
        AutoStackingNumber[][][] coeffByLag = new AutoStackingNumber[dimension][MAX_SUPPORTED_LAG + 1][dimension];
        AutoStackingNumber[] constants = new AutoStackingNumber[dimension];
        for (int row = 0; row < dimension; row++) {
            constants[row] = AutoStackingNumber.fromLong(0L);
            for (int lag = 0; lag <= MAX_SUPPORTED_LAG; lag++) {
                for (int col = 0; col < dimension; col++) {
                    coeffByLag[row][lag][col] = AutoStackingNumber.fromLong(0L);
                }
            }
        }

        int maxLag = 0;
        for (int row = 0; row < assignments.size(); row++) {
            Assignment assign = assignments.get(row);
            AutoStackingNumber[] constantRef = new AutoStackingNumber[]{AutoStackingNumber.fromLong(0L)};
            if (!collectVectorLinearTerms(
                assign.right,
                targetIndexByName,
                node.iterator,
                coeffByLag[row],
                constantRef,
                AutoStackingNumber.fromLong(1L))) {
                return new ArrayList<PatternHandler.PatternResult>();
            }
            constants[row] = constantRef[0];
        }

        for (int row = 0; row < dimension; row++) {
            boolean hasDependency = false;
            for (int lag = 1; lag <= MAX_SUPPORTED_LAG; lag++) {
                for (int col = 0; col < dimension; col++) {
                    if (!coeffByLag[row][lag][col].isZero()) {
                        hasDependency = true;
                        if (lag > maxLag) maxLag = lag;
                    }
                }
            }
            if (!hasDependency) {
                return new ArrayList<PatternHandler.PatternResult>();
            }
        }
        if (maxLag <= 0) {
            return new ArrayList<PatternHandler.PatternResult>();
        }

        long[] bounds = resolveLoopBounds(node);
        if (bounds == null) {
            return new ArrayList<PatternHandler.PatternResult>();
        }
        long min = bounds[0];
        long max = bounds[1];
        long recurrenceStart = min;
        if (recurrenceStart < maxLag) {
            recurrenceStart = maxLag;
        }
        if (recurrenceStart > max) {
            return new ArrayList<PatternHandler.PatternResult>();
        }

        long seedStart = recurrenceStart - maxLag;
        AutoStackingNumber[][] seedValues = new AutoStackingNumber[dimension][maxLag];
        for (int seq = 0; seq < dimension; seq++) {
            NaturalArray arr = targetArrays.get(seq);
            for (int offset = 0; offset < maxLag; offset++) {
                long seedIndex = seedStart + offset;
                Object seedObj = arr.get(seedIndex);
                AutoStackingNumber seedNum = typeSystem.toAutoStackingNumber(seedObj);
                if (seedNum == null) {
                    return new ArrayList<PatternHandler.PatternResult>();
                }
                seedValues[seq][offset] = seedNum;
            }
        }

AutoStackingNumber[][][] flatCoefficients = new AutoStackingNumber[dimension][dimension * maxLag][1];
for (int row = 0; row < dimension; row++) {
    for (int lag = 1; lag <= maxLag; lag++) {
        for (int col = 0; col < dimension; col++) {
            int flatCol = ((lag - 1) * dimension) + col;
            flatCoefficients[row][flatCol][0] = coeffByLag[row][lag][col];
        }
    }
}

// Create AccumulationPattern for VEC type
AccumulationPattern accPattern = new AccumulationPattern(
    AccumulationPattern.AccumulationType.VEC,
    orderedTargets.toArray(new String[dimension]),
    dimension,
    maxLag,
    flatCoefficients,
    constants,
    seedValues,
    orderedTargets
);

        for (Expr targetExpr : targetExprs) {
            results.add(new PatternHandler.PatternResult(
                PatternHandler.PatternType.VECTOR_RECURRENCE,
                accPattern,
                targetExpr
            ));
        }
        return results;
    }

    public AccumulationPattern extractLinearRecurrencePattern(For node) {
        if (node == null || node.body == null || node.body.statements == null) {
            return null;
        }
        if (node.body.statements.size() != 1) {
            return null;
        }
        if (!(node.body.statements.get(0) instanceof Assignment)) {
            return null;
        }
        Assignment assign = (Assignment) node.body.statements.get(0);
        if (!(assign.left instanceof IndexAccess)) {
            return null;
        }
        IndexAccess leftAccess = (IndexAccess) assign.left;
        if (!(leftAccess.array instanceof Identifier) || !(leftAccess.index instanceof Identifier)) {
            return null;
        }
        String iter = node.iterator;
        Identifier idx = (Identifier) leftAccess.index;
        if (!iter.equals(idx.name)) {
            return null;
        }

        Object resolved = dispatcher.dispatch(leftAccess.array);
        resolved = typeSystem.unwrap(resolved);
        if (!(resolved instanceof NaturalArray)) {
            return null;
        }
        NaturalArray targetArray = (NaturalArray) resolved;

        Set<String> deps = new HashSet<String>();
        collectIndexedArrayRefs(assign.right, iter, deps);
        String targetName = ((Identifier) leftAccess.array).name;
        if (!deps.contains(targetName)) {
            return null;
        }
        for (String dep : deps) {
            if (!targetName.equals(dep)) {
                return null;
            }
        }

        AutoStackingNumber[] coeff = new AutoStackingNumber[MAX_SUPPORTED_LAG + 1];
        for (int i = 0; i < coeff.length; i++) coeff[i] = AutoStackingNumber.fromLong(0L);
        AutoStackingNumber[] constant = new AutoStackingNumber[]{AutoStackingNumber.fromLong(0L)};
        if (!collectLinearTerms(assign.right, targetName, iter, coeff, constant, AutoStackingNumber.fromLong(1L))) {
            return null;
        }

        int maxLag = 0;
        boolean hasAnyLag = false;
        for (int lag = 1; lag < coeff.length; lag++) {
            if (!coeff[lag].isZero()) {
                hasAnyLag = true;
                if (lag > maxLag) maxLag = lag;
            }
        }
        if (!hasAnyLag || maxLag <= 0) {
            return null;
        }

        int order = maxLag;
        AutoStackingNumber[] coeffByLag = new AutoStackingNumber[order];
        for (int lag = 1; lag <= order; lag++) {
            coeffByLag[lag - 1] = coeff[lag];
        }

        long[] bounds = resolveLoopBounds(node);
        if (bounds == null) {
            return null;
        }
        long min = bounds[0];
        long max = bounds[1];
        long recurrenceStart = min;
        if (recurrenceStart < order) {
            recurrenceStart = order;
        }
        if (recurrenceStart > max) {
            return null;
        }

        AutoStackingNumber[] seed = new AutoStackingNumber[order];
        long seedStart = recurrenceStart - order;
        for (int i = 0; i < order; i++) {
            long seedIndex = seedStart + i;
            Object seedValue = targetArray.get(seedIndex);
            AutoStackingNumber v = typeSystem.toAutoStackingNumber(seedValue);
            if (v == null) {
                return null;
            }
            seed[i] = v;
        }

        // Convert to AccumulationPattern (FREC type)
        AutoStackingNumber[] recurrenceCoeffs = new AutoStackingNumber[3];
        if (order >= 1) recurrenceCoeffs[0] = coeffByLag[0];
        if (order >= 2) recurrenceCoeffs[1] = coeffByLag[1];
        recurrenceCoeffs[2] = constant[0];
        
        AutoStackingNumber[] initialValues = new AutoStackingNumber[2];
        initialValues[0] = seed[0];
        initialValues[1] = seed.length > 1 ? seed[1] : seed[0];
        
        return new AccumulationPattern(
            AccumulationPattern.AccumulationType.FREC,
            new String[]{targetName},
            node.range != null ? node.range.start : null,
            node.range != null ? node.range.end : null,
            node.range != null ? node.range.step : null,
            recurrenceCoeffs
        );
    }

    private boolean collectLinearTerms(
        Expr expr,
        String targetArrayName,
        String iterator,
        AutoStackingNumber[] coeffByLag,
        AutoStackingNumber[] constant,
        AutoStackingNumber sign
    ) {
        if (expr == null) return false;

        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            if ("+".equals(bin.op)) {
                return collectLinearTerms(bin.left, targetArrayName, iterator, coeffByLag, constant, sign) &&
                       collectLinearTerms(bin.right, targetArrayName, iterator, coeffByLag, constant, sign);
            }
            if ("-".equals(bin.op)) {
                return collectLinearTerms(bin.left, targetArrayName, iterator, coeffByLag, constant, sign) &&
                       collectLinearTerms(bin.right, targetArrayName, iterator, coeffByLag, constant, sign.multiply(AutoStackingNumber.fromLong(-1L)));
            }
            if ("*".equals(bin.op)) {
                TermRef ref = extractIndexedTargetTerm(bin.left, targetArrayName, iterator);
                AutoStackingNumber scalar = toNumericLiteral(bin.right);
                if (ref == null || scalar == null) {
                    ref = extractIndexedTargetTerm(bin.right, targetArrayName, iterator);
                    scalar = toNumericLiteral(bin.left);
                }
                if (ref != null && scalar != null) {
                    AutoStackingNumber c = sign.multiply(scalar);
                    coeffByLag[ref.lag] = coeffByLag[ref.lag].add(c);
                    return true;
                }
                return false;
            }
            return false;
        }

        TermRef ref = extractIndexedTargetTerm(expr, targetArrayName, iterator);
        if (ref != null) {
            coeffByLag[ref.lag] = coeffByLag[ref.lag].add(sign);
            return true;
        }

        AutoStackingNumber literal = toNumericLiteral(expr);
        if (literal != null) {
            constant[0] = constant[0].add(sign.multiply(literal));
            return true;
        }

        return false;
    }

    private static class TermRef {
        final int lag;
        TermRef(int lag) { this.lag = lag; }
    }

    private static class VectorTermRef {
        final int lag;
        final int sequenceIndex;
        VectorTermRef(int lag, int sequenceIndex) {
            this.lag = lag;
            this.sequenceIndex = sequenceIndex;
        }
    }

    private TermRef extractIndexedTargetTerm(Expr expr, String targetArrayName, String iterator) {
        if (!(expr instanceof IndexAccess)) {
            return null;
        }
        IndexAccess access = (IndexAccess) expr;
        if (!(access.array instanceof Identifier)) {
            return null;
        }
        String arrayName = ((Identifier) access.array).name;
        if (!targetArrayName.equals(arrayName)) {
            return null;
        }
        int lag = extractLag(access.index, iterator);
        if (lag <= 0 || lag > MAX_SUPPORTED_LAG) {
            return null;
        }
        return new TermRef(lag);
    }

    private List<Assignment> collectVectorAssignments(For node) {
        List<Assignment> assignments = new ArrayList<Assignment>();
        if (node == null || node.body == null || node.body.statements == null) {
            return assignments;
        }
        for (Stmt stmt : node.body.statements) {
            if (!(stmt instanceof Assignment)) {
                return new ArrayList<Assignment>();
            }
            Assignment assign = (Assignment) stmt;
            if (assign.isDeclaration || !(assign.left instanceof IndexAccess)) {
                return new ArrayList<Assignment>();
            }
            IndexAccess access = (IndexAccess) assign.left;
            if (!(access.array instanceof Identifier) || !(access.index instanceof Identifier)) {
                return new ArrayList<Assignment>();
            }
            Identifier idx = (Identifier) access.index;
            if (!node.iterator.equals(idx.name)) {
                return new ArrayList<Assignment>();
            }
            assignments.add(assign);
        }
        return assignments;
    }

    private boolean collectVectorLinearTerms(
        Expr expr,
        Map<String, Integer> targetIndexByName,
        String iterator,
        AutoStackingNumber[][] coefficientsByLagAndSequence,
        AutoStackingNumber[] constant,
        AutoStackingNumber sign
    ) {
        if (expr == null) return false;

        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            if ("+".equals(bin.op)) {
                return collectVectorLinearTerms(bin.left, targetIndexByName, iterator, coefficientsByLagAndSequence, constant, sign) &&
                    collectVectorLinearTerms(bin.right, targetIndexByName, iterator, coefficientsByLagAndSequence, constant, sign);
            }
            if ("-".equals(bin.op)) {
                return collectVectorLinearTerms(bin.left, targetIndexByName, iterator, coefficientsByLagAndSequence, constant, sign) &&
                    collectVectorLinearTerms(bin.right, targetIndexByName, iterator, coefficientsByLagAndSequence, constant,
                        sign.multiply(AutoStackingNumber.fromLong(-1L)));
            }
            if ("*".equals(bin.op)) {
                VectorTermRef ref = extractIndexedVectorTerm(bin.left, targetIndexByName, iterator);
                AutoStackingNumber scalar = toNumericLiteral(bin.right);
                if (ref == null || scalar == null) {
                    ref = extractIndexedVectorTerm(bin.right, targetIndexByName, iterator);
                    scalar = toNumericLiteral(bin.left);
                }
                if (ref != null && scalar != null) {
                    AutoStackingNumber delta = sign.multiply(scalar);
                    coefficientsByLagAndSequence[ref.lag][ref.sequenceIndex] =
                        coefficientsByLagAndSequence[ref.lag][ref.sequenceIndex].add(delta);
                    return true;
                }
                return false;
            }
            return false;
        }

        VectorTermRef ref = extractIndexedVectorTerm(expr, targetIndexByName, iterator);
        if (ref != null) {
            coefficientsByLagAndSequence[ref.lag][ref.sequenceIndex] =
                coefficientsByLagAndSequence[ref.lag][ref.sequenceIndex].add(sign);
            return true;
        }

        AutoStackingNumber literal = toNumericLiteral(expr);
        if (literal != null) {
            constant[0] = constant[0].add(sign.multiply(literal));
            return true;
        }

        return false;
    }

    private VectorTermRef extractIndexedVectorTerm(
        Expr expr,
        Map<String, Integer> targetIndexByName,
        String iterator
    ) {
        if (!(expr instanceof IndexAccess)) {
            return null;
        }
        IndexAccess access = (IndexAccess) expr;
        if (!(access.array instanceof Identifier)) {
            return null;
        }
        String arrayName = ((Identifier) access.array).name;
        Integer sequenceIndex = targetIndexByName.get(arrayName);
        if (sequenceIndex == null) {
            return null;
        }
        int lag = extractLag(access.index, iterator);
        if (lag <= 0 || lag > MAX_SUPPORTED_LAG) {
            return null;
        }
        return new VectorTermRef(lag, sequenceIndex.intValue());
    }

    private int extractLag(Expr indexExpr, String iterator) {
        if (indexExpr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) indexExpr;
            if ("-".equals(bin.op) && bin.left instanceof Identifier &&
                iterator.equals(((Identifier) bin.left).name)) {
                AutoStackingNumber n = toNumericLiteral(bin.right);
                if (n == null) return -1;
                long lag = n.longValue();
                if (lag <= 0 || lag > Integer.MAX_VALUE) return -1;
                return (int) lag;
            }
        }
        return -1;
    }

    private AutoStackingNumber toNumericLiteral(Expr expr) {
        if (expr instanceof IntLiteral) {
            return ((IntLiteral) expr).value;
        }
        if (expr instanceof FloatLiteral) {
            return ((FloatLiteral) expr).value;
        }
        if (expr instanceof Unary) {
            Unary unary = (Unary) expr;
            if ("-".equals(unary.op)) {
                AutoStackingNumber inner = toNumericLiteral(unary.operand);
                if (inner == null) return null;
                return AutoStackingNumber.fromLong(0L).subtract(inner);
            }
            if ("+".equals(unary.op)) {
                return toNumericLiteral(unary.operand);
            }
        }
        return null;
    }

    private long[] resolveLoopBounds(For node) {
        if (node == null) return null;
        if (node.range != null) {
            Object startObj = dispatcher.dispatch(node.range.start);
            Object endObj = dispatcher.dispatch(node.range.end);
            long start = exprHandler.toLong(startObj);
            long end = exprHandler.toLong(endObj);
            return new long[]{Math.min(start, end), Math.max(start, end)};
        }
        if (node.arraySource != null) {
            Object sourceObj = dispatcher.dispatch(node.arraySource);
            sourceObj = typeSystem.unwrap(sourceObj);
            if (sourceObj instanceof NaturalArray) {
                NaturalArray sourceArr = (NaturalArray) sourceObj;
                if (sourceArr.size() > 0) {
                    return new long[]{0L, sourceArr.size() - 1L};
                }
            } else if (sourceObj instanceof List) {
                List<?> list = (List<?>) sourceObj;
                if (!list.isEmpty()) {
                    return new long[]{0L, list.size() - 1L};
                }
            }
        }
        return null;
    }

    public List<PatternHandler.PatternResult> extractMultiArraySequencePatterns(For node) {
        List<PatternHandler.PatternResult> results = new ArrayList<PatternHandler.PatternResult>();
        if (node == null || node.body == null || node.body.statements == null) {
            return results;
        }

        List<Stmt> statements = node.body.statements;
        if (statements.size() < 2) {
            return results;
        }

        List<String> orderedTargets = new ArrayList<String>();
        List<Assignment> orderedAssignments = new ArrayList<Assignment>();

        for (Stmt stmt : statements) {
            if (!(stmt instanceof Assignment)) {
                return new ArrayList<PatternHandler.PatternResult>();
            }

            Assignment assign = (Assignment) stmt;
            if (assign.isDeclaration || !(assign.left instanceof IndexAccess)) {
                return new ArrayList<PatternHandler.PatternResult>();
            }

            IndexAccess indexAccess = (IndexAccess) assign.left;
            if (!(indexAccess.array instanceof Identifier) || !(indexAccess.index instanceof Identifier)) {
                return new ArrayList<PatternHandler.PatternResult>();
            }

            Identifier index = (Identifier) indexAccess.index;
            if (!node.iterator.equals(index.name)) {
                return new ArrayList<PatternHandler.PatternResult>();
            }

            String targetName = ((Identifier) indexAccess.array).name;
            if (orderedTargets.contains(targetName)) {
                return new ArrayList<PatternHandler.PatternResult>();
            }

            orderedTargets.add(targetName);
            orderedAssignments.add(assign);
        }

        for (int i = 0; i < orderedAssignments.size(); i++) {
            Assignment assign = orderedAssignments.get(i);
            IndexAccess indexAccess = (IndexAccess) assign.left;
            Identifier targetArray = (Identifier) indexAccess.array;

            Set<String> refs = new HashSet<String>();
            collectIndexedArrayRefs(assign.right, node.iterator, refs);

            for (String ref : refs) {
                int refIndex = orderedTargets.indexOf(ref);
                if (refIndex == -1 || refIndex > i) {
                    return new ArrayList<PatternHandler.PatternResult>();
                }
            }

            List<SequencePattern.Step> steps = new ArrayList<SequencePattern.Step>();
            steps.add(new SequencePattern.Step(null, assign.right));
            SequencePattern.Pattern pattern = new SequencePattern.Pattern(steps, targetArray, node.iterator);
            results.add(new PatternHandler.PatternResult(PatternHandler.PatternType.SEQUENCE, pattern, targetArray));
        }

        return results;
    }

    private void collectIndexedArrayRefs(Expr expr, String iterator, Set<String> refs) {
        if (expr == null || refs == null) {
            return;
        }

        if (expr instanceof IndexAccess) {
            IndexAccess access = (IndexAccess) expr;
            if (access.array instanceof Identifier && access.index instanceof Identifier) {
                Identifier idx = (Identifier) access.index;
                if (iterator.equals(idx.name)) {
                    refs.add(((Identifier) access.array).name);
                }
            }
            collectIndexedArrayRefs(access.array, iterator, refs);
            collectIndexedArrayRefs(access.index, iterator, refs);
            return;
        }

        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            collectIndexedArrayRefs(bin.left, iterator, refs);
            collectIndexedArrayRefs(bin.right, iterator, refs);
            return;
        }

        if (expr instanceof Unary) {
            collectIndexedArrayRefs(((Unary) expr).operand, iterator, refs);
            return;
        }

        if (expr instanceof MethodCall) {
            MethodCall call = (MethodCall) expr;
            if (call.arguments != null) {
                for (Expr arg : call.arguments) {
                    collectIndexedArrayRefs(arg, iterator, refs);
                }
            }
            return;
        }

        if (expr instanceof TypeCast) {
            collectIndexedArrayRefs(((TypeCast) expr).expression, iterator, refs);
            return;
        }

        if (expr instanceof PropertyAccess) {
            PropertyAccess prop = (PropertyAccess) expr;
            collectIndexedArrayRefs(prop.left, iterator, refs);
            collectIndexedArrayRefs(prop.right, iterator, refs);
            return;
        }

        if (expr instanceof Tuple) {
            Tuple tuple = (Tuple) expr;
            if (tuple.elements != null) {
                for (Expr elem : tuple.elements) {
                    collectIndexedArrayRefs(elem, iterator, refs);
                }
            }
            return;
        }

        if (expr instanceof Array) {
            Array array = (Array) expr;
            if (array.elements != null) {
                for (Expr elem : array.elements) {
                    collectIndexedArrayRefs(elem, iterator, refs);
                }
            }
        }
    }

    // ========== OUTPUT-AWARE LOOP METHODS ==========

    public Object executeOutputAwareLoop(For node, OutputAwarePattern.OutputPattern pattern) {
        
        ExecutionContext ctx = dispatcher.getCurrentContext();

        try {
            NaturalArray arr = createArrayFromOutputPattern(node, pattern.computation, ctx);

            ctx.enterOptimizedLoop();

            List<Object> allValues = new ArrayList<Object>();
            
            if (node.range != null) {
                allValues = collectOutputRangeValues(ctx, node, arr);
            } else if (node.arraySource != null) {
                allValues = collectOutputArrayValues(ctx, node, arr);
            }
            
            // Update the variable to point to the optimized array
            if (pattern.computation instanceof SequencePattern.Pattern) {
                SequencePattern.Pattern seqPattern = (SequencePattern.Pattern) pattern.computation;
                if (seqPattern.targetArray instanceof Identifier) {
                    String arrayVarName = ((Identifier) seqPattern.targetArray).name;
                    ctx.setVariable(arrayVarName, arr);
                }
            } else if (pattern.computation instanceof ConditionalPattern) {
                ConditionalPattern condPattern = (ConditionalPattern) pattern.computation;
                if (condPattern.array instanceof Identifier) {
                    String arrayVarName = ((Identifier) condPattern.array).name;
                    ctx.setVariable(arrayVarName, arr);
                }
            }
            
            // EXIT the optimized loop BEFORE dispatching output
            ctx.exitOptimizedLoop();
            
            for (OutputAwarePattern.OutputBatch batch : pattern.getBatches()) {
                for (MethodCall outputCall : batch.calls) {
                    MethodCall batchedCall = new MethodCall();
                    batchedCall.name = outputCall.name;
                    batchedCall.arguments = new ArrayList<Expr>();
                    
                    for (Object val : allValues) {
                        batchedCall.arguments.add(new ValueExpr(val));
                    }
                    
                    
                    dispatcher.dispatch(batchedCall);
                }
            }
            return arr;
        } finally {
            // Ensure we exit if there was an exception before exitOptimizedLoop was called
            if (ctx.isInOptimizedLoop()) {
                ctx.exitOptimizedLoop();
            }
        }
    }

    private List<Object> collectOutputRangeValues(ExecutionContext ctx, For node, NaturalArray arr) {
        List<Object> values = new ArrayList<Object>();
        
        try {
            Object startObj = dispatcher.dispatch(node.range.start);
            Object endObj = dispatcher.dispatch(node.range.end);
            startObj = typeSystem.unwrap(startObj);
            endObj = typeSystem.unwrap(endObj);

            long start = exprHandler.toLong(startObj);
            long end = exprHandler.toLong(endObj);
            long step = arrHandler.calculateRangeStep(node.range);

            for (long i = start; i <= end; i += step) {
                Object value = arr.get(i);
                values.add(value);
                ctx.setVariable(node.iterator, value);
            }
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Output range value collection failed", e);
        }
        
        return values;
    }

    private List<Object> collectOutputArrayValues(ExecutionContext ctx, For node, NaturalArray arr) {
        List<Object> values = new ArrayList<Object>();
        
        try {
            Object sourceObj = dispatcher.dispatch(node.arraySource);
            sourceObj = typeSystem.unwrap(sourceObj);

            long size = 0;
            if (sourceObj instanceof NaturalArray) {
                size = ((NaturalArray) sourceObj).size();
            } else if (sourceObj instanceof List) {
                size = ((List<?>) sourceObj).size();
            } else {
                throw new ProgramError("Cannot iterate over: " +
                    (sourceObj != null ? sourceObj.getClass().getSimpleName() : "null"));
            }

            for (long i = 0; i < size; i++) {
                Object value = arr.get(i);
                values.add(value);
                ctx.setVariable(node.iterator, value);
            }
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Output array value collection failed", e);
        }
        
        return values;
    }

    public NaturalArray createArrayFromOutputPattern(For node, Object computation, ExecutionContext ctx) {
        
        if (computation instanceof SequencePattern.Pattern) {
            SequencePattern.Pattern seqPattern = (SequencePattern.Pattern) computation;

            Range range = node.range;
            if (range == null && node.arraySource != null) {
                Object sourceObj = dispatcher.dispatch(node.arraySource);
                sourceObj = typeSystem.unwrap(sourceObj);

                if (sourceObj instanceof NaturalArray) {
                    NaturalArray sourceArr = (NaturalArray) sourceObj;
                    long size = sourceArr.size();

                    Expr start = ASTFactory.createIntLiteral(0, null);
                    Expr end = ASTFactory.createIntLiteral((int)(size - 1), null);
                    range = ASTFactory.createRange(null, start, end, null, null);
                    }
            }

            if (range == null) {
                throw new ProgramError("Cannot create array from pattern: no range specified");
            }

            NaturalArray arr = new NaturalArray(range, dispatcher, ctx);

            Expr finalExpr;
            if (seqPattern.substitutedFinalExpr != null) {
                finalExpr = seqPattern.substitutedFinalExpr;
            } else {
                finalExpr = seqPattern.getFinalExpression();
            }

            if (seqPattern.isSimple()) {
                SequenceFormula formula = SequenceFormula.createSimple(
                    0, arr.size() - 1,
                    finalExpr,
                    node.iterator
                );
                arr.addSequenceFormula(formula);
            } else {
                SequenceFormula formula = SequenceFormula.createFromSequence(
                    0, arr.size() - 1, node.iterator,
                    seqPattern.getTempVarNames(),
                    seqPattern.getTempExpressions(),
                    finalExpr
                );
                arr.addSequenceFormula(formula);
            }
            return arr;

        } else if (computation instanceof ConditionalPattern) {
            ConditionalPattern condPattern = (ConditionalPattern) computation;

            Range range = node.range;
            if (range == null && node.arraySource != null) {
                Object sourceObj = dispatcher.dispatch(node.arraySource);
                sourceObj = typeSystem.unwrap(sourceObj);

                if (sourceObj instanceof NaturalArray) {
                    NaturalArray sourceArr = (NaturalArray) sourceObj;
                    long size = sourceArr.size();

                    Expr start = ASTFactory.createIntLiteral(0, null);
                    Expr end = ASTFactory.createIntLiteral((int)(size - 1), null);
                    range = ASTFactory.createRange(null, start, end, null, null);
                }
            }

            if (range == null) {
                throw new ProgramError("Cannot create array from pattern: no range specified");
            }

            NaturalArray arr = new NaturalArray(range, dispatcher, ctx);

            List<Expr> conditions = new ArrayList<Expr>();
            List<List<Stmt>> branchStatements = new ArrayList<List<Stmt>>();

            for (ConditionalPattern.Branch branch : condPattern.branches) {
                conditions.add(branch.condition);
                branchStatements.add(branch.statements);
            }

            ConditionalFormula formula = new ConditionalFormula(
                0, arr.size() - 1, node.iterator,
                conditions,
                branchStatements,
                condPattern.elseStatements
            );
            arr.addConditionalFormula(formula);

            return arr;
        }

        throw new ProgramError("Unknown computation pattern type");
    }

    // Legacy methods kept for compatibility
    public void executeOutputRangeLoop(ExecutionContext ctx, For node,
                                       NaturalArray arr, List<MethodCall> outputCalls) {
        collectOutputRangeValues(ctx, node, arr);
    }

    public void executeOutputArrayLoop(ExecutionContext ctx, For node,
                                       NaturalArray arr, List<MethodCall> outputCalls) {
        collectOutputArrayValues(ctx, node, arr);
    }

    public List<ConditionalPattern> extractConditionalPatterns(StmtIf ifStmt, String iterator) {
        try {
            return ConditionalPattern.extractAll(ifStmt, iterator);
        } catch (Exception e) {
            DebugSystem.debug("OPTIMIZER", "Failed to extract conditional pattern: " + e.getMessage());
            return new ArrayList<ConditionalPattern>();
        }
    }
}