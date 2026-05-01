package cod.semantic;

import cod.ast.ASTFactory;
import cod.ast.node.*;
import cod.error.InternalError;
import cod.error.ProgramError;
import cod.lexer.*;
import static cod.lexer.TokenType.*;
import static cod.lexer.TokenType.Keyword.*;
import static cod.lexer.TokenType.Symbol.*;
import cod.parser.MainParser;
import cod.debug.DebugSystem;
import cod.interpreter.Index;
import cod.ir.IRManager;

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ImportResolver {
    // The new layout uses src/main/cod/demo/src/main with internal as a sibling of demo.
    private static final String DEMO_DIR_NAME = "demo";
    private static final Pattern SAFE_UNIT_NAME_PATTERN =
        Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Map<String, String> STANDARD_UNIT_PATH_OVERRIDES = createStandardUnitPathOverrides();
    private static final int IMPORT_NAME_CACHE_LIMIT = 4096;
    private static final int TYPE_CACHE_LIMIT = 4096;
    private static final int INDEX_CACHE_LIMIT = 1024;
    private static final int LOADED_TYPES_CACHE_LIMIT = 4096;
    private static final int FILE_CACHE_LIMIT = 2048;
    private static final int FILE_METADATA_CACHE_LIMIT = 4096;
    private final Object policyLookupLock = new Object();

    private Map<String, Program> importedUnits = new HashMap<String, Program>();
    private Map<String, Program> loadedPrograms = new HashMap<String, Program>();
    private Map<String, Program> preloadedImports = new HashMap<String, Program>();
    private Set<String> registeredImports = new HashSet<String>();
    private List<String> methodImportSpecs = new ArrayList<String>();
    private Set<String> wildcardEverythingUnits = new HashSet<String>();
    private Set<String> wildcardClassUnits = new HashSet<String>();
    private Map<String, String> explicitFieldImports = new HashMap<String, String>();
    private List<String> importPaths = new ArrayList<String>();
    private Map<String, String> packageBroadcasts = new HashMap<String, String>();
    
    // Concurrent map keeps lock-free fast-path reads in findPolicy/get/register paths.
    private Map<String, Policy> importedPolicies = new ConcurrentHashMap<String, Policy>();
    private Map<String, String> policyToUnitMap = new HashMap<String, String>();
    
    // Import name cache for O(1) lookups
    private Map<String, String> importNameCache = createBoundedMap(IMPORT_NAME_CACHE_LIMIT);
    
    // Type cache for O(1) type lookups
    private Map<String, Type> typeCache = createBoundedMap(TYPE_CACHE_LIMIT);
    
    // Index cache for O(1) class lookups
    private Map<String, Index> indexCache = createBoundedMap(INDEX_CACHE_LIMIT);
    
    // IR manager for .codb files
    private IRManager irManager;
    
    // Cache for loaded TypeNodes (bytecode or parsed)
    private Map<String, Type> loadedTypes = createBoundedMap(LOADED_TYPES_CACHE_LIMIT);
    // Filesystem result cache
    private Map<String, CachedFileResult> fileCache = createBoundedMap(FILE_CACHE_LIMIT);
    
    // File metadata cache (exists, isDirectory, lastModified)
    private Map<String, FileMetadata> fileMetadataCache = createBoundedMap(FILE_METADATA_CACHE_LIMIT);
    
    // Cache hit/miss counters for debugging
    private int fileCacheHits = 0;
    private int fileCacheMisses = 0;
    private int metadataCacheHits = 0;
    private int metadataCacheMisses = 0;
    private int indexCacheHits = 0;
    private int indexCacheMisses = 0;
    private int bytecodeCacheHits = 0;
    private int bytecodeCacheMisses = 0;
    
    // Current file location for relative import resolution
    private String srcMainRoot;
    private String demoSiblingRoot;
    private String currentFileDirectory;
    private String projectRoot;
    
    // Cache entry with timestamp
    private static class CachedFileResult {
        final Program program;
        final long lastModified;
        
        CachedFileResult(Program program, long lastModified) {
            this.program = program;
            this.lastModified = lastModified;
        }
        
        boolean isValid(File file) {
            return file.exists() && file.lastModified() == lastModified;
        }
    }
    
    // File metadata cache
    private static class FileMetadata {
        final boolean exists;
        final boolean isDirectory;
        final long lastModified;
        
        FileMetadata(boolean exists, boolean isDirectory, long lastModified) {
            this.exists = exists;
            this.isDirectory = isDirectory;
            this.lastModified = lastModified;
        }
        
        boolean isValid(File file) {
            return file.exists() == exists && 
                   file.isDirectory() == isDirectory && 
                   file.lastModified() == lastModified;
        }
    }

    private static <K, V> Map<K, V> createBoundedMap(final int maxSize) {
        Map<K, V> lru = new LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                // removeEldestEntry is evaluated after insertion, so '>' enforces maxSize.
                return size() > maxSize;
            }
        };
        return Collections.synchronizedMap(lru);
    }
    
    public ImportResolver() {
        importPaths.add("src/main");
        DebugSystem.debug("IMPORTS", "Initialized with import paths: " + importPaths);
    }
    
    /**
     * Set the current file directory and automatically find the src/main/ root
     */
    public void setCurrentFileDirectory(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            DebugSystem.debug("IMPORTS", "setCurrentFileDirectory called with null/empty path");
            return;
        }
        
        DebugSystem.debug("IMPORTS", "=== setCurrentFileDirectory CALLED ===");
        DebugSystem.debug("IMPORTS", "filePath: " + filePath);
        
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        
        if (parentDir == null || !parentDir.exists()) {
            DebugSystem.debug("IMPORTS", "Parent directory does not exist for: " + filePath);
            return;
        }
        
        this.currentFileDirectory = parentDir.getAbsolutePath();
        DebugSystem.debug("IMPORTS", "Current file directory set to: " + currentFileDirectory);
        
        // Navigate up to find the src/main/ directory structure
        File searchDir = parentDir;
        this.srcMainRoot = null;
        
        while (searchDir != null) {
            // Check if this directory IS src/main/
            if (searchDir.getName().equals("main") && 
                searchDir.getParentFile() != null && 
                searchDir.getParentFile().getName().equals("src")) {
                
                this.srcMainRoot = searchDir.getAbsolutePath();
                DebugSystem.debug("IMPORTS", "Found src/main/ root (directory itself): " + srcMainRoot);
                break;
            }
            
            // Check if src/main/ is a subdirectory
            File srcMain = new File(searchDir, "src/main");
            if (srcMain.exists() && srcMain.isDirectory()) {
                this.srcMainRoot = srcMain.getAbsolutePath();
                DebugSystem.debug("IMPORTS", "Found src/main/ at: " + srcMainRoot);
                break;
            }
            
            // Move up one level
            searchDir = searchDir.getParentFile();
        }
        
        // If we found src/main/, add it to import paths and set project root
        if (srcMainRoot != null) {
            // Add the src/main/ root to import paths if not present
            if (!importPaths.contains(srcMainRoot)) {
                importPaths.add(0, srcMainRoot);
                DebugSystem.debug("IMPORTS", "Added srcMainRoot to import paths: " + srcMainRoot);
            }

            this.demoSiblingRoot = null;
            File srcMainDir = new File(srcMainRoot);
            File srcDir = srcMainDir.getParentFile();
            File srcHolder = srcDir != null ? srcDir.getParentFile() : null;
            if (srcHolder != null && DEMO_DIR_NAME.equals(srcHolder.getName())) {
                File siblingRoot = srcHolder.getParentFile();
                if (siblingRoot != null) {
                    this.demoSiblingRoot = siblingRoot.getAbsolutePath();
                    if (!importPaths.contains(this.demoSiblingRoot)) {
                        importPaths.add(1, this.demoSiblingRoot);
                        DebugSystem.debug("IMPORTS", "Added demo sibling root to import paths: " + this.demoSiblingRoot);
                    }
                }
            }
            
            // Set the project root for Index class (for src/bin/project.codc index location)
            Index.setProjectRoot(srcMainRoot);
            DebugSystem.debug("IMPORTS", "Set Index project root from: " + srcMainRoot);
            
            // Calculate and store project root for IR manager
            this.projectRoot = Index.getProjectRoot();
            if (this.projectRoot != null) {
                this.irManager = new IRManager(this.projectRoot);
                DebugSystem.debug("IMPORTS", "Initialized IRManager with root: " + this.projectRoot);
            }
            
        } else {
            DebugSystem.debug("IMPORTS", "Could not find src/main/ structure, imports will be relative to: " + currentFileDirectory);
        }
        
        DebugSystem.debug("IMPORTS", "Final configuration - srcMainRoot: " + srcMainRoot + 
                         ", currentFileDirectory: " + currentFileDirectory +
                         ", importPaths: " + importPaths);
    }
    
    public String getCurrentFileDirectory() {
        return currentFileDirectory;
    }
    
    public String getSrcMainRoot() {
        return srcMainRoot;
    }
    
    public String getProjectRoot() {
        return projectRoot;
    }
    
    /**
     * Get absolute path for a unit
     */
    private String getUnitPath(String unitName) {
        validateUnitName(unitName);
        String fallbackPath = buildUnitPath(srcMainRoot, unitName);
        if (fallbackPath == null) {
            fallbackPath = buildUnitPath("src/main", unitName);
        }

        if (fallbackPath != null) {
            File primaryDir = new File(fallbackPath);
            if (primaryDir.exists() && primaryDir.isDirectory()) {
                return fallbackPath;
            }
        }

        for (String basePath : importPaths) {
            if (basePath == null || basePath.isEmpty()) {
                continue;
            }
            String candidate = buildUnitPath(basePath, unitName);
            File dir = new File(candidate);
            if (dir.exists() && dir.isDirectory()) {
                return candidate;
            }

            String overrideDir = STANDARD_UNIT_PATH_OVERRIDES.get(unitName);
            if (overrideDir != null) {
                String overrideCandidate = new File(basePath, overrideDir).getPath();
                File override = new File(overrideCandidate);
                if (override.exists() && override.isDirectory()) {
                    return overrideCandidate;
                }
            }
        }

        return fallbackPath;
    }

    private static String buildUnitPath(String basePath, String unitName) {
        if (basePath == null || basePath.isEmpty()) {
            return null;
        }
        return new File(basePath, toUnitDirectoryPath(unitName)).getPath();
    }

    private static String toUnitDirectoryPath(String unitName) {
        return unitName.replace('.', '/');
    }

    private static Map<String, String> createStandardUnitPathOverrides() {
        Map<String, String> overrides = new HashMap<String, String>();
        overrides.put("math", "std/math");
        overrides.put("scimath", "std/scimath");
        overrides.put("scimath.distribution", "std/scimath/distribution");
        return Collections.unmodifiableMap(overrides);
    }

    private static String toModuleMainFileName(String unitName) {
        if (unitName == null || unitName.isEmpty()) {
            return null;
        }
        int lastDot = unitName.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? unitName.substring(lastDot + 1) : unitName;
        if (simpleName.isEmpty()) {
            return null;
        }
        return Character.toUpperCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private void validateUnitName(String unitName) {
        if (unitName == null || unitName.isEmpty()) {
            throw new IllegalArgumentException("Unit name cannot be null/empty");
        }
        if (!SAFE_UNIT_NAME_PATTERN.matcher(unitName).matches()) {
            throw new ProgramError("Invalid import unit name: " + unitName);
        }
    }

    private boolean isMatchingProgramUnit(Program program, String expectedUnitName) {
        if (program == null || program.unit == null || program.unit.name == null) {
            return false;
        }
        return expectedUnitName.equals(program.unit.name);
    }
    
    /**
     * Generate index by scanning unit directory
     */

    private FileMetadata getFileMetadata(File file) {
        String path = file.getAbsolutePath();
        
        if (fileMetadataCache.containsKey(path)) {
            FileMetadata cached = fileMetadataCache.get(path);
            if (cached.isValid(file)) {
                metadataCacheHits++;
                return cached;
            } else {
                fileMetadataCache.remove(path);
            }
        }
        
        metadataCacheMisses++;
        
        boolean exists = file.exists();
        boolean isDirectory = exists && file.isDirectory();
        long lastModified = exists ? file.lastModified() : 0;
        
        FileMetadata metadata = new FileMetadata(exists, isDirectory, lastModified);
        fileMetadataCache.put(path, metadata);
        
        return metadata;
    }
    
    private Program loadImportFromFileCached(String filePath) throws Exception {
        if (filePath == null || filePath.isEmpty()) {
            throw new InternalError("loadImportFromFileCached called with null/empty path");
        }
        
        File file = new File(filePath);
        
        FileMetadata metadata = getFileMetadata(file);
        if (!metadata.exists) {
            return null;
        }
        if (!metadata.isDirectory) {
            if (fileCache.containsKey(filePath)) {
                CachedFileResult cached = fileCache.get(filePath);
                if (cached.isValid(file)) {
                    fileCacheHits++;
                    DebugSystem.debug("IMPORTS_CACHE", "File cache hit: " + filePath);
                    return cached.program;
                } else {
                    fileCache.remove(filePath);
                    DebugSystem.debug("IMPORTS_CACHE", "File cache stale: " + filePath);
                }
            }
            
            fileCacheMisses++;
            DebugSystem.debug("IMPORTS_CACHE", "File cache miss: " + filePath);
            
            Program program = loadImportFromFile(filePath);
            if (program != null) {
                fileCache.put(filePath, new CachedFileResult(program, metadata.lastModified));
            }
            return program;
        }
        
        return null;
    }
    
    public void registerBroadcast(String packageName, String mainClassName) {
        if (packageName == null || packageName.isEmpty()) {
            throw new InternalError("registerBroadcast called with null/empty packageName");
        }
        if (mainClassName == null || mainClassName.isEmpty()) {
            throw new InternalError("registerBroadcast called with null/empty mainClassName");
        }
        
        if (packageBroadcasts.containsKey(packageName)) {
            String existing = packageBroadcasts.get(packageName);
            if (!existing.equals(mainClassName)) {
                throw new ProgramError(
                    "Broadcast conflict in package '" + packageName + "':\n" +
                    "  Already declared: (main: " + existing + ")\n" +
                    "  New declaration: (main: " + mainClassName + ")\n" +
                    "Only one broadcast per package allowed."
                );
            }
        } else {
            packageBroadcasts.put(packageName, mainClassName);
            DebugSystem.debug("BROADCAST", "Registered broadcast for package '" + 
                packageName + "': (main: " + mainClassName + ")");
        }
    }
    
    public String getBroadcast(String packageName) {
        return packageBroadcasts.get(packageName);
    }
    
    public void clearBroadcasts() {
        packageBroadcasts.clear();
    }
    
    public Policy findPolicy(String qualifiedPolicyName) {
        if (qualifiedPolicyName == null || qualifiedPolicyName.isEmpty()) {
            throw new InternalError("findPolicy called with null/empty name");
        }
        
        DebugSystem.debug("POLICY", "findPolicy called for: " + qualifiedPolicyName);
        
        Policy cached = importedPolicies.get(qualifiedPolicyName);
        if (cached != null) {
            DebugSystem.debug("POLICY", "Policy already loaded: " + qualifiedPolicyName);
            return cached;
        }

        synchronized (policyLookupLock) {
            cached = importedPolicies.get(qualifiedPolicyName);
            if (cached != null) {
                DebugSystem.debug("POLICY", "Policy already loaded (post-lock): " + qualifiedPolicyName);
                return cached;
            }

            int lastDot = qualifiedPolicyName.lastIndexOf('.');
            String policyName;
            String importName;
            
            if (lastDot == -1) {
                policyName = qualifiedPolicyName;
                importName = qualifiedPolicyName;
            } else {
                policyName = qualifiedPolicyName.substring(lastDot + 1);
                importName = qualifiedPolicyName.substring(0, lastDot);
            }
            
            DebugSystem.debug("POLICY", "Import part: '" + importName + "', policy: '" + policyName + "'");
            
            String actualImportName = findMatchingImportCached(importName);
            
            if (!loadedPrograms.containsKey(actualImportName)) {
                try {
                    DebugSystem.debug("POLICY", "Import not loaded, attempting to load: " + actualImportName);
                    Program program = resolveImportAsProgram(actualImportName);
                    if (program == null) {
                        throw new ProgramError("Failed to load import: " + actualImportName);
                    }
                } catch (ProgramError e) {
                    throw e;
                } catch (Exception e) {
                    throw new InternalError("Unexpected error loading import: " + actualImportName, e);
                }
            }

            Program program = loadedPrograms.get(actualImportName);
            if (program != null && program.unit != null && program.unit.policies != null) {
                for (Policy policy : program.unit.policies) {
                    if (policy.name.equals(policyName)) {
                        DebugSystem.debug("POLICY", "Found policy: " + policy.name);
                        importedPolicies.put(qualifiedPolicyName, policy);
                        policyToUnitMap.put(policyName, program.unit.name);
                        return policy;
                    }
                }
            }
            
            throw new ProgramError(
                "Policy not found: '" + qualifiedPolicyName + "'\n" +
                "Available policies in import '" + actualImportName + "': " + 
                getPolicyNames(program)
            );
        }
    }
    
    private String getPolicyNames(Program program) {
        if (program == null || program.unit == null || program.unit.policies == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < program.unit.policies.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(program.unit.policies.get(i).name);
        }
        return sb.toString();
    }
    
    public void registerPolicy(String qualifiedName, Policy policy) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            throw new InternalError("registerPolicy called with null/empty qualifiedName");
        }
        if (policy == null) {
            throw new InternalError("registerPolicy called with null policy");
        }
        
        importedPolicies.put(qualifiedName, policy);
        DebugSystem.debug("POLICY", "Registered policy: " + qualifiedName);
        
        if (!importedPolicies.containsKey(policy.name)) {
            importedPolicies.put(policy.name, policy);
        }
    }
    
    public String getPolicyUnit(String policyName) {
        return policyToUnitMap.get(policyName);
    }
    
    public Set<String> getRegisteredPolicies() {
        return importedPolicies.keySet();
    }

    public void addImportPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new InternalError("addImportPath called with null/empty path");
        }
        importPaths.add(path);
        DebugSystem.debug("IMPORTS", "Added import path: " + path);
    }
    
    public void registerImport(String importName) {
        if (importName == null || importName.isEmpty()) {
            throw new InternalError("registerImport called with null/empty importName");
        }

        if (importName.contains("(")) {
            if (!methodImportSpecs.contains(importName)) {
                methodImportSpecs.add(importName);
            }
            DebugSystem.debug("IMPORTS", "Registered method import spec: " + importName);
            return;
        }

        if (importName.endsWith(".**")) {
            String unitName = importName.substring(0, importName.length() - 3);
            wildcardEverythingUnits.add(unitName);
            DebugSystem.debug("IMPORTS", "Registered everything wildcard import: " + importName);
            return;
        }

        if (importName.endsWith(".*")) {
            String unitName = importName.substring(0, importName.length() - 2);
            wildcardClassUnits.add(unitName);
            DebugSystem.debug("IMPORTS", "Registered class wildcard import: " + importName);
            return;
        }

        if (!importName.contains(".")) {
            wildcardEverythingUnits.add(importName);
            if (!registeredImports.contains(importName)) {
                registeredImports.add(importName);
                cacheImportName(importName);
            }
            DebugSystem.debug("IMPORTS", "Registered unit import as everything wildcard: " + importName);
            return;
        }

        int lastDot = importName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < importName.length() - 1) {
            String alias = importName.substring(lastDot + 1);
            explicitFieldImports.put(alias, importName);
        }

        if (!registeredImports.contains(importName)) {
            registeredImports.add(importName);
            cacheImportName(importName);
            DebugSystem.debug("IMPORTS", "Registered import (lazy): " + importName);
        }
    }
    
    private void cacheImportName(String importName) {
        String[] parts = importName.split("\\.");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            if (!importNameCache.containsKey(lastPart)) {
                importNameCache.put(lastPart, importName);
            }
            
            StringBuilder partial = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) partial.append(".");
                partial.append(parts[i]);
                String key = partial.toString();
                if (!importNameCache.containsKey(key)) {
                    importNameCache.put(key, importName);
                }
            }
        }
    }
    
    private String findMatchingImportCached(String calledImport) {
        if (importNameCache.containsKey(calledImport)) {
            String cached = importNameCache.get(calledImport);
            DebugSystem.debug("IMPORTS", "Cache hit for import: " + calledImport + " -> " + cached);
            return cached;
        }
        
        for (String loadedImport : loadedPrograms.keySet()) {
            if (loadedImport.endsWith("." + calledImport) || loadedImport.equals(calledImport)) {
                importNameCache.put(calledImport, loadedImport);
                return loadedImport;
            }
        }
        
        for (String registeredImport : registeredImports) {
            if (registeredImport.endsWith("." + calledImport) || registeredImport.equals(calledImport)) {
                importNameCache.put(calledImport, registeredImport);
                return registeredImport;
            }
        }
        
        return calledImport;
    }

    /**
     * Resolve import and return Type directly
     */
    public Type resolveImport(String importName) throws Exception {
    if (importName == null || importName.isEmpty()) {
        throw new InternalError("resolveImport called with null/empty importName");
    }
    
    DebugSystem.debug("IMPORTS", "=== RESOLVING IMPORT PROGRESSIVELY: " + importName + " ===");
    
    // Check cache first
    if (loadedTypes.containsKey(importName)) {
        DebugSystem.debug("IMPORTS", "Type already loaded: " + importName);
        return loadedTypes.get(importName);
    }
    
    int lastDot = importName.lastIndexOf('.');
    if (lastDot <= 0 || lastDot >= importName.length() - 1) {
        throw new ProgramError("Invalid import format: '" + importName + "'");
    }
    
    String unitName = importName.substring(0, lastDot);
    String className = importName.substring(lastDot + 1);
    
    DebugSystem.debug("IMPORTS", "Unit: " + unitName + ", Class: " + className);
    
    // Try to find which file contains this class using index
    Index index = Index.load(unitName);
    String fileName = null;
    
    if (index != null) {
        fileName = index.getFile(className);
        DebugSystem.debug("IMPORTS", "Index suggests file: " + fileName);
    }
    
    // If index doesn't have it, we need to find the file (lazy scan)
    if (fileName == null) {
        String unitPath = getUnitPath(unitName);
        if (unitPath != null) {
            File dir = new File(unitPath);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File d, String name) {
                        return name.endsWith(".cod");
                    }
                });
                
                if (files != null) {
                    for (File file : files) {
                        // Quick check: does filename match class name?
                        String baseName = file.getName().replace(".cod", "");
                        if (baseName.equals(className)) {
                            fileName = file.getName();
                            break;
                        }
                    }
                    
                    // If still not found, scan lazily
                    if (fileName == null) {
                        for (File file : files) {
                            // Parse just the class names from this file (minimal parse)
                            List<String> classesInFile = extractClassNamesFast(file);
                            if (classesInFile.contains(className)) {
                                fileName = file.getName();
                                // Update index for future lookups
                                if (index == null) {
                                    index = new Index(unitName);
                                }
                                for (String cls : classesInFile) {
                                    index.add(cls, fileName);
                                }
                                index.save();
                                break;
                            }
                        }
                    }
                }
            }
        }
        
        if (fileName == null) {
            throw new ProgramError("Class '" + className + "' not found in unit '" + unitName + "'");
        }
    }
    
    // Parse only the needed file
    String filePath = getUnitPath(unitName) + "/" + fileName;
    Program program = loadImportFromFileCached(filePath);
    
    if (program != null && program.unit != null) {
        for (Type type : program.unit.types) {
            String qualifiedName = program.unit.name + "." + type.name;
            loadedTypes.put(qualifiedName, type);
            loadedTypes.put(type.name, type);  // Also cache by simple name
        }
    }
    
    Type result = loadedTypes.get(importName);
    if (result == null) {
        throw new ProgramError("Type not found after parsing: " + importName);
    }
    
    return result;
}
    
    /**
     * Legacy method for Program resolution (for policies, etc.)
     */
    public Program resolveImportAsProgram(String importName) throws Exception {
        if (importName == null || importName.isEmpty()) {
            throw new InternalError("resolveImportAsProgram called with null/empty importName");
        }
        
        // Check if already loaded
        if (loadedPrograms.containsKey(importName)) {
            return loadedPrograms.get(importName);
        }
        
        // Check preloaded imports
        if (preloadedImports.containsKey(importName)) {
            Program program = preloadedImports.get(importName);
            if (program != null) {
                loadedPrograms.put(importName, program);
                importedUnits.put(importName, program);
                cacheImportName(importName);
                registerPoliciesAndBroadcast(program, importName);
                return program;
            }
        }
        
        // Resolve as Type first, then wrap
        Type type = resolveImport(importName);
        if (type != null) {
            Program program = ASTFactory.createProgram();
            program.unit = ASTFactory.createUnit("default", null);
            program.unit.types.add(type);
            loadedPrograms.put(importName, program);
            return program;
        }
        
        return null;
    }
    
    private void registerPoliciesAndBroadcast(Program program, String importName) {
        if (program == null || program.unit == null) {
            throw new InternalError("registerPoliciesAndBroadcast called with null program/unit");
        }
        
        if (program.unit.policies != null) {
            for (Policy policy : program.unit.policies) {
                String qualifiedName = program.unit.name + "." + policy.name;
                registerPolicy(qualifiedName, policy);
                policyToUnitMap.put(policy.name, program.unit.name);
                DebugSystem.debug("IMPORTS", "Registered policy from import: " + qualifiedName);
            }
        }
        
        if (program.unit.mainClassName != null && !program.unit.mainClassName.isEmpty()) {
            String packageName = program.unit.name;
            String mainClassName = program.unit.mainClassName;
            
            DebugSystem.debug("BROADCAST", "Registering broadcast from import '" + 
                importName + "': package '" + packageName + "' (main: " + mainClassName + ")");
            
            registerBroadcast(packageName, mainClassName);
        }
    }
    
    private Program loadImportFromFile(String filePath) throws Exception {
        if (filePath == null || filePath.isEmpty()) {
            throw new InternalError("loadImportFromFile called with null/empty path");
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        if (!file.isFile()) {
            throw new ProgramError("Import path is not a file: " + filePath);
        }
        
        DebugSystem.debug("IMPORTS", "Loading import from file: " + filePath);
        
        try {
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            
            DebugSystem.debug("IMPORTS", "File content length: " + content.length() + " characters");
            
            MainLexer lexer = new MainLexer(content.toString());
            List<Token> tokens = lexer.tokenize();
            
            DebugSystem.debug("IMPORTS", "Generated " + tokens.size() + " tokens");
            
            MainParser parser = new MainParser(tokens);
            Program program = parser.parseProgram();
            
            if (program == null) {
                throw new InternalError("Parser returned null program for: " + filePath);
            }
            
            DebugSystem.debug("IMPORTS", "Successfully parsed import file: " + filePath);
            return program;

        } catch (ProgramError e) {
            throw e;
        } catch (Exception e) {
            throw new InternalError("Failed to parse import file: " + filePath, e);
        }
    }

    public Type findType(String qualifiedTypeName) {
        if (qualifiedTypeName == null || qualifiedTypeName.isEmpty()) {
            throw new InternalError("findType called with null/empty name");
        }
        
        DebugSystem.debug("IMPORTS", "findType called for: " + qualifiedTypeName);
        
        if (typeCache.containsKey(qualifiedTypeName)) {
            DebugSystem.debug("IMPORTS", "Type cache hit: " + qualifiedTypeName);
            return typeCache.get(qualifiedTypeName);
        }
        
        int lastDot = qualifiedTypeName.lastIndexOf('.');
        if (lastDot == -1) {
            DebugSystem.debug("IMPORTS", "Simple type name, searching all imports");
            Type found = findTypeByName(qualifiedTypeName);
            if (found == null) {
                throw new ProgramError("Type not found: " + qualifiedTypeName);
            }
            typeCache.put(qualifiedTypeName, found);
            return found;
        }
        
        String typeName = qualifiedTypeName.substring(lastDot + 1);
        String importPart = qualifiedTypeName.substring(0, lastDot);
        
        DebugSystem.debug("IMPORTS", "Import part: '" + importPart + "', type: '" + typeName + "'");
        
        String actualImportName = qualifiedTypeName;
        if (typeName.isEmpty() || !Character.isUpperCase(typeName.charAt(0))) {
            actualImportName = findMatchingImportCached(importPart);
        }
        
        DebugSystem.debug("IMPORTS", "Final import to resolve: '" + actualImportName + "', type: '" + typeName + "'");
        
        if (!loadedTypes.containsKey(actualImportName)) {
            DebugSystem.debug("IMPORTS", "Import not loaded, trying to resolve: " + actualImportName);
            try {
                Type type = resolveImport(actualImportName);
                if (type == null) {
                    throw new ProgramError("Failed to resolve import: " + actualImportName);
                }
                loadedTypes.put(actualImportName, type);
            } catch (ProgramError e) {
                throw e;
            } catch (Exception e) {
                throw new InternalError("Unexpected error resolving import: " + actualImportName, e);
            }
        }
        
        return loadedTypes.get(actualImportName);
    }

    private Type findTypeByName(String typeName) {
        if (typeCache.containsKey(typeName)) {
            return typeCache.get(typeName);
        }
        
        for (Map.Entry<String, Type> entry : loadedTypes.entrySet()) {
            Type type = entry.getValue();
            if (type != null && type.name.equals(typeName)) {
                typeCache.put(typeName, type);
                return type;
            }
        }
        
        for (String importName : registeredImports) {
            if (importName.endsWith("." + typeName)) {
                try {
                    Type type = resolveImport(importName);
                    if (type != null) {
                        typeCache.put(typeName, type);
                        return type;
                    }
                } catch (Exception e) {
                    DebugSystem.debug("IMPORTS", "Failed to load " + importName + " while searching for " + typeName);
                }
            }
        }

        for (String unitName : wildcardClassUnits) {
            try {
                Type type = resolveImport(unitName + "." + typeName);
                if (type != null) {
                    typeCache.put(typeName, type);
                    return type;
                }
            } catch (Exception e) {
                // Continue searching
            }
        }
        
        for (String unitName : wildcardEverythingUnits) {
            try {
                Type type = resolveImport(unitName + "." + typeName);
                if (type != null) {
                    typeCache.put(typeName, type);
                    return type;
                }
            } catch (Exception e) {
                // Continue searching
            }
        }
        
        return null;
    }

    public void clearCache() {
        importNameCache.clear();
        typeCache.clear();
        indexCache.clear();
        fileCache.clear();
        fileMetadataCache.clear();
        loadedTypes.clear();
        explicitFieldImports.clear();
        methodImportSpecs.clear();
        wildcardEverythingUnits.clear();
        wildcardClassUnits.clear();
        if (irManager != null) {
            irManager.clearCache();
        }
        fileCacheHits = 0;
        fileCacheMisses = 0;
        metadataCacheHits = 0;
        metadataCacheMisses = 0;
        indexCacheHits = 0;
        indexCacheMisses = 0;
        bytecodeCacheHits = 0;
        bytecodeCacheMisses = 0;
        DebugSystem.debug("IMPORTS_CACHE", "All caches cleared");
    }
    
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<String, Object>();
        stats.put("importNameCache", importNameCache.size());
        stats.put("typeCache", typeCache.size());
        stats.put("indexCache", indexCache.size());
        stats.put("fileCache", fileCache.size());
        stats.put("fileMetadataCache", fileMetadataCache.size());
        stats.put("loadedTypes", loadedTypes.size());
        stats.put("fileCacheHits", fileCacheHits);
        stats.put("fileCacheMisses", fileCacheMisses);
        stats.put("metadataCacheHits", metadataCacheHits);
        stats.put("metadataCacheMisses", metadataCacheMisses);
        stats.put("indexCacheHits", indexCacheHits);
        stats.put("indexCacheMisses", indexCacheMisses);
        stats.put("bytecodeCacheHits", bytecodeCacheHits);
        stats.put("bytecodeCacheMisses", bytecodeCacheMisses);
        if (irManager != null) {
            stats.put("bytecodeStats", irManager.getCacheStats());
        }
        
        double hitRate = (fileCacheHits + metadataCacheHits + indexCacheHits + bytecodeCacheHits) / 
            (double)(fileCacheHits + fileCacheMisses + metadataCacheHits + metadataCacheMisses + 
                     indexCacheHits + indexCacheMisses + bytecodeCacheHits + bytecodeCacheMisses + 1) * 100;
        stats.put("cacheHitRate", String.format("%.1f%%", hitRate));
        
        return stats;
    }

    public Method findMethod(String qualifiedMethodName) {
        if (qualifiedMethodName == null || qualifiedMethodName.isEmpty()) {
            throw new InternalError("findMethod called with null/empty name");
        }
        
        DebugSystem.debug("IMPORTS", "findMethod called for: " + qualifiedMethodName);
        
        int lastDot = qualifiedMethodName.lastIndexOf('.');
        if (lastDot == -1) {
            Method methodBySpec = findMethodFromSpecs(qualifiedMethodName);
            if (methodBySpec != null) {
                return methodBySpec;
            }
            return null;
        }
        
        String methodName = qualifiedMethodName.substring(lastDot + 1);
        String calledImport = qualifiedMethodName.substring(0, lastDot);
        
        DebugSystem.debug("IMPORTS", "Called import part: '" + calledImport + "', method: '" + methodName + "'");
        
        String actualImportName = findMatchingImportCached(calledImport);
        
        DebugSystem.debug("IMPORTS", "Final import to resolve: '" + actualImportName + "', method: '" + methodName + "'");
        
        Type type = null;
        try {
            type = findType(actualImportName);
            if (type != null) {
                DebugSystem.debug("IMPORTS", "Searching for method '" + methodName + "' in type: " + type.name);
                for (Method method : type.methods) {
                    DebugSystem.debug("IMPORTS", "    Checking method: " + method.methodName);
                    if (method.methodName.equals(methodName)) {
                        DebugSystem.debug("IMPORTS", "    *** FOUND METHOD: " + method.methodName + " ***");
                        return method;
                    }
                }
            }
        } catch (ProgramError ignoreTypeError) {
            // Not a class import; may still be a static-module method import.
        }
        
        Method moduleMethod = findMethodInStaticModule(actualImportName, methodName);
        if (moduleMethod != null) {
            return moduleMethod;
        }
        
        throw new ProgramError(
            "Method not found: '" + qualifiedMethodName + "'\n" +
            "Available methods in import '" + actualImportName + "': " + 
            getMethodNames(type)
        );
    }
    
    private String getMethodNames(Type type) {
        if (type == null || type.methods == null || type.methods.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < type.methods.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(type.methods.get(i).methodName);
        }
        return sb.toString();
    }

    public void debugFileSystem(String importName) {
        DebugSystem.debug("FILE_SYSTEM", "=== FILE SYSTEM DEBUG for: " + importName + " ===");
        
        String dirPath = importName.replace('.', '/');
        DebugSystem.debug("FILE_SYSTEM", "Looking for unit directory: " + dirPath);
        DebugSystem.debug("FILE_SYSTEM", "Import paths: " + importPaths);
        
        for (String basePath : importPaths) {
            String fullDirPath;
            if (basePath.isEmpty()) {
                fullDirPath = dirPath;
            } else {
                fullDirPath = basePath + "/" + dirPath;
            }
            
            File dir = new File(fullDirPath);
            DebugSystem.debug("FILE_SYSTEM", "Directory: " + dir.getAbsolutePath() + 
                             " [exists: " + dir.exists() + ", isDirectory: " + dir.isDirectory() + "]");
            
            if (dir.exists() && dir.isDirectory()) {
                DebugSystem.debug("FILE_SYSTEM", "Directory contents:");
                String[] files = dir.list();
                if (files != null) {
                    for (String f : files) {
                        DebugSystem.debug("FILE_SYSTEM", "  - " + f + 
                                         " [dir: " + new File(dir, f).isDirectory() + "]");
                    }
                }
            }
            
            File file = new File(fullDirPath + ".cod");
            DebugSystem.debug("FILE_SYSTEM", "File: " + file.getAbsolutePath() + 
                             " [exists: " + file.exists() + ", isFile: " + file.isFile() + "]");
        }
        DebugSystem.debug("FILE_SYSTEM", "=== END FILE SYSTEM DEBUG ===");
    }

    public void debugImportStatus() {
        DebugSystem.debug("IMPORTS", "=== IMPORT RESOLVER STATUS ===");
        DebugSystem.debug("IMPORTS", "Import paths: " + importPaths);
        DebugSystem.debug("IMPORTS", "Loaded imports: " + importedUnits.keySet());
        DebugSystem.debug("IMPORTS", "Loaded types: " + loadedTypes.keySet());
        DebugSystem.debug("IMPORTS", "Registered imports (lazy): " + registeredImports);
        DebugSystem.debug("IMPORTS", "Registered policies: " + getRegisteredPolicies());
        DebugSystem.debug("IMPORTS", "Import name cache size: " + importNameCache.size());
        DebugSystem.debug("IMPORTS", "Type cache size: " + typeCache.size());
        DebugSystem.debug("IMPORTS", "Index cache size: " + indexCache.size());
        DebugSystem.debug("IMPORTS", "File cache size: " + fileCache.size());
        DebugSystem.debug("IMPORTS", "File metadata cache size: " + fileMetadataCache.size());
        if (irManager != null) {
            DebugSystem.debug("IMPORTS", "IR stats: " + irManager.getCacheStats());
        }
        
        Map<String, Object> stats = getCacheStats();
        DebugSystem.debug("IMPORTS", "File cache hits: " + stats.get("fileCacheHits"));
        DebugSystem.debug("IMPORTS", "File cache misses: " + stats.get("fileCacheMisses"));
        DebugSystem.debug("IMPORTS", "Metadata cache hits: " + stats.get("metadataCacheHits"));
        DebugSystem.debug("IMPORTS", "Metadata cache misses: " + stats.get("metadataCacheMisses"));
        DebugSystem.debug("IMPORTS", "Index cache hits: " + stats.get("indexCacheHits"));
        DebugSystem.debug("IMPORTS", "Index cache misses: " + stats.get("indexCacheMisses"));
        DebugSystem.debug("IMPORTS", "Bytecode cache hits: " + stats.get("bytecodeCacheHits"));
        DebugSystem.debug("IMPORTS", "Bytecode cache misses: " + stats.get("bytecodeCacheMisses"));
        DebugSystem.debug("IMPORTS", "Cache hit rate: " + stats.get("cacheHitRate"));
        DebugSystem.debug("IMPORTS", "Loaded types: " + stats.get("loadedTypes"));

        for (Map.Entry<String, Type> entry : loadedTypes.entrySet()) {
            String typeName = entry.getKey();
            Type type = entry.getValue();
            DebugSystem.debug("IMPORTS", "Type: " + typeName);
            if (type != null && type.methods != null) {
                DebugSystem.debug("IMPORTS", "  Methods: " + type.methods.size());
                for (Method method : type.methods) {
                    DebugSystem.debug("IMPORTS", "    Method: " + method.methodName);
                }
            }
        }
        
        for (Map.Entry<String, Program> entry : importedUnits.entrySet()) {
            String unitName = entry.getKey();
            Program program = entry.getValue();
            DebugSystem.debug("IMPORTS", "Unit: " + unitName);
            if (program != null && program.unit != null) {
                if (program.unit.policies != null && !program.unit.policies.isEmpty()) {
                    DebugSystem.debug("IMPORTS", "  Policies:");
                    for (Policy policy : program.unit.policies) {
                        DebugSystem.debug("IMPORTS", "    " + policy.name + 
                            (policy.composedPolicies != null && !policy.composedPolicies.isEmpty() ? 
                             " with " + policy.composedPolicies : ""));
                    }
                }
                if (program.unit.types != null) {
                    for (Type type : program.unit.types) {
                        DebugSystem.debug("IMPORTS", "  Type: " + type.name);
                        for (Method method : type.methods) {
                            DebugSystem.debug(
                                "IMPORTS",
                                "    Method: "
                                    + method.methodName
                                    + " (params: "
                                    + method.parameters.size()
                                    + ")");
                        }
                    }
                }
            }
        }
        DebugSystem.debug("IMPORTS", "=== END IMPORT STATUS ===");
    }

    public void preloadImport(String qualifiedName, Program program) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            throw new InternalError("preloadImport called with null/empty qualifiedName");
        }
        if (program == null) {
            throw new InternalError("preloadImport called with null program");
        }
        
        importedUnits.put(qualifiedName, program);
        loadedPrograms.put(qualifiedName, program);
        preloadedImports.put(qualifiedName, program);
        
        cacheImportName(qualifiedName);
        
        DebugSystem.debug("IMPORTS", "Pre-loaded import into resolver: " + qualifiedName);
        
        if (program.unit != null && program.unit.policies != null) {
            for (Policy policy : program.unit.policies) {
                String qualifiedPolicyName = program.unit.name + "." + policy.name;
                registerPolicy(qualifiedPolicyName, policy);
                policyToUnitMap.put(policy.name, program.unit.name);
            }
        }
        
        if (program.unit != null && program.unit.types != null) {
            for (Type type : program.unit.types) {
                String qualifiedTypeName = program.unit.name + "." + type.name;
                typeCache.put(qualifiedTypeName, type);
                typeCache.put(type.name, type);
                loadedTypes.put(qualifiedTypeName, type);
                
                // Pre-save IR if available
                if (irManager != null) {
                    irManager.save(program.unit.name, type);
                }
            }
        }
        
        // Preload index for this unit
        if (program.unit != null && program.unit.name != null) {
            String unitName = program.unit.name;
            Index index = new Index(unitName);
            for (Type type : program.unit.types) {
                String fileName = type.name + ".cod";
                index.add(type.name, fileName);
            }
            if (!index.isEmpty()) {
                index.save();
                indexCache.put(unitName, index);
            }
        }
    }

    public Map<String, Program> getLoadedPrograms() {
        return loadedPrograms;
    }

    public Map<String, Type> getLoadedTypes() {
        return loadedTypes;
    }

    public Set<String> getLoadedImports() {
        return importedUnits.keySet();
    }
    
    public Set<String> getRegisteredImports() {
        return new HashSet<String>(registeredImports);
    }

    public Field findField(String qualifiedFieldName) {
        if (qualifiedFieldName == null || qualifiedFieldName.isEmpty()) {
            return null;
        }
        
        String fullName = qualifiedFieldName;
        if (explicitFieldImports.containsKey(qualifiedFieldName)) {
            fullName = explicitFieldImports.get(qualifiedFieldName);
        }
        
        int lastDot = fullName.lastIndexOf('.');
        if (lastDot == -1) {
            for (String wildcardUnit : wildcardEverythingUnits) {
                Type wildcardType = loadStaticModuleType(wildcardUnit);
                if (wildcardType != null && wildcardType.fields != null) {
                    for (Field field : wildcardType.fields) {
                        if (qualifiedFieldName.equals(field.name)) {
                            return field;
                        }
                    }
                }
            }
            return null;
        }
        
        if (lastDot <= 0 || lastDot >= fullName.length() - 1) {
            return null;
        }
        
        String unitName = fullName.substring(0, lastDot);
        String fieldName = fullName.substring(lastDot + 1);
        
        Type staticType = loadStaticModuleType(unitName);
        if (staticType != null && staticType.fields != null) {
            for (Field field : staticType.fields) {
                if (fieldName.equals(field.name)) {
                    return field;
                }
            }
        }
        
        for (String wildcardUnit : wildcardEverythingUnits) {
            Type wildcardType = loadStaticModuleType(wildcardUnit);
            if (wildcardType != null && wildcardType.fields != null) {
                for (Field field : wildcardType.fields) {
                    if (qualifiedFieldName.equals(field.name) || fieldName.equals(field.name)) {
                        return field;
                    }
                }
            }
        }
        
        return null;
    }

    private Method findMethodFromSpecs(String methodName) {
        for (String spec : methodImportSpecs) {
            ParsedMethodImport parsed = parseMethodImport(spec);
            if (parsed != null && parsed.methodName.equals(methodName)) {
                Method method = findMethodInStaticModule(parsed.unitName, parsed.methodName);
                if (method != null) {
                    return method;
                }
            }
        }
        
        for (String unitName : wildcardEverythingUnits) {
            Method method = findMethodInStaticModule(unitName, methodName);
            if (method != null) {
                return method;
            }
        }
        
        return null;
    }

    private Method findMethodInStaticModule(String unitName, String methodName) {
        Type staticType = loadStaticModuleType(unitName);
        if (staticType == null || staticType.methods == null) {
            return null;
        }
        
        for (Method method : staticType.methods) {
            if (methodName.equals(method.methodName)) {
                return method;
            }
        }
        
        return null;
    }

    private Type loadStaticModuleType(String unitName) {
        if (unitName == null || unitName.isEmpty()) {
            return null;
        }
        
        Program program = loadedPrograms.get(unitName);
        if (program == null) {
            program = loadStaticModuleProgram(unitName);
            if (program != null) {
                loadedPrograms.put(unitName, program);
            }
        }
        
        if (program == null || program.unit == null || program.unit.types == null) {
            return null;
        }
        
        for (Type type : program.unit.types) {
            if ("__StaticModule__".equals(type.name)) {
                return type;
            }
        }
        
        return null;
    }

    private Program loadStaticModuleProgram(String unitName) {
        validateUnitName(unitName);
        String dirPath = unitName.replace('.', '/');
        String moduleMainFileName = toModuleMainFileName(unitName);
        String unitDirPath = getUnitPath(unitName);
        List<String> pathsToTry = new ArrayList<String>();
        
        if (srcMainRoot != null) {
            pathsToTry.add(srcMainRoot + "/" + dirPath + ".cod");
        }
        
        if (currentFileDirectory != null && 
            (srcMainRoot == null || !currentFileDirectory.equals(srcMainRoot))) {
            pathsToTry.add(currentFileDirectory + "/" + dirPath + ".cod");
        }
        
        for (String basePath : importPaths) {
            if (basePath == null || basePath.isEmpty()) continue;
            if (srcMainRoot != null && basePath.equals(srcMainRoot)) continue;
            pathsToTry.add(basePath + "/" + dirPath + ".cod");
        }
        
        pathsToTry.add(dirPath + ".cod");
        if (unitDirPath != null && moduleMainFileName != null) {
            pathsToTry.add(unitDirPath + File.separator + moduleMainFileName + ".cod");
        }
        
        for (String path : pathsToTry) {
            try {
                Program program = loadImportFromFileCached(path);
                if (program != null) {
                    if (!isMatchingProgramUnit(program, unitName)) {
                        continue;
                    }
                    return program;
                }
            } catch (Exception e) {
                // Continue trying
            }
        }
        
        return null;
    }

