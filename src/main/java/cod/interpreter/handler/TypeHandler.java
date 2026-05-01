package cod.interpreter.handler;

import cod.ast.node.*;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.math.AutoStackingNumber;
import cod.range.NaturalArray;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

public class TypeHandler {
    
    // ========== BITMASK TYPE IDS ==========
    public static final int TID_NONE   = 0;
    public static final int TID_INT    = 1;
    public static final int TID_FLOAT  = 2;
    public static final int TID_TEXT   = 3;
    public static final int TID_BOOL   = 4;
    public static final int TID_TYPE   = 5;
    public static final int TID_ARRAY  = 6;
    public static final int TID_TUPLE  = 7;
    public static final int TID_OBJECT = 8;
    public static final int TID_FUNC   = 9;
    
    public static final int MASK_NONE   = 1 << TID_NONE;
    public static final int MASK_INT    = 1 << TID_INT;
    public static final int MASK_FLOAT  = 1 << TID_FLOAT;
    public static final int MASK_TEXT   = 1 << TID_TEXT;
    public static final int MASK_BOOL   = 1 << TID_BOOL;
    public static final int MASK_TYPE   = 1 << TID_TYPE;
    public static final int MASK_ARRAY  = 1 << TID_ARRAY;
    public static final int MASK_TUPLE  = 1 << TID_TUPLE;
    public static final int MASK_OBJECT = 1 << TID_OBJECT;
    public static final int MASK_FUNC   = 1 << TID_FUNC;
    
    public static final int MASK_NUMERIC = MASK_INT | MASK_FLOAT;
    public static final int MASK_PRIMITIVE = MASK_INT | MASK_FLOAT | MASK_TEXT | MASK_BOOL | MASK_NONE;
    public static final int MASK_ANY = 0x7FFFFFFF;
    
    private static final Map<String, Integer> signatureCache = new HashMap<String, Integer>();
    
    // ========== VALUE CLASS (NO STRINGS) ==========
    public static class Value {
        public final Object value;
        public final int concreteMask;
        public final int declaredMask;
        
        public Value(Object value, int concreteMask, int declaredMask) {
            this.value = value;
            this.concreteMask = concreteMask;
            this.declaredMask = declaredMask;
        }
        
        public boolean matches(int expectedMask) {
            return (this.concreteMask & expectedMask) != 0;
        }
        
        public boolean matchesDeclared() {
            return (this.concreteMask & this.declaredMask) != 0;
        }
        
        public boolean isTypeValue() {
            return (this.concreteMask & MASK_TYPE) != 0;
        }
        
        public static Value createTypeValue(int typeMask) {
            return new Value(maskToTypeString(typeMask), MASK_TYPE, MASK_TYPE);
        }
        
        public String toString() {
            return String.valueOf(this.value);
        }
        
