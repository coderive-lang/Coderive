package cod.interpreter;

import cod.ast.node.*;
import cod.debug.DebugSystem;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.interpreter.context.*;
import cod.interpreter.exception.*;
import cod.interpreter.registry.*;
import cod.interpreter.handler.*;
import cod.lexer.*;
import cod.parser.MainParser;
import cod.semantic.ImportResolver;
import cod.semantic.ConstructorResolver;
import static cod.lexer.TokenType.Keyword.*;
import static cod.lexer.TokenType.Symbol.*;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.*;

public class Interpreter {

  public IOHandler ioHandler;
  private ImportResolver importResolver;
  private TypeHandler typeSystem;
  private InterpreterVisitor visitor;
  private ConstructorResolver constructorResolver;
  private Program currentProgram;
  private BuiltinRegistry builtinRegistry;
  private GlobalRegistry globalRegistry;
  private LiteralRegistry literalRegistry;
  private String currentFilePath;
  
  // Helper class for broadcast info
  private static class BroadcastInfo {
    final String mainClassName;
    final boolean shouldLoad;
    
    BroadcastInfo(String mainClassName, boolean shouldLoad) {
      this.mainClassName = mainClassName;
      this.shouldLoad = shouldLoad;
    }
  }
  
  public Interpreter() {
    // Initialize independent components first
    this.ioHandler = new IOHandler();
    this.typeSystem = new TypeHandler();
    this.builtinRegistry = new BuiltinRegistry();
    this.importResolver = new ImportResolver();
    
    // Create global registry (depends on ioHandler and builtinRegistry)
    this.globalRegistry = new GlobalRegistry(ioHandler, builtinRegistry);
    
    // TWO-STEP INITIALIZATION:
    // 1. Create registry with null evaluator
    this.literalRegistry = new LiteralRegistry(null);
    
    // 2. Create visitor with the registry
    this.visitor = new InterpreterVisitor(this, typeSystem, literalRegistry);
    
    // 3. Now set the evaluator on the registry
    this.literalRegistry.setEvaluator(this.visitor);
    
    // 4. Initialize remaining components
    this.constructorResolver = new ConstructorResolver(typeSystem, this);
    this.currentProgram = null;
  }

  public InterpreterVisitor getVisitor() {
    return visitor;
  }

  public void setFilePath(String filePath) {
    if (filePath == null || filePath.isEmpty()) {
        throw new InternalError("setFilePath called with null/empty path");
    }
    this.currentFilePath = filePath;
    
    // CRITICAL: Pass the file path to import resolver so it can resolve imports relative to this file
    if (importResolver != null) {
        importResolver.setCurrentFileDirectory(filePath);
    }
    
    DebugSystem.debug("INTERPRETER", "Set file path: " + filePath);
}
  
  public String getCurrentFilePath() {
    return currentFilePath;
  }

  public ImportResolver getImportResolver() {
    return importResolver;
  }

  public ConstructorResolver getConstructorResolver() {
    return constructorResolver;
  }

  public GlobalRegistry getGlobalRegistry() {
    return globalRegistry;
  }

  public LiteralRegistry getLiteralRegistry() {
    return literalRegistry;
  }
  
  public TypeHandler getTypeHandler() {
    return typeSystem;
  }

  boolean shouldReturnEarly(Map<String, Object> slotValues, Set<String> slotsInCurrentPath) {
    if (slotValues == null || slotsInCurrentPath == null) {
      throw new InternalError("shouldReturnEarly called with null maps");
    }
    if (slotsInCurrentPath.isEmpty()) return false;
    for (String slotName : slotsInCurrentPath) {
      if (slotValues.get(slotName) == null) return false;
    }
    return true;
  }
  
  public Program getCurrentProgram() {
    return currentProgram;
  }

  public void setCurrentProgram(Program program) {
    this.currentProgram = program;
  }

  public Object evalReplStatement(
      Stmt stmt,
      ObjectInstance obj,
      Map<String, Object> locals,
      Map<String, Object> slotValues) {
    
    if (stmt == null) {
        throw new InternalError("evalReplStatement called with null stmt");
    }
    if (locals == null) {
        throw new InternalError("evalReplStatement called with null locals");
    }
    
    Map<String, String> slotTypes = new HashMap<String, String>();
    ExecutionContext ctx = new ExecutionContext(obj, locals, slotValues, slotTypes, typeSystem);
    visitor.pushContext(ctx);
    
    try {
        Object result = visitor.visit((Base) stmt);
        
        // Sync changes back to original locals map!
        // The ExecutionContext may have modified its internal copy,
        // so we need to copy back to the original map that REPLRunner holds.
        locals.clear();
        locals.putAll(ctx.getLocalsMap());
        
        return result;
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Unexpected error in REPL evaluation", e);
    } finally {
        visitor.popContext();
    }
  }

