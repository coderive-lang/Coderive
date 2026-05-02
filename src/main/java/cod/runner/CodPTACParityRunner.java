package cod.runner;

import cod.ast.node.Method;
import cod.ast.node.Program;
import cod.ast.node.Type;
import cod.interpreter.Index;
import cod.interpreter.Interpreter;
import cod.ir.IRManager;
import cod.ptac.Artifact;
import cod.ptac.Executor;
import cod.ptac.Options;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public final class CodPTACParityRunner extends BaseRunner {
    private static final String NUMBER_REGEX_PATTERN = "[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?";
    private static final int DEFAULT_INPUT_LINE_COUNT = 10;
    private static final String DEFAULT_INPUT = buildDefaultInput(DEFAULT_INPUT_LINE_COUNT);
    
    private final String androidPath = "/storage/emulated/0";
    private final String baseTestPath = "/JavaNIDE/Programming-Language/Coderive/app/src/main/cod/demo/src/main/test/";
    
    private static String buildDefaultInput(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public void run(String[] args) throws Exception {
        List<String> files = new ArrayList<String>();
        
        if (args != null && args.length > 0) {
            for (String arg : args) {
                if (arg != null && !arg.trim().isEmpty() && !arg.startsWith("-")) {
                    File f = new File(arg);
                    if (f.isDirectory()) {
                        files.addAll(findCodFiles(f));
                    } else if (f.exists() && arg.endsWith(".cod")) {
                        files.add(f.getAbsolutePath());
                    }
                }
            }
        }
        
        if (files.isEmpty()) {
            try (Scanner scanner = newSystemInScanner()) {
                // Check if Android default path exists
                File androidTestDir = new File(androidPath + baseTestPath);
                if (androidTestDir.exists() && androidTestDir.isDirectory()) {
                    System.out.println("Found Android test directory at: " + androidPath + baseTestPath);
                    System.out.print("Use Android tests? (y/n): ");
                    System.out.flush();
                    
                    String response = scanner.nextLine().trim().toLowerCase();
                    
                    if (response.equals("y") || response.equals("yes")) {
                        files.addAll(findCodFiles(androidTestDir));
                    } else {
                        System.out.print("Enter directory or file path: ");
                        String userPath = scanner.nextLine().trim();
                        if (!userPath.isEmpty()) {
                            File userFile = new File(userPath);
                            if (userFile.isDirectory()) {
                                files.addAll(findCodFiles(userFile));
                            } else if (userFile.exists() && userPath.endsWith(".cod")) {
                                files.add(userFile.getAbsolutePath());
                            }
                        }
                    }
                } else {
                    System.out.println("No Android test directory found at: " + androidPath + baseTestPath);
                    System.out.print("Enter directory or file path: ");
                    System.out.flush();
                    
                    String userPath = scanner.nextLine().trim();
                    if (!userPath.isEmpty()) {
                        File userFile = new File(userPath);
                        if (userFile.isDirectory()) {
                            files.addAll(findCodFiles(userFile));
                        } else if (userFile.exists() && userPath.endsWith(".cod")) {
                            files.add(userFile.getAbsolutePath());
                        }
                    }
                }
            }
        }
        
        if (files.isEmpty()) {
            System.out.println("No .cod files found.");
            System.out.println("Usage: CodPTACParityRunner <file.cod> or <directory>");
            return;
        }

        int passed = 0;
        int failed = 0;
        List<String> failures = new ArrayList<String>();

        System.out.println();
        System.out.println("CodP-TAC Parity Validation");
        System.out.println("=========================");
        System.out.println("Found " + files.size() + " test file(s)");
        System.out.println();

        for (int i = 0; i < files.size(); i++) {
            final String file = files.get(i);
            String shortName = new File(file).getName();
            
            System.out.print("[" + (i + 1) + "/" + files.size() + "] Testing " + shortName + "... ");
            System.out.flush();
            
            PathResult astResult = executePath(new PathSupplier() {
                @Override
                public String get() throws Exception {
                    return runAstPath(file);
                }
            });
            PathResult ptacResult = executePath(new PathSupplier() {
                @Override
                public String get() throws Exception {
                    return runCodPTACPath(file);
                }
            });

            if (astResult.ok && ptacResult.ok) {
                if (normalize(astResult.text).equals(normalize(ptacResult.text))) {
                    System.out.println("PASSED");
                    passed++;
                } else {
                    System.out.println("FAILED");
                    failed++;
                    failures.add(shortName + " - output mismatch");
                }
            } else if (!astResult.ok && !ptacResult.ok) {
                if (normalize(astResult.error).equals(normalize(ptacResult.error))) {
                    System.out.println("PASSED");
                    passed++;
                } else {
                    System.out.println("FAILED");
                    failed++;
                    failures.add(shortName + " - error mismatch");
                }
            } else {
                String astState = astResult.ok ? "ok" : "error";
                String ptacState = ptacResult.ok ? "ok" : "error";
                System.out.println("FAILED");
                failed++;
                failures.add(shortName + " - AST(" + astState + ") vs PTAC(" + ptacState + ")");
            }
            
            System.out.flush();
        }

        System.out.println();
        System.out.println("=========================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed, " + files.size() + " total");
        
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Failed tests:");
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
        }
        
        System.out.println();
        System.out.println("Parity check complete.");
    }
    
    private List<String> findCodFiles(File dir) {
        List<String> files = new ArrayList<String>();
        File[] entries = dir.listFiles();
        if (entries == null) return files;
        
        for (File entry : entries) {
            if (entry.isDirectory()) {
                files.addAll(findCodFiles(entry));
            } else if (entry.getName().endsWith(".cod")) {
                files.add(entry.getAbsolutePath());
            }
        }
        return files;
    }

    private String runAstPath(String file) throws Exception {
        Interpreter interpreter = new Interpreter();
        interpreter.setFilePath(file);
        Program ast = parse(file, interpreter);
        return captureOutput(interpreter, ast);
    }

    private String runCodPTACPath(String file) throws Exception {
        Interpreter interpreter = new Interpreter();
        interpreter.setFilePath(file);
        Program ast = parse(file, interpreter);
        interpreter.setCurrentProgram(ast);
        
        if (ast == null || ast.unit == null || ast.unit.types == null || ast.unit.types.isEmpty()) {
            return captureOutput(interpreter, ast);
        }

        String projectRoot = Index.getProjectRoot();
        if (projectRoot == null) {
            projectRoot = new File(".").getAbsoluteFile().getAbsolutePath();
        }
        
        IRManager manager = new IRManager(projectRoot);
        String unitName = ast.unit.name;
        Type entryType = findMainType(ast);
        
        if (entryType == null) {
            return captureOutput(interpreter, ast);
        }
        
        manager.save(unitName, entryType);
        Artifact artifact = manager.loadArtifact(unitName, entryType.name);
        
        if (artifact == null) {
            throw new Exception("Failed to load CodP-TAC artifact for: " + file);
        }

        return captureOutputPTAC(artifact, interpreter);
    }

    private Type findMainType(Program ast) {
        if (ast == null || ast.unit == null || ast.unit.types == null || ast.unit.types.isEmpty()) {
            return null;
        }
        for (Type type : ast.unit.types) {
            if (type == null || type.methods == null) continue;
            for (Method method : type.methods) {
                if (method != null && "main".equals(method.methodName)
                    && (method.parameters == null || method.parameters.isEmpty())) {
                    return type;
                }
            }
        }
        return ast.unit.types.get(0);
    }

    private String captureOutput(Interpreter interpreter, Program ast) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        java.io.InputStream oldIn = System.in;
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream outReplacement = new PrintStream(outBuffer);
        PrintStream errReplacement = new PrintStream(errBuffer);
        
        try {
            System.setOut(outReplacement);
            System.setErr(errReplacement);
            System.setIn(new ByteArrayInputStream(DEFAULT_INPUT.getBytes(StandardCharsets.UTF_8)));
            interpreter.run(ast);
            outReplacement.flush();
            errReplacement.flush();
            
            String output = outBuffer.toString();
            String error = errBuffer.toString();
            
            if (error != null && !error.trim().isEmpty()) {
                output = output + "\n[STDERR]\n" + error;
            }
            return output;
        } finally {
            outReplacement.close();
            errReplacement.close();
            System.setOut(oldOut);
            System.setErr(oldErr);
            System.setIn(oldIn);
        }
    }

    private String captureOutputPTAC(Artifact artifact, Interpreter interpreter) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        java.io.InputStream oldIn = System.in;
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream outReplacement = new PrintStream(outBuffer);
        PrintStream errReplacement = new PrintStream(errBuffer);
        
        try {
            System.setOut(outReplacement);
            System.setErr(errReplacement);
            System.setIn(new ByteArrayInputStream(DEFAULT_INPUT.getBytes(StandardCharsets.UTF_8)));
            new Executor(Options.compileExecuteWithFallback(true))
                .execute(artifact, interpreter);
            outReplacement.flush();
            errReplacement.flush();
            
            String output = outBuffer.toString();
            String error = errBuffer.toString();
            
            if (error != null && !error.trim().isEmpty()) {
                output = output + "\n[STDERR]\n" + error;
            }
            return output;
        } finally {
            outReplacement.close();
            errReplacement.close();
            System.setOut(oldOut);
            System.setErr(oldErr);
            System.setIn(oldIn);
        }
    }
    
    private PathResult executePath(PathSupplier supplier) {
        try {
            return new PathResult(true, supplier.get(), null);
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.trim().isEmpty()) {
                msg = t.getClass().getName();
            }
            return new PathResult(false, null, msg);
        }
    }

    private String normalize(String text) {
        if (text == null) return "";
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String cleaned = line.trim().replaceFirst("^\\[[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}\\]\\s*", "");
            cleaned = cleaned.replaceFirst("(?i)(Output-aware loop time:\\s*)" + NUMBER_REGEX_PATTERN + "(\\s*ms)", "$1<TIME>$2");
            cleaned = cleaned.replaceFirst("(?i)(Timer resolution:\\s*)" + NUMBER_REGEX_PATTERN + "(\\s*ms)", "$1<TIME>$2");
            cleaned = cleaned.replaceFirst("(?i)(elapsed_ms=)" + NUMBER_REGEX_PATTERN, "$1<TIME>");
            cleaned = cleaned.replaceFirst("(?i)(.*(?:time|elapsed|duration|latency)[^=]*=)" + NUMBER_REGEX_PATTERN + "$", "$1<TIME>");
            cleaned = cleaned.replaceFirst("(?i)(.*\\btime:\\s*)" + NUMBER_REGEX_PATTERN + "(\\s*ms.*)", "$1<TIME>$2");
            cleaned = cleaned.replaceFirst("(.*:\\s*)" + NUMBER_REGEX_PATTERN + "(\\s*ms)$", "$1<TIME>$2");
            cleaned = cleaned.replaceFirst("(?i)(.*\\bat\\s+)" + NUMBER_REGEX_PATTERN + "(\\s*ms)", "$1<TIME>$2");
            cleaned = cleaned.replaceFirst("NaturalArray\\[id=\\d+", "NaturalArray[id=<ID>");
            sb.append(cleaned).append("\n");
        }
        return sb.toString().trim();
    }

    private Scanner newSystemInScanner() {
        InputStream nonClosingIn =
            new FilterInputStream(System.in) {
                @Override
                public void close() throws IOException {
                    // Keep System.in available for the process lifetime.
                }
            };
        return new Scanner(nonClosingIn);
    }
    
    private interface PathSupplier {
        String get() throws Exception;
    }
    
    private static final class PathResult {
        final boolean ok;
        final String text;
        final String error;
        
        PathResult(boolean ok, String text, String error) {
            this.ok = ok;
            this.text = text;
            this.error = error;
        }
    }

    public static void main(String[] args) {
        CodPTACParityRunner runner = new CodPTACParityRunner();
        try {
            runner.run(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
