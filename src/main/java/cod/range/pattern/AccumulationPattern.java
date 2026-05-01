package cod.range.pattern;

import cod.ast.node.*;
import cod.math.AutoStackingNumber;
import java.util.*;

public class AccumulationPattern {
    
    public enum AccumulationType {
        FSUM,   // Flat Sum (1D polynomial)
        NSUM,   // Nested Sum (ND product of sums)
        FREC,   // Flat Recurrence (1D linear)
        NREC,   // Nested Recurrence (ND linear)
        VEC     // Vector Recurrence (multiple coupled)
    }
    
    public final AccumulationType type;
    public final String[] stateVars;
    
    // For FSUM (1D)
    public final Expr startBound;
    public final Expr endBound;
    public final Expr stepBound;
    public final AutoStackingNumber[] polynomialCoeffs;
    public final Expr constantExpr;
    
    // For FREC (1D)
    public final AutoStackingNumber[] recurrenceCoeffs;
    public final int order;
    
    // For NSUM (ND)
    public final List<Expr> nestedBounds;
    public final List<String> nestedIterators;
    public final List<AutoStackingNumber[]> nestedFactorCoeffs;
    public final boolean isProductOfSums;
    
    // For NREC (ND)
    public final int dimensions;
    public final List<Expr> dimBounds;
    public final List<String> dimIterators;
    public final AutoStackingNumber[][][] ndCoeffs;
    public final AutoStackingNumber[] ndInitialState;
    
    // For VEC (Vector)
    public final int vectorDim;
    public final int vectorOrder;
    public final AutoStackingNumber[][][] vectorCoeffs;
    public final AutoStackingNumber[] vectorConstants;
    public final AutoStackingNumber[][] vectorSeedValues;
    public final List<String> vectorVarNames;
    
    public final boolean isOptimizable;
    
    // ========== Constructors ==========
    
    // FSUM Constructor
    public AccumulationPattern(AccumulationType type, String[] stateVars,
                                Expr startBound, Expr endBound, Expr stepBound,
                                AutoStackingNumber[] coeffs, Expr constantExpr) {
        this.type = type;
        this.stateVars = stateVars;
        this.startBound = startBound;
        this.endBound = endBound;
        this.stepBound = stepBound;
        this.polynomialCoeffs = coeffs;
        this.constantExpr = constantExpr;
        this.recurrenceCoeffs = null;
        this.order = 1;
        this.nestedBounds = null;
        this.nestedIterators = null;
        this.nestedFactorCoeffs = null;
        this.isProductOfSums = false;
        this.dimensions = 0;
        this.dimBounds = null;
        this.dimIterators = null;
        this.ndCoeffs = null;
        this.ndInitialState = null;
        this.vectorDim = 0;
        this.vectorOrder = 0;
        this.vectorCoeffs = null;
        this.vectorConstants = null;
        this.vectorSeedValues = null;
        this.vectorVarNames = null;
        this.isOptimizable = true;
    }
    
    // FREC Constructor
    public AccumulationPattern(AccumulationType type, String[] stateVars,
                                Expr startBound, Expr endBound, Expr stepBound,
                                AutoStackingNumber[] coeffs) {
        this.type = type;
        this.stateVars = stateVars;
        this.startBound = startBound;
        this.endBound = endBound;
        this.stepBound = stepBound;
        this.polynomialCoeffs = null;
        this.constantExpr = null;
        this.recurrenceCoeffs = coeffs;
        this.order = 2;
        this.nestedBounds = null;
        this.nestedIterators = null;
        this.nestedFactorCoeffs = null;
        this.isProductOfSums = false;
        this.dimensions = 0;
        this.dimBounds = null;
        this.dimIterators = null;
        this.ndCoeffs = null;
        this.ndInitialState = null;
        this.vectorDim = 0;
        this.vectorOrder = 0;
        this.vectorCoeffs = null;
        this.vectorConstants = null;
        this.vectorSeedValues = null;
        this.vectorVarNames = null;
        this.isOptimizable = true;
    }
    
