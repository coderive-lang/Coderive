# Testing and Quality Improvements

## Test Pyramid
- Expand unit tests for lexer/parser/interpreter boundaries.
- Add more integration tests for end-to-end language behavior.

## Golden Tests
- Snapshot expected parse trees and runtime outputs.
- Version snapshots with intentional updates.

## Regression Suite
- Track every fixed bug with a dedicated test.
- Group by subsystem for easier maintenance.

## Property-Based Testing
- Randomized input generation for parser robustness.
- Invariant checks for lazy array behavior.

## Fuzzing
- Lexer/parser fuzzing to catch crashers and edge cases.

## CI Matrix
- Validate against multiple Java versions and OS targets.

## Coverage Reporting
- Track statement/branch coverage trends over time.
- Set practical minimum thresholds for critical modules.
