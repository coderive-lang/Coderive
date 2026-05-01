package cod.test;

import cod.math.AutoStackingNumber;

/**
 * Comprehensive test suite for AutoStackingNumber
 * Java 7 compatible - no annotations, no modern features
 */
public class BigNumTest {
    
    private static int testsPassed = 0;
    private static int testsFailed = 0;
    
    public static void main(String[] args) {
        System.out.println("=== AutoStackingNumber Test Suite ===\n");
        
        testConstructors();
        testValueOf();
        testAddition();
        testSubtraction();
        testMultiplication();
        testDivision();
        testComparison();
        testNegation();
        testShifts();
        testOverflow();
        testFractional();
        testEdgeCases();
        
        System.out.println("\n=== Test Summary ===");
        System.out.println("Passed: " + testsPassed);
        System.out.println("Failed: " + testsFailed);
        System.out.println("Total:  " + (testsPassed + testsFailed));
        
        if (testsFailed == 0) {
            System.out.println("\n✓ All tests passed!");
        } else {
            System.out.println("\n✗ Some tests failed!");
        }
    }
    
    private static void assertEqual(String testName, AutoStackingNumber expected, AutoStackingNumber actual) {
        if (expected.equals(actual)) {
            System.out.println("  ✓ " + testName);
            testsPassed++;
        } else {
            System.out.println("  ✗ " + testName);
            System.out.println("    Expected: " + expected.toPlainString());
            System.out.println("    Actual:   " + actual.toPlainString());
            testsFailed++;
        }
    }
    
    private static void assertEqual(String testName, long expected, long actual) {
        if (expected == actual) {
            System.out.println("  ✓ " + testName);
            testsPassed++;
        } else {
            System.out.println("  ✗ " + testName);
            System.out.println("    Expected: " + expected);
            System.out.println("    Actual:   " + actual);
            testsFailed++;
        }
    }
    
    private static void assertTrue(String testName, boolean condition) {
        if (condition) {
            System.out.println("  ✓ " + testName);
            testsPassed++;
        } else {
            System.out.println("  ✗ " + testName);
            System.out.println("    Condition failed");
            testsFailed++;
        }
    }
    
    private static void assertThrows(String testName, Class<? extends Exception> expected, Runnable code) {
        try {
            code.run();
            System.out.println("  ✗ " + testName);
            System.out.println("    Expected exception: " + expected.getSimpleName());
            testsFailed++;
        } catch (Exception e) {
            if (expected.isInstance(e)) {
                System.out.println("  ✓ " + testName);
                testsPassed++;
            } else {
                System.out.println("  ✗ " + testName);
                System.out.println("    Expected: " + expected.getSimpleName());
                System.out.println("    Got:      " + e.getClass().getSimpleName());
                testsFailed++;
            }
        }
    }
    
    private static void testConstructors() {
        System.out.println("--- Constructor Tests ---");
        
        AutoStackingNumber n1 = new AutoStackingNumber(5L);
        assertEqual("Long constructor", 5L, n1.longValue());
        
        AutoStackingNumber n2 = new AutoStackingNumber(3);
        assertTrue("Zero of stacks=3", n2.isZero());
        
        AutoStackingNumber n3 = new AutoStackingNumber(3, 42L);
        assertEqual("Stacks+long constructor", 42L, n3.longValue());
        
        long[] words = {1L, 2L, 3L};
        AutoStackingNumber n4 = new AutoStackingNumber(words);
        assertTrue("Words array constructor - fits correctly", !n4.fitsInStacks(1));
        
        assertThrows("Invalid stacks - too low", 
            IllegalArgumentException.class,
            new Runnable() { public void run() { new AutoStackingNumber(0); } });
        
        assertThrows("Invalid stacks - too high", 
            IllegalArgumentException.class,
            new Runnable() { public void run() { new AutoStackingNumber(8); } });
        
        System.out.println();
    }
    