    // NSUM Constructor
    public AccumulationPattern(AccumulationType type, String[] stateVars,
                                List<Expr> nestedBounds, List<String> nestedIterators,
                                List<AutoStackingNumber[]> nestedFactorCoeffs, boolean isProductOfSums) {
        this.type = type;
        this.stateVars = stateVars;
        this.startBound = null;
        this.endBound = null;
        this.stepBound = null;
        this.polynomialCoeffs = null;
        this.constantExpr = null;
        this.recurrenceCoeffs = null;
        this.order = 0;
        this.nestedBounds = nestedBounds;
        this.nestedIterators = nestedIterators;
        this.nestedFactorCoeffs = nestedFactorCoeffs;
        this.isProductOfSums = isProductOfSums;
        this.dimensions = nestedBounds.size();
        this.dimBounds = null;
        this.dimIterators = null;
        this.ndCoeffs = null;
        this.ndInitialState = null;
        this.vectorDim = 0;
        this.vectorOrder = 0;
        this.vectorCoeffs = null;
        this.vectorConstants = null;
        this.vectorSeedValues = null;
        this.vectorVarNames = null;
        this.isOptimizable = true;
    }
    
    // NREC Constructor
    public AccumulationPattern(AccumulationType type, String[] stateVars,
                                List<Expr> dimBounds, List<String> dimIterators,
                                AutoStackingNumber[][][] ndCoeffs, AutoStackingNumber[] ndInitialState) {
        this.type = type;
        this.stateVars = stateVars;
        this.startBound = null;
        this.endBound = null;
        this.stepBound = null;
        this.polynomialCoeffs = null;
        this.constantExpr = null;
        this.recurrenceCoeffs = null;
        this.order = 0;
        this.nestedBounds = null;
        this.nestedIterators = null;
        this.nestedFactorCoeffs = null;
        this.isProductOfSums = false;
        this.dimensions = dimBounds.size();
        this.dimBounds = dimBounds;
        this.dimIterators = dimIterators;
        this.ndCoeffs = ndCoeffs;
        this.ndInitialState = ndInitialState;
        this.vectorDim = 0;
        this.vectorOrder = 0;
        this.vectorCoeffs = null;
        this.vectorConstants = null;
        this.vectorSeedValues = null;
        this.vectorVarNames = null;
        this.isOptimizable = true;
    }
    
    // VEC Constructor
    public AccumulationPattern(AccumulationType type, String[] stateVars,
                                int vectorDim, int vectorOrder,
                                AutoStackingNumber[][][] vectorCoeffs,
                                AutoStackingNumber[] vectorConstants,
                                AutoStackingNumber[][] vectorSeedValues,
                                List<String> vectorVarNames) {
        this.type = type;
        this.stateVars = stateVars;
        this.startBound = null;
        this.endBound = null;
        this.stepBound = null;
        this.polynomialCoeffs = null;
        this.constantExpr = null;
        this.recurrenceCoeffs = null;
        this.order = 0;
        this.nestedBounds = null;
        this.nestedIterators = null;
        this.nestedFactorCoeffs = null;
        this.isProductOfSums = false;
        this.dimensions = 0;
        this.dimBounds = null;
        this.dimIterators = null;
        this.ndCoeffs = null;
        this.ndInitialState = null;
        this.vectorDim = vectorDim;
        this.vectorOrder = vectorOrder;
        this.vectorCoeffs = vectorCoeffs;
        this.vectorConstants = vectorConstants;
        this.vectorSeedValues = vectorSeedValues;
        this.vectorVarNames = vectorVarNames;
        this.isOptimizable = true;
    }
    
    // ========== Main Extract Method ==========
    
