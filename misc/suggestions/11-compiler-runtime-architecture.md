# Compiler/Runtime Architecture Suggestions

## IR Layer
- Introduce a stable intermediate representation to decouple parser and interpreter.

## Incremental Compilation
- Re-parse/re-check only changed files for faster iteration.

## Type System Roadmap
- Gradual enrichment with inference improvements and stricter checks.

## Runtime Plug-in Points
- Extension APIs for host embedding and custom built-ins.

## Internal Observability
- Trace hooks and structured internal metrics for debugging performance.

## Compatibility Harness
- Formalize backward-compat tests across language versions.