    private static void testValueOf() {
        System.out.println("--- valueOf Tests ---");
        
        assertEqual("valueOf integer", 
            AutoStackingNumber.valueOf("123"), 
            new AutoStackingNumber(123L));
        
        assertEqual("valueOf negative", 
            AutoStackingNumber.valueOf("-456"), 
            new AutoStackingNumber(-456L));
        
        AutoStackingNumber dec1 = AutoStackingNumber.valueOf("123.456");
        AutoStackingNumber dec2 = AutoStackingNumber.fromDouble(123.456);
        assertTrue("valueOf decimal", dec1.toPlainString().equals(dec2.toPlainString()));
        
        assertEqual("valueOf leading zeros", 
            AutoStackingNumber.valueOf("00123"), 
            new AutoStackingNumber(123L));
        
        AutoStackingNumber trail1 = AutoStackingNumber.valueOf("123.4500");
        AutoStackingNumber trail2 = AutoStackingNumber.valueOf("123.45");
        assertTrue("valueOf trailing zeros", trail1.toPlainString().equals(trail2.toPlainString()));
        
        AutoStackingNumber dot5 = AutoStackingNumber.valueOf(".5");
        AutoStackingNumber expected5 = AutoStackingNumber.fromDouble(0.5);
        assertTrue("valueOf .5", dot5.toPlainString().equals(expected5.toPlainString()));
        
        assertEqual("valueOf 5.", 
            AutoStackingNumber.valueOf("5."), 
            new AutoStackingNumber(5L));
        
        assertThrows("valueOf empty string",
            NumberFormatException.class,
            new Runnable() { public void run() { AutoStackingNumber.valueOf(""); } });
        
        assertThrows("valueOf invalid",
            NumberFormatException.class,
            new Runnable() { public void run() { AutoStackingNumber.valueOf("abc"); } });
        
        System.out.println();
    }
    
    private static void testAddition() {
        System.out.println("--- Addition Tests ---");
        
        AutoStackingNumber a1 = new AutoStackingNumber(123L);
        AutoStackingNumber b1 = new AutoStackingNumber(456L);
        assertEqual("Simple addition", 579L, a1.add(b1).longValue());
        
        AutoStackingNumber a2 = new AutoStackingNumber(-50L);
        AutoStackingNumber b2 = new AutoStackingNumber(30L);
        assertEqual("Negative addition", -20L, a2.add(b2).longValue());
        
        AutoStackingNumber a3 = new AutoStackingNumber(Long.MAX_VALUE);
        AutoStackingNumber b3 = new AutoStackingNumber(1L);
        AutoStackingNumber sum3 = a3.add(b3);
        assertTrue("Overflow to 2 stacks", sum3.getStacks() >= 2);
        
        AutoStackingNumber a4 = AutoStackingNumber.valueOf("5.5");
        AutoStackingNumber b4 = AutoStackingNumber.valueOf("2.25");
        AutoStackingNumber sum4 = a4.add(b4);
        assertEqual("Fractional addition", AutoStackingNumber.valueOf("7.75"), sum4);
        
        AutoStackingNumber a5 = AutoStackingNumber.valueOf("0.999999999999999999");
        AutoStackingNumber b5 = AutoStackingNumber.valueOf("0.000000000000000001");
        AutoStackingNumber sum5 = a5.add(b5);
        assertEqual("Carry to integer", AutoStackingNumber.valueOf("1.0"), sum5);
        
        System.out.println();
    }
    
    private static void testSubtraction() {
        System.out.println("--- Subtraction Tests ---");
        
        AutoStackingNumber a1 = new AutoStackingNumber(456L);
        AutoStackingNumber b1 = new AutoStackingNumber(123L);
        assertEqual("Simple subtraction", 333L, a1.subtract(b1).longValue());
        
        AutoStackingNumber a2 = new AutoStackingNumber(50L);
        AutoStackingNumber b2 = new AutoStackingNumber(100L);
        assertEqual("Negative result", -50L, a2.subtract(b2).longValue());
        
        AutoStackingNumber a3 = AutoStackingNumber.valueOf("5.5");
        AutoStackingNumber b3 = AutoStackingNumber.valueOf("2.25");
        assertEqual("Fractional subtraction", AutoStackingNumber.valueOf("3.25"), a3.subtract(b3));
        
        System.out.println();
    }
    
