package cod.math;

import java.util.Arrays;

public class AutoStackingNumber implements Comparable<AutoStackingNumber> {

    public static final int MAX_STACKS = 7;
    private static final double TWO_64 = 18446744073709551616.0; // 2^64
    
    // Identity Constants & Cache
    private static final AutoStackingNumber[] SMALL_CACHE = new AutoStackingNumber[256];
    public static final AutoStackingNumber ZERO;
    public static final AutoStackingNumber ONE;
    public static final AutoStackingNumber MINUS_ONE;
    public static final AutoStackingNumber TEN;
    
    // Power of 10 Cache for normalization speed
    private static final UBigInt[] POW10_CACHE = new UBigInt[20];

    static {
        // Initialize Cache first
        for (int i = 0; i < 256; i++) SMALL_CACHE[i] = new AutoStackingNumber((long) (i - 128));
        
        // Point constants to the cache to enable lightning-fast '==' checks
        ZERO = SMALL_CACHE[128];
        ONE = SMALL_CACHE[129];
        MINUS_ONE = SMALL_CACHE[127];
        TEN = SMALL_CACHE[138]; // 10 - (-128) = 138

        POW10_CACHE[0] = new UBigInt(1);
        for (int i = 1; i < 20; i++) {
            POW10_CACHE[i] = POW10_CACHE[i - 1].multiply(TEN.mag);
        }
    }

    private final int signum;
    private final UBigInt mag;
    private final int scale;
    private final int stacks;
    
    private final boolean isCompact;
    private final long compactValue;
    
    private transient volatile String cachedToString;

    // ---------- Constructors ----------

    private AutoStackingNumber(int signum, UBigInt mag, int scale, int stacks) {
        this.signum = signum;
        this.mag = mag;
        this.scale = scale;
        this.stacks = stacks;
        this.isCompact = false;
        this.compactValue = 0;
    }

    public AutoStackingNumber(int stacks) {
        if (stacks < 1 || stacks > MAX_STACKS) throw new IllegalArgumentException("Stacks must 1-" + MAX_STACKS);
        this.signum = 0;
        this.mag = new UBigInt(0);
        this.scale = 0;
        this.stacks = stacks;
        this.isCompact = true;
        this.compactValue = 0;
    }

    public AutoStackingNumber(long value) {
        this.signum = value == 0 ? 0 : (value < 0 ? -1 : 1);
        this.mag = new UBigInt(Math.abs(value));
        this.scale = 0;
        this.stacks = 1;
        this.isCompact = true;
        this.compactValue = value;
    }

    public AutoStackingNumber(int stacks, long value) {
        if (stacks < 1 || stacks > MAX_STACKS) throw new IllegalArgumentException("Stacks must be 1-" + MAX_STACKS);
        this.signum = value == 0 ? 0 : (value < 0 ? -1 : 1);
        this.mag = new UBigInt(Math.abs(value));
        this.scale = 0;
        this.stacks = stacks;
        this.isCompact = true;
        this.compactValue = value;
    }

    public AutoStackingNumber(long[] words) {
        if (words == null || words.length < 1 || words.length > MAX_STACKS)
            throw new IllegalArgumentException("Words must be 1-" + MAX_STACKS);
        this.stacks = words.length;
        this.scale = 0;
        boolean neg = words[0] < 0;
        long[] w = words.clone();
        if (neg) {
            long carry = 1;
            for (int i = w.length - 1; i >= 0; i--) {
                long val = ~w[i] + carry;
                w[i] = val;
                carry = (val == 0 && carry == 1) ? 1 : 0;
            }
        }
        int[] ints = new int[w.length * 2];
        for (int i = 0; i < w.length; i++) {
            ints[i * 2] = (int)(w[i] >>> 32);
            ints[i * 2 + 1] = (int)(w[i]);
        }
        this.mag = new UBigInt(ints);
        this.signum = this.mag.isZero() ? 0 : (neg ? -1 : 1);
        this.isCompact = false;
        this.compactValue = 0;
    }

    public AutoStackingNumber(AutoStackingNumber other) {
        this.signum = other.signum;
        this.mag = other.mag;
        this.scale = other.scale;
        this.stacks = other.stacks;
        this.isCompact = other.isCompact;
        this.compactValue = other.compactValue;
    }

    // ---------- Internal Helpers ----------

