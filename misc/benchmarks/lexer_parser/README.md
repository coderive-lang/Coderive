# Lexer/Parser Throughput Benchmark

This suite benchmarks lexer/parser throughput for:

- Coderive (real `MainLexer` + `MainParser`)
- Java
- Go
- Kotlin
- Python
- Lua

## Run

From repository root:

```bash
bash benchmarks/lexer_parser/run_lexer_parser_benchmark.sh
```

Optional:

```bash
bash benchmarks/lexer_parser/run_lexer_parser_benchmark.sh <runs> <iterations>
```

- `runs`: median sample count (default `3`)
- `iterations`: full corpus passes per run (default `20`)

Output columns:

- **Median ms**: median wall time per language
- **Throughput MB/s**: processed corpus bytes per second
