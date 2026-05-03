import java.io.File

private fun isAlpha(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z'
private fun isDigit(c: Char): Boolean = c in '0'..'9'

private fun lexParse(text: String): Long {
    var i = 0
    val n = text.length
    var tokens = 0L
    var stmts = 0L
    var depth = 0L
    var maxDepth = 0L
    var kindSum = 0L

    while (i < n) {
        val c = text[i]

        if (c.isWhitespace()) {
            if (c == '\n') stmts++
            i++
            continue
        }

        if (c == '/' && i + 1 < n && text[i + 1] == '/') {
            i += 2
            while (i < n && text[i] != '\n') i++
            continue
        }

        if (c == '/' && i + 1 < n && text[i + 1] == '*') {
            i += 2
            while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
            i = minOf(i + 2, n)
            continue
        }

        if (isAlpha(c)) {
            val start = i
            i++
            while (i < n && (isAlpha(text[i]) || isDigit(text[i]))) i++
            tokens++
            kindSum += (i - start) % 97
            continue
        }

        if (isDigit(c)) {
            i++
            while (i < n && (isDigit(text[i]) || text[i] == '.')) i++
            tokens++
            kindSum += 3
            continue
        }

        if (c == '"' || c == '\'') {
            val quote = c
            i++
            while (i < n) {
                val d = text[i]
                if (d == '\\') {
                    i += 2
                    continue
                }
                if (d == quote) {
                    i++
                    break
                }
                i++
            }
            tokens++
            kindSum += 7
            continue
        }

        if (c == '(' || c == '[' || c == '{') {
            depth++
            if (depth > maxDepth) maxDepth = depth
        } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
            depth--
        }
        if (c == ';') stmts++

        tokens++
        kindSum++
        i++
    }

    return tokens * 31 + stmts * 17 + depth * 13 + maxDepth * 7 + kindSum
}

fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: LexerParserBench <file-list> <iterations>" }

    val fileList = File(args[0])
    val iterations = args[1].toInt()
    val paths = fileList.readLines().map { it.trim() }.filter { it.isNotEmpty() }

    var digest = 1469598103934665603L
    repeat(iterations) {
        for (p in paths) {
            val text = File(p).readText()
            digest = digest * 1315423911L + lexParse(text)
        }
    }

    println("DIGEST:${java.lang.Long.toUnsignedString(digest)}")
}