    private static int calculateRequiredStacks(int sign, UBigInt part) {
        if (part.isZero()) return 1;
        long[] raw = part.toLongArray();
        int req = raw.length;
        if (sign > 0 && raw[0] < 0) {
            req++;
        } else if (sign < 0 && raw[0] < 0) {
            boolean isMin = (raw[0] == 0x8000000000000000L);
            for (int i = 1; isMin && i < raw.length; i++) {
                if (raw[i] != 0) isMin = false;
            }
            if (!isMin) req++;
        }
        return req;
    }

    private AutoStackingNumber normalize(int resSign, UBigInt resMag, int resScale, int requestStacks) {
        while (resScale > 0) {
            UBigInt[] dr = resMag.divideAndRemainder(TEN.mag);
            if (dr[1].isZero()) {
                resMag = dr[0];
                resScale--;
            } else {
                break;
            }
        }
        if (resMag.isZero()) { resSign = 0; resScale = 0; }
        
        UBigInt intPart = resScale == 0 ? resMag : resMag.divideAndRemainder(pow10(resScale))[0];
        int intStacks = calculateRequiredStacks(resSign, intPart);
        
        if (intStacks > MAX_STACKS) throw new ArithmeticException("Overflow beyond " + MAX_STACKS + " stacks");
        
        if (resScale == 0 && intStacks == 1) {
            long val = resMag.toLong();
            return fromLong(resSign < 0 ? -val : val);
        }
        
        return new AutoStackingNumber(resSign, resMag, resScale, Math.max(requestStacks, intStacks));
    }

    private static UBigInt pow10(int exp) {
        if (exp < 20) return POW10_CACHE[exp];
        UBigInt res = POW10_CACHE[19];
        UBigInt base = TEN.mag;
        for (int i = 19; i < exp; i++) res = res.multiply(base);
        return res;
    }

    // ---------- Factory ----------

    public static AutoStackingNumber zero(int stacks) { return new AutoStackingNumber(stacks); }
    public static AutoStackingNumber one(int stacks) { return new AutoStackingNumber(stacks, 1L); }
    public static AutoStackingNumber minusOne(int stacks) { return new AutoStackingNumber(stacks, -1L); }
    
    public static AutoStackingNumber fromLong(long value) {
        if (value >= -128 && value <= 127) return SMALL_CACHE[(int) value + 128];
        return new AutoStackingNumber(value);
    }
    