  public void run(Program program) {
    if (program == null) {
      throw new InternalError("run called with null program");
    }
    
    DebugSystem.startTimer("program_execution");
    DebugSystem.info("INTERPRETER", "Starting program execution from: " + 
        (currentFilePath != null ? currentFilePath : "unknown location"));
    
    this.currentProgram = program;

    try {
      if (program.programType == null) {
        DebugSystem.warn("INTERPRETER", "No program type detected, assuming MODULE");
        runModule(program);
      } else {
        switch (program.programType) {
          case MODULE:
            runModule(program);
            break;
          case SCRIPT:
            runScript(program);
            break;
          case STATIC_MODULE:
            runStaticModule(program);
            break;
          default:
            throw new InternalError("Unknown program type: " + program.programType);
        }
      }
    } catch (ProgramError e) {
      throw e;
    } catch (InternalError e) {
      throw e;
    } catch (Exception e) {
      throw new InternalError("Program execution failed", e);
    } finally {
      DebugSystem.stopTimer("program_execution");
      DebugSystem.info("INTERPRETER", "Program execution completed");
      ioHandler.close();
    }
  }
  
public void runType(Type typeNode) {
    if (typeNode == null) {
        throw new InternalError("runType called with null typeNode");
    }
    
    DebugSystem.startTimer("program_execution");
    DebugSystem.info("INTERPRETER", "Starting type execution for: " + typeNode.name);
    
    // Find main method
    Method mainMethod = null;
    for (Method method : typeNode.methods) {
        if ("main".equals(method.methodName) && method.parameters.isEmpty()) {
            mainMethod = method;
            break;
        }
    }
    
    if (mainMethod == null) {
        throw new ProgramError("No main() method found in " + typeNode.name);
    }
    
    ObjectInstance obj = new ObjectInstance(typeNode);
    Map<String, Object> locals = new HashMap<String, Object>();
    ExecutionContext ctx = new ExecutionContext(obj, locals, null, null, typeSystem);
    
    visitor.pushContext(ctx);
    
    try {
        for (Stmt stmt : mainMethod.body) {
            visitor.visit(stmt);
        }
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Type execution failed: " + typeNode.name, e);
    } finally {
        visitor.popContext();
        DebugSystem.stopTimer("program_execution");
        DebugSystem.info("INTERPRETER", "Type execution completed");
        ioHandler.close();
    }
}

public void run(Object entryPoint) {
    if (entryPoint == null) {
        throw new InternalError("run called with null entryPoint");
    }
    
    if (entryPoint instanceof Program) {
        run((Program) entryPoint);
    } else if (entryPoint instanceof Type) {
        runType((Type) entryPoint);
    } else {
        throw new ProgramError("Invalid entry point type: " + entryPoint.getClass().getName());
    }
}

  // ========== UPDATED BROADCAST DETECTION WITH MODULAR LEXER ==========
  
