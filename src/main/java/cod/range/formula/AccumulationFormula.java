package cod.range.formula;

import cod.math.AutoStackingNumber;
import java.util.*;

/**
 * Unified formula for all accumulation patterns:
 * 
 * FSUM - Flat Sum: polynomial sums (1D, O(1))
 * NSUM - Nested Sum: product of sums (ND, O(1))
 * FREC - Flat Recurrence: 1D scalar recurrence (O(log n))
 * NREC - Nested Recurrence: ND array recurrence (O(log maxBound))
 * VEC  - Vector Recurrence: multiple coupled recurrences (O(log n))
 */
public class AccumulationFormula {
    
    private static final AutoStackingNumber ZERO = AutoStackingNumber.fromLong(0);
    private static final AutoStackingNumber ONE = AutoStackingNumber.fromLong(1);
    private static final AutoStackingNumber TWO = AutoStackingNumber.fromLong(2);
    private static final AutoStackingNumber SIX = AutoStackingNumber.fromLong(6);
    
    // Type constants
    public static final int TYPE_FSUM = 0;
    public static final int TYPE_FREC = 1;
    public static final int TYPE_NSUM = 2;
    public static final int TYPE_NREC = 3;
    public static final int TYPE_VEC = 4;
    
    private final int type;
    
    // Common fields
    private AutoStackingNumber[] initialState;
    
    // FSUM fields
    private AutoStackingNumber[] polynomialCoeffs;
    private AutoStackingNumber actualStart;
    private AutoStackingNumber actualEnd;
    private AutoStackingNumber actualStep;
    
    // FREC fields
    private AutoStackingNumber[][] transitionMatrix;
    private int matrixDim;
    
    // NSUM fields
    private List<AutoStackingNumber> nestedBounds;
    private List<AutoStackingNumber[]> nestedFactorCoeffs;
    private boolean isProductOfSums;
    
    // NREC fields
    private int dimensions;
    private AutoStackingNumber[] dimBounds;
    private AutoStackingNumber[][][] ndCoeffs;
    private int stateSize;
    private AutoStackingNumber[][] ndTransitionMatrix;
    private Map<Long, AutoStackingNumber[][]> ndPowerCache;
    
    // VEC fields
    private int vectorDim;
    private int vectorOrder;
    private int vectorMatrixDim;
    private AutoStackingNumber[][] vectorTransitionMatrix;
    
    // Caching for FREC
    private transient long lastIndex = -1;
    private transient AutoStackingNumber[] lastState = null;
    
    // ========== Constructors ==========
    
    // FSUM Constructor
    public AccumulationFormula(AutoStackingNumber[] coeffs, AutoStackingNumber initialValue,
                                AutoStackingNumber start, AutoStackingNumber end, AutoStackingNumber step) {
        this.type = TYPE_FSUM;
        this.polynomialCoeffs = new AutoStackingNumber[4];
        for (int i = 0; i < 4 && i < coeffs.length; i++) {
            this.polynomialCoeffs[i] = coeffs[i] != null ? coeffs[i] : ZERO;
        }
        this.initialState = new AutoStackingNumber[]{initialValue};
        this.actualStart = start;
        this.actualEnd = end;
        this.actualStep = step;
        
        // Null other fields
        this.transitionMatrix = null;
        this.matrixDim = 0;
        this.nestedBounds = null;
        this.nestedFactorCoeffs = null;
        this.isProductOfSums = false;
        this.dimensions = 0;
        this.dimBounds = null;
        this.ndCoeffs = null;
        this.stateSize = 0;
        this.vectorDim = 0;
        this.vectorOrder = 0;
    }
    
    // FREC Constructor (1D recurrence)
    public AccumulationFormula(AutoStackingNumber[] coeffs, AutoStackingNumber[] initialValues) {
        this.type = TYPE_FREC;
        this.polynomialCoeffs = null;
        this.initialState = new AutoStackingNumber[3];
        this.actualStart = ZERO;
        this.actualEnd = ZERO;
        this.actualStep = ONE;
        
        this.transitionMatrix = new AutoStackingNumber[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                this.transitionMatrix[i][j] = ZERO;
            }
        }
        