    public static AutoStackingNumber fromDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException("Invalid double");
        return valueOf(Double.toString(value));
    }

    // ---------- Parsing ----------

    public static AutoStackingNumber valueOf(String s) {
        if (s == null || s.trim().isEmpty()) throw new NumberFormatException("Empty string");
        s = s.trim();
        boolean neg = s.startsWith("-");
        if (neg || s.startsWith("+")) s = s.substring(1);
        
        int eIdx = Math.max(s.indexOf('e'), s.indexOf('E'));
        int exp = 0;
        if (eIdx >= 0) {
            String expStr = s.substring(eIdx + 1);
            exp = Integer.parseInt(expStr.startsWith("+") ? expStr.substring(1) : expStr);
            s = s.substring(0, eIdx);
        }
        
        int scale = 0;
        int dot = s.indexOf('.');
        if (dot >= 0) {
            scale = s.length() - 1 - dot;
            s = s.substring(0, dot) + s.substring(dot + 1);
        }
        
        if (s.isEmpty()) s = "0";
        scale -= exp;
        
        UBigInt mag = parseBase10(s);
        if (scale < 0) {
            mag = mag.multiply(pow10(-scale));
            scale = 0;
        }
        
        return new AutoStackingNumber(neg && !mag.isZero() ? -1 : 1, mag, scale, 1).normalize(neg ? -1 : 1, mag, scale, 1);
    }
    
    private static UBigInt parseBase10(String s) {
        UBigInt res = new UBigInt(0);
        for (int i = 0; i < s.length(); i++) {
            res = res.multiply(TEN.mag).add(new UBigInt(s.charAt(i) - '0'));
        }
        return res;
    }

    // ---------- Arithmetic ----------

    public AutoStackingNumber add(AutoStackingNumber other) {
        if (this.isZero()) return other;
        if (other.isZero()) return this;

        if (this.isCompact && other.isCompact && this.scale == 0 && other.scale == 0) {
            long r = this.compactValue + other.compactValue;
            if (((this.compactValue ^ r) & (other.compactValue ^ r)) >= 0) return fromLong(r);
        }
        
        int maxScale = Math.max(this.scale, other.scale);
        UBigInt m1 = this.mag.multiply(pow10(maxScale - this.scale));
        UBigInt m2 = other.mag.multiply(pow10(maxScale - other.scale));
        
        UBigInt resMag;
        int resSign;
        if (this.signum == other.signum) {
            resMag = m1.add(m2);
            resSign = this.signum;
        } else {
            int cmp = m1.compareTo(m2);
            if (cmp == 0) return ZERO;
            resMag = cmp > 0 ? m1.subtract(m2) : m2.subtract(m1);
            resSign = cmp > 0 ? this.signum : other.signum;
        }
        return normalize(resSign, resMag, maxScale, Math.max(this.stacks, other.stacks));
    }

    public AutoStackingNumber subtract(AutoStackingNumber other) { return add(other.negate()); }

    public AutoStackingNumber multiply(AutoStackingNumber other) {
        if (this.isZero() || other.isZero()) return ZERO;
        if (this == ONE) return other;
        if (other == ONE) return this;

        if (this.isCompact && other.isCompact && this.scale == 0 && other.scale == 0) {
            long a = this.compactValue, b = other.compactValue;
            long r = a * b;
            if (a == 0 || r / a == b) return fromLong(r);
        }

        return normalize(this.signum * other.signum, this.mag.multiply(other.mag), this.scale + other.scale, Math.max(this.stacks, other.stacks));
    }

    public AutoStackingNumber divide(AutoStackingNumber other) {
        if (other.isZero()) throw new ArithmeticException("Division by zero");
        if (this.isZero()) return ZERO;
        
        // Critical Fast-Path: Bypasses scaling for exact integer divisions
        if (this.isCompact && other.isCompact && this.scale == 0 && other.scale == 0) {
            long a = this.compactValue, b = other.compactValue;
            if (a % b == 0) return fromLong(a / b);
        }
        
        int targetScale = Math.max(this.scale, other.scale) + 18;
        UBigInt num = this.mag.multiply(pow10(targetScale - this.scale + other.scale));
        return normalize(this.signum * other.signum, num.divideAndRemainder(other.mag)[0], targetScale, Math.max(this.stacks, other.stacks));
    }

    public AutoStackingNumber remainder(AutoStackingNumber other) {
        if (other.isZero()) throw new ArithmeticException("Division by zero");
        if (this.isCompact && other.isCompact && this.scale == 0 && other.scale == 0) return fromLong(this.compactValue % other.compactValue);
        AutoStackingNumber quot = this.divide(other);
        UBigInt intMag = quot.scale > 0 ? quot.mag.divideAndRemainder(pow10(quot.scale))[0] : quot.mag;
        return this.subtract(normalize(intMag.isZero() ? 0 : quot.signum, intMag, 0, 1).multiply(other));
    }

    public AutoStackingNumber negate() {
        if (isCompact) return fromLong(-compactValue);
        return new AutoStackingNumber(signum == 0 ? 0 : -signum, mag, scale, stacks);
    }

    public AutoStackingNumber abs() { return signum < 0 ? negate() : this; }

    public AutoStackingNumber shiftLeft(int bits) {
        if (bits == 0) return this;
        if (bits < 0) return shiftRight(-bits);
        return normalize(signum, mag.shiftLeft(bits), scale, stacks);
    }

    public AutoStackingNumber shiftRight(int bits) {
        if (bits == 0) return this;
        if (bits < 0) return shiftLeft(-bits);
        UBigInt shifted = mag.divideAndRemainder(ONE.mag.shiftLeft(bits))[0];
        return normalize(signum, shifted, scale, stacks);
    }

    public AutoStackingNumber pow(int exp) {
        if (exp < 0) throw new IllegalArgumentException("Negative exponent");
        if (exp == 0) return ONE;
        if (exp == 1) return this;
        AutoStackingNumber res = ONE, base = this;
        while (exp > 0) {
            if ((exp & 1) != 0) res = res.multiply(base);
            exp >>= 1;
            if (exp > 0) base = base.multiply(base);
        }
        return res;
    }

    // ---------- Comparison ----------

    @Override
    public int compareTo(AutoStackingNumber o) {
        if (this.isCompact && o.isCompact && this.scale == 0 && o.scale == 0)
            return compactValue < o.compactValue ? -1 : (compactValue > o.compactValue ? 1 : 0);
        if (this.signum != o.signum) return this.signum < o.signum ? -1 : 1;
        if (this.signum == 0) return 0;
        int maxScale = Math.max(this.scale, o.scale);
        int cmp = this.mag.multiply(pow10(maxScale - this.scale)).compareTo(o.mag.multiply(pow10(maxScale - o.scale)));
        return this.signum < 0 ? -cmp : cmp;
    }

    public boolean isZero() { return signum == 0; }
    public boolean isNegative() { return signum < 0; }
    public boolean isPositive() { return signum > 0; }

    // ---------- Conversion ----------

    public long longValue() {
        if (isCompact && scale == 0) return compactValue;
        UBigInt[] dr = mag.divideAndRemainder(pow10(scale));
        if (!dr[1].isZero()) throw new ArithmeticException("Has fractional part");
        return signum < 0 ? -dr[0].toLong() : dr[0].toLong();
    }

    public double doubleValue() {
        if (isCompact && scale == 0) return (double) compactValue;
        double res = 0.0;
        long[] words = mag.toLongArray();
        for (long w : words) {
            double d = (double) w;
            if (d < 0) d += TWO_64; 
            res = res * TWO_64 + d;
        }
        if (scale > 0) res /= Math.pow(10, scale);
        return signum < 0 ? -res : res;
    }

    @Override
    public String toString() {
        if (cachedToString != null) return cachedToString;
        if (isCompact && scale == 0) return cachedToString = Long.toString(compactValue);
        String s = mag.toStringBase10();
        if (scale == 0) return cachedToString = (signum < 0 ? "-" : "") + s;
        StringBuilder sb = new StringBuilder(s.length() + scale + 2);
        if (signum < 0) sb.append('-');
        if (s.length() <= scale) {
            sb.append("0.");
            for (int i = 0; i < scale - s.length(); i++) sb.append('0');
            sb.append(s);
        } else {
            int dot = s.length() - scale;
            sb.append(s.substring(0, dot)).append('.').append(s.substring(dot));
        }
        return cachedToString = sb.toString();
    }

    public String toPlainString() { return toString(); }

    public int getStacks() { return stacks; }
    public long[] getWords() {
        UBigInt intPart = scale == 0 ? mag : mag.divideAndRemainder(pow10(scale))[0];
        long[] res = new long[stacks], raw = intPart.toLongArray();
        long carry = signum < 0 ? 1 : 0;
        for (int i = 0; i < stacks; i++) {
            long val = (i < raw.length ? raw[raw.length - 1 - i] : 0);
            if (signum < 0) { val = ~val + carry; carry = (val == 0 && carry == 1) ? 1 : 0; }
            res[stacks - 1 - i] = val;
        }
        return res;
    }

    public boolean fitsInStacks(int target) { return target >= calculateRequiredStacks(signum, scale == 0 ? mag : mag.divideAndRemainder(pow10(scale))[0]); }
    public AutoStackingNumber promote(int s) { return normalize(signum, mag, scale, s); }
    public AutoStackingNumber demote(int s) { if (!fitsInStacks(s)) throw new ArithmeticException("Data loss"); return new AutoStackingNumber(signum, mag, scale, s); }
    @Override
    public boolean equals(Object obj) { return (obj instanceof AutoStackingNumber) && this.compareTo((AutoStackingNumber) obj) == 0; }
    @Override
    public int hashCode() { return toPlainString().hashCode(); }

    // ---------- Internal UBigInt Engine ----------

    private static class UBigInt {
        final int[] mag;
        UBigInt(long v) { v = Math.abs(v); if (v == 0) mag = new int[0]; else { int h = (int)(v >>> 32), l = (int)v; mag = h == 0 ? new int[]{l} : new int[]{h, l}; } }
        UBigInt(int[] m) { int s = 0; while (s < m.length && m[s] == 0) s++; mag = s == m.length ? new int[0] : Arrays.copyOfRange(m, s, m.length); }
        boolean isZero() { return mag.length == 0; }
        int compareTo(UBigInt o) {
            if (mag.length != o.mag.length) return mag.length < o.mag.length ? -1 : 1;
            for (int i = 0; i < mag.length; i++) { long a = mag[i] & 0xFFFFFFFFL, b = o.mag[i] & 0xFFFFFFFFL; if (a != b) return a < b ? -1 : 1; }
            return 0;
        }
        UBigInt add(UBigInt o) {
            int len = Math.max(mag.length, o.mag.length); int[] res = new int[len + 1]; long c = 0;
            for (int i = 0; i < len; i++) { long a = i < mag.length ? (mag[mag.length-1-i] & 0xFFFFFFFFL) : 0, b = i < o.mag.length ? (o.mag[o.mag.length-1-i] & 0xFFFFFFFFL) : 0, s = a+b+c; res[res.length-1-i] = (int)s; c = s >>> 32; }
            res[0] = (int)c; return new UBigInt(res);
        }
        UBigInt subtract(UBigInt o) {
            int[] res = new int[mag.length]; long b = 0;
            for (int i = 0; i < mag.length; i++) { long a = mag[mag.length-1-i] & 0xFFFFFFFFL, v = i < o.mag.length ? (o.mag[o.mag.length-1-i] & 0xFFFFFFFFL) : 0, d = a-v-b; res[res.length-1-i] = (int)d; b = d < 0 ? 1 : 0; }
            return new UBigInt(res);
        }
        UBigInt multiply(UBigInt o) {
            if (isZero() || o.isZero()) return new UBigInt(0);
            int[] res = new int[mag.length + o.mag.length];
            for (int i = o.mag.length - 1; i >= 0; i--) {
                long v = o.mag[i] & 0xFFFFFFFFL, c = 0;
                for (int j = mag.length - 1; j >= 0; j--) { long p = (mag[j] & 0xFFFFFFFFL) * v + (res[i+j+1] & 0xFFFFFFFFL) + c; res[i+j+1] = (int)p; c = p >>> 32; }
                res[i] = (int)c;
            }
            return new UBigInt(res);
        }
        UBigInt shiftLeft(int n) {
            if (isZero() || n == 0) return this;
            int ws = n / 32, bs = n % 32; 
            int[] res = new int[mag.length + ws + 1]; 
            long c = 0;
            for (int i = mag.length - 1; i >= 0; i--) { 
                long v = mag[i] & 0xFFFFFFFFL;
                long s = (v << bs) | c; 
                res[i+1] = (int)s; // Fixed indexing
                c = v >>> (32 - bs); 
            }
            res[0] = (int)c; // Fixed indexing
            return new UBigInt(res);
        }
        boolean testBit(int n) {
            int word = mag.length - 1 - (n / 32);
            return word >= 0 && (mag[word] & (1 << (n % 32))) != 0;
        }
        UBigInt setBit(int n) {
            int wordShift = n / 32, len = Math.max(mag.length, wordShift + 1);
            int[] res = new int[len]; System.arraycopy(mag, 0, res, len - mag.length, mag.length);
            res[len - 1 - wordShift] |= (1 << (n % 32)); return new UBigInt(res);
        }
        int bitLength() { if (isZero()) return 0; int t = mag[0], b = (mag.length-1)*32; for (int i = 31; i >= 0; i--) if ((t & (1 << i)) != 0) return b+i+1; return b; }
        UBigInt[] divideAndRemainder(UBigInt b) {
            if (b.isZero()) throw new ArithmeticException("Divide by zero");
            if (compareTo(b) < 0) return new UBigInt[]{new UBigInt(0), this};
            UBigInt q = new UBigInt(0), r = new UBigInt(0);
            for (int i = bitLength() - 1; i >= 0; i--) {
                r = r.shiftLeft(1); 
                if (testBit(i)) r = r.add(new UBigInt(1)); 
                if (r.compareTo(b) >= 0) { r = r.subtract(b); q = q.setBit(i); } 
            }
            return new UBigInt[]{q, r};
        }
        long toLong() { if (mag.length == 0) return 0; if (mag.length == 1) return mag[0] & 0xFFFFFFFFL; return ((mag[mag.length-2] & 0xFFFFFFFFL) << 32) | (mag[mag.length-1] & 0xFFFFFFFFL); }
        long[] toLongArray() {
            int len = (mag.length + 1) / 2; long[] res = new long[len];
            for (int i = 0; i < len; i++) { long l = mag.length-1-(i*2) >= 0 ? mag[mag.length-1-(i*2)] & 0xFFFFFFFFL : 0, h = mag.length-2-(i*2) >= 0 ? mag[mag.length-2-(i*2)] & 0xFFFFFFFFL : 0; res[len-1-i] = (h << 32) | l; }
            return res;
        }
        String toStringBase10() {
            if (isZero()) return "0";
            UBigInt t = this; StringBuilder sb = new StringBuilder();
            UBigInt ten = TEN.mag;
            while (!t.isZero()) { UBigInt[] dr = t.divideAndRemainder(ten); sb.append(dr[1].isZero() ? 0 : dr[1].mag[0]); t = dr[0]; }
            return sb.reverse().toString();
        }
    }
}
