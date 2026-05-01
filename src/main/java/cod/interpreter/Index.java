package cod.interpreter;

import cod.debug.DebugSystem;
import cod.ir.IRManager;

import java.io.*;
import java.util.*;

/**
 * Index file for Coderive units.
 * Stores classname → filename mappings for O(1) import resolution.
 * 
 * NOW PROGRESSIVE: Index entries are added as files are parsed, not pre-scanned.
 * 
 * File format (preferred): {projectRoot}/src/bin/project.codc -> HOOK.toml
 */
public final class Index {
    
    private static final String CLASSES_SECTION = "classes";
    private static final String CLASSES_SECTION_PREFIX = CLASSES_SECTION + ":";
    private static final String SRC_DIR_NAME = "src";
    private static final String DEFAULT_GENERATOR = "Coderive 1.0";
    
    private final String unit;
    private long timestamp;
    private String generator;
    private final Map<String, String> classes;
    
    // Track parsed files to avoid re-parsing
    private final Set<String> parsedFiles = new HashSet<String>();
    
    // Project root - set once when we have srcMainRoot
    private static String projectRoot;
    
    /**
     * Sets the project root directory (where src/ is located).
     * This must be called before using load/save.
     * 
     * @param srcMainRoot the src/main/ root path
     */
    public static void setProjectRoot(String srcMainRoot) {
        if (srcMainRoot == null || srcMainRoot.isEmpty()) {
            return;
        }
        
        try {
            File srcMain = new File(srcMainRoot);
            File src = srcMain.getParentFile();
            
            if (src != null && SRC_DIR_NAME.equals(src.getName())) {
                projectRoot = src.getParentFile().getAbsolutePath();
            } else {
                File parent = srcMain.getParentFile();
                if (parent != null) {
                    projectRoot = parent.getParentFile().getAbsolutePath();
                } else {
                    projectRoot = srcMain.getAbsolutePath();
                }
            }
            
            if (!DebugSystem.isBenchmarkMode()) {
                System.err.println("[INDEX] Project root set to: " + projectRoot);
            }
        } catch (Exception e) {
            if (!DebugSystem.isBenchmarkMode()) {
                System.err.println("[INDEX] Failed to set project root: " + e.getMessage());
            }
        }
    }
    
    /**
     * Gets the project root directory.
     */
    public static String getProjectRoot() {
        return projectRoot;
    }
    
    private static String getUnitSectionName(String unitName) {
        return CLASSES_SECTION_PREFIX + unitName;
    }
    
    /**
     * Creates a new index for the specified unit.
     * 
     * @param unit the unit name (cannot be null or empty)
     * @throws IllegalArgumentException if unit is null or empty
     */
    public Index(String unit) {
        if (unit == null || unit.trim().isEmpty()) {
            throw new IllegalArgumentException("Unit name cannot be null or empty");
        }
        this.unit = unit;
        this.classes = new HashMap<String, String>();
        this.timestamp = System.currentTimeMillis();
        this.generator = DEFAULT_GENERATOR;
    }
    
    /**
     * Creates a new index with explicit timestamp and generator.
     * 
     * @param unit the unit name
     * @param timestamp the timestamp
     * @param generator the generator string
     */
    public Index(String unit, long timestamp, String generator) {
        this(unit);
        this.timestamp = timestamp;
        if (generator != null && !generator.isEmpty()) {
            this.generator = generator;
        }
    }
    
    // ========== PROGRESSIVE INDEX METHODS ==========
    