    private static void testMultiplication() {
        System.out.println("--- Multiplication Tests ---");
        
        AutoStackingNumber a1 = new AutoStackingNumber(12L);
        AutoStackingNumber b1 = new AutoStackingNumber(34L);
        assertEqual("Simple multiplication", 408L, a1.multiply(b1).longValue());
        
        AutoStackingNumber a2 = new AutoStackingNumber(-7L);
        AutoStackingNumber b2 = new AutoStackingNumber(8L);
        assertEqual("Negative multiplication", -56L, a2.multiply(b2).longValue());
        
        AutoStackingNumber a3 = AutoStackingNumber.valueOf("2.5");
        AutoStackingNumber b3 = AutoStackingNumber.valueOf("4.0");
        assertEqual("Fractional multiplication", AutoStackingNumber.valueOf("10.0"), a3.multiply(b3));
        
        AutoStackingNumber a4 = AutoStackingNumber.valueOf("0.1");
        AutoStackingNumber b4 = AutoStackingNumber.valueOf("0.1");
        assertTrue("Small fractional", Math.abs(a4.multiply(b4).doubleValue() - 0.01) < 0.0000001);
        
        System.out.println();
    }
    
    private static void testDivision() {
        System.out.println("--- Division Tests ---");
        
        AutoStackingNumber a1 = new AutoStackingNumber(100L);
        AutoStackingNumber b1 = new AutoStackingNumber(4L);
        assertEqual("Simple division", 25L, a1.divide(b1).longValue());
        
        AutoStackingNumber a2 = new AutoStackingNumber(10L);
        AutoStackingNumber b2 = new AutoStackingNumber(3L);
        AutoStackingNumber quot2 = a2.divide(b2);
        assertTrue("Non-integer division", Math.abs(quot2.doubleValue() - 3.3333333333333335) < 0.0000001);
        
        AutoStackingNumber a3 = AutoStackingNumber.valueOf("7.5");
        AutoStackingNumber b3 = AutoStackingNumber.valueOf("2.5");
        assertEqual("Fractional division", AutoStackingNumber.valueOf("3.0"), a3.divide(b3));
        
        assertThrows("Division by zero",
            ArithmeticException.class,
            new Runnable() { public void run() { 
                new AutoStackingNumber(5L).divide(new AutoStackingNumber(0L)); 
            } });
        
        System.out.println();
    }
    
    private static void testComparison() {
        System.out.println("--- Comparison Tests ---");
        
        AutoStackingNumber a1 = new AutoStackingNumber(5L);
        AutoStackingNumber b1 = new AutoStackingNumber(10L);
        assertTrue("Less than", a1.compareTo(b1) < 0);
        assertTrue("Greater than", b1.compareTo(a1) > 0);
        
        AutoStackingNumber a2 = AutoStackingNumber.valueOf("5.5");
        AutoStackingNumber b2 = AutoStackingNumber.valueOf("5.5");
        assertTrue("Equal", a2.compareTo(b2) == 0);
        
        AutoStackingNumber a3 = AutoStackingNumber.valueOf("-5.5");
        AutoStackingNumber b3 = AutoStackingNumber.valueOf("5.5");
        assertTrue("Negative vs positive", a3.compareTo(b3) < 0);
        
        assertTrue("isZero true", AutoStackingNumber.valueOf("0").isZero());
        assertTrue("isZero false", !AutoStackingNumber.valueOf("1").isZero());
        assertTrue("isNegative true", AutoStackingNumber.valueOf("-5").isNegative());
        assertTrue("isPositive true", AutoStackingNumber.valueOf("5").isPositive());
        
        System.out.println();
    }
    
    private static void testNegation() {
        System.out.println("--- Negation Tests ---");
        
        AutoStackingNumber a1 = new AutoStackingNumber(42L);
        assertEqual("Negate positive", -42L, a1.negate().longValue());
        
        AutoStackingNumber a2 = new AutoStackingNumber(-42L);
        assertEqual("Negate negative", 42L, a2.negate().longValue());
        
        AutoStackingNumber a3 = AutoStackingNumber.valueOf("123.456");
        AutoStackingNumber neg3 = a3.negate();
        assertTrue("Negate fractional", neg3.isNegative());
        assertEqual("Abs", a3, neg3.abs());
        
        System.out.println();
    }
    
