#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BENCH_DIR="$ROOT_DIR/benchmarks/lexer_parser"
WORK_DIR="/tmp/coderive-lexer-parser-bench"
RUNS="${1:-3}"
ITERATIONS="${2:-20}"

mkdir -p "$WORK_DIR"

has_cmd() {
  command -v "$1" >/dev/null 2>&1
}

extract_digest() {
  local output="$1"
  local line
  line="$(printf '%s\n' "$output" | grep '^DIGEST:' | tail -n 1 || true)"
  if [[ -z "$line" ]]; then
    return 1
  fi
  printf '%s' "${line#DIGEST:}"
}

median_ms() {
  local values=("$@")
  local sorted
  sorted="$(printf '%s\n' "${values[@]}" | sort -n)"
  local count
  count="$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')"
  local index=$(( (count + 1) / 2 ))
  printf '%s\n' "$sorted" | sed -n "${index}p"
}

run_many() {
  local lang="$1"
  shift
  local -a command=("$@")
  local -a times=()
  local baseline_digest=""

  local i
  for ((i=1; i<=RUNS; i++)); do
    local start_ns end_ns elapsed_ns elapsed_ms output digest
    start_ns="$(date +%s%N)"
    output="$("${command[@]}")"
    end_ns="$(date +%s%N)"
    elapsed_ns=$((end_ns - start_ns))
    elapsed_ms=$((elapsed_ns / 1000000))
    digest="$(extract_digest "$output")" || {
      echo "[${lang}] ERROR: missing DIGEST output"
      echo "$output"
      return 1
    }
    if [[ -z "$baseline_digest" ]]; then
      baseline_digest="$digest"
    elif [[ "$digest" != "$baseline_digest" ]]; then
      echo "[${lang}] ERROR: non-deterministic digest (got ${digest}, expected ${baseline_digest})"
      return 1
    fi
    times+=("$elapsed_ms")
  done

  local median
  median="$(median_ms "${times[@]}")"
  echo "${lang}|${median}"
}

total_bytes() {
  awk '{sum += $1} END {print sum}'
}

echo "Lexer/parser throughput benchmark (runs: ${RUNS}, iterations per run: ${ITERATIONS})"

{
  find "$ROOT_DIR/benchmarks/coderive" -type f -name '*.cod'
} | sort > "$WORK_DIR/files.txt"
if [[ ! -s "$WORK_DIR/files.txt" ]]; then
  echo "No .cod files found under src/main/cod"
  exit 1
fi

BYTES_PER_ITER="$(xargs -d '\n' wc -c < "$WORK_DIR/files.txt" | total_bytes)"
if [[ -z "$BYTES_PER_ITER" || "$BYTES_PER_ITER" -le 0 ]]; then
  echo "Failed to compute corpus size"
  exit 1
fi
TOTAL_BYTES=$((BYTES_PER_ITER * ITERATIONS))

rm -rf "$WORK_DIR/coderive-java" "$WORK_DIR/java-bin" "$WORK_DIR/go-bin" "$WORK_DIR/kotlin"
mkdir -p "$WORK_DIR/coderive-java" "$WORK_DIR/java-bin" "$WORK_DIR/kotlin"

javac -d "$WORK_DIR/coderive-java" $(find "$ROOT_DIR/src/main/java" -name '*.java') \
  "$BENCH_DIR/java/CoderiveLexerParserBenchmark.java"

if has_cmd javac; then
  javac -d "$WORK_DIR/java-bin" "$BENCH_DIR/java/JavaLexerParserBenchmark.java"
fi

if has_cmd go; then
  go build -o "$WORK_DIR/go-bin" "$BENCH_DIR/go/lexer_parser_bench.go"
fi

if has_cmd kotlinc; then
  kotlinc "$BENCH_DIR/kotlin/LexerParserBench.kt" -include-runtime -d "$WORK_DIR/kotlin/bench.jar"
fi

echo
printf "%-10s | %-9s | %s\n" "Language" "Median ms" "Throughput MB/s"
echo "-----------|-----------|----------------"

print_row() {
  local lang="$1"
  local median="$2"
  local throughput
  throughput="$(awk -v bytes="$TOTAL_BYTES" -v ms="$median" 'BEGIN { if (ms <= 0) print "inf"; else printf "%.2f", (bytes / 1000000.0) / (ms / 1000.0) }')"
  printf "%-10s | %-9s | %s\n" "$lang" "$median" "$throughput"
}

row="$(run_many "Coderive" java -cp "$WORK_DIR/coderive-java" benchmarks.lexer_parser.java.CoderiveLexerParserBenchmark "$WORK_DIR/files.txt" "$ITERATIONS")"
print_row "$(echo "$row" | cut -d'|' -f1)" "$(echo "$row" | cut -d'|' -f2)"

if [[ -f "$WORK_DIR/java-bin/benchmarks/lexer_parser/java/JavaLexerParserBenchmark.class" ]]; then
  row="$(run_many "Java" java -cp "$WORK_DIR/java-bin" benchmarks.lexer_parser.java.JavaLexerParserBenchmark "$WORK_DIR/files.txt" "$ITERATIONS")"
  print_row "$(echo "$row" | cut -d'|' -f1)" "$(echo "$row" | cut -d'|' -f2)"
fi

if [[ -x "$WORK_DIR/go-bin" ]]; then
  row="$(run_many "Go" "$WORK_DIR/go-bin" "$WORK_DIR/files.txt" "$ITERATIONS")"
  print_row "$(echo "$row" | cut -d'|' -f1)" "$(echo "$row" | cut -d'|' -f2)"
fi

if [[ -f "$WORK_DIR/kotlin/bench.jar" ]]; then
  row="$(run_many "Kotlin" java -jar "$WORK_DIR/kotlin/bench.jar" "$WORK_DIR/files.txt" "$ITERATIONS")"
  print_row "$(echo "$row" | cut -d'|' -f1)" "$(echo "$row" | cut -d'|' -f2)"
fi

if has_cmd python3; then
  row="$(run_many "Python" python3 "$BENCH_DIR/python/lexer_parser_bench.py" "$WORK_DIR/files.txt" "$ITERATIONS")"
  print_row "$(echo "$row" | cut -d'|' -f1)" "$(echo "$row" | cut -d'|' -f2)"
fi

if has_cmd lua; then
  row="$(run_many "Lua" lua "$BENCH_DIR/lua/lexer_parser_bench.lua" "$WORK_DIR/files.txt" "$ITERATIONS")"
  print_row "$(echo "$row" | cut -d'|' -f1)" "$(echo "$row" | cut -d'|' -f2)"
elif has_cmd lua5.4; then
  row="$(run_many "Lua" lua5.4 "$BENCH_DIR/lua/lexer_parser_bench.lua" "$WORK_DIR/files.txt" "$ITERATIONS")"
  print_row "$(echo "$row" | cut -d'|' -f1)" "$(echo "$row" | cut -d'|' -f2)"
else
  echo "[skip] Lua benchmark: lua interpreter not found"
fi
