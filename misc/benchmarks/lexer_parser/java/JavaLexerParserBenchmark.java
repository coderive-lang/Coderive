package benchmarks.lexer_parser.java;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class JavaLexerParserBenchmark {
    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static long mix(long digest, long value) {
        return digest * 1315423911L + value;
    }

    private static long lexParse(String text) {
        int n = text.length();
        int i = 0;
        long tokens = 0;
        long stmts = 0;
        long depth = 0;
        long maxDepth = 0;
        long kindSum = 0;

        while (i < n) {
            char c = text.charAt(i);

            if (Character.isWhitespace(c)) {
                if (c == '\n') stmts++;
                i++;
                continue;
            }

            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && text.charAt(i) != '\n') i++;
                continue;
            }

            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
                continue;
            }

            if (isAlpha(c)) {
                int start = i;
                i++;
                while (i < n && (isAlpha(text.charAt(i)) || isDigit(text.charAt(i)))) i++;
                tokens++;
                kindSum += (i - start) % 97;
                continue;
            }

            if (isDigit(c)) {
                i++;
                while (i < n && (isDigit(text.charAt(i)) || text.charAt(i) == '.')) i++;
                tokens++;
                kindSum += 3;
                continue;
            }

            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    char d = text.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                tokens++;
                kindSum += 7;
                continue;
            }

            if (c == '(' || c == '[' || c == '{') {
                depth++;
                if (depth > maxDepth) maxDepth = depth;
            } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                depth--;
            }
            if (c == ';') stmts++;

            tokens++;
            kindSum++;
            i++;
        }

        return (((tokens * 31 + stmts * 17 + depth * 13 + maxDepth * 7) + kindSum));
    }

    private static List<String> readPaths(String fileList) throws Exception {
        List<String> out = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new FileReader(fileList));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
        } finally {
            reader.close();
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: JavaLexerParserBenchmark <file-list> <iterations>");
            System.exit(2);
        }

        String fileList = args[0];
        int iterations = Integer.parseInt(args[1]);
        List<String> paths = readPaths(fileList);
        long digest = 1469598103934665603L;

        for (int i = 0; i < iterations; i++) {
            for (String p : paths) {
                String text = new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
                digest = mix(digest, lexParse(text));
            }
        }

        System.out.println("DIGEST:" + Long.toUnsignedString(digest));
    }
}
