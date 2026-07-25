<!-- markdownlint-disable first-line-h1 -->
<!-- markdownlint-disable html -->
<!-- markdownlint-disable no-duplicate-header -->

<div align="center">

<p>⚠️ Pre-v1.0 — API may change</p>


  <img src="https://raw.githubusercontent.com/coderive-lang/Coderive/main/docs/assets/coderive-logo.png" alt="Coderive Logo" width="80%" />
  <h3>Safe. Fast. Clear.</h3>
  <p><em>A modern general-purpose programming language built for expressive, safe code.</em></p>
</div>

<div align="center">

[![Version](https://img.shields.io/badge/version-0.9.6-536af5?style=flat-square&logo=github)](https://github.com/coderive-lang/Coderive/releases)
[![Java](https://img.shields.io/badge/requires-Java%207%2B-ed8b00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-f5de53?style=flat-square)](LICENSE)
[![Stars](https://img.shields.io/github/stars/coderive-lang/Coderive?style=flat-square&color=7289da&logo=github)](https://github.com/coderive-lang/Coderive/stargazers)
[![Discussions](https://img.shields.io/badge/discussions-join-ffc107?style=flat-square&logo=github)](https://github.com/coderive-lang/Coderive/discussions)
[![Code of Conduct](https://img.shields.io/badge/Contributor%20Covenant-3.0-4baaaa?style=flat-square)](.github/CODE_OF_CONDUCT.md)

</div>

---

## Table of Contents

- [What is Coderive?](#what-is-coderive)
- [Why Coderive?](#why-coderive)
- [Getting Started](#getting-started)
- [Language Features](#language-features)
- [Latest in src](#latest-in-src)
- [Validation Snapshot](#validation-snapshot)
- [Demo Programs](#demo-programs)
- [Web Playground](#web-playground)
- [License](#license)
- [Contact](#contact)
- [Code of Conduct](#code-of-conduct)

---

## What is Coderive?

**Coderive** is a modern programming language designed around three principles:

| Principle | What it means |
|-----------|--------------|
| **Safe** | Quantifier-first logic eliminates operator-precedence bugs; typed slots make multi-return values explicit |
| **Fast** | O(1) lazy arrays and formula-optimized loops handle data at any scale without materialising elements |
| **Clear** | Human-readable syntax — `all[...]`, `any[...]`, `~>`, `::` — reads like intent, not symbols |

Coderive features a fully hand-written, modular lexer-parser pipeline (no ANTLR), a recursive-descent parser with backtracking, and an O(1) range system that can represent a quintillion-element array in a few microseconds.

> **Built on mobile.** Coderive was developed entirely on a phone — Java NIDE, Quickedit, Termux, and AI assistants (DeepSeek, Gemini). Serious language design knows no hardware limits.

---

## Why Coderive?

### ① Quantifier-First Logic

No more `&&` / `||` precedence headaches. Logic reads exactly like its intent.

```python
# Traditional — what does this evaluate first?
if a && b || c && d { ... }

# Coderive — crystal clear
if any[all[a, b], all[c, d]] { ... }
```

Use `all[]` for AND, `any[]` for OR, nest them freely:

```python
if all[score >= 60, attempts <= maxAttempts] {
    out("Passed!")
}

if role == any["admin", "moderator", "owner"] {
    out("Access granted for {role}")
}
```

---

### ② Multi-Return Slot System

Functions declare named return slots with `::` and assign them with `~>(slot-returns)`. Call sites destructure them cleanly.

```python
local divide(a: int, b: int)
:: quotient: float, remainder: int, status: text {
    if b == 0 {
        ~> (0.0, 0, "division by zero")
    }
    ~> (a / b, a % b, "ok")
}

# Destructure only what you need
q, r, s := [quotient, remainder, status]:divide(17, 5)
out("{17} ÷ 5 = {q}  remainder {r}  ({s})")
```

---

### ③ Smart For-Loops

Every step pattern you'll ever need, built into the language:

```python
for i of 1 to 10          { ... }  # count up
for i of 10 to 1          { ... }  # auto-reverse
for i of 1 to 100 by 2    { ... }  # additive step
for i of 1 to 64  by *2   { ... }  # multiplicative (powers of 2)
for i of 64 to 1  by /2   { ... }  # divisive (halving)
for i of 1..32#*2          { ... }  # inline formula shorthand
```

---

### ④ O(1) Lazy Arrays

Create a quintillion-element array instantly — no memory, no iteration:

```python
huge : [text] = [0 to 1Qi]   // 1 Quintillion elements, created in < 1 ms

// Loop body detected as a conditional formula — still O(1)
for i of huge {
    if i % 2 == 0 { huge[i] = "even" }
    else          { huge[i] = "odd"  }
}

out(huge[24000])  // → "even"
out(huge[24001])  // → "odd"
```

Range indexing, negative indices, and lexicographic ranges all work out of the box:

```python
slice    := nums[-5 to -1]           // last 5 elements
alphabet := ["a" to "z"]            // 26-element letter range
letters  := ["aa" to "zz"]          // 676-element 2-letter range
```

---

## Getting Started

### Requirements

- **Java 7 or later** ([Download Adoptium JDK](https://adoptium.net/))
- Linux, macOS, or Windows (WSL/Git Bash for the installer)

---

### Option 1 — One-command Install (recommended)

Clone the repo and run the installer. It will verify Java, install the runtime, and register the `coderive` command:

```bash
git clone https://github.com/coderive-lang/Coderive.git
cd Coderive
chmod +x install.sh && ./install.sh
```

After install, add `~/.local/bin` to your PATH if prompted:

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

Then run your first program:

```bash
coderive hello.cod
```

---

### Option 2 — Direct jar execution

If you prefer not to use the installer, run programs directly with Java:

```bash
java -cp docs/assets/Coderive.jar cod.runner.CommandRunner hello.cod
```

You can alias this for convenience:

```bash
alias coderive='java -cp /path/to/Coderive.jar cod.runner.CommandRunner'
```

---

### Your first program

Create `hello.cod`:

```python
name := in(text, "What's your name? ")
out("Hello, {name}! 👋")
out("Welcome to Coderive.")
```

Run it:

```bash
coderive hello.cod
```

```
What's your name? Ada
Hello, Ada! 👋
Welcome to Coderive.
```

---

## Language Features

### Variables & Types

```python
# Type-inferred declaration
x := 42
name := "Coderive"
ratio := 3.14

# Explicit typed declaration
count: int = 0
label: text = "hello"

# Union types
value: int|float = 9.5

# Type aliases
NUMBER: type = int
num: NUMBER = 4
```

### Input & Output

```python
out("Hello!")                           # print with newline
out("A", "B", "C")                      # each arg on its own line
outs("Loading") outs(".") outs(".")     # print without newline

name    := in(text, "Enter name: ")     # prompt + read text
age     := in(int,  "Enter age: ")      # prompt + read int
height  := in(float)                    # read float (no prompt)
```

### Control Flow

```python
if all[age >= 18, hasId] {
    out("Welcome!")
} else if age >= 16 {
    out("Almost there.")
} else {
    out("Sorry, too young.")
}
```

### Functions & Slots

```python
# Single-expression shorthand
local square(n: int) ~> (n * n)

# Multi-return slots
local stats(a: int, b: int, c: int)
:: total: int, average: float {
    sum := a + b + c
    ~> (sum, sum / 3.0)
}

# Call and destructure
t, avg := [total, average]:stats(10, 20, 30)
out("Total: {t}, Average: {avg}")
```

### Default Parameters & Skipping

```python
local greet(name: text, greeting: text = "Hello") :: msg: text {
    ~> (msg: "{greeting}, {name}!")
}

result := greet("Alice", _)   # skip optional param — uses default
```

### Classes & Policies

```python
policy Printable {
    print() :: output: text
}

share Document with Printable {
    title: text = "Untitled"

    policy print() :: output: text {
        ~> (output: "Document: {this.title}")
    }

    share main() {
        doc := Document()
        msg := doc.print()
        out(msg)
    }
}
```

### Multi-line Strings

```python
report := |"
    Name:  Alice
    Score: 98
    Grade: A+
    "|
out(report)
```

### Numeric Shorthands

| Suffix | Value |
|--------|-------|
| `1K`   | 1,000 |
| `1M`   | 1,000,000 |
| `1B`   | 1,000,000,000 |
| `1T`   | 1,000,000,000,000 |
| `1Q`   | 1,000,000,000,000,000 |
| `1Qi`  | 1,000,000,000,000,000,000 |
| `1e6`  | 1,000,000 (scientific) |

---

## Latest in src

Recent `src/**/*.cod` programs and std modules now showcase:

- **Unsafe pointers and fixed buffers** (`*<unsafe-type>`, `<unsafe-type>[n]`, `&`, `*`, pointer arithmetic) in `test/unsafe/*`.
- **Static imports** for methods/fields in `test/staticimports*`.
- **Lambda auto-currying** forms like `\(a) \(b) ...` plus classic tuple lambdas in `test/lambda/*`.
- **Union + runtime type checks** via `int|text` and `is` in `test/typesliterals/*`.
- **Range and formula optimizations** across lazy arrays, multi-array dependencies, and recurrence tests.
- **Expanded standard library coverage** in `std/math`, `std/scimath`, and `std/json` with comprehensive demo suites.

---

## Validation Snapshot

Current demo validation status from `src/cod/demo/src/main/test`:

- `CodP-TAC parity runner` (`cod.runner.CodPTACParityRunner`, excluding `*Invalid*.cod`): **56/56 passed**.
- Direct `CommandRunner` sweep for non-invalid demos: **49/49 passed** (with required stdin fixtures for input-driven demos such as `Interactive.cod`, `IO.cod`, and `Parity.cod`).
- `JsonStandardLibraryComprehensive.cod` now runs successfully in direct `CommandRunner` mode with full text/object/unicode/invalid-case assertions enabled.

Checked invalid demos (`*Invalid*.cod`) confirm currently unsupported/invalid patterns:

- Reassigning constants after declaration.
- Declaring constants without initializer.
- Malformed self-call forms (`<~` without call target/parentheses, or invalid level-call syntax in methods).

---

## Demo Programs

Runnable demos live in [`src/cod/demo/src/main/test/`](src/cod/demo/src/main/test/):

| Category | What it shows |
|----------|--------------|
| [`helloworld/`](src/cod/demo/src/main/test/helloworld/) | String interpolation, modules, static entry |
| [`controlflow/`](src/cod/demo/src/main/test/controlflow/) | `if`/`elif`/`else`, `skip`, `break`, `fin` |
| [`loop/`](src/cod/demo/src/main/test/loop/) | Loop patterns, benchmark (`BMark.cod`) |
| [`lambda/`](src/cod/demo/src/main/test/lambda/) | Lambdas, auto-currying, self-call |
| [`lazyloop/`](src/cod/demo/src/main/test/lazyloop/) | O(1) lazy arrays, formula-optimized loops |
| [`array/`](src/cod/demo/src/main/test/array/) | Array operations |
| [`unsafe/`](src/cod/demo/src/main/test/unsafe/) | Unsafe pointers, borrow checker |
| [`json/`](src/cod/demo/src/main/test/json/) | JSON standard library |

Or try everything instantly in the **[Web Playground](https://coderive-lang.github.io/Coderive)** — no install required.

---

## Web Playground

Try Coderive directly in your browser — no install required:

**[→ Launch the Playground](https://coderive-lang.github.io/Coderive)**

The playground gives you a full interactive editor with instant output, making it the fastest way to experiment with the language.

---

## License

This project is licensed under the [MIT License](LICENSE).

---

## Contact

- 💬 [GitHub Discussions](https://github.com/coderive-lang/Coderive/discussions) — ask questions, share ideas
- 🐛 [GitHub Issues](https://github.com/coderive-lang/Coderive/issues) — report bugs
- 🗣️ [Discord](https://discord.gg/5Ne3wrXCX) — chat and community
- 📧 coderive.lang@gmail.com — code of conduct reports

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](.github/CODE_OF_CONDUCT.md).
All participants must abide by its terms. Report violations to
[coderive.lang@gmail.com](mailto:coderive.lang@gmail.com) or via Discord.

### Contributing

All participants should read the [Contributing Guide](.github/CONTRIBUTING.md) to understand how to contribute to this project.

---

<div align="center">
  <em>Built with passion on mobile — proving that innovation knows no hardware boundaries.</em>
</div>
