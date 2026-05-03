#include <cstdint>
#include <iostream>

static std::int64_t fib(int n) {
    if (n == 0) return 0;
    std::int64_t a = 0;
    std::int64_t b = 1;
    for (int i = 1; i <= n; ++i) {
        std::int64_t next = a + b;
        a = b;
        b = next;
    }
    return a;
}

static std::int64_t sumSquares(int limit) {
    std::int64_t total = 0;
    for (int i = 1; i <= limit; ++i) {
        total += static_cast<std::int64_t>(i) * static_cast<std::int64_t>(i);
    }
    return total;
}

static std::int64_t primeCount(int limit) {
    std::int64_t count = 0;
    for (int candidate = 2; candidate <= limit; ++candidate) {
        bool isPrime = true;
        if (candidate > 2) {
            for (int divisor = 2; divisor < candidate; ++divisor) {
                if (candidate % divisor == 0) {
                    isPrime = false;
                    break;
                }
            }
        }
        if (isPrime) {
            ++count;
        }
    }
    return count;
}

int main() {
    std::int64_t sumPart = sumSquares(2000000);
    std::int64_t fibPart = fib(35) * 2000;
    std::int64_t primePart = primeCount(5000);
    std::int64_t checksum = sumPart + fibPart + primePart;
    std::cout << "CHECKSUM:" << checksum << '\n';
    return 0;
}