        public int hashCode() {
    int result = 31 + this.concreteMask;
    result = 31 * result + this.declaredMask;
    result = 31 * result + (this.value == null ? 0 : this.value.hashCode());
    return result;
}

public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    Value other = (Value) obj;
    if (this.concreteMask != other.concreteMask) return false;
    if (this.declaredMask != other.declaredMask) return false;
    if (this.value == null) {
        if (other.value != null) return false;
    } else if (!this.value.equals(other.value)) return false;
    return true;
}
    }
    
    // ========== POINTER VALUE ==========
    public static class PointerValue {
        public final Object container;
        public final long index;
        public final int pointedMask;
        
        public PointerValue(Object container, long index, int pointedMask) {
            this.container = container;
            this.index = index;
            this.pointedMask = pointedMask;
        }
        
        public String toString() {
            return "&" + maskToTypeString(this.pointedMask) + "@" + this.index;
        }
    }
    
    // ========== STATIC MASK UTILITIES ==========
    
    public static int parseTypeMask(String typeSig) {
        if (typeSig == null) return MASK_ANY;
        
        Integer cached = signatureCache.get(typeSig);
        if (cached != null) return cached.intValue();
        
        int mask = parseTypeMaskSlow(typeSig);
        signatureCache.put(typeSig, Integer.valueOf(mask));
        return mask;
    }
    
    private static int parseTypeMaskSlow(String typeSig) {
        if (typeSig.indexOf('|') >= 0) {
            int mask = 0;
            String[] parts = typeSig.split("\\|");
            for (int i = 0; i < parts.length; i++) {
                mask |= parseSingleTypeMask(parts[i].trim());
            }
            return mask;
        }
        return parseSingleTypeMask(typeSig.trim());
    }
    
    private static int parseSingleTypeMask(String type) {
        if ("none".equals(type)) return MASK_NONE;
        if ("int".equals(type)) return MASK_INT;
        if ("float".equals(type)) return MASK_FLOAT;
        if ("text".equals(type)) return MASK_TEXT;
        if ("bool".equals(type)) return MASK_BOOL;
        if ("type".equals(type)) return MASK_TYPE;
        if ("[]".equals(type)) return MASK_ARRAY;
        if (type.startsWith("[") && type.endsWith("]")) return MASK_ARRAY;
        if (type.startsWith("(") && type.endsWith(")")) return MASK_TUPLE;
        if (type.startsWith("*")) return MASK_OBJECT;
        return MASK_OBJECT;
    }
    
    public static String maskToTypeString(int mask) {
        if (mask == MASK_NONE) return "none";
        if (mask == MASK_INT) return "int";
        if (mask == MASK_FLOAT) return "float";
        if (mask == MASK_TEXT) return "text";
        if (mask == MASK_BOOL) return "bool";
        if (mask == MASK_TYPE) return "type";
        if (mask == MASK_ARRAY) return "[]";
        if (mask == MASK_TUPLE) return "tuple";
        if (mask == MASK_OBJECT) return "object";
        if (mask == MASK_FUNC) return "func";
        if (mask == MASK_NUMERIC) return "int|float";
        
        // Build string for custom unions
        StringBuilder sb = new StringBuilder();
        if ((mask & MASK_INT) != 0) { if (sb.length() > 0) sb.append("|"); sb.append("int"); }
        if ((mask & MASK_FLOAT) != 0) { if (sb.length() > 0) sb.append("|"); sb.append("float"); }
        if ((mask & MASK_TEXT) != 0) { if (sb.length() > 0) sb.append("|"); sb.append("text"); }
        if ((mask & MASK_BOOL) != 0) { if (sb.length() > 0) sb.append("|"); sb.append("bool"); }
        if ((mask & MASK_NONE) != 0) { if (sb.length() > 0) sb.append("|"); sb.append("none"); }
        if ((mask & MASK_TYPE) != 0) { if (sb.length() > 0) sb.append("|"); sb.append("type"); }
        if ((mask & MASK_ARRAY) != 0) { if (sb.length() > 0) sb.append("|"); sb.append("[]"); }
        return sb.length() > 0 ? sb.toString() : "any";
    }
    
    // ========== INSTANCE METHODS ==========
    
    public int getConcreteMask(Object value) {
        if (value == null) return MASK_NONE;
        
        if (value instanceof Value) {
            return ((Value) value).concreteMask;
        }
        
        if (value instanceof Integer || value instanceof IntLiteral ||
            value instanceof Long || value instanceof Byte || value instanceof Short) {
            return MASK_INT;
        }
        
        if (value instanceof Float || value instanceof Double || 
            value instanceof FloatLiteral) {
            return MASK_FLOAT;
        }
        
        if (value instanceof AutoStackingNumber) {
            AutoStackingNumber num = (AutoStackingNumber) value;
            if (num.fitsInStacks(1) && 
                (num.getWords()[0] & 0x7FFFFFFFFFFFFFFFL) < Long.MAX_VALUE) {
                return MASK_INT;
            }
            return MASK_FLOAT;
        }
        
        if (value instanceof String) {
            String str = (String) value;
            if (isTypeLiteral(str)) {
                return MASK_TYPE;
            }
            return MASK_TEXT;
        }
        
        if (value instanceof TextLiteral) {
            String str = ((TextLiteral) value).value;
            if (isTypeLiteral(str)) {
                return MASK_TYPE;
            }
            return MASK_TEXT;
        }
        
        if (value instanceof Boolean || value instanceof BoolLiteral) return MASK_BOOL;
        if (value instanceof NoneLiteral) return MASK_NONE;
        if (value instanceof List || value instanceof NaturalArray) return MASK_ARRAY;
        if (value instanceof PointerValue) return MASK_OBJECT;
        
        return MASK_OBJECT;
    }
    
    public String getConcreteType(Object value) {
        return maskToTypeString(getConcreteMask(value));
    }
    
    public boolean validateType(int expectedMask, Object value) {
        if (expectedMask == MASK_ANY) return true;
        int actualMask = getConcreteMask(value);
        return (actualMask & expectedMask) != 0;
    }
    
    public boolean validateType(String typeSig, Object value) {
        return validateType(parseTypeMask(typeSig), value);
    }
    
    public boolean isNoneValue(Object obj) {
        if (obj == null) return true;
        if (obj instanceof NoneLiteral) return true;
        if (obj instanceof Value) {
            return ((Value) obj).value == null || isNoneValue(((Value) obj).value);
        }
        return false;
    }
    
    public Object unwrap(Object obj) {
        if (obj instanceof Value) {
            return ((Value) obj).value;
        }
        if (obj instanceof NoneLiteral) {
            return null;
        }
        return obj;
    }
    
    // ========== TYPE LITERAL DETECTION ==========
    
    public boolean isTypeLiteral(String str) {
        if (str == null) return false;
        if ("int".equals(str) || "float".equals(str) || "text".equals(str) || 
            "bool".equals(str) || "type".equals(str) || "none".equals(str)) {
            return true;
        }
        if (isPointerType(str)) return true;
        if (isSizedArrayType(str)) return true;
        if (isUnsafeNumericType(str)) return true;
        if ("[]".equals(str)) return true;
        if (str.startsWith("[") || str.startsWith("(") || str.indexOf('|') >= 0) return true;
        return false;
    }
    
    public Object processTypeLiteral(String typeLiteral) {
        if ("none".equals(typeLiteral)) {
            return new NoneLiteral();
        }
        return Value.createTypeValue(parseTypeMask(typeLiteral));
    }
    
    // ========== POINTER & ARRAY HELPERS ==========
    
    public boolean isPointerType(String type) {
        return type != null && type.startsWith("*") && type.length() > 1;
    }
    
    public boolean isSizedArrayType(String type) {
        if (type == null) return false;
        int l = type.lastIndexOf('[');
        int r = type.lastIndexOf(']');
        if (l <= 0 || r != type.length() - 1) return false;
        String sizePart = type.substring(l + 1, r).trim();
        if (sizePart.isEmpty()) return false;
        for (int i = 0; i < sizePart.length(); i++) {
            if (!Character.isDigit(sizePart.charAt(i))) return false;
        }
        return true;
    }
    
    public String getSizedArrayElementType(String type) {
        if (!isSizedArrayType(type)) return null;
        return type.substring(0, type.lastIndexOf('['));
    }
    
    public int getSizedArrayLength(String type) {
        if (!isSizedArrayType(type)) return -1;
        String sizePart = type.substring(type.lastIndexOf('[') + 1, type.length() - 1).trim();
        try {
            return Integer.parseInt(sizePart);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    public boolean isUnsafeNumericType(String type) {
        if (type == null) return false;
        if ("i8".equals(type) || "i16".equals(type) || "i32".equals(type) || "i64".equals(type)) return true;
        if ("u8".equals(type) || "u16".equals(type) || "u32".equals(type) || "u64".equals(type)) return true;
        if ("f32".equals(type) || "f64".equals(type)) return true;
        return false;
    }
    
    // ========== WRAPPER FOR UNION TYPES ==========
    
    public Object wrapUnionType(Object value, String declaredType) {
        int declaredMask = parseTypeMask(declaredType);
        if (declaredMask != MASK_PRIMITIVE && Integer.bitCount(declaredMask) > 1) {
            int concreteMask = getConcreteMask(value);
            return new Value(value, concreteMask, declaredMask);
        }
        return value;
    }
    
    public Object normalizeForDeclaredType(String declaredType, Object value) {
        if (declaredType == null) return value;
        if (!isUnsafeNumericType(declaredType)) return value;
        Object converted = convertType(value, declaredType);
        int mask = parseTypeMask(declaredType);
        return new Value(converted, mask, mask);
    }
    
    // ========== TRUTHY ==========
    
    public boolean isTruthy(Object value) {
        if (value == null) return false;
        
        if (value instanceof BoolLiteral) return ((BoolLiteral) value).value;
        if (value instanceof IntLiteral) return !((IntLiteral) value).value.isZero();
        if (value instanceof FloatLiteral) return !((FloatLiteral) value).value.isZero();
        if (value instanceof TextLiteral) {
            String str = ((TextLiteral) value).value;
            return !str.isEmpty() && !"false".equalsIgnoreCase(str);
        }
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).doubleValue() != 0.0;
        if (value instanceof String) {
            String str = (String) value;
            return !str.isEmpty() && !"false".equalsIgnoreCase(str);
        }
        if (value instanceof List<?>) return !((List<?>) value).isEmpty();
        if (value instanceof NaturalArray) return ((NaturalArray) value).size() > 0;
        if (value instanceof AutoStackingNumber) return !((AutoStackingNumber) value).isZero();
        
        return true;
    }
    
    // ========== ARITHMETIC ==========
    
    public Object addNumbers(Object a, Object b) {
        a = unwrap(a); b = unwrap(b);
        if (isArray(a) || isArray(b)) return applyArrayOperation(a, b, "+");
        return addScalars(a, b);
    }
    
    public Object subtractNumbers(Object a, Object b) {
        a = unwrap(a); b = unwrap(b);
        if (isArray(a) || isArray(b)) return applyArrayOperation(a, b, "-");
        return subtractScalars(a, b);
    }
    
    public Object multiplyNumbers(Object a, Object b) {
        a = unwrap(a); b = unwrap(b);
        if (isArray(a) || isArray(b)) return applyArrayOperation(a, b, "*");
        return multiplyScalars(a, b);
    }
    
    public Object divideNumbers(Object a, Object b) {
        a = unwrap(a); b = unwrap(b);
        if (isArray(a) || isArray(b)) return applyArrayOperation(a, b, "/");
        return divideScalars(a, b);
    }
    
    public Object modulusNumbers(Object a, Object b) {
        a = unwrap(a); b = unwrap(b);
        if (isArray(a) || isArray(b)) throw new ProgramError("Cannot use modulus '%' on arrays");
        return modulusScalars(a, b);
    }
    
    public Object negateNumber(Object a) {
        a = unwrap(a);
        if (a instanceof List) throw new ProgramError("Cannot negate an array");
        return toAutoStackingNumber(a).negate();
    }
    
    private Object addScalars(Object a, Object b) {
        if (a instanceof String || b instanceof String ||
            a instanceof TextLiteral || b instanceof TextLiteral) {
            return String.valueOf(a) + String.valueOf(b);
        }
        return toAutoStackingNumber(a).add(toAutoStackingNumber(b));
    }
    
    private Object subtractScalars(Object a, Object b) {
        return toAutoStackingNumber(a).subtract(toAutoStackingNumber(b));
    }
    
    private Object multiplyScalars(Object a, Object b) {
        if ((a instanceof String || a instanceof TextLiteral) && isNumeric(b)) {
            return multiplyString(a, b);
        }
        if ((b instanceof String || b instanceof TextLiteral) && isNumeric(a)) {
            return multiplyString(a, b);
        }
        return toAutoStackingNumber(a).multiply(toAutoStackingNumber(b));
    }
    
    private Object divideScalars(Object a, Object b) {
        AutoStackingNumber divisor = toAutoStackingNumber(b);
        if (divisor.isZero()) throw new ProgramError("Division by zero");
        return toAutoStackingNumber(a).divide(divisor);
    }
    
    private Object modulusScalars(Object a, Object b) {
        AutoStackingNumber divisor = toAutoStackingNumber(b);
        if (divisor.isZero()) throw new ProgramError("Modulus by zero");
        return toAutoStackingNumber(a).remainder(divisor);
    }
    
    private Object multiplyString(Object a, Object b) {
        String str;
        long repeat;
        
        if (a instanceof String || a instanceof TextLiteral) {
            str = a instanceof TextLiteral ? ((TextLiteral) a).value : (String) a;
            repeat = toAutoStackingNumber(b).longValue();
        } else {
            str = b instanceof TextLiteral ? ((TextLiteral) b).value : (String) b;
            repeat = toAutoStackingNumber(a).longValue();
        }
        
        if (repeat < 0) throw new ProgramError("Cannot repeat string negative times");
        if (repeat == 0) return "";
        if (repeat > Integer.MAX_VALUE) throw new ProgramError("Repeat count too large");
        
        StringBuilder sb = new StringBuilder(str.length() * (int) repeat);
        for (int i = 0; i < repeat; i++) sb.append(str);
        return sb.toString();
    }
    
    private boolean isArray(Object obj) {
        return obj instanceof List || obj instanceof NaturalArray;
    }
    
    private boolean isNumeric(Object obj) {
        return obj instanceof AutoStackingNumber || 
               obj instanceof IntLiteral ||
               obj instanceof FloatLiteral || 
               obj instanceof Number;
    }
    
    // ========== ARRAY OPERATIONS (BROADCASTING) ==========
    
    private Object applyArrayOperation(Object a, Object b, String op) {
        boolean aIsArray = isArray(a);
        boolean bIsArray = isArray(b);
        
        if (aIsArray && bIsArray) return applyArrayArrayOperation(a, b, op);
        if (aIsArray) return applyArrayScalarOperation(a, b, op);
        if (bIsArray) return applyArrayScalarOperation(b, a, op);
        throw new InternalError("Invalid array operation state");
    }
    
    private Object applyArrayArrayOperation(Object a, Object b, String op) {
        int sizeA = getArraySize(a);
        int sizeB = getArraySize(b);
        int resultSize;
        
        if (sizeA == sizeB) resultSize = sizeA;
        else if (sizeA == 1) resultSize = sizeB;
        else if (sizeB == 1) resultSize = sizeA;
        else throw new ProgramError("Arrays not broadcast-compatible for '" + op + "'");
        
        int opCode = getArrayOpCode(op);
        List<Object> result = new ArrayList<Object>(resultSize);
        
        for (int i = 0; i < resultSize; i++) {
            Object elemA = getArrayElement(a, sizeA == 1 ? 0 : i);
            Object elemB = getArrayElement(b, sizeB == 1 ? 0 : i);
            if (isArray(elemA) || isArray(elemB)) {
                result.add(applyArrayOperation(elemA, elemB, op));
            } else {
                result.add(applyScalarOpCode(elemA, elemB, opCode));
            }
        }
        return result;
    }
    
    private Object applyArrayScalarOperation(Object array, Object scalar, String op) {
        int size = getArraySize(array);
        int opCode = getArrayOpCode(op);
        
        if (array instanceof NaturalArray) {
            NaturalArray natural = (NaturalArray) array;
            if (!natural.isMutable() && !natural.hasPendingUpdates() && size <= 8192) {
                return new LazyArrayScalarResult(natural, scalar, op, opCode, size);
            }
        }
        
        List<Object> result = new ArrayList<Object>(size);
        for (int i = 0; i < size; i++) {
            Object elem = getArrayElement(array, i);
            if (isArray(elem)) {
                result.add(applyArrayOperation(elem, scalar, op));
            } else {
                result.add(applyScalarOpCode(elem, scalar, opCode));
            }
        }
        return result;
    }
    
    private int getArraySize(Object obj) {
        if (obj instanceof List<?>) return ((List<?>) obj).size();
        if (obj instanceof NaturalArray) {
            long size = ((NaturalArray) obj).size();
            if (size > Integer.MAX_VALUE) throw new ProgramError("Array too large");
            return (int) size;
        }
        throw new InternalError("Cannot get array size");
    }
    
    private Object getArrayElement(Object obj, int index) {
        if (obj instanceof List<?>) return ((List<?>) obj).get(index);
        if (obj instanceof NaturalArray) return ((NaturalArray) obj).get(index);
        throw new InternalError("Cannot get array element");
    }
    
    private int getArrayOpCode(String op) {
        if ("+".equals(op)) return 1;
        if ("-".equals(op)) return 2;
        if ("*".equals(op)) return 3;
        if ("/".equals(op)) return 4;
        throw new InternalError("Unknown array op: " + op);
    }
    
    private Object applyScalarOpCode(Object a, Object b, int opCode) {
        switch (opCode) {
            case 1: return addScalars(a, b);
            case 2: return subtractScalars(a, b);
            case 3: return multiplyScalars(a, b);
            case 4: return divideScalars(a, b);
            default: throw new InternalError("Unknown op code");
        }
    }
    
    private final class LazyArrayScalarResult extends AbstractList<Object> implements RandomAccess {
        private final NaturalArray source;
        private final Object scalar;
        private final String op;
        private final int opCode;
        private final int size;
        private final Object[] cache;
        
        private LazyArrayScalarResult(NaturalArray source, Object scalar, String op, int opCode, int size) {
            this.source = source;
            this.scalar = scalar;
            this.op = op;
            this.opCode = opCode;
            this.size = size;
            this.cache = new Object[size];
        }
        
        public Object get(int index) {
            if (index < 0 || index >= this.size) throw new IndexOutOfBoundsException();
            Object cached = this.cache[index];
            if (cached != null) return cached;
            
            Object elem = this.source.get(index);
            Object computed = isArray(elem) ? 
                applyArrayOperation(elem, this.scalar, this.op) : 
                applyScalarOpCode(elem, this.scalar, this.opCode);
            this.cache[index] = computed;
            return computed;
        }
        
        public int size() {
            return this.size;
        }
    }
    
    // ========== COMPARISON ==========
    
    public int compare(Object a, Object b) {
        a = unwrap(a); b = unwrap(b);
        
        boolean aIsNone = isNoneValue(a);
        boolean bIsNone = isNoneValue(b);
        if (aIsNone && bIsNone) return 0;
        if (aIsNone) return -1;
        if (bIsNone) return 1;
        
        if (a instanceof String || b instanceof String ||
            a instanceof TextLiteral || b instanceof TextLiteral) {
            String sa = a instanceof TextLiteral ? ((TextLiteral) a).value : String.valueOf(a);
            String sb = b instanceof TextLiteral ? ((TextLiteral) b).value : String.valueOf(b);
            return sa.compareTo(sb);
        }
        
        return toAutoStackingNumber(a).compareTo(toAutoStackingNumber(b));
    }
    
    public boolean areEqual(Object a, Object b) {
        a = unwrap(a); b = unwrap(b);
        
        boolean aIsNone = isNoneValue(a);
        boolean bIsNone = isNoneValue(b);
        if (aIsNone && bIsNone) return true;
        if (aIsNone || bIsNone) return false;
        if (a == null) return b == null;
        if (b == null) return false;
        
        if (isNumeric(a) && isNumeric(b)) {
            return toAutoStackingNumber(a).compareTo(toAutoStackingNumber(b)) == 0;
        }
        
        return a.equals(b);
    }
    
    // ========== CONVERSIONS ==========
    
    public AutoStackingNumber toAutoStackingNumber(Object o) {
        o = unwrap(o);
        if (o instanceof AutoStackingNumber) return (AutoStackingNumber) o;
        if (o instanceof IntLiteral) return ((IntLiteral) o).value;
        if (o instanceof FloatLiteral) return ((FloatLiteral) o).value;
        if (o instanceof Integer) return AutoStackingNumber.fromLong(((Integer) o).longValue());
        if (o instanceof Long) return AutoStackingNumber.fromLong(((Long) o).longValue());
        if (o instanceof Number) return AutoStackingNumber.fromDouble(((Number) o).doubleValue());
        if (o instanceof Boolean) return ((Boolean) o).booleanValue() ? AutoStackingNumber.one(1) : AutoStackingNumber.zero(1);
        if (o instanceof BoolLiteral) return ((BoolLiteral) o).value ? AutoStackingNumber.one(1) : AutoStackingNumber.zero(1);
        if (o instanceof String) {
            try { return AutoStackingNumber.valueOf((String) o); }
            catch (NumberFormatException e) { throw new ProgramError("Cannot convert string to number"); }
        }
        if (o instanceof TextLiteral) {
            try { return AutoStackingNumber.valueOf(((TextLiteral) o).value); }
            catch (NumberFormatException e) { throw new ProgramError("Cannot convert string to number"); }
        }
        throw new ProgramError("Cannot convert to number: " + (o != null ? o.getClass().getSimpleName() : "null"));
    }
    
    public Object convertType(Object value, String targetType) {
        value = unwrap(value);
        
        if (value instanceof NaturalArray) {
            NaturalArray arr = (NaturalArray) value;
            if (arr.hasPendingUpdates()) arr.commitUpdates();
        }
        
        int targetMask = parseTypeMask(targetType);
        
        // Type to type
        if (targetMask == MASK_TYPE) {
            if (value instanceof Value && ((Value) value).isTypeValue()) return value;
            if (value instanceof String && isTypeLiteral((String) value)) {
                return Value.createTypeValue(parseTypeMask((String) value));
            }
            if (value instanceof TextLiteral && isTypeLiteral(((TextLiteral) value).value)) {
                return Value.createTypeValue(parseTypeMask(((TextLiteral) value).value));
            }
            throw new ProgramError("Cannot convert to type");
        }
        
        // None
        if (targetMask == MASK_NONE) return new NoneLiteral();
        
        // Unsafe numeric
        if (isUnsafeNumericType(targetType)) return convertUnsafeNumeric(value, targetType);
        
        // Pointer
        if (isPointerType(targetType)) {
            if (value instanceof PointerValue) {
                PointerValue ptr = (PointerValue) value;
                int expected = parseTypeMask(targetType.substring(1));
                if (ptr.pointedMask == expected) return ptr;
            }
            throw new ProgramError("Cannot convert to pointer type: " + targetType);
        }
        
        // Numeric literals
        if (value instanceof IntLiteral) {
            AutoStackingNumber num = ((IntLiteral) value).value;
            if (targetMask == MASK_INT) return num.longValue();
            if (targetMask == MASK_FLOAT) return num;
            if (targetMask == MASK_TEXT) return num.toString();
        }
        
        if (value instanceof FloatLiteral) {
            AutoStackingNumber num = ((FloatLiteral) value).value;
            if (targetMask == MASK_INT) return num.longValue();
            if (targetMask == MASK_FLOAT) return num;
            if (targetMask == MASK_TEXT) return num.toString();
        }
        
        if (value instanceof AutoStackingNumber) {
            AutoStackingNumber num = (AutoStackingNumber) value;
            if (targetMask == MASK_INT) return num.longValue();
            if (targetMask == MASK_FLOAT) return num;
            if (targetMask == MASK_TEXT) return num.toString();
        }
        
        // Boolean
        if (value instanceof BoolLiteral) {
            boolean b = ((BoolLiteral) value).value;
            if (targetMask == MASK_BOOL) return b;
            if (targetMask == MASK_INT) return b ? 1 : 0;
            if (targetMask == MASK_FLOAT) return b ? AutoStackingNumber.one(1) : AutoStackingNumber.zero(1);
            if (targetMask == MASK_TEXT) return String.valueOf(b);
        }
        
        // Text
        if (value instanceof TextLiteral) {
            String s = ((TextLiteral) value).value;
            if (targetMask == MASK_TEXT) return s;
            if (targetMask == MASK_INT) {
                try { return Integer.parseInt(s); }
                catch (NumberFormatException e) { throw new ProgramError("Cannot convert text to int"); }
            }
            if (targetMask == MASK_FLOAT) {
                try { return AutoStackingNumber.valueOf(s); }
                catch (NumberFormatException e) { throw new ProgramError("Cannot convert text to float"); }
            }
            if (targetMask == MASK_BOOL) {
                String lower = s.toLowerCase().trim();
                if ("true".equals(lower)) return true;
                if ("false".equals(lower)) return false;
                throw new ProgramError("Cannot convert text to bool");
            }
        }
        
        // Fallback numeric
        if (targetMask == MASK_INT) return (int) toAutoStackingNumber(value).longValue();
        if (targetMask == MASK_FLOAT) return toAutoStackingNumber(value);
        if (targetMask == MASK_TEXT) return String.valueOf(value);
        if (targetMask == MASK_BOOL) return toAutoStackingNumber(value).doubleValue() != 0.0;
        
        throw new ProgramError("Cannot convert to: " + targetType);
    }
    
    private Object convertUnsafeNumeric(Object value, String targetType) {
        double d = toAutoStackingNumber(value).doubleValue();
        if ("f32".equals(targetType)) return AutoStackingNumber.fromDouble((float) d);
        if ("f64".equals(targetType)) return AutoStackingNumber.fromDouble(d);
        
        long l = toAutoStackingNumber(value).longValue();
        if ("i8".equals(targetType)) return (byte) l;
        if ("i16".equals(targetType)) return (short) l;
        if ("i32".equals(targetType)) return (int) l;
        if ("i64".equals(targetType)) return l;
        if ("u8".equals(targetType)) return (byte) l;
        if ("u16".equals(targetType)) return (short) l;
        if ("u32".equals(targetType)) return (int) l;
        if ("u64".equals(targetType)) return l;
        
        throw new ProgramError("Unknown unsafe numeric type: " + targetType);
    }
}