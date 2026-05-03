package main

import (
"bufio"
"fmt"
"os"
"strconv"
"strings"
)

func isAlpha(c byte) bool {
return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
}

func isDigit(c byte) bool {
return c >= '0' && c <= '9'
}

func lexParse(text string) uint64 {
n := len(text)
i := 0
var tokens, stmts, depth, maxDepth, kindSum uint64

for i < n {
c := text[i]
if c == ' ' || c == '\t' || c == '\r' || c == '\n' {
if c == '\n' {
stmts++
}
i++
continue
}
if c == '/' && i+1 < n && text[i+1] == '/' {
i += 2
for i < n && text[i] != '\n' {
i++
}
continue
}
if c == '/' && i+1 < n && text[i+1] == '*' {
i += 2
for i+1 < n && !(text[i] == '*' && text[i+1] == '/') {
i++
}
i += 2
if i > n {
i = n
}
continue
}
if isAlpha(c) {
start := i
i++
for i < n && (isAlpha(text[i]) || isDigit(text[i])) {
i++
}
tokens++
kindSum += uint64((i - start) % 97)
continue
}
if isDigit(c) {
i++
for i < n && (isDigit(text[i]) || text[i] == '.') {
i++
}
tokens++
kindSum += 3
continue
}
if c == '"' || c == '\'' {
quote := c
i++
for i < n {
d := text[i]
if d == '\\' {
i += 2
continue
}
if d == quote {
i++
break
}
i++
}
tokens++
kindSum += 7
continue
}
if c == '(' || c == '[' || c == '{' {
depth++
if depth > maxDepth {
maxDepth = depth
}
} else if (c == ')' || c == ']' || c == '}') && depth > 0 {
depth--
}
if c == ';' {
stmts++
}
tokens++
kindSum++
i++
}
return tokens*31 + stmts*17 + depth*13 + maxDepth*7 + kindSum
}

func main() {
if len(os.Args) < 3 {
fmt.Fprintln(os.Stderr, "usage: lexer_parser_bench <file-list> <iterations>")
os.Exit(2)
}
fileList := os.Args[1]
iterations, err := strconv.Atoi(os.Args[2])
if err != nil {
panic(err)
}

file, err := os.Open(fileList)
if err != nil {
panic(err)
}
defer file.Close()

var paths []string
scanner := bufio.NewScanner(file)
for scanner.Scan() {
line := strings.TrimSpace(scanner.Text())
if line != "" {
paths = append(paths, line)
}
}
if err := scanner.Err(); err != nil {
panic(err)
}

var digest uint64 = 1469598103934665603
for i := 0; i < iterations; i++ {
for _, p := range paths {
b, err := os.ReadFile(p)
if err != nil {
panic(err)
}
digest = digest*1315423911 + lexParse(string(b))
}
}

fmt.Printf("DIGEST:%d\n", digest)
}
