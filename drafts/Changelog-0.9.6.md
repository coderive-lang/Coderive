# Changelog Draft

## [v0.9.6] - Runtime & Benchmark Consolidation - 2026-05-01

### ⚙️ Lexer & Parser Architecture
- Introduced `CharClassifier` and `LexerSource` to centralize fast ASCII classification and lexer state access.
- Reworked `MainLexer` to use zero-allocation skipping for whitespace/comments and streamlined token creation.
- Updated lexer components (comment/identifier/number/string/symbol/whitespace) and parser contexts for more consistent token flow and error handling.
- Added lexer aliases so `continue` maps to `skip` and `return` maps to `exit` in `.cod` sources.

### 🧠 Interpreter & Runtime Refactor
- Split loop/array/lambda behavior into dedicated handlers (`LoopHandler`, `ArrayHandler`, `LambdaHandler`) and simplified loop decision paths.
- Added `Exit` AST node and refreshed interpreter visit logic around early exits, loop flow, and tail-call handling.
- Refined execution context, literal registry integration, and method/slot invocation flow for improved consistency.

### 📦 Range & Formula System Updates
- Added unified `AccumulationFormula` + `AccumulationPattern` to cover flat/nested sums and recurrence variants.
- Removed legacy recurrence formula classes and refreshed `NaturalArray`/pattern handling to use the new accumulation path.

### 🧩 Standard Library & Demo Coverage
- JSON standard library improvements: streamlined object access loops, expanded escape handling (`\b`, `\f`), and simplified Unicode hex formatting.
- SciMath loops updated with safer continue-style early exits in statistical helpers.
- Demo refreshes across control flow, lazy loop, recurrence, and JSON coverage.
- Added `BMark.cod` benchmark in loop tests plus a new `BigNumTest` Java test scaffold.

### 🧪 Benchmarks
- Coderive cross-language benchmark moved into `src/main/cod/demo/src/main/test/loop/BMark.cod`.
- Cross-language benchmark script now measures non-Coderive toolchains only.
