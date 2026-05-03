#include <stdint.h>
#include <stdio.h>

static int64_t fib(int n) {
    if (n == 0) return 0;
    int64_t a = 0;
    int64_t b = 1;
    for (int i = 1; i <= n; i++) {
        int64_t next = a + b;
        a = b;
        b = next;
    }
    return a;
}

static int64_t sum_squares(int limit) {
    int64_t total = 0;
    for (int i = 1; i <= limit; i++) {
        total += (int64_t)i * (int64_t)i;
    }
    return total;
}

static int64_t prime_count(int limit) {
    int64_t count = 0;
    for (int candidate = 2; candidate <= limit; candidate++) {
        int is_prime = 1;
        if (candidate > 2) {
            for (int divisor = 2; divisor < candidate; divisor++) {
                if (candidate % divisor == 0) {
                    is_prime = 0;
                    break;
                }
            }
        }
        if (is_prime) {
            count++;
        }
    }
    return count;
}

int main(void) {
    int64_t sum_part = sum_squares(2000000);
    int64_t fib_part = fib(35) * 2000;
    int64_t prime_part = prime_count(5000);
    int64_t checksum = sum_part + fib_part + prime_part;
    printf("CHECKSUM:%lld\n", (long long)checksum);
    return 0;
}