    public static AccumulationPattern extract(For node) {
        if (node == null || node.body == null || node.body.statements == null) {
            return null;
        }
        
        int depth = getNestingDepth(node);
        
        if (depth > 1) {
            // Try nested sum pattern
            AccumulationPattern nestedSum = extractNestedSum(node, depth);
            if (nestedSum != null) {
                return nestedSum;
            }
            
            // Try nested recurrence pattern
            AccumulationPattern nestedRecurrence = extractNestedRecurrence(node, depth);
            if (nestedRecurrence != null) {
                return nestedRecurrence;
            }
        }
        
        List<Stmt> stmts = node.body.statements;
        String iterator = node.iterator;
        Expr startBound = (node.range != null) ? node.range.start : null;
        Expr endBound = (node.range != null) ? node.range.end : null;
        Expr stepBound = (node.range != null) ? node.range.step : null;
        
        // Try vector recurrence pattern (multiple assignments)
        AccumulationPattern vecPattern = extractVectorRecurrence(stmts, iterator,
                                                                  startBound, endBound, stepBound);
        if (vecPattern != null) {
            return vecPattern;
        }
        
        // Try flat sum pattern (single assignment)
        if (stmts.size() == 1) {
            AccumulationPattern sumPattern = extractFlatSum(stmts.get(0), iterator, 
                                                             startBound, endBound, stepBound);
            if (sumPattern != null) {
                return sumPattern;
            }
        } else {
            // Try flat recurrence pattern
            AccumulationPattern recurrencePattern = extractFlatRecurrence(stmts, iterator,
                                                                           startBound, endBound, stepBound);
            if (recurrencePattern != null) {
                return recurrencePattern;
            }
        }
        
        return null;
    }
    
    private static int getNestingDepth(For node) {
        int depth = 1;
        For current = node;
        while (current.body != null && current.body.statements != null && 
               current.body.statements.size() == 1) {
            Stmt stmt = current.body.statements.get(0);
            if (stmt instanceof For) {
                depth++;
                current = (For) stmt;
            } else {
                break;
            }
        }
        return depth;
    }
    
    // ========== Vector Recurrence Extraction ==========
    
    private static AccumulationPattern extractVectorRecurrence(List<Stmt> stmts, String iterator,
                                                                Expr startBound, Expr endBound, Expr stepBound) {
        if (stmts.size() < 2) {
            return null;
        }
        
        List<Assignment> assignments = new ArrayList<Assignment>();
        List<String> varNames = new ArrayList<String>();
        Map<String, Integer> varIndex = new HashMap<String, Integer>();
        
        for (Stmt stmt : stmts) {
            if (stmt instanceof Assignment && !((Assignment) stmt).isDeclaration) {
                Assignment assign = (Assignment) stmt;
                if (assign.left instanceof Identifier && assign.right != null) {
                    String varName = ((Identifier) assign.left).name;
                    if (!varIndex.containsKey(varName)) {
                        varIndex.put(varName, varNames.size());
                        varNames.add(varName);
                    }
                    assignments.add(assign);
                }
            }
        }
        
        if (assignments.size() < 2) {
            return null;
        }
        
        int vectorDim = varNames.size();
        int maxLag = 0;
        
        // Build coefficient matrix
        AutoStackingNumber[][][] coeffs = new AutoStackingNumber[vectorDim][MAX_LAG + 1][vectorDim];
        AutoStackingNumber[] constants = new AutoStackingNumber[vectorDim];
        
        for (int i = 0; i < vectorDim; i++) {
            constants[i] = ZERO;
            for (int lag = 0; lag <= MAX_LAG; lag++) {
                for (int j = 0; j < vectorDim; j++) {
                    coeffs[i][lag][j] = ZERO;
                }
            }
        }
        
        for (Assignment assign : assignments) {
            int targetIdx = varIndex.get(((Identifier) assign.left).name);
            AutoStackingNumber[] constRef = new AutoStackingNumber[]{ZERO};
            if (!collectVectorTerms(assign.right, varIndex, iterator, coeffs[targetIdx], constRef, ONE)) {
                return null;
            }
            constants[targetIdx] = constRef[0];
        }
        
        // Find max lag
        for (int i = 0; i < vectorDim; i++) {
            for (int lag = 1; lag <= MAX_LAG; lag++) {
                for (int j = 0; j < vectorDim; j++) {
                    if (!coeffs[i][lag][j].isZero() && lag > maxLag) {
                        maxLag = lag;
                    }
                }
            }
        }
        
        if (maxLag == 0) {
            return null;
        }
        
        // Flatten coefficients
        AutoStackingNumber[][][] flatCoeffs = new AutoStackingNumber[vectorDim][vectorDim * maxLag][1];
        for (int i = 0; i < vectorDim; i++) {
            for (int lag = 1; lag <= maxLag; lag++) {
                for (int j = 0; j < vectorDim; j++) {
                    int flatCol = ((lag - 1) * vectorDim) + j;
                    flatCoeffs[i][flatCol][0] = coeffs[i][lag][j];
                }
            }
        }
        
        // Get seed values from context (will be evaluated at runtime)
        AutoStackingNumber[][] seedValues = new AutoStackingNumber[vectorDim][maxLag];
        
        return new AccumulationPattern(
            AccumulationType.VEC,
            varNames.toArray(new String[vectorDim]),
            vectorDim, maxLag,
            flatCoeffs, constants, seedValues,
            varNames
        );
    }
    
