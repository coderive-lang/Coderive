fn fib(n: i64) -> i128 {
    if n == 0 {
        return 0;
    }
    let mut a: i128 = 0;
    let mut b: i128 = 1;
    for _ in 1..=n {
        let next = a + b;
        a = b;
        b = next;
    }
    a
}

fn sum_squares(limit: i64) -> i128 {
    let mut total: i128 = 0;
    for i in 1..=limit {
        let v = i as i128;
        total += v * v;
    }
    total
}

fn prime_count(limit: i64) -> i128 {
    let mut count: i128 = 0;
    for candidate in 2..=limit {
        let mut is_prime = true;
        if candidate > 2 {
            for divisor in 2..candidate {
                if candidate % divisor == 0 {
                    is_prime = false;
                    break;
                }
            }
        }
        if is_prime {
            count += 1;
        }
    }
    count
}

fn main() {
    let sum_part = sum_squares(2_000_000);
    let fib_part = fib(35) * 2_000;
    let prime_part = prime_count(5_000);
    let checksum = sum_part + fib_part + prime_part;
    println!("CHECKSUM:{}", checksum);
}