/**
 * Fast class name extraction without full AST parsing.
 * Only looks for "share? class Name {" pattern.
 */
private List<String> extractClassNamesFast(File file) {
    List<String> classNames = new ArrayList<String>();
    try {
        String content = readFileToString(file);
        MainLexer lexer = new MainLexer(content);
        List<Token> tokens = lexer.tokenize();
        
        // Simple scan: look for "share? class Name {" pattern
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isKeyword(SHARE) || t.isKeyword(LOCAL)) {
                i++;
                while (i < tokens.size() && tokens.get(i).type == WS) i++;
                if (i < tokens.size() && tokens.get(i).type == ID) {
                    String possibleClass = tokens.get(i).getText();
                    if (!possibleClass.isEmpty() && Character.isUpperCase(possibleClass.charAt(0))) {
                        // Look ahead for {
                        int j = i + 1;
                        while (j < tokens.size() && tokens.get(j).type == WS) j++;
                        if (j < tokens.size() && tokens.get(j).isSymbol(LBRACE)) {
                            classNames.add(possibleClass);
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        // Fallback to filename
        String fileName = file.getName();
        if (fileName.endsWith(".cod")) {
            classNames.add(fileName.substring(0, fileName.length() - 4));
        }
    }
    return classNames;
}

private String readFileToString(File file) throws IOException {
    StringBuilder content = new StringBuilder();
    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
    try {
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
    } finally {
        reader.close();
    }
    return content.toString();
}

    private ParsedMethodImport parseMethodImport(String spec) {
        int open = spec.indexOf('(');
        int close = spec.lastIndexOf(')');
        if (open <= 0 || close <= open) {
            return null;
        }
        
        String head = spec.substring(0, open);
        int lastDot = head.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= head.length() - 1) {
            return null;
        }
        
        ParsedMethodImport parsed = new ParsedMethodImport();
        parsed.unitName = head.substring(0, lastDot);
        parsed.methodName = head.substring(lastDot + 1);
        return parsed;
    }

    private static class ParsedMethodImport {
        String unitName;
        String methodName;
    }
}