        AutoStackingNumber a = coeffs.length > 0 ? coeffs[0] : ZERO;
        AutoStackingNumber b = coeffs.length > 1 ? coeffs[1] : ZERO;
        AutoStackingNumber c = coeffs.length > 2 ? coeffs[2] : ZERO;
        
        this.transitionMatrix[0][0] = a;
        this.transitionMatrix[0][1] = b;
        this.transitionMatrix[0][2] = c;
        this.transitionMatrix[1][0] = ONE;
        this.transitionMatrix[2][2] = ONE;
        
        this.initialState[0] = initialValues.length > 1 ? initialValues[1] : initialValues[0];
        this.initialState[1] = initialValues.length > 0 ? initialValues[0] : ZERO;
        this.initialState[2] = ONE;
        
        this.matrixDim = 3;
        
        // Null other fields
        this.nestedBounds = null;
        this.nestedFactorCoeffs = null;
        this.isProductOfSums = false;
        this.dimensions = 0;
        this.dimBounds = null;
        this.ndCoeffs = null;
        this.stateSize = 0;
        this.vectorDim = 0;
        this.vectorOrder = 0;
    }
    
    // NSUM Constructor (nested sum)
    public AccumulationFormula(List<AutoStackingNumber> bounds, List<AutoStackingNumber[]> factorCoeffs,
                                boolean isProductOfSums, AutoStackingNumber initialValue) {
        this.type = TYPE_NSUM;
        this.nestedBounds = bounds;
        this.nestedFactorCoeffs = factorCoeffs;
        this.isProductOfSums = isProductOfSums;
        this.initialState = new AutoStackingNumber[]{initialValue};
        
        // Null other fields
        this.polynomialCoeffs = null;
        this.transitionMatrix = null;
        this.matrixDim = 0;
        this.actualStart = ZERO;
        this.actualEnd = ZERO;
        this.actualStep = ONE;
        this.dimensions = 0;
        this.dimBounds = null;
        this.ndCoeffs = null;
        this.stateSize = 0;
        this.vectorDim = 0;
        this.vectorOrder = 0;
    }
    
    // NREC Constructor (ND recurrence)
    public AccumulationFormula(int dimensions, AutoStackingNumber[] dimBounds,
                                AutoStackingNumber[][][] ndCoeffs, AutoStackingNumber[] initialState) {
        this.type = TYPE_NREC;
        this.dimensions = dimensions;
        this.dimBounds = dimBounds;
        this.ndCoeffs = ndCoeffs;
        this.initialState = initialState;
        this.stateSize = 1 << dimensions;
        this.ndPowerCache = new HashMap<Long, AutoStackingNumber[][]>();
        
        // Build ND transition matrix
        int matrixSize = stateSize + 1;
        this.ndTransitionMatrix = new AutoStackingNumber[matrixSize][matrixSize];
        for (int i = 0; i < matrixSize; i++) {
            for (int j = 0; j < matrixSize; j++) {
                ndTransitionMatrix[i][j] = ZERO;
            }
        }
        
        // Build transitions for each previous state
        for (int prevState = 0; prevState < stateSize; prevState++) {
            int currState = computeCurrentState(prevState);
            AutoStackingNumber coeff = getNDCoefficient(prevState);
            ndTransitionMatrix[currState][prevState] = ndTransitionMatrix[currState][prevState].add(coeff);
        }
        
        // Set constant term
        ndTransitionMatrix[stateSize][stateSize] = ONE;
        AutoStackingNumber constantTerm = getNDConstantTerm();
        for (int prevState = 0; prevState < stateSize; prevState++) {
            ndTransitionMatrix[prevState][stateSize] = constantTerm;
        }
        
        // Null other fields
        this.polynomialCoeffs = null;
        this.transitionMatrix = null;
        this.matrixDim = 0;
        this.actualStart = ZERO;
        this.actualEnd = ZERO;
        this.actualStep = ONE;
        this.nestedBounds = null;
        this.nestedFactorCoeffs = null;
        this.isProductOfSums = false;
        this.vectorDim = 0;
        this.vectorOrder = 0;
    }
    
    // VEC Constructor (vector recurrence)
    public AccumulationFormula(int vectorDim, int vectorOrder, AutoStackingNumber[][][] coefficients,
                                AutoStackingNumber[] constants, AutoStackingNumber[][] seedValues) {
        this.type = TYPE_VEC;
        this.vectorDim = vectorDim;
        this.vectorOrder = vectorOrder;
        
        // Build transition matrix for vector recurrence
        int baseDim = vectorDim * vectorOrder;
        int matrixDim = baseDim + (hasNonZeroConstant(constants) ? 1 : 0);
        this.vectorTransitionMatrix = new AutoStackingNumber[matrixDim][matrixDim];
        this.vectorMatrixDim = matrixDim;
        
        // Initialize to zeros
        for (int i = 0; i < matrixDim; i++) {
            for (int j = 0; j < matrixDim; j++) {
                vectorTransitionMatrix[i][j] = ZERO;
            }
        }
        
        // Fill coefficients for current values
        for (int row = 0; row < vectorDim; row++) {
            AutoStackingNumber[][] coeffRow = coefficients[row];
            if (coeffRow != null) {
                for (int col = 0; col < baseDim; col++) {
                    if (col < coeffRow.length && coeffRow[col] != null) {
                        vectorTransitionMatrix[row][col] = coeffRow[row][col];
                    }
                }
            }
        }
        
        // Fill shift registers
        for (int block = 1; block < vectorOrder; block++) {
            for (int seq = 0; seq < vectorDim; seq++) {
                int row = (block * vectorDim) + seq;
                int col = ((block - 1) * vectorDim) + seq;
                vectorTransitionMatrix[row][col] = ONE;
            }
        }
        
        // Fill constant term column
        if (hasNonZeroConstant(constants)) {
            int constCol = matrixDim - 1;
            for (int row = 0; row < vectorDim; row++) {
                vectorTransitionMatrix[row][constCol] = constants[row];
            }
            vectorTransitionMatrix[constCol][constCol] = ONE;
        }
        
        // Build initial state
        int stateSize = matrixDim;
        this.initialState = new AutoStackingNumber[stateSize];
        for (int block = 0; block < vectorOrder; block++) {
            for (int seq = 0; seq < vectorDim; seq++) {
                this.initialState[(block * vectorDim) + seq] = seedValues[seq][vectorOrder - 1 - block];
            }
        }
        if (hasNonZeroConstant(constants)) {
            this.initialState[stateSize - 1] = ONE;
        }
        
        // Null other fields
        this.polynomialCoeffs = null;
        this.transitionMatrix = null;
        this.matrixDim = 0;
        this.actualStart = ZERO;
        this.actualEnd = ZERO;
        this.actualStep = ONE;
        this.nestedBounds = null;
        this.nestedFactorCoeffs = null;
        this.isProductOfSums = false;
        this.dimensions = 0;
        this.dimBounds = null;
        this.ndCoeffs = null;
        this.stateSize = 0;
    }
    
    // ========== Helper Methods ==========
    
    private static boolean hasNonZeroConstant(AutoStackingNumber[] constants) {
        if (constants == null) return false;
        for (AutoStackingNumber c : constants) {
            if (c != null && !c.isZero()) return true;
        }
        return false;
    }
    
    private int computeCurrentState(int prevState) {
        int currState = 0;
        for (int d = 0; d < dimensions; d++) {
            int bit = (prevState >> d) & 1;
            if (bit == 1) {
                currState |= (1 << d);
            }
        }
        return currState;
    }
    
    private AutoStackingNumber getNDCoefficient(int prevState) {
        AutoStackingNumber result = ZERO;
        for (int d = 0; d < dimensions; d++) {
            int bit = (prevState >> d) & 1;
            if (bit == 1 && ndCoeffs != null && ndCoeffs.length > d) {
                AutoStackingNumber[][] dimCoeff = ndCoeffs[d];
                if (dimCoeff != null && dimCoeff.length > 0) {
                    result = result.add(dimCoeff[0][0]);
                }
            }
        }
        return result;
    }
    
    private AutoStackingNumber getNDConstantTerm() {
        if (ndCoeffs != null && ndCoeffs.length > 0 && 
            ndCoeffs[0] != null && ndCoeffs[0].length > 0 &&
            ndCoeffs[0][0].length > dimensions) {
            return ndCoeffs[0][0][dimensions];
        }
        return ZERO;
    }
    
    // ========== Main Evaluate Methods ==========
    
    public synchronized AutoStackingNumber evaluate() {
        switch (type) {
            case TYPE_FSUM:
                return evaluateFSum();
            case TYPE_FREC:
                AutoStackingNumber diff = actualEnd.subtract(actualStart);
                AutoStackingNumber steps = diff.divide(actualStep);
                return evaluateFRec(steps);
            case TYPE_NSUM:
                return evaluateNSum();
            case TYPE_NREC:
                return evaluateNRec();
            case TYPE_VEC:
                return evaluateVRec();
            default:
                return ZERO;
        }
    }
    
    public synchronized AutoStackingNumber evaluate(AutoStackingNumber n) {
        if (type == TYPE_FSUM) {
            return evaluateFSumWithIterations(n);
        } else if (type == TYPE_FREC) {
            return evaluateFRec(n);
        } else if (type == TYPE_VEC) {
            return evaluateVRec(n);
        }
        return evaluate();
    }
    
    public synchronized AutoStackingNumber evaluateAt(AutoStackingNumber[] indices) {
        if (type == TYPE_NREC) {
            return evaluateNRecAt(indices);
        }
        return ZERO;
    }
    
    public synchronized AutoStackingNumber evaluateAt(int sequenceIndex, AutoStackingNumber n) {
        if (type == TYPE_VEC) {
            long nLong;
            try {
                nLong = n.longValue();
            } catch (ArithmeticException e) {
                return evaluateVRecIterative(n, sequenceIndex);
            }
            
            if (nLong <= 0) {
                return initialState[sequenceIndex];
            }
            
            AutoStackingNumber[][] power = matrixPower(vectorTransitionMatrix, nLong);
            AutoStackingNumber result = ZERO;
            for (int j = 0; j < vectorMatrixDim; j++) {
                result = result.add(power[sequenceIndex][j].multiply(initialState[j]));
            }
            return result;
        }
        return ZERO;
    }
    
    // ========== FSUM Methods ==========
    
    private AutoStackingNumber evaluateFSum() {
        if (!actualStep.equals(ONE)) {
            return evaluateFSumIterative();
        }
        
        AutoStackingNumber sumToEnd = sumFromOne(actualEnd);
        AutoStackingNumber sumToStartMinus1 = sumFromOne(actualStart.subtract(ONE));
        AutoStackingNumber total = sumToEnd.subtract(sumToStartMinus1);
        
        return initialState[0].add(total);
    }
    
    private AutoStackingNumber evaluateFSumWithIterations(AutoStackingNumber n) {
        if (!actualStep.equals(ONE) || !actualStart.equals(ONE)) {
            return evaluateFSumIterative();
        }
        AutoStackingNumber total = sumFromOne(n);
        return initialState[0].add(total);
    }
    
    private AutoStackingNumber sumFromOne(AutoStackingNumber n) {
        if (n.compareTo(ZERO) <= 0) return ZERO;
        
        AutoStackingNumber nPlus1 = n.add(ONE);
        AutoStackingNumber twoNPlus1 = n.multiply(TWO).add(ONE);
        
        AutoStackingNumber sumConst = n;
        AutoStackingNumber sumLinear = n.multiply(nPlus1).divide(TWO);
        AutoStackingNumber sumSquares = n.multiply(nPlus1).multiply(twoNPlus1).divide(SIX);
        AutoStackingNumber sumCubesBase = n.multiply(nPlus1).divide(TWO);
        AutoStackingNumber sumCubes = sumCubesBase.multiply(sumCubesBase);
        
        AutoStackingNumber total = ZERO;
        total = total.add(polynomialCoeffs[0].multiply(sumConst));
        total = total.add(polynomialCoeffs[1].multiply(sumLinear));
        total = total.add(polynomialCoeffs[2].multiply(sumSquares));
        total = total.add(polynomialCoeffs[3].multiply(sumCubes));
        
        return total;
    }
    
    private AutoStackingNumber evaluateFSumIterative() {
        AutoStackingNumber sum = initialState[0];
        AutoStackingNumber current = actualStart;
        
        while (current.compareTo(actualEnd) <= 0) {
            AutoStackingNumber term = ZERO;
            if (!polynomialCoeffs[0].isZero()) term = term.add(polynomialCoeffs[0]);
            if (!polynomialCoeffs[1].isZero()) term = term.add(polynomialCoeffs[1].multiply(current));
            if (!polynomialCoeffs[2].isZero()) term = term.add(polynomialCoeffs[2].multiply(current.multiply(current)));
            if (!polynomialCoeffs[3].isZero()) term = term.add(polynomialCoeffs[3].multiply(current.multiply(current).multiply(current)));
            sum = sum.add(term);
            current = current.add(actualStep);
        }
        return sum;
    }
    
    // ========== FREC Methods (1D Recurrence) ==========
    
    private AutoStackingNumber evaluateFRec(AutoStackingNumber n) {
        long nLong;
        try {
            nLong = n.longValue();
        } catch (ArithmeticException e) {
            return evaluateFRecIterative(n);
        }
        
        if (nLong <= 0) return initialState[1];
        if (nLong == 1) return initialState[0];
        
        if (lastState != null && nLong == lastIndex) return lastState[0];
        
        if (lastState != null && nLong == lastIndex + 1) {
            AutoStackingNumber next = ZERO;
            for (int j = 0; j < matrixDim; j++) {
                next = next.add(transitionMatrix[0][j].multiply(lastState[j]));
            }
            AutoStackingNumber[] newState = new AutoStackingNumber[matrixDim];
            newState[0] = next;
            for (int i = 1; i < matrixDim; i++) {
                newState[i] = lastState[i - 1];
            }
            newState[matrixDim - 1] = ONE;
            lastState = newState;
            lastIndex = nLong;
            return next;
        }
        
        AutoStackingNumber[][] power = matrixPower(transitionMatrix, nLong);
        AutoStackingNumber result = ZERO;
        for (int j = 0; j < matrixDim; j++) {
            result = result.add(power[0][j].multiply(initialState[j]));
        }
        
        lastState = new AutoStackingNumber[matrixDim];
        lastState[0] = result;
        for (int i = 1; i < matrixDim; i++) {
            lastState[i] = (i == matrixDim - 1) ? ONE : ZERO;
        }
        lastIndex = nLong;
        
        return result;
    }
    
    private AutoStackingNumber evaluateFRecIterative(AutoStackingNumber n) {
        AutoStackingNumber current = initialState[1];
        AutoStackingNumber prev = initialState[0];
        AutoStackingNumber count = ONE;
        
        while (count.compareTo(n) < 0) {
            AutoStackingNumber next = ZERO;
            for (int j = 0; j < matrixDim; j++) {
                next = next.add(transitionMatrix[0][j].multiply(
                    j == 0 ? current : (j == 1 ? prev : ONE)
                ));
            }
            prev = current;
            current = next;
            count = count.add(ONE);
        }
        
        return current;
    }
    
    // ========== NSUM Methods (Nested Sum) ==========
    
    private AutoStackingNumber evaluateNSum() {
        AutoStackingNumber result = initialState[0];
        
        if (isProductOfSums) {
            for (int dim = 0; dim < nestedBounds.size(); dim++) {
                AutoStackingNumber bound = nestedBounds.get(dim);
                AutoStackingNumber[] coeffs = nestedFactorCoeffs.get(dim);
                AutoStackingNumber factorSum = computePolynomialSum(coeffs, bound);
                result = result.multiply(factorSum);
            }
        } else {
            result = evaluateNSumIterative();
        }
        
        return result;
    }
    
    private AutoStackingNumber computePolynomialSum(AutoStackingNumber[] coeffs, AutoStackingNumber n) {
        if (n.compareTo(ZERO) <= 0) return ZERO;
        
        AutoStackingNumber nPlus1 = n.add(ONE);
        AutoStackingNumber twoNPlus1 = n.multiply(TWO).add(ONE);
        
        AutoStackingNumber sumConst = n;
        AutoStackingNumber sumLinear = n.multiply(nPlus1).divide(TWO);
        AutoStackingNumber sumSquares = n.multiply(nPlus1).multiply(twoNPlus1).divide(SIX);
        AutoStackingNumber sumCubesBase = n.multiply(nPlus1).divide(TWO);
        AutoStackingNumber sumCubes = sumCubesBase.multiply(sumCubesBase);
        
        AutoStackingNumber total = ZERO;
        if (coeffs.length > 0 && coeffs[0] != null) total = total.add(coeffs[0].multiply(sumConst));
        if (coeffs.length > 1 && coeffs[1] != null) total = total.add(coeffs[1].multiply(sumLinear));
        if (coeffs.length > 2 && coeffs[2] != null) total = total.add(coeffs[2].multiply(sumSquares));
        if (coeffs.length > 3 && coeffs[3] != null) total = total.add(coeffs[3].multiply(sumCubes));
        
        return total;
    }
    
    private AutoStackingNumber evaluateNSumIterative() {
        AutoStackingNumber sum = initialState[0];
        List<AutoStackingNumber> indices = new ArrayList<AutoStackingNumber>();
        for (int i = 0; i < nestedBounds.size(); i++) {
            indices.add(ONE);
        }
        
        boolean done = false;
        while (!done) {
            AutoStackingNumber term = ONE;
            for (int dim = 0; dim < nestedBounds.size(); dim++) {
                AutoStackingNumber[] coeffs = nestedFactorCoeffs.get(dim);
                AutoStackingNumber idx = indices.get(dim);
                AutoStackingNumber factor = ZERO;
                if (coeffs.length > 0 && coeffs[0] != null) factor = factor.add(coeffs[0]);
                if (coeffs.length > 1 && coeffs[1] != null) factor = factor.add(coeffs[1].multiply(idx));
                if (coeffs.length > 2 && coeffs[2] != null) factor = factor.add(coeffs[2].multiply(idx.multiply(idx)));
                if (coeffs.length > 3 && coeffs[3] != null) factor = factor.add(coeffs[3].multiply(idx.multiply(idx).multiply(idx)));
                term = term.multiply(factor);
            }
            sum = sum.add(term);
            
            for (int dim = nestedBounds.size() - 1; dim >= 0; dim--) {
                AutoStackingNumber newIdx = indices.get(dim).add(ONE);
                if (newIdx.compareTo(nestedBounds.get(dim)) <= 0) {
                    indices.set(dim, newIdx);
                    break;
                } else {
                    indices.set(dim, ONE);
                    if (dim == 0) done = true;
                }
            }
        }
        
        return sum;
    }
    
    // ========== NREC Methods (ND Recurrence) ==========
    
    private AutoStackingNumber evaluateNRec() {
        long maxStep = 0;
        for (int d = 0; d < dimensions; d++) {
            long step = dimBounds[d].longValue();
            if (step > maxStep) maxStep = step;
        }
        
        if (maxStep <= 0) {
            int targetState = computeTargetState(dimBounds);
            return initialState[targetState];
        }
        
        AutoStackingNumber[][] power = getNDMatrixPower(maxStep);
        int matrixSize = stateSize + 1;
        AutoStackingNumber[] resultVec = new AutoStackingNumber[matrixSize];
        for (int i = 0; i < matrixSize; i++) {
            resultVec[i] = ZERO;
            for (int j = 0; j < matrixSize; j++) {
                resultVec[i] = resultVec[i].add(power[i][j].multiply(initialState[j]));
            }
        }
        
        int targetState = computeTargetState(dimBounds);
        return resultVec[targetState];
    }
    
    private AutoStackingNumber evaluateNRecAt(AutoStackingNumber[] indices) {
        long maxStep = 0;
        for (int d = 0; d < dimensions; d++) {
            long step = indices[d].longValue();
            if (step > maxStep) maxStep = step;
        }
        
        if (maxStep <= 0) {
            int targetState = computeTargetState(indices);
            return initialState[targetState];
        }
        
        AutoStackingNumber[][] power = getNDMatrixPower(maxStep);
        int matrixSize = stateSize + 1;
        AutoStackingNumber[] resultVec = new AutoStackingNumber[matrixSize];
        for (int i = 0; i < matrixSize; i++) {
            resultVec[i] = ZERO;
            for (int j = 0; j < matrixSize; j++) {
                resultVec[i] = resultVec[i].add(power[i][j].multiply(initialState[j]));
            }
        }
        
        int targetState = computeTargetState(indices);
        return resultVec[targetState];
    }
    
    private int computeTargetState(AutoStackingNumber[] indices) {
        int target = 0;
        for (int d = 0; d < dimensions; d++) {
            if (indices[d].compareTo(ZERO) > 0) {
                target |= (1 << d);
            }
        }
        return target;
    }
    
    private AutoStackingNumber[][] getNDMatrixPower(long power) {
        if (ndPowerCache.containsKey(power)) {
            return ndPowerCache.get(power);
        }
        
        AutoStackingNumber[][] result = matrixPower(ndTransitionMatrix, power);
        ndPowerCache.put(power, result);
        return result;
    }
    
    // ========== VEC Methods (Vector Recurrence) ==========
    
    private AutoStackingNumber evaluateVRec() {
        long steps = 1;
        return evaluateVRec(AutoStackingNumber.fromLong(steps));
    }
    
    private AutoStackingNumber evaluateVRec(AutoStackingNumber n) {
        long nLong;
        try {
            nLong = n.longValue();
        } catch (ArithmeticException e) {
            return evaluateVRecIterative(n, 0);
        }
        
        if (nLong <= 0) {
            return initialState[0];
        }
        
        AutoStackingNumber[][] power = matrixPower(vectorTransitionMatrix, nLong);
        AutoStackingNumber result = ZERO;
        for (int j = 0; j < vectorMatrixDim; j++) {
            result = result.add(power[0][j].multiply(initialState[j]));
        }
        
        return result;
    }
    
    private AutoStackingNumber evaluateVRecIterative(AutoStackingNumber n, int sequenceIndex) {
        AutoStackingNumber[] state = Arrays.copyOf(initialState, initialState.length);
        AutoStackingNumber count = ZERO;
        
        while (count.compareTo(n) < 0) {
            AutoStackingNumber[] next = new AutoStackingNumber[state.length];
            for (int i = 0; i < state.length; i++) {
                next[i] = ZERO;
                for (int j = 0; j < state.length; j++) {
                    next[i] = next[i].add(vectorTransitionMatrix[i][j].multiply(state[j]));
                }
            }
            state = next;
            count = count.add(ONE);
        }
        
        return state[sequenceIndex];
    }
    
    // ========== Utility Methods ==========
    
    private AutoStackingNumber[][] matrixPower(AutoStackingNumber[][] m, long power) {
        int n = m.length;
        AutoStackingNumber[][] result = new AutoStackingNumber[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                result[i][j] = (i == j) ? ONE : ZERO;
            }
        }
        
        AutoStackingNumber[][] base = copyMatrix(m);
        long exp = power;
        
        while (exp > 0) {
            if ((exp & 1) == 1) {
                result = multiplyMatrix(result, base);
            }
            base = multiplyMatrix(base, base);
            exp >>= 1;
        }
        
        return result;
    }
    
    private AutoStackingNumber[][] multiplyMatrix(AutoStackingNumber[][] a, AutoStackingNumber[][] b) {
        int n = a.length;
        AutoStackingNumber[][] result = new AutoStackingNumber[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                AutoStackingNumber sum = ZERO;
                for (int k = 0; k < n; k++) {
                    sum = sum.add(a[i][k].multiply(b[k][j]));
                }
                result[i][j] = sum;
            }
        }
        return result;
    }
    
    private AutoStackingNumber[][] copyMatrix(AutoStackingNumber[][] src) {
        int n = src.length;
        AutoStackingNumber[][] dst = new AutoStackingNumber[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                dst[i][j] = src[i][j];
            }
        }
        return dst;
    }
    
    // ========== Cache Management ==========
    
    public void clearCache() {
        if (ndPowerCache != null) {
            ndPowerCache.clear();
        }
        lastIndex = -1;
        lastState = null;
    }
    
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<String, Object>();
        stats.put("type", type);
        if (ndPowerCache != null) {
            stats.put("ndPowerCacheSize", ndPowerCache.size());
        }
        if (type == TYPE_NREC) {
            stats.put("dimensions", dimensions);
            stats.put("stateSize", stateSize);
            stats.put("matrixSize", stateSize + 1);
        }
        if (type == TYPE_VEC) {
            stats.put("vectorDim", vectorDim);
            stats.put("vectorOrder", vectorOrder);
            stats.put("vectorMatrixDim", vectorMatrixDim);
        }
        return stats;
    }
}