  private void checkForMultipleBroadcasts(String packageName) {
    if (packageName == null || packageName.isEmpty()) {
        throw new InternalError("checkForMultipleBroadcasts called with null/empty packageName");
    }
    
    DebugSystem.debug("BROADCAST", "Checking for multiple broadcasts in package: " + packageName);
    
    Map<String, String> broadcastMap = new HashMap<String, String>();
    List<String> broadcastSources = new ArrayList<String>();
    
    // First, check if this file has a broadcast (already parsed)
    if (currentProgram != null && currentProgram.unit != null && 
        currentProgram.unit.mainClassName != null && !currentProgram.unit.mainClassName.isEmpty()) {
        broadcastMap.put("current file", currentProgram.unit.mainClassName);
        broadcastSources.add("Current file (main: " + currentProgram.unit.mainClassName + ")");
    }
    
    // Check all loaded imports for broadcasts in this package
    if (importResolver != null) {
        Map<String, Program> loadedPrograms = importResolver.getLoadedPrograms();
        for (Map.Entry<String, Program> entry : loadedPrograms.entrySet()) {
            Program program = entry.getValue();
            if (program != null && program.unit != null && 
                program.unit.name != null && program.unit.name.equals(packageName) &&
                program.unit.mainClassName != null && !program.unit.mainClassName.isEmpty()) {
                
                String source = "import: " + entry.getKey();
                broadcastMap.put(source, program.unit.mainClassName);
                broadcastSources.add(source + " (main: " + program.unit.mainClassName + ")");
            }
        }
    }
    
    // If current file path exists, scan the filesystem for other .cod files
    if (currentFilePath != null) {
        File currentFile = new File(currentFilePath);
        File packageDir = currentFile.getParentFile();
        if (packageDir != null && packageDir.exists()) {
            File[] files = packageDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".cod");
                }
            });
            
            if (files != null) {
                for (File file : files) {
                    if (file.getAbsolutePath().equals(currentFilePath)) {
                        continue;
                    }
                    
                    // Use lexer to extract broadcast information (no false positives)
                    BroadcastInfo info = extractBroadcastFromFile(file, packageName);
                    if (info != null) {
                        String source = "file: " + file.getName();
                        broadcastMap.put(source, info.mainClassName);
                        broadcastSources.add(source + " (main: " + info.mainClassName + ")");
                        
                        // Optionally load the file if it has types we need
                        if (info.shouldLoad) {
                            loadFileIntoSameUnit(file, packageName, currentProgram);
                        }
                    }
                }
            }
        }
    }
    
    // If more than one broadcast found, throw error
    if (broadcastMap.size() > 1) {
        StringBuilder errorMsg = new StringBuilder();
        errorMsg.append("Multiple broadcast declarations found for package '").append(packageName).append("':\n");
        
        for (String sourceInfo : broadcastSources) {
            errorMsg.append("  - ").append(sourceInfo).append("\n");
        }
        
        errorMsg.append("\nOnly one broadcast allowed per package.\n");
        errorMsg.append("Solutions:\n");
        errorMsg.append("1. Remove extra broadcast declarations\n");
        errorMsg.append("2. Or add your own main() method in this file (takes precedence)\n");
        errorMsg.append("3. Or use import to explicitly choose which file to use");
        
        throw new ProgramError(errorMsg.toString());
    }
  }
  
  private BroadcastInfo extractBroadcastFromFile(File file, String expectedPackage) {
    BufferedReader reader = null;
    try {
        StringBuilder content = new StringBuilder();
        reader = new BufferedReader(new FileReader(file));
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        reader.close();
        
        String fileContent = content.toString();
        
        // Use lexer to tokenize and find REAL unit declaration (ignores comments/strings)
        MainLexer lexer = new MainLexer(fileContent);
        List<Token> tokens = lexer.tokenize(); // Now returns only meaningful tokens
        
        // Find the unit declaration
        boolean foundUnit = false;
        String unitName = null;
        String mainClass = null;
        
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            
            // Look for 'unit' keyword
            if (!foundUnit && t.isKeyword(UNIT)) {
                foundUnit = true;
                
                // Look ahead for the unit name (next ID token)
                int j = i + 1;
                while (j < tokens.size()) {
                    Token next = tokens.get(j);
                    if (next.type == TokenType.ID) {
                        unitName = next.getText();
                        j++;
                        break;
                    }
                    j++;
                }
                
                // Look for (main: ...) pattern
                while (j < tokens.size()) {
                    Token maybeParen = tokens.get(j);
                    if (maybeParen.isSymbol(LPAREN)) {
                        j++;
                        // Parse inside parentheses
                        while (j < tokens.size()) {
                            Token inside = tokens.get(j);
                            if (inside.type == TokenType.ID && inside.getText().equals("main")) {
                                j++;
                                if (j < tokens.size() && tokens.get(j).isSymbol(COLON)) {
                                    j++;
                                    if (j < tokens.size() && tokens.get(j).type == TokenType.ID) {
                                        mainClass = tokens.get(j).getText();
                                        break;
                                    }
                                }
                            } else if (inside.isSymbol(RPAREN)) {
                                break;
                            }
                            j++;
                        }
                        break;
                    }
                    j++;
                }
                break;
            }
        }
        
        // If we found a valid unit with matching package name and main class
        if (foundUnit && expectedPackage.equals(unitName) && mainClass != null) {
            // Should load if this file has types we might need
            return new BroadcastInfo(mainClass, hasTypes(tokens));
        }
        
        return null;
        
    } catch (Exception e) {
        DebugSystem.debug("BROADCAST", "Error extracting broadcast from " + file.getName() + ": " + e.getMessage());
        return null;
    } finally {
        if (reader != null) {
            try { reader.close(); } catch (IOException e) {}
        }
    }
  }
  
  private boolean hasTypes(List<Token> tokens) {
    // Check if file contains any class declarations
    for (int i = 0; i < tokens.size(); i++) {
        Token t = tokens.get(i);
        
        // Look for class name (uppercase identifier not followed by '(')
        if (t.type == TokenType.ID && 
            t.length > 0 && 
            Character.isUpperCase(t.charAt(0))) {
            
            // Check if it's a class (followed by {, is, or with)
            if (i + 1 < tokens.size()) {
                Token next = tokens.get(i + 1);
                if (next.isSymbol(LBRACE) || 
                    next.isKeyword(IS) || 
                    next.isKeyword(WITH)) {
                    return true;
                }
            }
        }
    }
    return false;
  }
  
  // ========== END UPDATED BROADCAST DETECTION ==========

  private void runModule(Program program) {
    if (program == null || program.unit == null) {
        throw new InternalError("runModule called with null program or unit");
    }
    
    Unit unit = program.unit;
    initializeImportResolver(unit);

    Type localMainClass = null;
    Method localMainMethod = null;
    if (unit.types != null) {
      for (Type type : unit.types) {
        Method candidate = findNoArgMainMethod(type);
        if (candidate != null) {
          localMainClass = type;
          localMainMethod = candidate;
          break;
        }
      }
    }
    if (localMainMethod != null) {
      DebugSystem.info("INTERPRETER", "Found local main() in class: " + localMainClass.name);
      executeMainMethod(localMainClass, localMainMethod, "main (local)", "Failed to execute local main()");
      return;
    }
    
    DebugSystem.debug("BROADCAST", "=== STARTING MODULE EXECUTION ===");
    DebugSystem.debug("BROADCAST", "Unit name: " + unit.name);
    DebugSystem.debug("BROADCAST", "Local main class: " + unit.mainClassName);
    
    // Enhanced check for multiple broadcasts in the same package (UPDATED)
    checkForMultipleBroadcasts(unit.name);
    
    if (unit.mainClassName == null || unit.mainClassName.isEmpty()) {
        scanPackageForBroadcasts(unit.name);
    }
    
    String importedBroadcastCheck = importResolver.getBroadcast(unit.name);
    DebugSystem.debug("BROADCAST", "Imported broadcast check result: " + importedBroadcastCheck);
    
    String mainClassNameToUse = null;
    boolean useImportedBroadcast = false;
    
    if (unit.mainClassName != null && !unit.mainClassName.isEmpty()) {
        mainClassNameToUse = unit.mainClassName;
        DebugSystem.info("INTERPRETER", "Using local broadcast: (main: " + mainClassNameToUse + ")");
    } else {
        String importedBroadcast = importResolver.getBroadcast(unit.name);
        if (importedBroadcast != null) {
            mainClassNameToUse = importedBroadcast;
            useImportedBroadcast = true;
            DebugSystem.info("INTERPRETER", "Using imported broadcast for package '" + 
                unit.name + "': (main: " + mainClassNameToUse + ")");
        }
    }
    
    DebugSystem.debug("BROADCAST", "Main class to use: " + mainClassNameToUse);
    DebugSystem.debug("BROADCAST", "Is imported broadcast: " + useImportedBroadcast);
    
    if (mainClassNameToUse != null) {
        DebugSystem.info("INTERPRETER", "Running " + 
            (useImportedBroadcast ? "imported" : "local") + 
            " broadcasted main class: " + mainClassNameToUse);
        
        Type broadcastedClass = null;
        Method broadcastedMainMethod = null;
        
        if (useImportedBroadcast) {
            DebugSystem.debug("BROADCAST", "Searching for imported class: " + mainClassNameToUse);
            
            String[] searchPatterns = {
                mainClassNameToUse,
                unit.name + "." + mainClassNameToUse,
            };
            
            for (String pattern : searchPatterns) {
                try {
                    broadcastedClass = importResolver.findType(pattern);
                    if (broadcastedClass != null) {
                        DebugSystem.debug("BROADCAST", "Found class using pattern: " + pattern);
                        break;
                    }
                } catch (ProgramError e) {
                    DebugSystem.debug("BROADCAST", "Pattern " + pattern + " not found: " + e.getMessage());
                }
            }
            
            if (broadcastedClass == null) {
                DebugSystem.debug("BROADCAST", "Searching all loaded imports for class: " + mainClassNameToUse);
                for (Program importedProgram : importResolver.getLoadedPrograms().values()) {
                    if (importedProgram.unit != null && importedProgram.unit.types != null) {
                        for (Type type : importedProgram.unit.types) {
                            if (type.name.equals(mainClassNameToUse)) {
                                broadcastedClass = type;
                                DebugSystem.debug("BROADCAST", "Found in import: " + importedProgram.unit.name);
                                break;
                            }
                        }
                    }
                    if (broadcastedClass != null) break;
                }
            }
            
            if (broadcastedClass != null) {
                ExecutionContext searchCtx = new ExecutionContext(null, new HashMap<String, Object>(), null, null, typeSystem);
                broadcastedMainMethod = constructorResolver.findMethodInHierarchy(broadcastedClass, "main", searchCtx);
            }
        } else {
            for (Type type : unit.types) {
                if (type.name.equals(mainClassNameToUse)) {
                    broadcastedClass = type;
                    ExecutionContext searchCtx = new ExecutionContext(null, new HashMap<String, Object>(), null, null, typeSystem);
                    broadcastedMainMethod = constructorResolver.findMethodInHierarchy(type, "main", searchCtx);
                    break;
                }
            }
        }
        
        if (broadcastedClass != null && broadcastedMainMethod != null) {
            DebugSystem.info("BROADCAST", "Executing broadcasted main() from class: " + broadcastedClass.name);
            executeMainMethod(
                broadcastedClass,
                broadcastedMainMethod,
                "main (broadcast)",
                "Failed to execute broadcasted main()");
            return;
        } else {
            String source = useImportedBroadcast ? "imported broadcast" : "local broadcast";
            String errorMsg = source + " main class '" + mainClassNameToUse + 
                "' not found or has no main() method";
            
            if (useImportedBroadcast) {
                errorMsg += "\n\nPossible issues:";
                errorMsg += "\n1. The broadcasted class might not be in a loaded import";
                errorMsg += "\n2. The class might exist but doesn't have a main() method";
                errorMsg += "\n3. There might be multiple broadcasts in the package (check above error)";
                
                errorMsg += "\n\nLoaded imports: " + importResolver.getLoadedImports();
            }
            
            throw new ProgramError(errorMsg);
        }
    }
    
    if (mainClassNameToUse == null) {
        String errorMessage = "No executable main() found in package '" + unit.name + "'\n\n" +
            "This file has no main() method and no local broadcast.\n" +
            "Options:\n" +
            "1. Add a class with main() method in this file\n" +
            "2. Add a broadcast declaration: unit " + unit.name + " (main: YourClass)\n" +
            "3. If another file broadcasts a main class, ensure only ONE file broadcasts\n" +
            "   (Multiple broadcasts in same package cause conflicts)";
        
        if (currentFilePath != null) {
            File dir = new File(currentFilePath).getParentFile();
            if (dir != null && dir.exists()) {
                File[] codFiles = dir.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File d, String name) {
                        return name.endsWith(".cod") && !name.equals(new File(currentFilePath).getName());
                    }
                });
                
                if (codFiles != null && codFiles.length > 0) {
                    errorMessage += "\n\nOther .cod files in package:";
                    for (File file : codFiles) {
                        errorMessage += "\n  - " + file.getName();
                    }
                }
            }
        }
        
        throw new ProgramError(errorMessage);
    }
  }

  private void scanPackageForBroadcasts(String packageName) {
    if (packageName == null || packageName.isEmpty()) {
      throw new InternalError("scanPackageForBroadcasts called with null/empty packageName");
    }
    
    DebugSystem.debug("BROADCAST", "=== STARTING BROADCAST SCAN ===");
    DebugSystem.debug("BROADCAST", "Scanning package '" + packageName + "' for broadcasts");
    
    if (currentFilePath == null) {
      DebugSystem.debug("BROADCAST", "No file path available for scanning");
      DebugSystem.debug("BROADCAST", "=== BROADCAST SCAN COMPLETE (NO FILE PATH) ===");
      return;
    }
    
    File currentFile = new File(currentFilePath);
    File packageDir = currentFile.getParentFile();
    if (packageDir == null || !packageDir.exists()) {
      DebugSystem.debug("BROADCAST", "Package directory not found");
      return;
    }
    
    File[] files = packageDir.listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.endsWith(".cod");
      }
    });
    
    if (files == null) return;
    
    for (File file : files) {
      if (file.getAbsolutePath().equals(currentFilePath)) {
        continue;
      }
      
      // Use the lexer-based extraction instead of regex (UPDATED)
      BroadcastInfo info = extractBroadcastFromFile(file, packageName);
      if (info != null) {
        DebugSystem.info("BROADCAST", "Found broadcast in " + file.getName() + ": (main: " + info.mainClassName + ")");
        
        importResolver.registerBroadcast(packageName, info.mainClassName);
        
        if (info.shouldLoad) {
          loadFileIntoSameUnit(file, packageName, currentProgram);
        }
        
        return;
      }
    }
    
    DebugSystem.debug("BROADCAST", "=== BROADCAST SCAN COMPLETE ===");
  }

  private void loadFileIntoSameUnit(File file, String unitName, Program currentProgram) {
    if (file == null) {
      throw new InternalError("loadFileIntoSameUnit called with null file");
    }
    if (unitName == null || unitName.isEmpty()) {
      throw new InternalError("loadFileIntoSameUnit called with null/empty unitName");
    }
    if (currentProgram == null) {
      throw new InternalError("loadFileIntoSameUnit called with null currentProgram");
    }
    
    DebugSystem.debug("BROADCAST", "Loading file into same unit: " + file.getName());
    
    try {
      BufferedReader reader = new BufferedReader(new FileReader(file));
      StringBuilder content = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        content.append(line).append("\n");
      }
      reader.close();
      
      MainLexer lexer = new MainLexer(content.toString());
      List<Token> tokens = lexer.tokenize();
      MainParser parser = new MainParser(tokens, this);
      Program otherProgram = parser.parseProgram();
      
      if (otherProgram.unit != null && otherProgram.unit.types != null) {
        for (Type type : otherProgram.unit.types) {
          boolean exists = false;
          for (Type existingType : currentProgram.unit.types) {
            if (existingType.name.equals(type.name)) {
              exists = true;
              break;
            }
          }
          
          if (!exists) {
            currentProgram.unit.types.add(type);
            DebugSystem.debug("BROADCAST", "Added type to unit: " + type.name);
          }
        }
      }
      
      DebugSystem.info("BROADCAST", "Successfully loaded " + file.getName() + " into unit '" + unitName + "'");
      
    } catch (Exception e) {
      throw new InternalError("Failed to load file " + file.getName() + " into unit", e);
    }
  }

  private Method findNoArgMainMethod(Type type) {
    if (type == null || type.methods == null) {
      return null;
    }
    for (Method method : type.methods) {
      if ("main".equals(method.methodName) && (method.parameters == null || method.parameters.isEmpty())) {
        return method;
      }
    }
    return null;
  }

  private Type findTypeByName(Unit unit, String typeName) {
    if (unit == null || unit.types == null || typeName == null) {
      return null;
    }
    for (Type type : unit.types) {
      if (typeName.equals(type.name)) {
        return type;
      }
    }
    return null;
  }

  private void executeMainMethod(Type ownerType, Method mainMethod, String entryTag, String failureMessage) {
    DebugSystem.methodEntry(entryTag, Collections.<String, Object>emptyMap());
    ObjectInstance obj = new ObjectInstance(ownerType);
    Map<String, Object> locals = new HashMap<String, Object>();
    ExecutionContext ctx = new ExecutionContext(obj, locals, null, null, typeSystem);
    visitor.pushContext(ctx);
    try {
      if (mainMethod.body != null) {
        for (Stmt stmt : mainMethod.body) {
          visitor.visit((Base) stmt);
        }
      }
      DebugSystem.methodExit("main", null);
    } catch (EarlyFinException e) {
      DebugSystem.methodExit("main", null);
    } catch (ProgramError e) {
      throw e;
    } catch (Exception e) {
      throw new InternalError(failureMessage, e);
    } finally {
      visitor.popContext();
    }
  }

  private void runScript(Program program) {
    if (program == null || program.unit == null) {
        throw new InternalError("runScript called with null program or unit");
    }
    
    Unit unit = program.unit;
    initializeImportResolver(unit);

    ObjectInstance obj = new ObjectInstance(null);
    Map<String, Object> locals = new HashMap<String, Object>();

    DebugSystem.methodEntry("script", Collections.<String, Object>emptyMap());

    ExecutionContext ctx = new ExecutionContext(obj, locals, null, null, typeSystem);
    visitor.pushContext(ctx);
    
    try {
        for (Type type : unit.types) {
            if (type.name != null && type.name.equals("__Script__")) {
                if (type.statements != null) {
                    for (Stmt stmt : type.statements) {
                        Object result = visitor.visit((Base) stmt);
                        DebugSystem.debug("INTERPRETER", "Executed script statement: " + stmt.getClass().getSimpleName());
                        if (result != null) {
                            DebugSystem.debug("INTERPRETER", "  Result: " + result);
                        }
                    }
                }
                break;
            }
        }
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Script execution failed", e);
    } finally {
        visitor.popContext();
    }

    DebugSystem.methodExit("script", "completed");
  }

  private void runStaticModule(Program program) {
    if (program == null || program.unit == null) {
        throw new InternalError("runStaticModule called with null program or unit");
    }
    
    Unit unit = program.unit;
    initializeImportResolver(unit);

    Method mainMethod = null;
    Type containerType = findTypeByName(unit, "__StaticModule__");
    if (containerType != null) {
      mainMethod = findNoArgMainMethod(containerType);
    }
    if (mainMethod == null && unit.types != null) {
      for (Type type : unit.types) {
        mainMethod = findNoArgMainMethod(type);
        if (mainMethod != null) {
          containerType = type;
          break;
        }
      }
    }

    if (mainMethod == null) {
        throw new ProgramError("Static module requires a 'main()' method");
    }

    executeMainMethod(containerType, mainMethod, "main", "Static module execution failed");
  }

  private void initializeImportResolver(Unit unit) {
    if (unit == null) {
      throw new InternalError("initializeImportResolver called with null unit");
    }
    
    if (unit.resolvedImports != null && !unit.resolvedImports.isEmpty()) {
      for (Map.Entry<String, Program> entry : unit.resolvedImports.entrySet()) {
        importResolver.preloadImport(entry.getKey(), entry.getValue());
      }
    }
    if (unit.imports != null && !unit.imports.imports.isEmpty()) {
      for (String importName : unit.imports.imports) {
        importResolver.registerImport(importName);
      }
    }
  }

  public Object evalMethod(Method node, ObjectInstance obj, Map<String, Object> locals) {
    if (node == null) {
      throw new InternalError("evalMethod called with null node");
    }
    if (locals == null) {
      throw new InternalError("evalMethod called with null locals");
    }
    
    DebugSystem.methodEntry(node.methodName, locals);
    Map<String, Object> slotValues = new LinkedHashMap<String, Object>();
    Map<String, String> slotTypes = new LinkedHashMap<String, String>();
    if (node.returnSlots != null) {
      for (Slot s : node.returnSlots) {
        slotValues.put(s.name, null);
        slotTypes.put(s.name, s.type);
      }
    }
    
    ExecutionContext ctx = new ExecutionContext(obj, locals, slotValues, slotTypes, typeSystem);
    ctx.objectInstance = obj;
    
    if (obj != null && obj.type != null) {
      Type currentClass = findTypeByName(obj.type.name);
      if (currentClass != null) {
        ctx.currentClass = currentClass;
      }
    }
    
    if (node.associatedClass != null && ctx.currentClass == null) {
      Type associatedClass = findTypeByName(node.associatedClass);
      if (associatedClass != null) {
        ctx.currentClass = associatedClass;
      }
    }
    boolean unsafeContext = node.isUnsafe || (ctx.currentClass != null && ctx.currentClass.isUnsafe);
    ctx.setUnsafeExecutionContext(unsafeContext);
    
    visitor.pushContext(ctx);
    Object result = null;
    boolean hasSlots = node.returnSlots != null && !node.returnSlots.isEmpty();

    try {
      if (node.body != null) {
        for (Stmt stmt : node.body) {
          result = visitor.visit((Base) stmt);
          if (hasSlots && shouldReturnEarly(slotValues, ctx.slotsInCurrentPath)) {
            break;
          }
        }
      }
    } catch (EarlyFinException e) {
      // Normal fin
    } catch (ProgramError e) {
      throw e;
    } catch (Exception e) {
      throw new InternalError("Method execution failed: " + node.methodName, e);
    } finally {
      visitor.popContext();
    }
    
    DebugSystem.methodExit(node.methodName, slotValues);
    return hasSlots ? slotValues : result;
  }