    private static boolean collectVectorTerms(Expr expr, Map<String, Integer> varIndex,
                                               String iterator, AutoStackingNumber[][] coeffs,
                                               AutoStackingNumber[] constant, AutoStackingNumber sign) {
        if (expr == null) return false;
        
        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            if ("+".equals(bin.op)) {
                return collectVectorTerms(bin.left, varIndex, iterator, coeffs, constant, sign) &&
                       collectVectorTerms(bin.right, varIndex, iterator, coeffs, constant, sign);
            }
            if ("-".equals(bin.op)) {
                return collectVectorTerms(bin.left, varIndex, iterator, coeffs, constant, sign) &&
                       collectVectorTerms(bin.right, varIndex, iterator, coeffs, constant, sign.multiply(MINUS_ONE));
            }
            if ("*".equals(bin.op)) {
                VectorTermRef ref = extractVectorTerm(bin.left, varIndex, iterator);
                AutoStackingNumber scalar = toNumericLiteral(bin.right);
                if (ref == null || scalar == null) {
                    ref = extractVectorTerm(bin.right, varIndex, iterator);
                    scalar = toNumericLiteral(bin.left);
                }
                if (ref != null && scalar != null) {
                    AutoStackingNumber delta = sign.multiply(scalar);
                    coeffs[ref.lag][ref.varIndex] = coeffs[ref.lag][ref.varIndex].add(delta);
                    return true;
                }
                return false;
            }
            return false;
        }
        
        VectorTermRef ref = extractVectorTerm(expr, varIndex, iterator);
        if (ref != null) {
            coeffs[ref.lag][ref.varIndex] = coeffs[ref.lag][ref.varIndex].add(sign);
            return true;
        }
        
        AutoStackingNumber literal = toNumericLiteral(expr);
        if (literal != null) {
            constant[0] = constant[0].add(sign.multiply(literal));
            return true;
        }
        
