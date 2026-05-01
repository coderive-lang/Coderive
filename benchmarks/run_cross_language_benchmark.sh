#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCH_DIR="$ROOT_DIR/benchmarks"
WORK_DIR="/tmp/coderive-cross-bench"
RUNS="${1:-3}"
# Deterministic checksum combining: sum of squares (1..2M), repeated fib(35) x2000,
# and prime count (2..5000). Update only when workload logic changes across implementations.
EXPECTED_CHECKSUM="2666668685121930669"

mkdir -p "$WORK_DIR"

has_cmd() {
  command -v "$1" >/dev/null 2>&1
}

extract_checksum() {
  local output="$1"
  local line
  line="$(printf '%s\n' "$output" | grep '^CHECKSUM:' | tail -n 1 || true)"
  if [[ -z "$line" ]]; then
    return 1
  fi
  printf '%s' "${line#CHECKSUM:}"
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

  local i
  for ((i=1; i<=RUNS; i++)); do
    local start_ns end_ns elapsed_ns output checksum elapsed_ms
    start_ns="$(date +%s%N)"
    output="$("${command[@]}")"
    end_ns="$(date +%s%N)"
    elapsed_ns=$((end_ns - start_ns))
    elapsed_ms=$((elapsed_ns / 1000000))
    checksum="$(extract_checksum "$output")" || {
      echo "[${lang}] ERROR: missing CHECKSUM output"
      echo "$output"
      return 1
    }
    if [[ "$checksum" != "$EXPECTED_CHECKSUM" ]]; then
      echo "[${lang}] ERROR: checksum mismatch (got ${checksum}, expected ${EXPECTED_CHECKSUM})"
      return 1
    fi
    times+=("$elapsed_ms")
  done

  local median
  median="$(median_ms "${times[@]}")"
  echo "${lang}|${median}"
}

echo "Cross-language benchmark (runs per language: ${RUNS})"
echo "Expected checksum: ${EXPECTED_CHECKSUM}"
echo

# Build Coderive runtime for benchmark execution.
echo "[setup] compiling benchmark binaries..."
rm -rf "$WORK_DIR/java-bin" "$WORK_DIR/c-bin" "$WORK_DIR/cpp-bin" "$WORK_DIR/rust-bin"
mkdir -p "$WORK_DIR/java-bin"

# Build language-specific binaries where toolchains exist.
if has_cmd javac; then
  javac -d "$WORK_DIR/java-bin" "$BENCH_DIR/java/CrossLanguageBenchmark.java"
else
  echo "[skip] Java benchmark: javac not found"
fi

if has_cmd gcc; then
  gcc -O3 -std=c11 "$BENCH_DIR/c/cross_language_benchmark.c" -o "$WORK_DIR/c-bin"
else
  echo "[skip] C benchmark: gcc not found"
fi

if has_cmd g++; then
  g++ -O3 -std=c++17 "$BENCH_DIR/cpp/cross_language_benchmark.cpp" -o "$WORK_DIR/cpp-bin"
else
  echo "[skip] C++ benchmark: g++ not found"
fi

if has_cmd rustc; then
  rustc -O "$BENCH_DIR/rust/cross_language_benchmark.rs" -o "$WORK_DIR/rust-bin"
else
  echo "[skip] Rust benchmark: rustc not found"
fi

echo
echo "Language | Median ms"
echo "---------|----------"

if [[ -f "$WORK_DIR/java-bin/CrossLanguageBenchmark.class" ]]; then
  run_many "Java" java -cp "$WORK_DIR/java-bin" CrossLanguageBenchmark | awk -F'|' '{printf "%-8s | %s\n", $1, $2}'
fi

if [[ -x "$WORK_DIR/c-bin" ]]; then
  run_many "C" "$WORK_DIR/c-bin" | awk -F'|' '{printf "%-8s | %s\n", $1, $2}'
fi

if [[ -x "$WORK_DIR/cpp-bin" ]]; then
  run_many "C++" "$WORK_DIR/cpp-bin" | awk -F'|' '{printf "%-8s | %s\n", $1, $2}'
fi

if [[ -x "$WORK_DIR/rust-bin" ]]; then
  run_many "Rust" "$WORK_DIR/rust-bin" | awk -F'|' '{printf "%-8s | %s\n", $1, $2}'
fi

if has_cmd python3; then
  run_many "Python" python3 "$BENCH_DIR/python/cross_language_benchmark.py" | awk -F'|' '{printf "%-8s | %s\n", $1, $2}'
else
  echo "[skip] Python benchmark: python3 not found"
fi