    private static void testShifts() {
        System.out.println("--- Shift Tests ---");
        
        AutoStackingNumber a1 = new AutoStackingNumber(5L);
        assertEqual("Shift left", 40L, a1.shiftLeft(3).longValue());
        assertEqual("Shift right", 1L, a1.shiftRight(2).longValue());
        
        AutoStackingNumber a2 = AutoStackingNumber.valueOf("123.456");
        AutoStackingNumber shifted = a2.shiftLeft(1);
        assertTrue("Shift doesn't lose sign", shifted.isPositive());
        // A bitwise shift left by 1 multiplies the magnitude exactly by 2
        assertTrue("Shift left works", Math.abs(shifted.doubleValue() - 246.912) < 0.0001);
        
        System.out.println();
    }
    
    private static void testOverflow() {
        System.out.println("--- Overflow Tests ---");
        
        assertThrows("Multiplication overflow detection",
            ArithmeticException.class,
            new Runnable() { 
                public void run() { 
                    // Create number with 10^70, squared is 10^140 which is ~2^465 bits.
                    // This will cleanly exceed the 448-bit (7 stack) ceiling.
                    AutoStackingNumber huge = AutoStackingNumber.valueOf("10000000000000000000000000000000000000000000000000000000000000000000000");
                    huge.multiply(huge);
                } 
            });
        
        System.out.println();
    }
    
    private static void testFractional() {
        System.out.println("--- Fractional Precision Tests ---");
        
        AutoStackingNumber oneThird = AutoStackingNumber.valueOf("1").divide(AutoStackingNumber.valueOf("3"));
        double oneThirdDouble = oneThird.doubleValue();
        assertTrue("1/3 approximate", Math.abs(oneThirdDouble - 0.3333333333333333) < 0.0000001);
        
        AutoStackingNumber precise = AutoStackingNumber.valueOf("0.123456789012345678");
        String str = precise.toPlainString();
        assertTrue("Fractional preservation", str.startsWith("0.12345678901234567"));
        
        AutoStackingNumber threeStack = new AutoStackingNumber(3);
        threeStack = threeStack.promote(5);
        assertTrue("Promotion increases stacks", threeStack.getStacks() >= 5);
        
        AutoStackingNumber demoted = threeStack.demote(3);
        assertTrue("Demotion works", demoted.getStacks() == 3);
        
        System.out.println();
    }
    
    private static void testEdgeCases() {
        System.out.println("--- Edge Cases Tests ---");
        
        AutoStackingNumber zero = AutoStackingNumber.valueOf("0");
        assertEqual("Zero addition", new AutoStackingNumber(5L), zero.add(new AutoStackingNumber(5L)));
        assertEqual("Zero multiplication", zero, zero.multiply(new AutoStackingNumber(5L)));
        
        AutoStackingNumber one = AutoStackingNumber.valueOf("1");
        assertEqual("One multiplication", new AutoStackingNumber(5L), one.multiply(new AutoStackingNumber(5L)));
        
        AutoStackingNumber negZero = AutoStackingNumber.fromDouble(-0.0);
        assertTrue("Negative zero becomes zero", negZero.isZero());
        
        String largeStr = "123456789012345678901234567890";
        AutoStackingNumber large = AutoStackingNumber.valueOf(largeStr);
        String back = large.toPlainString();
        assertTrue("Large number round trip", back.startsWith("12345678901234567890"));
        
        AutoStackingNumber n1 = AutoStackingNumber.valueOf("42.5");
        AutoStackingNumber n2 = AutoStackingNumber.valueOf("42.5");
        assertTrue("Hash code consistency", n1.hashCode() == n2.hashCode());
        
        assertTrue("Equals consistency", n1.equals(n2));
        assertTrue("Not equal null", !n1.equals(null));
        
        System.out.println();
    }
}
