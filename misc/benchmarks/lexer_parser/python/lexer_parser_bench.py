#!/usr/bin/env python3
import sys


def is_alpha(c: str) -> bool:
    return c == '_' or ('a' <= c <= 'z') or ('A' <= c <= 'Z')


def is_digit(c: str) -> bool:
    return '0' <= c <= '9'


def lex_parse(text: str) -> int:
    i = 0
    n = len(text)
    tokens = 0
    stmts = 0
    depth = 0
    max_depth = 0
    kind_sum = 0

    while i < n:
        c = text[i]

        if c.isspace():
            if c == '\n':
                stmts += 1
            i += 1
            continue

        if c == '/' and i + 1 < n and text[i + 1] == '/':
            i += 2
            while i < n and text[i] != '\n':
                i += 1
            continue

        if c == '/' and i + 1 < n and text[i + 1] == '*':
            i += 2
            while i + 1 < n and not (text[i] == '*' and text[i + 1] == '/'):
                i += 1
            i = min(i + 2, n)
            continue

        if is_alpha(c):
            start = i
            i += 1
            while i < n and (is_alpha(text[i]) or is_digit(text[i])):
                i += 1
            tokens += 1
            kind_sum += (i - start) % 97
            continue

        if is_digit(c):
            i += 1
            while i < n and (is_digit(text[i]) or text[i] == '.'):
                i += 1
            tokens += 1
            kind_sum += 3
            continue

        if c == '"' or c == "'":
            quote = c
            i += 1
            while i < n:
                d = text[i]
                if d == '\\':
                    i += 2
                    continue
                if d == quote:
                    i += 1
                    break
                i += 1
            tokens += 1
            kind_sum += 7
            continue

        if c in '([{':
            depth += 1
            if depth > max_depth:
                max_depth = depth
        elif c in ')]}' and depth > 0:
            depth -= 1

        if c == ';':
            stmts += 1

        tokens += 1
        kind_sum += 1
        i += 1

    return tokens * 31 + stmts * 17 + depth * 13 + max_depth * 7 + kind_sum


def main() -> None:
    if len(sys.argv) < 3:
        print('usage: lexer_parser_bench.py <file-list> <iterations>', file=sys.stderr)
        raise SystemExit(2)

    file_list = sys.argv[1]
    iterations = int(sys.argv[2])

    with open(file_list, 'r', encoding='utf-8') as f:
        paths = [line.strip() for line in f if line.strip()]

    digest = 1469598103934665603
    mask = (1 << 64) - 1

    for _ in range(iterations):
        for p in paths:
            with open(p, 'r', encoding='utf-8') as f:
                text = f.read()
            digest = ((digest * 1315423911) + lex_parse(text)) & mask

    print(f'DIGEST:{digest}')


if __name__ == '__main__':
    main()
