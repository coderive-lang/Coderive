# Diagnostics and Error Experience

## Better Parse Errors
- Point to exact token spans and expected alternatives.
- Show nearest-valid examples for common mistakes.

## Runtime Tracebacks
- Cleaner stack traces with source snippets.
- Distinguish user-code frames from runtime internals.

## Actionable Hints
- Suggest likely fixes for type mismatch and name errors.
- Include “did you mean” suggestions.

## Error Codes
- Stable, documented diagnostic codes for searchable help.

## Warning System
- Unused variables, dead code, unreachable branches.
- Optional strict mode elevating warnings to errors.

## LSP-Compatible Diagnostics
- Standard diagnostics payload for IDE integrations.
