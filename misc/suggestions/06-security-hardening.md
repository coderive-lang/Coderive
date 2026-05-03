# Security Hardening Suggestions

## Dependency Hygiene
- Enable automated dependency updates and vulnerability checks.

## Sandboxed Execution Modes
- Optional restricted runtime mode for untrusted scripts.
- Controlled I/O and network permissions.

## Secure Defaults
- Defensive defaults for file/network APIs.
- Explicit opt-in for risky capabilities.

## Input Validation
- Harden parsers and runtime boundaries against malformed input.

## Security Policy
- Add SECURITY.md with disclosure workflow and response targets.

## Threat Modeling
- Document threat model for CLI, runtime, and web playground.
