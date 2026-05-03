public final class CrossLanguageBenchmark {
    private CrossLanguageBenchmark() {}

    private static long fib(int n) {
        if (n == 0) return 0L;
        long a = 0L;
        long b = 1L;
        for (int i = 1; i <= n; i++) {
            long next = a + b;
            a = b;
            b = next;
        }
        return a;
    }

    private static long sumSquares(int limit) {
        long total = 0L;
        for (int i = 1; i <= limit; i++) {
            total += (long) i * (long) i;
        }
        return total;
    }

    private static long primeCount(int limit) {
        long count = 0L;
        for (int candidate = 2; candidate <= limit; candidate++) {
            boolean isPrime = true;
            if (candidate > 2) {
                for (int divisor = 2; divisor < candidate; divisor++) {
                    if (candidate % divisor == 0) {
                        isPrime = false;
                        break;
                    }
                }
            }
            if (isPrime) count++;
        }
        return count;
    }

    public static void main(String[] args) {
        long sumPart = sumSquares(2_000_000);
        long fibPart = fib(35) * 2_000L;
        long primePart = primeCount(5_000);
        long checksum = sumPart + fibPart + primePart;
        System.out.println("CHECKSUM:" + checksum);
    }
}
