def fib(n: int) -> int:
    if n == 0:
        return 0
    a = 0
    b = 1
    for _ in range(1, n + 1):
        nxt = a + b
        a = b
        b = nxt
    return a


def sum_squares(limit: int) -> int:
    total = 0
    for i in range(1, limit + 1):
        total += i * i
    return total


def prime_count(limit: int) -> int:
    count = 0
    for candidate in range(2, limit + 1):
        is_prime = True
        if candidate > 2:
            for divisor in range(2, candidate):
                if candidate % divisor == 0:
                    is_prime = False
                    break
        if is_prime:
            count += 1
    return count


def main() -> None:
    sum_part = sum_squares(2_000_000)
    fib_part = fib(35) * 2_000
    prime_part = prime_count(5_000)
    checksum = sum_part + fib_part + prime_part
    print(f"CHECKSUM:{checksum}")


if __name__ == "__main__":
    main()
