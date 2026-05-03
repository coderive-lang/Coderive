package benchmarks.lexer_parser.java;

import cod.ast.node.Program;
import cod.lexer.MainLexer;
import cod.lexer.Token;
import cod.parser.MainParser;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class CoderiveLexerParserBenchmark {
    private static long mix(long digest, long value) {
        return digest * 1315423911L + value;
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
            System.err.println("usage: CoderiveLexerParserBenchmark <file-list> <iterations>");
            System.exit(2);
        }

        String fileList = args[0];
        int iterations = Integer.parseInt(args[1]);
        List<String> paths = readPaths(fileList);

        long digest = 1469598103934665603L;
        for (int i = 0; i < iterations; i++) {
            for (String p : paths) {
                String source = new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
                MainLexer lexer = new MainLexer(source);
                List<Token> tokens = lexer.tokenize();
                MainParser parser = new MainParser(tokens);
                Program program = parser.parseProgram();

                long value = tokens.size();
                if (program != null && program.unit != null) {
                    value += (program.unit.types != null ? program.unit.types.size() : 0) * 17L;
                    value += (program.unit.policies != null ? program.unit.policies.size() : 0) * 29L;
                }
                digest = mix(digest, value);
            }
        }

        System.out.println("DIGEST:" + Long.toUnsignedString(digest));
    }
}