        return false;
    }
    
    private static class VectorTermRef {
        final int lag;
        final int varIndex;
        VectorTermRef(int lag, int varIndex) {
            this.lag = lag;
            this.varIndex = varIndex;
        }
    }
    
    private static VectorTermRef extractVectorTerm(Expr expr, Map<String, Integer> varIndex, String iterator) {
        if (!(expr instanceof IndexAccess)) {
            return null;
        }
        IndexAccess access = (IndexAccess) expr;
        if (!(access.array instanceof Identifier)) {
            return null;
        }
        String varName = ((Identifier) access.array).name;
        Integer idx = varIndex.get(varName);
        if (idx == null) {
            return null;
        }
        int lag = extractLag(access.index, iterator);
        if (lag <= 0 || lag > MAX_LAG) {
            return null;
        }
        return new VectorTermRef(lag, idx);
    }
    
    // ========== Nested Sum Extraction ==========
    
    private static AccumulationPattern extractNestedSum(For node, int depth) {
        List<Expr> bounds = new ArrayList<Expr>();
        List<String> iterators = new ArrayList<String>();
        List<AutoStackingNumber[]> factorCoeffs = new ArrayList<AutoStackingNumber[]>();
        For current = node;
        
        for (int i = 0; i < depth; i++) {
            iterators.add(current.iterator);
            if (current.range != null && current.range.end != null) {
                bounds.add(current.range.end);
            } else {
                return null;
            }
            if (i < depth - 1 && current.body != null && current.body.statements != null &&
                current.body.statements.size() == 1 && current.body.statements.get(0) instanceof For) {
                current = (For) current.body.statements.get(0);
            }
        }
        
        Expr innermostExpr = extractInnermostExpression(current);
        if (innermostExpr == null) {
            return null;
        }
        
        if (!(innermostExpr instanceof BinaryOp)) {
            return null;
        }
        BinaryOp bin = (BinaryOp) innermostExpr;
        if (!"+".equals(bin.op)) {
            return null;
        }
        
        Expr addedExpr = bin.right;
        
        for (int i = 0; i < depth; i++) {
            String iter = iterators.get(i);
            AutoStackingNumber[] coeffs = extractPolynomialCoefficientsForIterator(addedExpr, iter);
            if (coeffs == null) {
                coeffs = new AutoStackingNumber[]{ONE, ZERO, ZERO, ZERO};
            }
            factorCoeffs.add(coeffs);
        }
        
        return new AccumulationPattern(
            AccumulationType.NSUM,
            new String[]{"total"},
            bounds, iterators, factorCoeffs, true
        );
    }
    
    // ========== Nested Recurrence Extraction ==========
    
    private static AccumulationPattern extractNestedRecurrence(For node, int depth) {
    // Remove the depth != 2 restriction - support any N dimensions
    if (depth < 2) {
        return null;  // Need at least 2 dimensions for nested recurrence
    }
    
    // Collect all nested loops
    List<For> loops = new ArrayList<For>();
    For current = node;
    for (int i = 0; i < depth; i++) {
        loops.add(current);
        if (i < depth - 1 && current.body != null && current.body.statements != null &&
            current.body.statements.size() == 1 && current.body.statements.get(0) instanceof For) {
            current = (For) current.body.statements.get(0);
        }
    }
    
    List<Expr> bounds = new ArrayList<Expr>();
    List<String> iterators = new ArrayList<String>();
    for (For loop : loops) {
        iterators.add(loop.iterator);
        if (loop.range != null && loop.range.end != null) {
            bounds.add(loop.range.end);
        } else {
            return null;
        }
    }
    
    // Get the innermost assignment
    For innermost = loops.get(loops.size() - 1);
    Expr assignedExpr = null;
    String targetVar = null;
    for (Stmt stmt : innermost.body.statements) {
        if (stmt instanceof Assignment) {
            Assignment assign = (Assignment) stmt;
            if (!assign.isDeclaration && assign.left instanceof Identifier) {
                targetVar = ((Identifier) assign.left).name;
                assignedExpr = assign.right;
                break;
            }
        }
    }
    
    if (assignedExpr == null || targetVar == null) {
        return null;
    }
    
    // Check dependencies on previous indices in each dimension
    boolean[] hasPrev = new boolean[depth];
    Set<String> deps = collectDependencies(assignedExpr);
    
    for (int d = 0; d < depth; d++) {
        String iter = iterators.get(d);
        // Check for iter-1 pattern
        if (deps.contains(iter + "-1") || 
            deps.contains(iter + " - 1") ||
            assignedExpr.toString().contains(iter + "-1") ||
            assignedExpr.toString().contains(iter + " - 1")) {
            hasPrev[d] = true;
        }
    }
    
    // Need at least one dependency on previous values
    boolean hasAnyPrev = false;
    for (int d = 0; d < depth; d++) {
        if (hasPrev[d]) {
            hasAnyPrev = true;
            break;
        }
    }
    if (!hasAnyPrev) {
        return null;
    }
    
    // Build ND coefficients tensor: size [2^depth][2^depth][depth+1]
    int stateSize = 1 << depth;
    AutoStackingNumber[][][] ndCoeffs = new AutoStackingNumber[stateSize][stateSize][depth + 1];
    for (int i = 0; i < stateSize; i++) {
        for (int j = 0; j < stateSize; j++) {
            for (int k = 0; k <= depth; k++) {
                ndCoeffs[i][j][k] = ZERO;
            }
        }
    }
    
    // Set coefficients based on which dimensions have dependencies
    for (int d = 0; d < depth; d++) {
        if (hasPrev[d]) {
            // Map from state with bit d set to current state
            for (int prevState = 0; prevState < stateSize; prevState++) {
                if ((prevState & (1 << d)) != 0) {
                    int currState = prevState; // or compute properly
                    ndCoeffs[currState][prevState][d] = ONE;
                }
            }
        }
    }
    
    // Initial state size = 2^depth
    AutoStackingNumber[] initialState = new AutoStackingNumber[stateSize];
    for (int i = 0; i < stateSize; i++) {
        initialState[i] = ZERO;
    }
    
    return new AccumulationPattern(
        AccumulationType.NREC,
        new String[]{targetVar},
        bounds, iterators, ndCoeffs, initialState
    );
}
    
    // ========== Flat Sum Extraction ==========
    
    private static AccumulationPattern extractFlatSum(Stmt stmt, String iterator,
                                                       Expr startBound, Expr endBound, Expr stepBound) {
        if (!(stmt instanceof Assignment)) {
            return null;
        }
        
        Assignment assign = (Assignment) stmt;
        if (assign.isDeclaration) {
            return null;
        }
        if (!(assign.left instanceof Identifier)) {
            return null;
        }
        
        String target = ((Identifier) assign.left).name;
        Expr right = assign.right;
        
        if (!(right instanceof BinaryOp)) {
            return null;
        }
        
        BinaryOp bin = (BinaryOp) right;
        if (!(bin.left instanceof Identifier)) {
            return null;
        }
        
        String leftVar = ((Identifier) bin.left).name;
        if (!leftVar.equals(target)) {
            return null;
        }
        
        if (!"+".equals(bin.op)) {
            return null;
        }
        
        Expr expr = bin.right;
        
        if (!containsIterator(expr, iterator)) {
            return new AccumulationPattern(
                AccumulationType.FSUM,
                new String[]{target},
                startBound, endBound, stepBound,
                null, expr
            );
        }
        
        AutoStackingNumber[] coeffs = extractPolynomialCoefficients(expr, iterator);
        if (coeffs == null) {
            return null;
        }
        
        return new AccumulationPattern(
            AccumulationType.FSUM,
            new String[]{target},
            startBound, endBound, stepBound,
            coeffs, null
        );
    }
    
    // ========== Flat Recurrence Extraction ==========
    
    private static AccumulationPattern extractFlatRecurrence(List<Stmt> stmts, String iterator,
                                                          Expr startBound, Expr endBound, Expr stepBound) {
    
    Map<String, Expr> tempVars = new HashMap<String, Expr>();
    List<Stmt> nonDeclStmts = new ArrayList<Stmt>();
    
    for (Stmt stmt : stmts) {
        
        if (stmt instanceof Var) {
            Var varDecl = (Var) stmt;
            if (varDecl.value != null && varDecl.name != null) {
                tempVars.put(varDecl.name, varDecl.value);
            }
        } else if (stmt instanceof Assignment && ((Assignment) stmt).isDeclaration) {
            Assignment assign = (Assignment) stmt;
            if (assign.left instanceof Identifier && assign.right != null) {
                tempVars.put(((Identifier) assign.left).name, assign.right);
            }
        } else {
            nonDeclStmts.add(stmt);
        }
    }
    
    Map<String, Assignment> assignments = new LinkedHashMap<String, Assignment>();
    
    for (Stmt stmt : nonDeclStmts) {
        if (stmt instanceof Assignment) {
            Assignment assign = (Assignment) stmt;
            if (!assign.isDeclaration && assign.left instanceof Identifier) {
                String var = ((Identifier) assign.left).name;
                assignments.put(var, assign);
            }
        }
    }
    
    if (assignments.size() != 2) {
        return null;
    }
    
    String[] vars = assignments.keySet().toArray(new String[2]);
    Assignment a1 = assignments.get(vars[0]);
    Assignment a2 = assignments.get(vars[1]);
    
    // Get dependencies with temp var substitution
    Expr right1 = substituteTempVars(a1.right, tempVars);
    Expr right2 = substituteTempVars(a2.right, tempVars);
    
    Set<String> deps1 = collectDependencies(right1);
    Set<String> deps2 = collectDependencies(right2);
    
    boolean isRecurrence = (deps1.contains(vars[1]) && deps2.contains(vars[0])) ||
                           (deps1.contains(vars[0]) && deps2.contains(vars[1]));
    
    if (!isRecurrence) {
        return null;
    }
    
    AutoStackingNumber[] coeffs = extractRecurrenceCoefficients(right1, right2, vars);
    if (coeffs == null) {
        return null;
    }
    return new AccumulationPattern(
        AccumulationType.FREC,
        vars,
        startBound, endBound, stepBound,
        coeffs
    );
}
    
    // ========== Helper Methods ==========
    
    private static final AutoStackingNumber ZERO = AutoStackingNumber.fromLong(0);
    private static final AutoStackingNumber ONE = AutoStackingNumber.fromLong(1);
    private static final AutoStackingNumber MINUS_ONE = AutoStackingNumber.fromLong(-1);
    private static final int MAX_LAG = 64;
    
    private static Expr extractInnermostExpression(For node) {
        if (node == null || node.body == null || node.body.statements == null) {
            return null;
        }
        
        for (Stmt stmt : node.body.statements) {
            if (stmt instanceof Assignment) {
                Assignment assign = (Assignment) stmt;
                if (!assign.isDeclaration && assign.left instanceof Identifier) {
                    if (assign.right instanceof BinaryOp) {
                        BinaryOp bin = (BinaryOp) assign.right;
                        if ("+".equals(bin.op) && bin.left instanceof Identifier) {
                            String leftVar = ((Identifier) bin.left).name;
                            if (leftVar.equals(((Identifier) assign.left).name)) {
                                return bin.right;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
    
    private static AutoStackingNumber[] extractPolynomialCoefficientsForIterator(Expr expr, String iterator) {
        if (!containsIterator(expr, iterator)) {
            return null;
        }
        
        if (expr instanceof Identifier && ((Identifier) expr).name.equals(iterator)) {
            return new AutoStackingNumber[]{ZERO, ONE, ZERO, ZERO};
        }
        
        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            if ("*".equals(bin.op)) {
                if (bin.left instanceof Identifier && ((Identifier) bin.left).name.equals(iterator) &&
                    bin.right instanceof Identifier && ((Identifier) bin.right).name.equals(iterator)) {
                    return new AutoStackingNumber[]{ZERO, ZERO, ONE, ZERO};
                }
                AutoStackingNumber constVal = null;
                if (bin.left instanceof IntLiteral && 
                    bin.right instanceof Identifier && ((Identifier) bin.right).name.equals(iterator)) {
                    constVal = ((IntLiteral) bin.left).value;
                    return new AutoStackingNumber[]{ZERO, constVal, ZERO, ZERO};
                }
                if (bin.right instanceof IntLiteral && 
                    bin.left instanceof Identifier && ((Identifier) bin.left).name.equals(iterator)) {
                    constVal = ((IntLiteral) bin.right).value;
                    return new AutoStackingNumber[]{ZERO, constVal, ZERO, ZERO};
                }
            }
        }
        
        if (expr instanceof IntLiteral) {
            return new AutoStackingNumber[]{((IntLiteral) expr).value, ZERO, ZERO, ZERO};
        }
        
        return null;
    }
    
    private static boolean containsIterator(Expr expr, String iterator) {
        if (expr == null) return false;
        if (expr instanceof Identifier) {
            return ((Identifier) expr).name.equals(iterator);
        }
        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            return containsIterator(bin.left, iterator) || containsIterator(bin.right, iterator);
        }
        if (expr instanceof Unary) {
            return containsIterator(((Unary) expr).operand, iterator);
        }
        if (expr instanceof IntLiteral || expr instanceof FloatLiteral) {
            return false;
        }
        return true;
    }
    
    private static AutoStackingNumber[] extractPolynomialCoefficients(Expr expr, String iterator) {
        if (expr instanceof Identifier && ((Identifier) expr).name.equals(iterator)) {
            return new AutoStackingNumber[]{ZERO, ONE, ZERO, ZERO};
        }
        
        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            if ("*".equals(bin.op)) {
                if (bin.left instanceof Identifier && ((Identifier) bin.left).name.equals(iterator) &&
                    bin.right instanceof Identifier && ((Identifier) bin.right).name.equals(iterator)) {
                    return new AutoStackingNumber[]{ZERO, ZERO, ONE, ZERO};
                }
                AutoStackingNumber constVal = null;
                if (bin.left instanceof IntLiteral && 
                    bin.right instanceof Identifier && ((Identifier) bin.right).name.equals(iterator)) {
                    constVal = ((IntLiteral) bin.left).value;
                    return new AutoStackingNumber[]{ZERO, constVal, ZERO, ZERO};
                }
                if (bin.right instanceof IntLiteral && 
                    bin.left instanceof Identifier && ((Identifier) bin.left).name.equals(iterator)) {
                    constVal = ((IntLiteral) bin.right).value;
                    return new AutoStackingNumber[]{ZERO, constVal, ZERO, ZERO};
                }
            }
        }
        
        if (expr instanceof BinaryOp) {
            BinaryOp outer = (BinaryOp) expr;
            if ("*".equals(outer.op) && outer.left instanceof BinaryOp) {
                BinaryOp inner = (BinaryOp) outer.left;
                if ("*".equals(inner.op)) {
                    if (inner.left instanceof Identifier && ((Identifier) inner.left).name.equals(iterator) &&
                        inner.right instanceof Identifier && ((Identifier) inner.right).name.equals(iterator) &&
                        outer.right instanceof Identifier && ((Identifier) outer.right).name.equals(iterator)) {
                        return new AutoStackingNumber[]{ZERO, ZERO, ZERO, ONE};
                    }
                }
            }
        }
        
        if (expr instanceof IntLiteral) {
            return new AutoStackingNumber[]{((IntLiteral) expr).value, ZERO, ZERO, ZERO};
        }
        
        return null;
    }
    
    private static Expr substituteTempVars(Expr expr, Map<String, Expr> tempVars) {
        if (expr == null) return null;
        if (expr instanceof Identifier) {
            String name = ((Identifier) expr).name;
            if (tempVars.containsKey(name)) {
                return tempVars.get(name);
            }
            return expr;
        }
        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            Expr left = substituteTempVars(bin.left, tempVars);
            Expr right = substituteTempVars(bin.right, tempVars);
            return new BinaryOp(left, bin.op, right);
        }
        if (expr instanceof Unary) {
            Unary unary = (Unary) expr;
            Expr operand = substituteTempVars(unary.operand, tempVars);
            return new Unary(unary.op, operand);
        }
        return expr;
    }
    
    private static AutoStackingNumber[] extractRecurrenceCoefficients(Expr expr1, Expr expr2, String[] vars) {
        AutoStackingNumber[] coeffs = new AutoStackingNumber[3];
        coeffs[0] = ZERO;
        coeffs[1] = ZERO;
        coeffs[2] = ZERO;
        
        if (expr1 instanceof Identifier) {
            String var1 = ((Identifier) expr1).name;
            if (var1.equals(vars[1])) {
                coeffs[1] = ONE;
            }
        }
        
        if (expr2 instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr2;
            if ("+".equals(bin.op)) {
                if (bin.left instanceof Identifier && bin.right instanceof Identifier) {
                    String left = ((Identifier) bin.left).name;
                    String right = ((Identifier) bin.right).name;
                    if ((left.equals(vars[0]) && right.equals(vars[1])) ||
                        (left.equals(vars[1]) && right.equals(vars[0]))) {
                        coeffs[0] = ONE;
                        coeffs[1] = ONE;
                    }
                }
            }
        }
        
        return coeffs;
    }
    
    private static Set<String> collectDependencies(Expr expr) {
        Set<String> deps = new HashSet<String>();
        collectDepsRecursive(expr, deps);
        return deps;
    }
    
    private static void collectDepsRecursive(Expr expr, Set<String> deps) {
        if (expr == null) return;
        if (expr instanceof Identifier) {
            deps.add(((Identifier) expr).name);
            return;
        }
        if (expr instanceof BinaryOp) {
            BinaryOp bin = (BinaryOp) expr;
            collectDepsRecursive(bin.left, deps);
            collectDepsRecursive(bin.right, deps);
            return;
        }
        if (expr instanceof Unary) {
            collectDepsRecursive(((Unary) expr).operand, deps);
            return;
        }
        if (expr instanceof IndexAccess) {
            IndexAccess access = (IndexAccess) expr;
            collectDepsRecursive(access.array, deps);
            collectDepsRecursive(access.index, deps);
        }
    }
    
    private static int extractLag(Expr indexExpr, String iterator) {
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
    
    private static AutoStackingNumber toNumericLiteral(Expr expr) {
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
                return ZERO.subtract(inner);
            }
            if ("+".equals(unary.op)) {
                return toNumericLiteral(unary.operand);
            }
        }
        return null;
    }
}