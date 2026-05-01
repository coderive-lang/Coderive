package cod.runner;

import cod.ast.node.*;
import cod.debug.DebugSystem;
import cod.interpreter.Interpreter;
import cod.semantic.ImportResolver;
import cod.interpreter.Index;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.File;
import java.util.List;

import cod.lexer.*;
import cod.parser.MainParser;

public abstract class BaseRunner {

    public static class RunnerConfig {
        public String inputFilename;
        public String outputFilename;
        public DebugSystem.Level debugLevel = DebugSystem.Level.INFO;
        
        public RunnerConfig(String inputFilename) {
            this.inputFilename = inputFilename;
        }
        
        public RunnerConfig withOutputFilename(String outputFilename) {
            this.outputFilename = outputFilename;
            return this;
        }
        
        public RunnerConfig withDebugLevel(DebugSystem.Level debugLevel) {
            this.debugLevel = debugLevel;
            return this;
        }
    }

    public interface Configuration {
        void configure(RunnerConfig config);
    }

    protected static final String
    LOG_TAG = "RUNNER",
    PARSER = "PARSER",
    IR = "IR",
    NATIVE = "NATIVE",
    INTERPRETER = "INTERPRETER",
    AST = "AST";
    
    public static void out(String s) {
        System.out.println(s);
    }
    
    public static void outE(String err) {
        System.err.println(err);
    }
    
    public static void out() {
        System.out.println();
    }
    
    public static void outE() {
        System.err.println();
    }
    
    public Program parse(String filename, Interpreter interpreter) throws Exception {
        DebugSystem.debug(LOG_TAG, "Loading source file: " + filename);
        
        // Use Java 7 Files API to read entire file
        String sourceCode = new String(
            Files.readAllBytes(Paths.get(filename)), 
            StandardCharsets.UTF_8
        );
        
        DebugSystem.debug(LOG_TAG, "Source length: " + sourceCode.length() + " chars");
        DebugSystem.debug(PARSER, "Tokenizing...");
        
        DebugSystem.startTimer(DebugSystem.Level.INFO, "lexer");
        
        MainLexer lexer = new MainLexer(sourceCode);
        List<Token> tokens = lexer.tokenize();
        
        DebugSystem.stopTimer("lexer");

        DebugSystem.debug(PARSER, "Generated " + tokens.size() + " tokens");
        
        DebugSystem.debug(PARSER, "Parsing...");
        
        DebugSystem.startTimer(DebugSystem.Level.INFO, "parser");
        
        MainParser parser = new MainParser(tokens, interpreter);
        Program ast = parser.parseProgram();
        
        DebugSystem.stopTimer("parser");
        
        DebugSystem.debug(PARSER, "Parsing completed successfully");
       
        return ast;
    }

    protected void configureDebugSystem(DebugSystem.Level level) {
        DebugSystem.setLevel(level);
        DebugSystem.setShowTimestamp(!DebugSystem.isBenchmarkMode());
        if (!DebugSystem.isBenchmarkMode()) {
            DebugSystem.info(LOG_TAG, "DebugSystem configured to level: " + level);
        }
    }
    
    protected String extractFilenameFromArgs(String[] args, String defaultFilename) {
        for (String arg : args) {
            if (!arg.startsWith("--") && !arg.equals("-o")) {
                DebugSystem.debug(LOG_TAG, "Extracted filename from args: " + arg);
                return arg;
            }
        }
        DebugSystem.debug(LOG_TAG, "Using default filename: " + defaultFilename);
        return defaultFilename;
    }

    protected RunnerConfig processArgs(String[] args, String defaultInputFilename, Configuration configCallback) {
        DebugSystem.debug(LOG_TAG, "Processing command line args, count: " + args.length);
        RunnerConfig config = new RunnerConfig(defaultInputFilename);
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--debug".equals(arg)) {
                config.debugLevel = DebugSystem.Level.DEBUG;
                DebugSystem.debug(LOG_TAG, "Set debug level to DEBUG");
            } else if ("--trace".equals(arg)) {
                config.debugLevel = DebugSystem.Level.TRACE;
                DebugSystem.trace(LOG_TAG, "Set debug level to TRACE");
            } else if ("-o".equals(arg)) {
                if (i + 1 < args.length) {
                    config.outputFilename = args[i + 1];
                    i++;
                    DebugSystem.debug(LOG_TAG, "Set output filename: " + config.outputFilename);
                } else {
                    outE("Error: -o option requires an output filename.");
                    DebugSystem.error(LOG_TAG, "-o option missing filename");
                }
            }
        }
        
        if (config.inputFilename == null) {
            config.inputFilename = extractFilenameFromArgs(args, defaultInputFilename);
        }
        
        if (configCallback != null) {
            configCallback.configure(config);
        }
        
        DebugSystem.debug(LOG_TAG, "Config: input=" + config.inputFilename + 
            ", output=" + config.outputFilename + ", level=" + config.debugLevel);
        
        return config;
    }

    protected RunnerConfig processArgs(String[] args, String defaultInputFilename) {
        return processArgs(args, defaultInputFilename, null);
    }
    
    // ========== PROGRESSIVE INDEX GENERATION ==========
    
    /**
     * Generate index for the parsed program only (progressive, no directory scan).
     * This should be called after parsing to record the classes found.
     * 
     * @param ast the parsed program AST
     * @param interpreter the interpreter instance
     */
    protected void generateIndexes(Program ast, Interpreter interpreter) {
        if (ast == null || ast.unit == null) {
            DebugSystem.debug("INDEX", "No program or unit, skipping index generation");
            return;
        }
        
        DebugSystem.debug("INDEX", "=== Progressive index generation ===");
        
        ImportResolver resolver = interpreter.getImportResolver();
        String srcMainRoot = resolver.getSrcMainRoot();
        
        if (srcMainRoot == null) {
            DebugSystem.debug("INDEX", "No src/main root found, skipping index generation");
            return;
        }
        
        // Instead of scanning directories, collect from already-parsed AST
        String unitName = ast.unit.name;
        if (unitName != null && !unitName.equals("default")) {
            Index index = Index.load(unitName);
            if (index == null) {
                index = new Index(unitName);
            }
            
            // Add classes from this file only
            String currentFileName = new File(interpreter.getCurrentFilePath()).getName();
            for (Type type : ast.unit.types) {
                index.add(type.name, currentFileName);
            }
            index.markParsed(currentFileName);
            index.save();
            
            DebugSystem.debug("INDEX", "Updated index for unit: " + unitName + 
                             " with " + index.size() + " classes from " + currentFileName);
        }
        
        DebugSystem.debug("INDEX", "=== Index generation complete (progressive) ===");
    }
    
    /**
     * Get the current file name from interpreter
     */
    protected String getCurrentFileName(Interpreter interpreter) {
        String filePath = interpreter.getCurrentFilePath();
        if (filePath != null) {
            return new File(filePath).getName();
        }
        return null;
    }
    
    /**
     * Clear all index caches.
     */
    protected void clearIndexCaches(Interpreter interpreter) {
        if (interpreter != null && interpreter.getImportResolver() != null) {
            interpreter.getImportResolver().clearCache();
            DebugSystem.debug("INDEX", "Cleared import resolver caches");
        }
    }

    public abstract void run(String[] args) throws Exception;
}