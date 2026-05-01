# Cross-language benchmark suite

This benchmark compares:

- Java
- C
- C++
- Rust
- Python

The Coderive benchmark now lives in `src/main/cod/demo/src/main/test/loop/BMark.cod`.

## Workload

Each implementation runs the same deterministic kernels:

1. Sum of squares from `1..2,000,000`
2. Iterative `fib(35)` repeated `2,000` times
3. Naive prime counting in `2..5,000`

Each program prints a single line:

`CHECKSUM:<value>`

Expected checksum:

`2666668685121930669`

## Run

From repository root:

```bash
bash benchmarks/run_cross_language_benchmark.sh
```

Optional runs per language (median-based):

```bash
bash benchmarks/run_cross_language_benchmark.sh 5
```

The runner:

- compiles required binaries
- runs each language multiple times
- verifies checksum consistency
- prints median time in milliseconds
- skips languages missing toolchains

## Lexer/Parser throughput benchmark

See:

`benchmarks/lexer_parser/README.md`