    /**
     * Add a class mapping from parsed file (no directory scan!)
     */
    public void add(String className, String fileName) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("Class name cannot be null or empty");
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or empty");
        }
        classes.put(className, fileName);
        parsedFiles.add(fileName);
        touch();
    }
    
    /**
     * Add multiple class mappings at once
     */
    public void addAll(Map<String, String> mappings) {
        if (mappings != null) {
            for (Map.Entry<String, String> entry : mappings.entrySet()) {
                add(entry.getKey(), entry.getValue());
            }
        }
    }
    
    /**
     * Mark a file as parsed (to avoid re-parsing)
     */
    public void markParsed(String fileName) {
        if (fileName != null && !fileName.isEmpty()) {
            parsedFiles.add(fileName);
        }
    }
    
    /**
     * Check if file already parsed
     */
    public boolean isFileParsed(String fileName) {
        return fileName != null && parsedFiles.contains(fileName);
    }
    
    /**
     * Merge index entries from another index (for progressive loading)
     */
    public void merge(Index other) {
        if (other == null) return;
        if (!this.unit.equals(other.unit)) {
            throw new IllegalArgumentException("Cannot merge indexes from different units");
        }
        this.classes.putAll(other.classes);
        this.parsedFiles.addAll(other.parsedFiles);
        this.timestamp = Math.max(this.timestamp, other.timestamp);
    }
    
    /**
     * Refresh from parsed files only (no directory scan)
     */
    public boolean refresh() {
        this.timestamp = System.currentTimeMillis();
        return !classes.isEmpty();
    }
    
    /**
     * Loads an index from disk.
     * 
     * @param unitName the unit name
     * @return the loaded index, or null if not found or invalid
     */
    public static Index load(String unitName) {
        if (unitName == null || unitName.trim().isEmpty()) {
            return null;
        }
        String docText = loadPreferredDocumentText(unitName);
        if (docText == null) {
            return null;
        }

        IndexDocument doc = parseDocument(docText, unitName);
        Map<String, String> unitMappings = doc.unitMappings.get(unitName);
        if (unitMappings == null || unitMappings.isEmpty()) {
            return null;
        }

        Index index = new Index(unitName, doc.timestamp, doc.generator);
        index.addAll(unitMappings);
        return index;
    }
    
    /**
     * Saves this index to disk.
     * 
     * @return true if saved successfully, false otherwise
     */
    public boolean save() {
    if (projectRoot == null) {
        return false;
    }

    IndexDocument merged = loadExistingDocument(unit);
    merged.timestamp = timestamp;
    merged.generator = (generator == null || generator.isEmpty()) ? DEFAULT_GENERATOR : generator;
    merged.unitMappings.put(unit, new HashMap<String, String>(classes));

    String documentText = writeDocumentText(merged);

    IRManager manager = new IRManager(projectRoot);
    try {
        manager.saveIndex(unit, documentText);
        return true;
    } catch (Exception e) {
        return false;
    }
}
    
    /**
     * Gets the file name for a class.
     */
    public String getFile(String className) {
        if (className == null) {
            return null;
        }
        return classes.get(className);
    }
    
    /**
     * Checks if a class exists in this index.
     */
    public boolean contains(String className) {
        if (className == null) {
            return false;
        }
        return classes.containsKey(className);
    }
    
    /**
     * Removes a class from the index.
     */
    public boolean remove(String className) {
        if (className == null) {
            return false;
        }
        return classes.remove(className) != null;
    }
    
    /**
     * Returns all class names in this index.
     */
    public Set<String> getClassNames() {
        return Collections.unmodifiableSet(new HashSet<String>(classes.keySet()));
    }
    
    /**
     * Returns the number of classes in this index.
     */
    public int size() {
        return classes.size();
    }
    
    /**
     * Checks if this index is empty.
     */
    public boolean isEmpty() {
        return classes.isEmpty();
    }
    
    /**
     * Clears all class mappings from this index.
     */
    public void clear() {
        classes.clear();
        parsedFiles.clear();
        timestamp = System.currentTimeMillis();
    }
    
    /**
     * Updates the timestamp to the current time.
     */
    public void touch() {
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Checks if this index is stale relative to the unit directory.
     * Now uses parsed files set instead of scanning.
     */
    public boolean isStale(String unitPath) {
        if (unitPath == null || unitPath.isEmpty()) {
            return true;
        }
        
        File dir = new File(unitPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return true;
        }
        
        // Check if any parsed file is missing or modified
        for (String fileName : classes.values()) {
            File classFile = new File(dir, fileName);
            if (!classFile.exists()) {
                return true;
            }
            if (classFile.lastModified() > timestamp) {
                return true;
            }
        }
        
        return false;
    }
    
    // ========== Private Helpers ==========

    private static String loadPreferredDocumentText(String unitName) {
        if (projectRoot == null) {
            return null;
        }

        IRManager manager = new IRManager(projectRoot);
        return manager.loadIndex(unitName);
    }

    private static IndexDocument loadExistingDocument(String unitName) {
        String content = loadPreferredDocumentText(unitName);
        if (content == null) {
            return new IndexDocument(System.currentTimeMillis(), DEFAULT_GENERATOR, new HashMap<String, Map<String, String>>());
        }
        return parseDocument(content, unitName);
    }

    private static String writeDocumentText(IndexDocument doc) {
        StringBuilder out = new StringBuilder();
        out.append("# project-wide multi-unit class index\n");
        out.append("timestamp = \"").append(doc.timestamp).append("\"\n");
        out.append("generator = \"").append(doc.generator).append("\"\n");
        out.append("\n");

        List<String> units = new ArrayList<String>(doc.unitMappings.keySet());
        Collections.sort(units);
        for (String unitName : units) {
            out.append("[").append(getUnitSectionName(unitName)).append("]\n");
            Map<String, String> mappings = doc.unitMappings.get(unitName);
            List<String> classNames = new ArrayList<String>(mappings.keySet());
            Collections.sort(classNames);
            for (String className : classNames) {
                out.append(className).append(" = \"").append(mappings.get(className)).append("\"\n");
            }
            out.append("\n");
        }
        return out.toString();
    }

    private static IndexDocument parseDocument(String content, String fallbackUnitName) {
        IndexDocument doc = new IndexDocument(System.currentTimeMillis(), DEFAULT_GENERATOR, new HashMap<String, Map<String, String>>());
        if (content == null) return doc;

        BufferedReader reader = new BufferedReader(new StringReader(content));
        String currentSection = "";
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1);
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq == -1) {
                    continue;
                }

                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }

                if (currentSection.isEmpty()) {
                    if ("timestamp".equals(key)) {
                        try {
                            doc.timestamp = Long.parseLong(value);
                        } catch (NumberFormatException ignored) {}
                    } else if ("generator".equals(key)) {
                        doc.generator = value;
                    }
                    continue;
                }

                String targetUnit = null;
                if (CLASSES_SECTION.equals(currentSection)) {
                    targetUnit = fallbackUnitName;
                } else if (currentSection.startsWith(CLASSES_SECTION_PREFIX)) {
                    targetUnit = currentSection.substring(CLASSES_SECTION_PREFIX.length());
                }
                if (targetUnit == null || targetUnit.isEmpty()) continue;

                Map<String, String> map = doc.unitMappings.get(targetUnit);
                if (map == null) {
                    map = new HashMap<String, String>();
                    doc.unitMappings.put(targetUnit, map);
                }
                map.put(key, value);
            }
        } catch (IOException ignored) {}

        return doc;
    }

    private static final class IndexDocument {
        long timestamp;
        String generator;
        Map<String, Map<String, String>> unitMappings;

        IndexDocument(long timestamp, String generator, Map<String, Map<String, String>> unitMappings) {
            this.timestamp = timestamp;
            this.generator = generator;
            this.unitMappings = unitMappings;
        }
    }
    
    // ========== Object Methods ==========
    
    @Override
    public String toString() {
        return String.format("Index{unit='%s', classes=%d, parsedFiles=%d, timestamp=%d}", 
                             unit, classes.size(), parsedFiles.size(), timestamp);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        Index index = (Index) o;
        
        if (timestamp != index.timestamp) return false;
        if (!unit.equals(index.unit)) return false;
        if (!generator.equals(index.generator)) return false;
        return classes.equals(index.classes);
    }
    
    @Override
    public int hashCode() {
        int result = unit.hashCode();
        result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
        result = 31 * result + generator.hashCode();
        result = 31 * result + classes.hashCode();
        return result;
    }
    
    // ========== Builder ==========
    
    public static final class Builder {
        private final String unit;
        private long timestamp;
        private String generator;
        private final Map<String, String> mappings;
        
        public Builder(String unit) {
            this.unit = unit;
            this.timestamp = System.currentTimeMillis();
            this.generator = DEFAULT_GENERATOR;
            this.mappings = new HashMap<String, String>();
        }
        
        public Builder withTimestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder withGenerator(String generator) {
            this.generator = generator;
            return this;
        }
        
        public Builder add(String className, String fileName) {
            this.mappings.put(className, fileName);
            return this;
        }
        
        public Builder addAll(Map<String, String> mappings) {
            this.mappings.putAll(mappings);
            return this;
        }
        
        public Index build() {
            Index index = new Index(unit, timestamp, generator);
            index.addAll(mappings);
            return index;
        }
    }
}