@SuppressWarnings("unchecked")
public Object evalMethodCall(
    MethodCall call, ObjectInstance obj, Map<String, Object> locals, Method methodParam) {
    
    if (call == null) {
        throw new InternalError("evalMethodCall called with null call");
    }
    if (locals == null) {
        throw new InternalError("evalMethodCall called with null locals");
    }
    
    if (obj == null && globalRegistry.isGlobal(call.name)) {
        return globalRegistry.executeGlobal(call.name, (List<Object>)(List<?>)call.arguments);
    }
    
    Method method = methodParam;
    
    if (method == null) {
        if (obj != null && obj.type != null) {
            ExecutionContext searchCtx = new ExecutionContext(obj, locals, null, null, typeSystem);
            method = constructorResolver.findMethodInHierarchy(obj.type, call.name, searchCtx);
        }
        
        if (method == null) {
            String qName = call.qualifiedName != null ? call.qualifiedName : call.name;
            method = resolveImportedMethod(qName);
        }
    }
    
    if (method == null) {
        if (globalRegistry.isGlobal(call.name)) {
            return globalRegistry.executeGlobal(call.name, (List<Object>)(List<?>)call.arguments);
        }
        throw new ProgramError("Method not found: " + call.name);
    }

    ExecutionContext callerCtx = ExecutionContext.getCurrentContext();
    if (method.isUnsafe && !isUnsafeExecutionContext(callerCtx) && !ExecutionContext.isUnsafeCommitAllowed()) {
        throw new ProgramError(
            "Unsafe method '" + method.methodName + "' cannot be called in a safe context. Use safe("
                + call.name
                + "(...)).");
    }
    
    boolean hasSingleSlot = method.returnSlots != null && method.returnSlots.size() == 1;
    if (call.slotNames.isEmpty() && hasSingleSlot && !call.isSingleSlotCall) {
        call.isSingleSlotCall = true;
        call.slotNames.add(method.returnSlots.get(0).name);
    }
    
    if (method.isBuiltin) {
        return handleBuiltinMethod(method, call);
    }

    Map<String, Object> methodLocals = new HashMap<String, Object>();
    Map<String, String> methodLocalTypes = new HashMap<String, String>();

    int argCount = call.arguments != null ? call.arguments.size() : 0;
    int paramCount = method.parameters != null ? method.parameters.size() : 0;

    for (int i = 0; i < paramCount; i++) {
        Param param = method.parameters.get(i);
        Object argValue = null;

        if (i < argCount) {
            Expr argExpr = call.arguments.get(i);

            if (argExpr instanceof Identifier && "_".equals(((Identifier) argExpr).name)) {
                if (param.hasDefaultValue) {
                    ExecutionContext defaultCtx = new ExecutionContext(obj, locals, null, null, typeSystem);
                    visitor.pushContext(defaultCtx);
                    try {
                        argValue = visitor.visit((Base) param.defaultValue);
                    } finally {
                        visitor.popContext();
                    }
                } else {
                    throw new ProgramError(
                        "Parameter '" + param.name + "' has no default value and cannot be skipped with '_'");
                }
            } else {
                ExecutionContext savedCtx = null;
                if (!visitor.isContextStackEmpty()) {
                    savedCtx = visitor.getCurrentContext();
                }
                
                ExecutionContext argCtx = new ExecutionContext(obj, locals, null, null, typeSystem);
                visitor.pushContext(argCtx);
                try {
                    argValue = visitor.visit((Base) argExpr);
                } finally {
                    visitor.popContext();
                    if (savedCtx != null) {
                        visitor.pushContext(savedCtx);
                    }
                }
            }
        } else {
            if (param.hasDefaultValue) {
                ExecutionContext defaultCtx = new ExecutionContext(obj, locals, null, null, typeSystem);
                visitor.pushContext(defaultCtx);
                try {
                    argValue = visitor.visit((Base) param.defaultValue);
                } finally {
                    visitor.popContext();
                }

                if (!typeSystem.validateType(param.type, argValue)) {
                    throw new ProgramError(
                        "Default value for parameter '" + param.name + 
                        "' returns wrong type. Expected " + param.type + 
                        ", got: " + typeSystem.getConcreteType(argValue));
                }
            } else {
                throw new ProgramError(
                    "Missing argument for parameter '" + param.name + 
                    "'. Expected " + paramCount + " arguments, got " + argCount);
            }
        }

        String paramType = param.type;

        if (!typeSystem.validateType(paramType, argValue)) {
            if (paramType.equals(TEXT.toString())) {
                argValue = typeSystem.convertType(argValue, paramType);
            } else {
                throw new ProgramError(
                    "Argument type mismatch for parameter " + param.name + 
                    ". Expected " + paramType + ", got: " + typeSystem.getConcreteType(argValue));
            }
        }

        argValue = typeSystem.normalizeForDeclaredType(paramType, argValue);

        // FIXED: Use bitmask constructor for union types
        if (paramType != null && paramType.indexOf('|') >= 0) {
            int activeMask = typeSystem.getConcreteMask(typeSystem.unwrap(argValue));
            int declaredMask = TypeHandler.parseTypeMask(paramType);
            argValue = new TypeHandler.Value(argValue, activeMask, declaredMask);
        }

        methodLocals.put(param.name, argValue);
        methodLocalTypes.put(param.name, paramType);
    }

    if (argCount > paramCount) {
        throw new ProgramError(
            "Too many arguments: expected " + paramCount + ", got " + argCount);
    }

    Map<String, Object> slotValues = new LinkedHashMap<String, Object>();
    Map<String, String> slotTypes = new LinkedHashMap<String, String>();
    if (method.returnSlots != null) {
        for (Slot s : method.returnSlots) {
            slotValues.put(s.name, null);
            slotTypes.put(s.name, s.type);
        }
    }

    ExecutionContext ctx = new ExecutionContext(obj, methodLocals, slotValues, slotTypes, typeSystem);
    ctx.currentMethodName = call.name;
    
    for (Map.Entry<String, String> entry : methodLocalTypes.entrySet()) {
        ctx.setVariableType(entry.getKey(), entry.getValue());
    }
    
    ctx.objectInstance = obj;
    
    if (method.associatedClass != null) {
        Type classType = findTypeByName(method.associatedClass);
        if (classType != null) {
            ctx.currentClass = classType;
        }
    }
    
    if (obj != null && obj.type != null && ctx.currentClass == null) {
        Type classType = findTypeByName(obj.type.name);
        if (classType != null) {
            ctx.currentClass = classType;
        }
    }
    boolean unsafeContext = method.isUnsafe || (ctx.currentClass != null && ctx.currentClass.isUnsafe);
    ctx.setUnsafeExecutionContext(unsafeContext);

    visitor.pushContext(ctx);
    boolean calledMethodHasSlots = method.returnSlots != null && !method.returnSlots.isEmpty();
    Object methodResult = null;

    try {
        if (method.body != null) {
            for (Stmt stmt : method.body) {
                visitor.visit((Base) stmt);
                
                if (calledMethodHasSlots && shouldReturnEarly(slotValues, ctx.slotsInCurrentPath)) {
                    break;
                }
            }
        }
    } catch (EarlyFinException e) {
    } catch (ProgramError e) {
        throw e;
    } catch (Exception e) {
        throw new InternalError("Method call execution failed: " + call.name, e);
    } finally {
        visitor.popContext();
    }

    Object result = calledMethodHasSlots ? slotValues : methodResult;

    if (call.slotNames != null && !call.slotNames.isEmpty() && call.isSingleSlotCall) {
        if (result instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) result;
            String slotName = call.slotNames.get(0);
            return map.get(slotName);
        }
    }

    return result;
}

  @SuppressWarnings("unchecked")
  public Object handleBuiltinMethod(Method node, MethodCall call) {
    if (node == null) {
      throw new InternalError("handleBuiltinMethod called with null node");
    }
    if (call == null) {
      throw new InternalError("handleBuiltinMethod called with null call");
    }
    return builtinRegistry.executeBuiltin(node.methodName, (List<Object>)call);
  }

  private Method resolveImportedMethod(String qualifiedMethodName) {
    if (qualifiedMethodName == null || qualifiedMethodName.isEmpty()) {
      throw new InternalError("resolveImportedMethod called with null/empty name");
    }
    
    try {
      Method node = importResolver.findMethod(qualifiedMethodName);
      
      if (node != null) {
        return node;
      }
      
      if (qualifiedMethodName.contains(".")) {
        String[] parts = qualifiedMethodName.split("\\.");
        if (parts.length >= 2) {
          String className = parts[0];
          String methodName = parts[1];
          
          Type type = importResolver.findType(className);
          if (type != null) {
            ExecutionContext searchCtx = new ExecutionContext(null, new HashMap<String, Object>(), null, null, typeSystem);
            return constructorResolver.findMethodInHierarchy(type, methodName, searchCtx);
          }
        }
      }
      
      return null;
    } catch (ProgramError e) {
      DebugSystem.debug("INTERPRETER", "Method not found in imports: " + qualifiedMethodName);
      return null;
    } catch (Exception e) {
      throw new InternalError("Failed to resolve imported method: " + qualifiedMethodName, e);
    }
  }

  private Type findTypeByName(String className) {
    if (className == null || className.isEmpty()) {
      throw new InternalError("findTypeByName called with null/empty className");
    }
    
    Program currentProgram = getCurrentProgram();
    if (currentProgram != null && currentProgram.unit != null && currentProgram.unit.types != null) {
      for (Type t : currentProgram.unit.types) {
        if (t.name.equals(className)) {
          return t;
        }
      }
    }
    
    try {
      Type type = importResolver.findType(className);
      return type;
    } catch (ProgramError e) {
      DebugSystem.debug("INTERPRETER", "Type not found in imports (may be local): " + className);
      return null;
    }
  }

  private boolean isUnsafeExecutionContext(ExecutionContext ctx) {
    if (ctx == null) return false;
    if (ctx.currentClass != null && ctx.currentClass.isUnsafe) {
      return true;
    }
    if (ctx.currentMethodName == null || ctx.currentMethodName.isEmpty()) {
      return false;
    }
    Type searchType = ctx.currentClass;
    if (searchType == null && ctx.objectInstance != null) {
      searchType = ctx.objectInstance.type;
    }
    if (searchType == null) return false;
    Method currentMethod = constructorResolver.findMethodInHierarchy(searchType, ctx.currentMethodName, ctx);
    return currentMethod != null && currentMethod.isUnsafe;
  }
  
  public void clearAllCaches() {
    importResolver.clearCache();
    constructorResolver.clearCaches();
    DebugSystem.info("INTERPRETER", "All caches cleared");
  }
}