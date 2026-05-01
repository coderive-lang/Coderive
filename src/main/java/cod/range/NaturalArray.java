package cod.range;

import cod.ast.node.*;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.interpreter.Evaluator;
import cod.interpreter.context.ExecutionContext;
import cod.interpreter.handler.TypeHandler;
import cod.math.AutoStackingNumber;
import cod.range.formula.*;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NaturalArray {

    private final Range baseRange;
    private final Evaluator evaluator;
    private ExecutionContext context;
    private Map<Long, Object> cache;
    private boolean isMutable = false;

    // Element type and type handler
    private final String elementType;
    private final TypeHandler typeHandler;

    // ========== OPTIMIZATION: PRIMITIVE UNBOXING ==========
    private long[] unboxedIntCache;
    private double[] unboxedFloatCache;
    private static final int MAX_UNBOXED_SIZE = 10000000; // ~80MB max limit
    private static final long INT_SENTINEL = Long.MIN_VALUE;

    // Conversion support for [text] = [int range]
    private boolean convertToString = false;
    private String targetElementType = null;

    // Cached values as AutoStackingNumber
    private AutoStackingNumber cachedStart = null;
    private AutoStackingNumber cachedEnd = null;
    private AutoStackingNumber cachedStep = null;
    private Long cachedSize = null;

    // Recent index cache for sequential access
    private static final int RECENT_CACHE_SIZE = 64;
    private Object[] recentCache = new Object[RECENT_CACHE_SIZE];
    private long recentCacheStart = -1;
    private boolean recentCacheValid = false;

    private boolean isLexicographicalRange = false;
    private String startString = null;
    private String endString = null;

    // Lex range indices
    private long startIndex = 0;
    private long endIndex = 0;
    private long maxIndex = 0;
    private boolean isUp = true;

    // Single-element cache
    private transient Long lastIndex = null;
    private transient Object lastValue = null;

    private static final long[] POWERS_26 = new long[11];
    private static final long[] POWERS_2 = new long[11];
    private static final long[] TOTAL_UP_TO_LENGTH = new long[11];

    // Formula collections - using array-specific formulas
    private List<SequenceFormula> sequenceFormulas = new ArrayList<SequenceFormula>();
    private List<ConditionalFormula> conditionalFormulas = new ArrayList<ConditionalFormula>();
    
    // For array recurrences - now using AccumulationFormula (FREC type)
    private List<AccumulationFormula> linearRecurrenceFormulas = new ArrayList<AccumulationFormula>();
    
    // For vector recurrences - now using AccumulationFormula (VEC type)
    private List<AccumulationFormula> vectorRecurrenceFormulas = new ArrayList<AccumulationFormula>();
    
    private Map<Long, Object> computedCache = new HashMap<Long, Object>();
    
    // Pending updates for lazy assignment
    private static final int PENDING_UPDATES_TREE_THRESHOLD = 100;
    private List<PendingRangeUpdate> pendingUpdates = new ArrayList<PendingRangeUpdate>();
    private NavigableMap<Long, List<PendingRangeUpdate>> pendingUpdatesByStart = null;
    private NavigableMap<Long, Long> pendingUpdateOrderPrefixByStart = null;
    private boolean pendingUpdateOrderPrefixDirty = false;
    private long nextPendingUpdateOrder = 0L;
    private boolean hasPendingUpdates = false;
    
    // Output cache
    private Map<Long, List<Object>> outputCache = new HashMap<Long, List<Object>>();
    private boolean hasOutputs = false;
    
    // ========== ARRAY TRACKING FIELDS ==========
    private final int arrayId;
    private static final AtomicInteger nextArrayId = new AtomicInteger(1);
    private boolean tracked = false;

    static {
        POWERS_26[0] = 1;
        POWERS_2[0] = 1;
        for (int i = 1; i <= 10; i++) {
            POWERS_26[i] = POWERS_26[i - 1] * 26;
            POWERS_2[i] = POWERS_2[i - 1] * 2;
        }
        TOTAL_UP_TO_LENGTH[0] = 0;
        for (int len = 1; len <= 10; len++) {
            TOTAL_UP_TO_LENGTH[len] = TOTAL_UP_TO_LENGTH[len - 1] + POWERS_2[len] * POWERS_26[len];
        }
    }

    // ========== PROCESSED RANGE CLASS ==========
    
    /**
     * Immutable, pre-processed range with all values converted to longs
     * Created ONCE, used everywhere
     */
    public static class ProcessedRange {
        public final long start;
        public final long end;
        public final long step;
        public final boolean valid;
        public final String error;
        
        public ProcessedRange(Object range) {
            long s = 0, e = 0, st = 0;
            boolean ok = true;
            String err = null;
            
            try {
                s = toLongIndex(RangeObjects.getStart(range));
                e = toLongIndex(RangeObjects.getEnd(range));
                st = calculateStep(range);
            } catch (Exception ex) {
                ok = false;
                err = ex.getMessage();
            }
            
            this.start = s;
            this.end = e;
            this.step = st;
            this.valid = ok;
            this.error = err;
        }
        
        public ProcessedRange(long start, long end, long step) {
            this.start = start;
            this.end = end;
            this.step = step;
            this.valid = true;
            this.error = null;
        }
        
        public ProcessedRange(Object start, Object end, Object step) {
            long s = 0, e = 0, st = 0;
            boolean ok = true;
            String err = null;
            try {
                s = toLongIndex(start);
                e = toLongIndex(end);
                if (step != null) {
                    st = toLongIndex(step);
                } else {
                    st = (s < e) ? 1L : -1L;
                }
            } catch (Exception ex) {
                ok = false;
                err = ex.getMessage();
            }
            this.start = s;
            this.end = e;
            this.step = st;
            this.valid = ok;
            this.error = err;
        }
        
        public boolean contains(long index) {
            if (!valid) return false;
            
            if (step > 0) {
                return index >= start && index <= end && 
                       (index - start) % step == 0;
            } else {
                return index <= start && index >= end && 
                       (start - index) % Math.abs(step) == 0;
            }
        }
        
        public long size() {
            if (!valid) return 0;
            if (step == 0) return 0;
            
            if (step > 0) {
                return ((end - start) / step) + 1;
            }
            return ((start - end) / Math.abs(step)) + 1;
        }
        
        public long indexAt(long offset) {
            if (!valid) return -1;
            if (offset < 0 || offset >= size()) return -1;
            
            if (step > 0) {
                return start + (offset * step);
            }
            return start - (offset * Math.abs(step));
        }
        
        public boolean isAdjacent(ProcessedRange other) {
            if (!valid || !other.valid) return false;
            if (step != other.step) return false;
            
            if (step > 0) {
                return end + step == other.start;
            }
            return end + step == other.start;
        }
        
        public ProcessedRange merge(ProcessedRange other) {
            if (!isAdjacent(other)) {
                throw new IllegalArgumentException("Ranges are not adjacent");
            }
            long newEnd = (step > 0) ? Math.max(end, other.end) : Math.min(end, other.end);
            return new ProcessedRange(start, newEnd, step);
        }
    }

    // ========== PENDING UPDATE CLASS ==========
    
    private static class PendingRangeUpdate implements Comparable<PendingRangeUpdate> {
        final ProcessedRange range;
        final Object value;
        final long order;
        
        PendingRangeUpdate(Object spec, Object value, long order) {
            this.range = new ProcessedRange(spec);
            this.value = value;
            this.order = order;
        }
        
        PendingRangeUpdate(ProcessedRange range, Object value, long order) {
            this.range = range;
            this.value = value;
            this.order = order;
        }
        
        boolean contains(long index) {
            return range.contains(index);
        }
        
        @Override
        public int compareTo(PendingRangeUpdate other) {
            if (this.range.start < other.range.start) return -1;
            if (this.range.start > other.range.start) return 1;
            return 0;
        }
    }

    // ========== CONSTRUCTORS ==========

    public NaturalArray(Range range, Evaluator evaluator, ExecutionContext context) {
        if (range == null) {
            throw new InternalError("NaturalArray constructed with null range");
        }
        if (evaluator == null) {
            throw new InternalError("NaturalArray constructed with null evaluator");
        }
        if (context == null) {
            throw new InternalError("NaturalArray constructed with null context");
        }
        
        this.baseRange = range;
        this.evaluator = evaluator;
        this.context = context;
        
        this.typeHandler = context.getTypeHandler();
        if (this.typeHandler == null) {
            throw new InternalError("NaturalArray constructed with context that has null typeHandler");
        }
        
        this.arrayId = nextArrayId.getAndIncrement();
        this.tracked = false;
        
        this.elementType = determineElementType();
        
        this.cache = null;
        this.isMutable = false;
        this.maxIndex = TOTAL_UP_TO_LENGTH[10] - 1;

        clearRecentCache();

        Object rawStart = evaluator.evaluate(baseRange.start, context);
        Object rawEnd = evaluator.evaluate(baseRange.end, context);

        if (rawStart instanceof String && rawEnd instanceof String) {
            this.isLexicographicalRange = true;
            this.startString = (String) rawStart;
            this.endString = (String) rawEnd;

            if (!isValidLexString(startString) || !isValidLexString(endString)) {
                throw new ProgramError(
                    "Lexicographical range bounds must contain only letters (a-z, A-Z). " +
                    "Got: '" + startString + "' to '" + endString + "'"
                );
            }

            this.startIndex = hierarchicalSequenceToIndex(startString);
            this.endIndex = hierarchicalSequenceToIndex(endString);

            if (startIndex <= endIndex) {
                this.isUp = true;
                if (baseRange.step == null) {
                    this.cachedStep = AutoStackingNumber.one(1);
                } else {
                    Object stepObj = evaluator.evaluate(baseRange.step, context);
                    AutoStackingNumber stepNum = typeHandler.toAutoStackingNumber(stepObj);
                    if (stepNum.compareTo(AutoStackingNumber.zero(1)) <= 0) {
                        throw new ProgramError("Step must be positive for forward lex range");
                    }
                    this.cachedStep = stepNum;
                }
            } else {
                this.isUp = false;
                if (baseRange.step == null) {
                    this.cachedStep = AutoStackingNumber.minusOne(1);
                } else {
                    Object stepObj = evaluator.evaluate(baseRange.step, context);
                    AutoStackingNumber stepNum = typeHandler.toAutoStackingNumber(stepObj);
                    if (stepNum.compareTo(AutoStackingNumber.zero(1)) >= 0) {
                        throw new ProgramError("Step must be negative for reverse lex range");
                    }
                    this.cachedStep = stepNum;
                }
            }

            this.cachedStart = AutoStackingNumber.fromLong(startIndex);
            this.cachedEnd = AutoStackingNumber.fromLong(endIndex);

        } else {
            this.isLexicographicalRange = false;
            
            this.cachedStart = typeHandler.toAutoStackingNumber(rawStart);
            this.cachedEnd = typeHandler.toAutoStackingNumber(rawEnd);
            
            validateRangeBound(cachedStart, "start");
            validateRangeBound(cachedEnd, "end");
            
            if (baseRange.step != null) {
                Object stepObj = evaluator.evaluate(baseRange.step, context);
                this.cachedStep = typeHandler.toAutoStackingNumber(stepObj);
            }
        }
        
        if (ArrayTracker.getCurrentLoopId() != 0) {
            ArrayTracker.registerArray(this);
            this.tracked = true;
        }
    }

    public NaturalArray(Range range, Evaluator evaluator, ExecutionContext context, String targetType) {
        this(range, evaluator, context);
        
        if (targetType != null && targetType.startsWith("[") && targetType.endsWith("]")) {
            String expectedElementType = targetType.substring(1, targetType.length() - 1);
            if (expectedElementType.equals("text") && !this.elementType.equals("text")) {
                this.convertToString = true;
                this.targetElementType = expectedElementType;
                
                this.cachedSize = null;
                this.cachedStart = null;
                this.cachedEnd = null;
                this.cachedStep = null;
            }
        }
    }
    
    // ========== ARRAY TRACKING METHODS ==========
    
    public int getArrayId() {
        return arrayId;
    }
    
    public boolean isTracked() {
        return tracked;
    }
    
    public void enableTracking() {
        if (!tracked) {
            tracked = true;
            if (ArrayTracker.getCurrentLoopId() != 0) {
                ArrayTracker.registerArray(this);
            }
        }
    }
    
    public void disableTracking() {
        tracked = false;
    }
    
    private String determineElementType() {
        Object start = evaluator.evaluate(baseRange.start, context);
        Object end = evaluator.evaluate(baseRange.end, context);
        
        String startType = typeHandler.getConcreteType(start);
        String endType = typeHandler.getConcreteType(end);
        
        if (startType.equals(endType)) {
            return startType;
        }
        
        if ((startType.equals("int") || startType.equals("float")) &&
            (endType.equals("int") || endType.equals("float"))) {
            return "float";
        }
        
        return "text";
    }
    
    private void validateRangeBound(AutoStackingNumber bound, String boundName) {
        if (!typeHandler.validateType(elementType, bound)) {
            throw new ProgramError(
                "Range " + boundName + " type does not match inferred element type " + elementType
            );
        }
    }

    // ========== CACHE SIZE METHODS ==========
    
    private void invalidateSize() {
        cachedSize = null;
    }
    
    private long calculateSizeInternal() {
        try {
            if (isLexicographicalRange) {
                return calculateLexSize();
            }

            AutoStackingNumber startVal = getStart();
            AutoStackingNumber endVal = getEnd();
            AutoStackingNumber stepVal = getStep();

            if (stepVal.isZero()) {
                return 0L;
            }

            boolean increasing = stepVal.isPositive();
            if ((increasing && startVal.compareTo(endVal) > 0) || 
                (!increasing && startVal.compareTo(endVal) < 0)) {
                return 0L;
            }

            AutoStackingNumber diff = endVal.subtract(startVal);
            AutoStackingNumber steps = diff.divide(stepVal);
            AutoStackingNumber sizeNum = steps.add(AutoStackingNumber.one(1));
            
            if (sizeNum.compareTo(AutoStackingNumber.fromLong(Long.MAX_VALUE)) > 0) {
                throw new ProgramError("Array size too large: " + sizeNum);
            }

            return sizeNum.longValue();
            
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Failed to calculate array size", e);
        }
    }

    // ========== RECENT CACHE METHODS ==========
    
    private void clearRecentCache() {
        recentCacheValid = false;
        recentCacheStart = -1;
        for (int i = 0; i < RECENT_CACHE_SIZE; i++) {
            recentCache[i] = null;
        }
    }
    
    private void updateRecentCache(long index, Object value) {
        if (!recentCacheValid || index < recentCacheStart || 
            index >= recentCacheStart + RECENT_CACHE_SIZE) {
            recentCacheStart = Math.max(0, index - RECENT_CACHE_SIZE / 2);
            recentCacheValid = true;
            for (int i = 0; i < RECENT_CACHE_SIZE; i++) {
                recentCache[i] = null;
            }
        }
        
        if (index >= recentCacheStart && index < recentCacheStart + RECENT_CACHE_SIZE) {
            int cacheIndex = (int)(index - recentCacheStart);
            recentCache[cacheIndex] = value;
        }
    }
    
    private Object getFromRecentCache(long index) {
        if (!recentCacheValid) return null;
        if (index >= recentCacheStart && index < recentCacheStart + RECENT_CACHE_SIZE) {
            int cacheIndex = (int)(index - recentCacheStart);
            return recentCache[cacheIndex];
        }
        return null;
    }
    
    private void invalidateRecentCache(long index) {
        if (!recentCacheValid) return;
        if (index >= recentCacheStart && index < recentCacheStart + RECENT_CACHE_SIZE) {
            int cacheIndex = (int)(index - recentCacheStart);
            recentCache[cacheIndex] = null;
        }
    }

    // ========== LAZY RANGE VIEWS ==========

    private class LazyRangeView extends AbstractList<Object> {
        private final WeakReference<NaturalArray> parentRef;
        private final ProcessedRange range;
        private final long size;
        
        public LazyRangeView(Object spec) {
            if (spec == null) {
                throw new InternalError("LazyRangeView constructed with null range");
            }
            
            this.parentRef = new WeakReference<NaturalArray>(NaturalArray.this);
            
            ProcessedRange rawRange = new ProcessedRange(spec);
            
            long arraySize = NaturalArray.this.size();
            
            long adjStart = rawRange.start;
            long adjEnd = rawRange.end;
            
            if (adjStart < 0) adjStart = arraySize + adjStart;
            if (adjEnd < 0) adjEnd = arraySize + adjEnd;
            
            if (adjStart < 0 || adjStart >= arraySize) {
                throw new ProgramError("Start index out of bounds: " + adjStart);
            }
            if (adjEnd < 0 || adjEnd >= arraySize) {
                throw new ProgramError("End index out of bounds: " + adjEnd);
            }
            
            if (adjStart != rawRange.start || adjEnd != rawRange.end) {
                this.range = new ProcessedRange(adjStart, adjEnd, rawRange.step);
            } else {
                this.range = rawRange;
            }
            
            long calculatedSize = this.range.size();
            if (calculatedSize > Integer.MAX_VALUE) {
                throw new ProgramError("Range view size too large: " + calculatedSize);
            }
            this.size = calculatedSize;
        }

        @Override
        public Object get(int index) {
            if (index < 0 || index >= size) {
                throw new ProgramError("Index: " + index + ", Size: " + size);
            }

            NaturalArray parent = parentRef.get();
            if (parent == null) {
                throw new ProgramError("Cannot access view - original array was garbage collected");
            }

            long actualIndex = range.indexAt(index);
            return parent.get(actualIndex);
        }

        @Override
        public int size() {
            return (int) size;
        }

        @Override
        public Iterator<Object> iterator() {
            return new LazyRangeIterator();
        }

        private class LazyRangeIterator implements Iterator<Object> {
            private int currentIndex = 0;

            @Override
            public boolean hasNext() {
                return currentIndex < size;
            }

            @Override
            public Object next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return get(currentIndex++);
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        }
    }

    private class LazyMultiRangeView extends AbstractList<Object> {
        private final WeakReference<NaturalArray> parentRef;
        private final List<LazyRangeView> rangeViews;
        private final int totalSize;
        private final int[] rangeOffsets;
        private final int[] rangeForIndex;

        public LazyMultiRangeView(Object multiRange) {
            if (multiRange == null) {
                throw new InternalError("LazyMultiRangeView constructed with null multiRange");
            }
            
            this.parentRef = new WeakReference<NaturalArray>(NaturalArray.this);
            this.rangeViews = new ArrayList<LazyRangeView>();
            int total = 0;
            
            for (Object range : RangeObjects.getRanges(multiRange)) {
                if (range == null) {
                    throw new InternalError("Null range in MultiRangeSpec");
                }
                LazyRangeView view = new LazyRangeView(range);
                rangeViews.add(view);
                total += view.size();
            }
            
            this.totalSize = total;
            
            this.rangeOffsets = new int[rangeViews.size()];
            this.rangeForIndex = new int[total];
            
            int offset = 0;
            for (int i = 0; i < rangeViews.size(); i++) {
                rangeOffsets[i] = offset;
                int viewSize = rangeViews.get(i).size();
                for (int j = 0; j < viewSize; j++) {
                    rangeForIndex[offset + j] = i;
                }
                offset += viewSize;
            }
        }

        @Override
        public Object get(int index) {
            if (index < 0 || index >= totalSize) {
                throw new ProgramError("Index: " + index + ", Size: " + totalSize);
            }
            
            NaturalArray parent = parentRef.get();
            if (parent == null) {
                throw new ProgramError("Cannot access multi-range view - original array was garbage collected");
            }
            
            int rangeIdx = rangeForIndex[index];
            int offset = index - rangeOffsets[rangeIdx];
            return rangeViews.get(rangeIdx).get(offset);
        }

        @Override
        public int size() {
            NaturalArray parent = parentRef.get();
            if (parent == null) {
                throw new ProgramError("Cannot access multi-range view - original array was garbage collected");
            }
            return totalSize;
        }

        @Override
        public Iterator<Object> iterator() {
            return new LazyMultiRangeIterator();
        }

        private class LazyMultiRangeIterator implements Iterator<Object> {
            private int currentRange = 0;
            private Iterator<Object> currentIterator;
            private int visited = 0;

            public LazyMultiRangeIterator() {
                if (!rangeViews.isEmpty()) {
                    currentIterator = rangeViews.get(0).iterator();
                }
            }

            @Override
            public boolean hasNext() {
                NaturalArray parent = parentRef.get();
                if (parent == null) {
                    throw new ProgramError("Cannot iterate multi-range view - original array was garbage collected");
                }
                return visited < totalSize;
            }

            @Override
            public Object next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                
                while (currentIterator != null && !currentIterator.hasNext()) {
                    currentRange++;
                    if (currentRange < rangeViews.size()) {
                        currentIterator = rangeViews.get(currentRange).iterator();
                    } else {
                        currentIterator = null;
                    }
                }
                
                if (currentIterator == null) {
                    throw new NoSuchElementException();
                }
                
                visited++;
                return currentIterator.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        }
    }

    // ========== CORE ARRAY OPERATIONS ==========

    public long size() {
        if (cachedSize == null) {
            cachedSize = calculateSizeInternal();
        }
        return cachedSize;
    }

    public Object get(long index) {
        if (index < 0) {
            long size = size();
            index = size + index;
        }

        checkBounds(index);
        
        // --- PRIMITIVE FAST PATH START ---
        if (unboxedIntCache != null && index < unboxedIntCache.length) {
            long val = unboxedIntCache[(int) index];
            if (val != INT_SENTINEL) {
                if (tracked) ArrayTracker.recordArrayAccess(this);
                return maybeConvert(AutoStackingNumber.fromLong(val));
            }
        }
        if (unboxedFloatCache != null && index < unboxedFloatCache.length) {
            double val = unboxedFloatCache[(int) index];
            if (!Double.isNaN(val)) {
                if (tracked) ArrayTracker.recordArrayAccess(this);
                return maybeConvert(AutoStackingNumber.fromDouble(val));
            }
        }
        // --- PRIMITIVE FAST PATH END ---

        if (tracked) {
            ArrayTracker.recordArrayAccess(this);
        }
        
        Object recent = getFromRecentCache(index);
        if (recent != null) {
            if (tracked) ArrayTracker.recordCacheHit(this);
            lastIndex = index;
            lastValue = recent;
            return maybeConvert(recent);
        }
        
        if (tracked) ArrayTracker.recordCacheMiss(this);
        
        applyPendingUpdatesForIndex(index);

        if (lastIndex != null && lastIndex == index) {
            Object val = maybeConvert(lastValue);
            updateRecentCache(index, val);
            return val;
        }

        if (isMutable && cache != null && cache.containsKey(index)) {
            Object val = cache.get(index);
            lastIndex = index;
            lastValue = val;
            updateRecentCache(index, val);
            return maybeConvert(val);
        }

        if (computedCache != null && computedCache.containsKey(index)) {
            Object cached = computedCache.get(index);
            lastIndex = index;
            lastValue = cached;
            updateRecentCache(index, cached);
            return maybeConvert(cached);
        }

        Object sequenceResult = evaluateSequenceFormulas(index);
        if (sequenceResult != null) {
            if (computedCache == null) computedCache = new HashMap<Long, Object>();
            computedCache.put(index, sequenceResult);
            lastIndex = index;
            lastValue = sequenceResult;
            updateRecentCache(index, sequenceResult);
            return maybeConvert(sequenceResult);
        }

        Object conditionalResult = evaluateConditionalFormulas(index);
        if (conditionalResult != null) {
            if (computedCache == null) computedCache = new HashMap<Long, Object>();
            computedCache.put(index, conditionalResult);
            lastIndex = index;
            lastValue = conditionalResult;
            updateRecentCache(index, conditionalResult);
            return maybeConvert(conditionalResult);
        }
        
        Object vectorRecurrenceResult = evaluateVectorRecurrenceFormulas(index);
        if (vectorRecurrenceResult != null) {
            lastIndex = index;
            lastValue = vectorRecurrenceResult;
            updateRecentCache(index, vectorRecurrenceResult);
            return maybeConvert(vectorRecurrenceResult);
        }

        Object recurrenceResult = evaluateLinearRecurrenceFormulas(index);
        if (recurrenceResult != null) {
            lastIndex = index;
            lastValue = recurrenceResult;
            updateRecentCache(index, recurrenceResult);
            return maybeConvert(recurrenceResult);
        }

        Object result = calculateValue(index);
        lastIndex = index;
        lastValue = result;
        updateRecentCache(index, result);
        return maybeConvert(result);
    }

    public Object get(long index, boolean withConversion) {
        Object value = get(index);
        
        if (withConversion && convertToString) {
            return convertToString(value);
        }
        
        return value;
    }

    public Object peekMaterialized(long index) {
        if (index < 0) {
            long size = size();
            index = size + index;
        }

        checkBounds(index);

        if (isMutable && cache != null && cache.containsKey(index)) {
            return cache.get(index);
        }
        if (computedCache != null && computedCache.containsKey(index)) {
            return computedCache.get(index);
        }
        return null;
    }

    private Object convertToString(Object value) {
        if (value == null) return "none";
        if (value instanceof String) return value;
        if (value instanceof Integer) return String.valueOf(value);
        if (value instanceof Long) return String.valueOf(value);
        if (value instanceof AutoStackingNumber) {
            return value.toString();
        }
        if (value instanceof Boolean) return String.valueOf(value);
        if (value instanceof IntLiteral) {
            return ((IntLiteral) value).value.toString();
        }
        if (value instanceof FloatLiteral) {
            return ((FloatLiteral) value).value.toString();
        }
        if (value instanceof BoolLiteral) {
            return String.valueOf(((BoolLiteral) value).value);
        }
        if (value instanceof TextLiteral) {
            return ((TextLiteral) value).value;
        }
        return String.valueOf(value);
    }

    private Object maybeConvert(Object value) {
        if (convertToString) {
            return convertToString(value);
        }
        return value;
    }

    public void set(long index, Object value) {
        if (index < 0) {
            long size = size();
            index = size + index;
        }

        try {
            checkBounds(index);
        } catch (ProgramError e) {
            throw e;
        }
        
        if (tracked) {
            ArrayTracker.recordArrayModification(this);
        }
        
        String checkType = convertToString ? targetElementType : elementType;
        if (!typeHandler.validateType(checkType, value)) {
            throw new ProgramError(
                "Type mismatch: cannot assign " + 
                typeHandler.getConcreteType(value) + 
                " to array of type " + (convertToString ? "[" + targetElementType + "]" : "[" + elementType + "]")
            );
        }

        if (!isMutable) {
            becomeMutable();
        }

        // --- PRIMITIVE FAST PATH START ---
        if (unboxedIntCache != null && index < unboxedIntCache.length) {
            unboxedIntCache[(int) index] = typeHandler.toAutoStackingNumber(value).longValue();
            invalidateRecentCache(index);
            if (computedCache != null) computedCache.remove(index);
            invalidateSize();
            lastIndex = null;
            lastValue = null;
            return;
        }
        if (unboxedFloatCache != null && index < unboxedFloatCache.length) {
            unboxedFloatCache[(int) index] = typeHandler.toAutoStackingNumber(value).doubleValue();
            invalidateRecentCache(index);
            if (computedCache != null) computedCache.remove(index);
            invalidateSize();
            lastIndex = null;
            lastValue = null;
            return;
        }
        // --- PRIMITIVE FAST PATH END ---

        lastIndex = null;
        lastValue = null;

        invalidateRecentCache(index);
        if (computedCache != null) {
            computedCache.remove(index);
        }
        invalidateSize();

        if (cache == null) {
            cache = new HashMap<Long, Object>();
        }

        cache.put(index, value);
    }

    private void checkBounds(long index) {
        if (index < 0) {
            throw new ProgramError("Negative index: " + index);
        }
        long size = size();
        if (index >= size) {
            throw new ProgramError("Index: " + index + ", Size: " + size);
        }
    }

    // ========== OPTIMIZED RANGE OPERATIONS ==========

    public List<Object> getRange(Object range) {
        if (range == null) {
            throw new InternalError("getRange called with null range");
        }
        return new LazyRangeView(range);
    }

    public List<Object> getMultiRange(Object multiRange) {
        if (multiRange == null) {
            throw new InternalError("getMultiRange called with null multiRange");
        }
        return new LazyMultiRangeView(multiRange);
    }

    public void setRange(Object range, Object value) {
        if (range == null) {
            throw new InternalError("setRange called with null range");
        }
        
        if (tracked) {
            ArrayTracker.recordArrayModification(this);
            ProcessedRange processed = new ProcessedRange(range);
            if (processed.valid) {
                ArrayTracker.recordPendingUpdates(this, (int)processed.size());
            }
        }
        
        String checkType = convertToString ? targetElementType : elementType;
        if (!typeHandler.validateType(checkType, value)) {
            throw new ProgramError(
                "Type mismatch: cannot assign " + 
                typeHandler.getConcreteType(value) + 
                " to array of type " + (convertToString ? "[" + targetElementType + "]" : "[" + elementType + "]")
            );
        }
        
        try {
            registerPendingUpdate(new PendingRangeUpdate(range, value, nextPendingUpdateOrder++));
            
            if (!isMutable) {
                becomeMutable();
            }
            
            lastIndex = null;
            lastValue = null;
            invalidateSize();
            
            if (computedCache != null && !computedCache.isEmpty()) {
                ProcessedRange processed = new ProcessedRange(range);
                if (processed.valid) {
                    long rangeSize = processed.size();
                    if (rangeSize > 100 || computedCache.size() < rangeSize / 10) {
                        computedCache.clear();
                        clearRecentCache();
                    } else {
                        for (long i = 0; i < rangeSize; i++) {
                            long index = processed.indexAt(i);
                            computedCache.remove(index);
                            invalidateRecentCache(index);
                        }
                    }
                }
            }
            
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Lazy range assignment failed", e);
        }
    }

    public void setMultiRange(Object multiRange, Object value) {
        if (multiRange == null) {
            throw new InternalError("setMultiRange called with null multiRange");
        }
        
        if (tracked) {
            ArrayTracker.recordArrayModification(this);
            int total = 0;
            for (Object range : RangeObjects.getRanges(multiRange)) {
                ProcessedRange processed = new ProcessedRange(range);
                if (processed.valid) {
                    total += processed.size();
                }
            }
            ArrayTracker.recordPendingUpdates(this, total);
        }
        
        String checkType = convertToString ? targetElementType : elementType;
        if (!typeHandler.validateType(checkType, value)) {
            throw new ProgramError(
                "Type mismatch: cannot assign " + 
                typeHandler.getConcreteType(value) + 
                " to array of type " + (convertToString ? "[" + targetElementType + "]" : "[" + elementType + "]")
            );
        }
        
        try {
            for (Object range : RangeObjects.getRanges(multiRange)) {
                if (range == null) {
                    throw new InternalError("Null range in MultiRangeSpec");
                }
                registerPendingUpdate(new PendingRangeUpdate(range, value, nextPendingUpdateOrder++));
            }
            
            if (!isMutable) {
                becomeMutable();
            }
            
            lastIndex = null;
            lastValue = null;
            invalidateSize();
            
            if (computedCache != null) {
                computedCache.clear();
                clearRecentCache();
            }
            
        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Lazy multi-range assignment failed", e);
        }
    }
    
    private void applyPendingUpdatesForIndex(long index) {
        if (!hasPendingUpdates || pendingUpdates.isEmpty()) {
            return;
        }

        PendingRangeUpdate resolvedUpdate = resolvePendingUpdateForIndex(index);
        if (resolvedUpdate == null) {
            return;
        }

        if (cache == null) {
            cache = new HashMap<Long, Object>();
        }
        cache.put(index, resolvedUpdate.value);
        invalidateRecentCache(index);
    }

    private void registerPendingUpdate(PendingRangeUpdate update) {
        pendingUpdates.add(update);
        hasPendingUpdates = true;

        if (pendingUpdatesByStart != null) {
            addPendingUpdateToTree(update);
            return;
        }

        if (pendingUpdates.size() >= PENDING_UPDATES_TREE_THRESHOLD) {
            convertPendingUpdatesToTreeIndex();
        }
    }

    private void addPendingUpdateToTree(PendingRangeUpdate update) {
        if (pendingUpdatesByStart == null) {
            pendingUpdatesByStart = new TreeMap<Long, List<PendingRangeUpdate>>();
        }
        List<PendingRangeUpdate> updatesAtStart = pendingUpdatesByStart.get(update.range.start);
        if (updatesAtStart == null) {
            updatesAtStart = new ArrayList<PendingRangeUpdate>();
            pendingUpdatesByStart.put(update.range.start, updatesAtStart);
        }
        updatesAtStart.add(update);
        pendingUpdateOrderPrefixDirty = true;
    }

    private void convertPendingUpdatesToTreeIndex() {
        pendingUpdatesByStart = new TreeMap<Long, List<PendingRangeUpdate>>();
        for (PendingRangeUpdate update : pendingUpdates) {
            addPendingUpdateToTree(update);
        }
    }

    private void rebuildPendingUpdateOrderPrefix() {
        if (pendingUpdatesByStart == null) {
            pendingUpdateOrderPrefixByStart = null;
            pendingUpdateOrderPrefixDirty = false;
            return;
        }

        pendingUpdateOrderPrefixByStart = new TreeMap<Long, Long>();
        long runningMax = Long.MIN_VALUE;
        for (Map.Entry<Long, List<PendingRangeUpdate>> entry : pendingUpdatesByStart.entrySet()) {
            long bucketMax = Long.MIN_VALUE;
            for (PendingRangeUpdate update : entry.getValue()) {
                if (update.order > bucketMax) {
                    bucketMax = update.order;
                }
            }
            if (bucketMax > runningMax) {
                runningMax = bucketMax;
            }
            pendingUpdateOrderPrefixByStart.put(entry.getKey(), runningMax);
        }
        pendingUpdateOrderPrefixDirty = false;
    }

    private PendingRangeUpdate resolvePendingUpdateForIndex(long index) {
        if (pendingUpdatesByStart == null) {
            for (int i = pendingUpdates.size() - 1; i >= 0; i--) {
                PendingRangeUpdate update = pendingUpdates.get(i);
                if (update.contains(index)) {
                    return update;
                }
            }
            return null;
        }

        if (pendingUpdateOrderPrefixDirty || pendingUpdateOrderPrefixByStart == null) {
            rebuildPendingUpdateOrderPrefix();
        }

        PendingRangeUpdate winner = null;
        NavigableMap<Long, List<PendingRangeUpdate>> candidatesByStart = pendingUpdatesByStart.headMap(index, true);
        for (Map.Entry<Long, List<PendingRangeUpdate>> entry : candidatesByStart.descendingMap().entrySet()) {
            List<PendingRangeUpdate> updatesAtStart = entry.getValue();
            for (int i = updatesAtStart.size() - 1; i >= 0; i--) {
                PendingRangeUpdate candidate = updatesAtStart.get(i);
                if (winner != null && candidate.order <= winner.order) {
                    break;
                }
                if (!candidate.contains(index)) {
                    continue;
                }
                if (winner == null || candidate.order > winner.order) {
                    winner = candidate;
                }
            }

            if (winner != null && pendingUpdateOrderPrefixByStart != null) {
                Long remainingMaxOrder = pendingUpdateOrderPrefixByStart.get(entry.getKey());
                if (remainingMaxOrder != null && winner.order >= remainingMaxOrder) {
                    break;
                }
            }
        }
        return winner;
    }
    
    public void commitUpdates() {
        if (!hasPendingUpdates || pendingUpdates.isEmpty()) {
            return;
        }
        
        Collections.sort(pendingUpdates, new Comparator<PendingRangeUpdate>() {
            @Override
            public int compare(PendingRangeUpdate a, PendingRangeUpdate b) {
                if (a.range.start < b.range.start) return -1;
                if (a.range.start > b.range.start) return 1;
                return 0;
            }
        });
        
        List<PendingRangeUpdate> merged = new ArrayList<PendingRangeUpdate>();
        PendingRangeUpdate current = null;
        
        for (PendingRangeUpdate update : pendingUpdates) {
            if (!update.range.valid) continue;
            
            if (current == null) {
                current = update;
            } else if (canMerge(current, update)) {
                ProcessedRange mergedRange = current.range.merge(update.range);
                current = new PendingRangeUpdate(mergedRange, current.value, current.order);
            } else {
                merged.add(current);
                current = update;
            }
        }
        if (current != null) merged.add(current);
        
        for (PendingRangeUpdate update : merged) {
            applyPendingUpdate(update);
        }
        
        pendingUpdates.clear();
        pendingUpdatesByStart = null;
        pendingUpdateOrderPrefixByStart = null;
        pendingUpdateOrderPrefixDirty = false;
        hasPendingUpdates = false;
    }

    private boolean canMerge(PendingRangeUpdate a, PendingRangeUpdate b) {
        return a.value.equals(b.value) && 
               a.range.step == b.range.step &&
               a.range.isAdjacent(b.range);
    }
    
    private void applyPendingUpdate(PendingRangeUpdate update) {
        if (!update.range.valid) return;
        
        ProcessedRange range = update.range;
        Object value = update.value;
        
        long iterations = range.size();
        
        if (computedCache != null) {
            for (long i = 0; i < iterations; i++) {
                long index = range.indexAt(i);
                computedCache.remove(index);
                invalidateRecentCache(index);
            }
        }
        
        if (cache == null) {
            cache = new HashMap<Long, Object>();
        }
        
        for (long i = 0; i < iterations; i++) {
            long index = range.indexAt(i);
            cache.put(index, value);
        }
        
        invalidateSize();
    }
    
    private static long toLongIndex(Object obj) {
        if (obj instanceof Long) return (Long) obj;
        if (obj instanceof Integer) return ((Integer) obj).longValue();
        if (obj instanceof AutoStackingNumber) return ((AutoStackingNumber) obj).longValue();
        return Long.parseLong(obj.toString());
    }
    
    private static long calculateStep(Object range) {
        Object step = RangeObjects.getStep(range);
        if (step != null) {
            return toLongIndex(step);
        }
        long start = toLongIndex(RangeObjects.getStart(range));
        long end = toLongIndex(RangeObjects.getEnd(range));
        return (start < end) ? 1L : -1L;
    }

    // ========== LEXICOGRAPHIC ENCODING ==========

    private boolean isValidLexString(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }

    private long hierarchicalSequenceToIndex(String s) {
        int n = s.length();
        if (n > 10) {
            throw new ProgramError("String too long (max 10): " + s);
        }

        long index = TOTAL_UP_TO_LENGTH[n - 1];
        long patternMask = 0;
        long contentIndex = 0;

        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            boolean isUpper = c >= 'A' && c <= 'Z';
            if (isUpper) {
                patternMask |= (1L << (n - 1 - i));
                c += 32;
            }
            int digit = c - 'a';
            contentIndex = contentIndex * 26 + digit;
        }

        return index + (patternMask * POWERS_26[n]) + contentIndex;
    }

    private String hierarchicalIndexToSequence(long globalIndex) {
        int n = 1;
        while (n <= 10 && globalIndex >= TOTAL_UP_TO_LENGTH[n]) {
            n++;
        }
        if (n > 10) {
            throw new InternalError("Index too large: " + globalIndex);
        }

        long indexInLengthGroup = globalIndex - TOTAL_UP_TO_LENGTH[n - 1];
        long stringsPerPattern = POWERS_26[n];
        long patternIndex = indexInLengthGroup / stringsPerPattern;
        long contentIndex = indexInLengthGroup % stringsPerPattern;

        char[] chars = new char[n];
        long temp = contentIndex;

        for (int i = n - 1; i >= 0; i--) {
            int digit = (int) (temp % 26);
            chars[i] = (char) ('a' + digit);
            temp /= 26;
        }

        for (int i = 0; i < n; i++) {
            if (((patternIndex >> (n - 1 - i)) & 1) == 1) {
                chars[i] = (char) (chars[i] - 32);
            }
        }

        return new String(chars);
    }

    // ========== LEX RANGE CALCULATIONS ==========

    private long calculateLexSize() {
        AutoStackingNumber step = getStep();
        long stepLong = step.longValue();
        long absStep = Math.abs(stepLong);
        
        if (isUp) {
            if (startIndex > endIndex) return 0;
            return (endIndex - startIndex) / absStep + 1;
        } else {
            if (startIndex < endIndex) return 0;
            return (startIndex - endIndex) / absStep + 1;
        }
    }

    private String calculateLexValue(long index) {
        AutoStackingNumber step = getStep();
        long stepLong = step.longValue();
        long effectiveIndex;
        
        if (isUp) {
            effectiveIndex = startIndex + (index * stepLong);
        } else {
            effectiveIndex = startIndex - (index * Math.abs(stepLong));
        }

        if (effectiveIndex < 0 || effectiveIndex > maxIndex) {
            throw new InternalError(
                "Index " + index + " out of bounds for lex range [" +
                startString + " to " + endString + "] - should have been caught by bounds check"
            );
        }

        return hierarchicalIndexToSequence(effectiveIndex);
    }

    private Object calculateValue(long index) {
        if (isLexicographicalRange) {
            return calculateLexValue(index);
        }

        AutoStackingNumber startVal = getStart();
        AutoStackingNumber stepVal = getStep();
        AutoStackingNumber indexNum = AutoStackingNumber.fromLong(index);
        
        return startVal.add(indexNum.multiply(stepVal));
    }

    // ========== GETTERS WITH LAZY INITIALIZATION ==========

    private AutoStackingNumber getStart() {
        if (cachedStart == null) {
            Object startObj = evaluator.evaluate(baseRange.start, context);
            cachedStart = typeHandler.toAutoStackingNumber(startObj);
            if (cachedStart == null) {
                cachedStart = AutoStackingNumber.zero(1);
            }
        }
        return cachedStart;
    }

    private AutoStackingNumber getEnd() {
        if (cachedEnd == null) {
            Object endObj = evaluator.evaluate(baseRange.end, context);
            cachedEnd = typeHandler.toAutoStackingNumber(endObj);
            if (cachedEnd == null) {
                cachedEnd = AutoStackingNumber.zero(1);
            }
        }
        return cachedEnd;
    }

    private AutoStackingNumber getStep() {
        if (cachedStep != null) {
            return cachedStep;
        }

        if (baseRange.step != null) {
            Object stepObj = evaluator.evaluate(baseRange.step, context);
            cachedStep = typeHandler.toAutoStackingNumber(stepObj);
            if (cachedStep == null) {
                cachedStep = AutoStackingNumber.one(1);
            }
        } else {
            if (isLexicographicalRange) {
                cachedStep = isUp ? AutoStackingNumber.one(1) : AutoStackingNumber.minusOne(1);
            } else {
                AutoStackingNumber start = getStart();
                AutoStackingNumber end = getEnd();
                
                if (start == null || end == null) {
                    cachedStep = AutoStackingNumber.one(1);
                } else {
                    int cmp = start.compareTo(end);
                    cachedStep = (cmp <= 0) ? 
                        AutoStackingNumber.one(start.getStacks()) : 
                        AutoStackingNumber.minusOne(start.getStacks());
                }
            }
        }

        return cachedStep;
    }

    private void becomeMutable() {
        if (this.isMutable) return;
        this.isMutable = true;
        long sz = size();
        if (sz > 0 && sz <= MAX_UNBOXED_SIZE && !convertToString) {
            if ("int".equals(elementType) || "i64".equals(elementType)) {
                unboxedIntCache = new long[(int) sz];
                Arrays.fill(unboxedIntCache, INT_SENTINEL);
                return;
            } else if ("float".equals(elementType) || "f64".equals(elementType)) {
                unboxedFloatCache = new double[(int) sz];
                Arrays.fill(unboxedFloatCache, Double.NaN);
                return;
            }
        }
        if (cache == null) cache = new HashMap<Long, Object>();
    }

    // ========== FORMULA OPTIMIZATIONS ==========

    public void addSequenceFormula(SequenceFormula formula) {
        if (formula == null) {
            throw new InternalError("Attempted to add null SequenceFormula");
        }
        
        if (tracked) {
            ArrayTracker.recordFormulaApplication(this);
        }
        
        sequenceFormulas.add(formula);
        clearCache();
    }

    public void addConditionalFormula(ConditionalFormula formula) {
        if (formula == null) {
            throw new InternalError("Attempted to add null ConditionalFormula");
        }
        
        if (tracked) {
            ArrayTracker.recordFormulaApplication(this);
        }
        
        if (conditionalFormulas.isEmpty()) {
            conditionalFormulas.add(formula);
        } else {
            int lastIndex = conditionalFormulas.size() - 1;
            ConditionalFormula current = conditionalFormulas.get(lastIndex);
            ConditionalFormula merged = ConditionalFormula.compose(formula, current);
            conditionalFormulas.set(lastIndex, merged);
        }
        clearCache();
    }

    public void addLinearRecurrenceFormula(AccumulationFormula formula) {
        if (formula == null) {
            throw new InternalError("Attempted to add null AccumulationFormula");
        }
        
        if (tracked) {
            ArrayTracker.recordFormulaApplication(this);
        }
        
        if (linearRecurrenceFormulas.isEmpty()) {
            linearRecurrenceFormulas.add(formula);
        } else {
            // int lastIndex = linearRecurrenceFormulas.size() - 1;
            // For now, just add - composition would need to be implemented for AccumulationFormula
            linearRecurrenceFormulas.add(formula);
        }
        clearCache();
    }

    public void addVectorRecurrenceFormula(AccumulationFormula formula, int sequenceIndex) {
        if (formula == null) {
            throw new InternalError("Attempted to add null AccumulationFormula");
        }
        if (sequenceIndex < 0) {
            throw new ProgramError("Invalid vector recurrence sequence index: " + sequenceIndex);
        }

        if (tracked) {
            ArrayTracker.recordFormulaApplication(this);
        }

        vectorRecurrenceFormulas.add(formula);
        clearCache();
    }

    public void clearCache() {
        if (computedCache != null) {
            computedCache.clear();
        }
        clearRecentCache();
        lastIndex = null;
        lastValue = null;
        invalidateSize();
    }

    private Object evaluateSequenceFormulas(long index) {
        if (sequenceFormulas.isEmpty()) return null;
        
        for (int i = sequenceFormulas.size() - 1; i >= 0; i--) {
            SequenceFormula formula = sequenceFormulas.get(i);
            if (formula.contains(index)) {
                try {
                    Object result = formula.evaluate(index, evaluator, context);
                    if (computedCache == null) {
                        computedCache = new HashMap<Long, Object>();
                    }
                    computedCache.put(index, result);
                    return result;
                } catch (ProgramError e) {
                    throw e;
                } catch (Exception e) {
                    throw new InternalError(
                        "Sequence formula evaluation failed at index " + index, e);
                }
            }
        }
        return null;
    }

    private Object evaluateConditionalFormulas(long index) {
        if (conditionalFormulas.isEmpty()) return null;

        for (int i = conditionalFormulas.size() - 1; i >= 0; i--) {
            ConditionalFormula formula = conditionalFormulas.get(i);
            if (formula == null) {
                throw new InternalError("Null ConditionalFormula in list");
            }
            
            if (formula.contains(index)) {
                try {
                    Object result = formula.evaluate(index, evaluator, context);
                    if (result != null) {
                        if (computedCache == null) {
                            computedCache = new HashMap<Long, Object>();
                        }
                        computedCache.put(index, result);
                    }
                    return result;
                } catch (ProgramError e) {
                    throw e;
                } catch (Exception e) {
                    throw new InternalError(
                        "Conditional formula evaluation failed at index " + index, e);
                }
            }
        }
        return null;
    }

    private Object evaluateLinearRecurrenceFormulas(long index) {
        if (linearRecurrenceFormulas.isEmpty()) return null;

        for (int i = linearRecurrenceFormulas.size() - 1; i >= 0; i--) {
            AccumulationFormula formula = linearRecurrenceFormulas.get(i);
            if (formula == null) {
                throw new InternalError("Null AccumulationFormula in list");
            }
            
            // For scalar recurrence applied to array
            try {
                Object result = formula.evaluate(AutoStackingNumber.fromLong(index));
                if (result != null) {
                    if (computedCache == null) {
                        computedCache = new HashMap<Long, Object>();
                    }
                    computedCache.put(index, result);
                }
                return result;
            } catch (ProgramError e) {
                throw e;
            } catch (Exception e) {
                throw new InternalError(
                    "Linear recurrence formula evaluation failed at index " + index, e);
            }
        }
        return null;
    }

    private Object evaluateVectorRecurrenceFormulas(long index) {
        if (vectorRecurrenceFormulas.isEmpty()) return null;

        for (int i = vectorRecurrenceFormulas.size() - 1; i >= 0; i--) {
            AccumulationFormula binding = vectorRecurrenceFormulas.get(i);
            if (binding == null || binding == null) {
                throw new InternalError("Null AccumulationFormula in list");
            }
            try {
                Object result = binding.evaluate(AutoStackingNumber.fromLong(index));
                if (result != null) {
                    if (computedCache == null) {
                        computedCache = new HashMap<Long, Object>();
                    }
                    computedCache.put(index, result);
                }
                return result;
            } catch (ProgramError e) {
                throw e;
            } catch (Exception e) {
                throw new InternalError(
                    "Vector recurrence formula evaluation failed at index " + index, e);
            }
        }
        return null;
    }

    // ========== OUTPUT CACHING METHODS ==========

    public void recordOutput(long index, Object value) {
        List<Object> outputs = outputCache.get(index);
        if (outputs == null) {
            outputs = new ArrayList<Object>();
            outputCache.put(index, outputs);
        }
        outputs.add(value);
        hasOutputs = true;
    }

    public List<Object> getOutputs(long index) {
        return outputCache.get(index);
    }

    public boolean hasOutputs() {
        return hasOutputs;
    }

    public void clearOutputs() {
        outputCache.clear();
        hasOutputs = false;
    }

    public Object getWithOutputs(long index, List<Object> outputsOut) {
        Object value = get(index);
        
        if (hasOutputs && outputCache.containsKey(index)) {
            List<Object> recorded = outputCache.get(index);
            if (recorded != null) {
                outputsOut.addAll(recorded);
            }
        }
        
        return value;
    }

    // ========== UTILITY METHODS ==========

    public List<Object> toList() {
        commitUpdates();
        
        List<Object> result = new ArrayList<Object>();
        long size = size();
        for (long i = 0; i < size; i++) {
            result.add(get(i));
        }
        return result;
    }

    public boolean isMutable() {
        return isMutable;
    }

    public int getCacheSize() {
        return cache != null ? cache.size() : 0;
    }
    
    public boolean hasPendingUpdates() {
        return hasPendingUpdates;
    }
    
    public int getPendingUpdateCount() {
        return pendingUpdates.size();
    }
    
    public void discardUpdates() {
        pendingUpdates.clear();
        pendingUpdatesByStart = null;
        pendingUpdateOrderPrefixByStart = null;
        pendingUpdateOrderPrefixDirty = false;
        hasPendingUpdates = false;
    }
    
    public String getElementType() {
        return elementType;
    }
    
    public boolean needsConversion() {
        return convertToString;
    }
    
    public String getTargetElementType() {
        return targetElementType;
    }

    @Override
    public String toString() {
        try {
            String base = String.format("NaturalArray[id=%d, ", arrayId);
            
            if (isLexicographicalRange) {
                String direction = isUp ? "" : " (reverse)";
                String stepInfo = "";
                if (cachedStep != null) {
                    long step = cachedStep.longValue();
                    if (Math.abs(step) != 1) {
                        stepInfo = " step " + step;
                    }
                }
                String formula = String.format("%sLexArray[\"%s\" to \"%s\"%s]%s",
                    base, startString, endString, stepInfo, direction);

                StringBuilder sb = new StringBuilder(formula);
                long size = size();
                sb.append(String.format(" (size: %d", size));
                if (isMutable) sb.append(", mutable");
                if (hasPendingUpdates) sb.append(", pending: ").append(pendingUpdates.size());
                sb.append(", type: ").append(elementType);
                if (convertToString) sb.append(", converting to ").append(targetElementType);
                if (tracked) sb.append(", tracked");
                sb.append(")");
                return sb.toString();
            }

            AutoStackingNumber start = getStart();
            AutoStackingNumber end = getEnd();
            AutoStackingNumber step = getStep();

            String startStr = start.toString();
            String endStr = end.toString();
            String stepStr = step.toString();

            String formula = String.format("%sNaturalArray[%s to %s", 
                base, startStr, endStr);

            boolean isDefaultStep = (step.compareTo(AutoStackingNumber.one(1)) == 0 && start.compareTo(end) <= 0) ||
                                   (step.compareTo(AutoStackingNumber.minusOne(1)) == 0 && start.compareTo(end) > 0);

            if (!isDefaultStep) formula += " step " + stepStr;
            formula += "]";

            StringBuilder sb = new StringBuilder(formula);

            long size = size();
            sb.append(String.format(" (size: %d", size));

            if (isMutable) sb.append(", mutable, cache: ").append(getCacheSize());
            else sb.append(", immutable");
            
            if (hasPendingUpdates) {
                sb.append(", pending: ").append(pendingUpdates.size());
            }
            sb.append(", type: ").append(elementType);
            if (convertToString) sb.append(", converting to ").append(targetElementType);
            if (tracked) sb.append(", tracked");
            sb.append(")");

            if (!sequenceFormulas.isEmpty()) {
                sb.append("\n  Sequence formulas: ").append(sequenceFormulas.size());
            }
            if (!conditionalFormulas.isEmpty()) {
                sb.append("\n  Conditional formulas: ").append(conditionalFormulas.size());
            }
            if (!linearRecurrenceFormulas.isEmpty()) {
                sb.append("\n  Linear recurrence formulas: ").append(linearRecurrenceFormulas.size());
            }
            if (!vectorRecurrenceFormulas.isEmpty()) {
                sb.append("\n  Vector recurrence formulas: ").append(vectorRecurrenceFormulas.size());
            }
            return sb.toString();
            
        } catch (ProgramError e) {
            return "NaturalArray[id=" + arrayId + ", error: " + e.getMessage() + "]";
        } catch (Exception e) {
            return "NaturalArray[id=" + arrayId + ", internal error: " + e.getMessage() + "]";
        }
    }
}
