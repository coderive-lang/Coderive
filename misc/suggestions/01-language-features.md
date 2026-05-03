# Language Features to Consider

## Pattern Matching
- Introduce `match` with exhaustive checks.
- Support literals, ranges, tuple patterns, and wildcard arms.
- Add guards for conditional arms.

## Algebraic Data Types
- Add sum/variant types for safer modeling.
- Enable tagged unions with compiler checks.

## Option/Result Types
- Native `Option<T>` and `Result<T, E>` semantics.
- Ergonomic unwrapping and propagation operators.

## Generics
- Parameterized functions and classes.
- Generic constraints for safer APIs.

## Async Model
- Native async function support.
- Lightweight task scheduling and await semantics.

## Immutable-by-default Mode
- Optional strict mode where variables are immutable unless explicit.

## Modules/Namespaces
- Improve imports and visibility rules.
- Better namespacing to avoid symbol collisions.

## Traits/Interfaces Expansion
- Richer policy/trait composition.
- Default method bodies and conflict resolution rules.

## Safer Numeric Modes
- Explicit overflow handling modes.
- Decimal/big-number support for finance and science workloads.
