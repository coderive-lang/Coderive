package cod.ir;

import cod.ast.node.Type;
import cod.ptac.Artifact;
import cod.ptac.Compiler;
import cod.ptac.Unit;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

public class IRManager {
    private static final String BIN_DIR = "bin";
    private static final String IR_EXT = ".codb";
    private static final String CONTAINER_EXT = ".codc";
    private static final String PROJECT_CONTAINER_NAME = "project";
    private static final String PROJECT_INDEX_FILE_NAME = "HOOK.toml";
    private static final int BUFFER_SIZE = 8192;
    private static final Map<String, Object> CONTAINER_LOCKS = new ConcurrentHashMap<String, Object>();

    private final String projectRoot;
    // private final IRWriter writer; <- currently unused
    private final IRReader reader;
    
    // ========== MEMORY CACHE (Java 7 compatible) ==========
    private final Map<String, Map<String, Type>> typeCache;
    private final Map<String, Map<String, Artifact>> artifactCache;
    private final Map<String, String> indexCache;
    
    private final Compiler compiler;

    public IRManager(String projectRoot) {
        this.projectRoot = projectRoot;
        // this.writer = new IRWriter();
        this.reader = new IRReader();
        this.typeCache = new HashMap<String, Map<String, Type>>();
        this.artifactCache = new HashMap<String, Map<String, Artifact>>();
        this.indexCache = new HashMap<String, String>();
        this.compiler = new Compiler();
    }

    // ========== MEMORY CACHE METHODS ==========
    
    private Type getTypeFromCache(String unit, String className) {
        synchronized (typeCache) {
            Map<String, Type> unitCache = typeCache.get(unit);
            if (unitCache != null) {
                return unitCache.get(className);
            }
        }
        return null;
    }
    
    private void putTypeInCache(String unit, String className, Type type) {
        synchronized (typeCache) {
            Map<String, Type> unitCache = typeCache.get(unit);
            if (unitCache == null) {
                unitCache = new HashMap<String, Type>();
                typeCache.put(unit, unitCache);
            }
            unitCache.put(className, type);
        }
    }
    
    private Artifact getArtifactFromCache(String unit, String className) {
        synchronized (artifactCache) {
            Map<String, Artifact> unitCache = artifactCache.get(unit);
            if (unitCache != null) {
                return unitCache.get(className);
            }
        }
        return null;
    }
    
    private void putArtifactInCache(String unit, String className, Artifact artifact) {
        synchronized (artifactCache) {
            Map<String, Artifact> unitCache = artifactCache.get(unit);
            if (unitCache == null) {
                unitCache = new HashMap<String, Artifact>();
                artifactCache.put(unit, unitCache);
            }
            unitCache.put(className, artifact);
        }
    }
    
    private String getIndexFromCache(String unit) {
        synchronized (indexCache) {
            return indexCache.get(unit);
        }
    }
    
    private void putIndexInCache(String unit, String content) {
        synchronized (indexCache) {
            indexCache.put(unit, content);
        }
    }
    
    public void clearCache() {
        synchronized (typeCache) {
            typeCache.clear();
        }
        synchronized (artifactCache) {
            artifactCache.clear();
        }
        synchronized (indexCache) {
            indexCache.clear();
        }
    }
    
    public boolean isTypeCached(String unit, String className) {
        return getTypeFromCache(unit, className) != null;
    }
    
    public boolean isArtifactCached(String unit, String className) {
        return getArtifactFromCache(unit, className) != null;
    }
    
    /**
     * Preload all artifacts from container into memory cache at startup
     */
    public void preloadUnit(String unit) {
        if (unit == null || unit.isEmpty()) return;
        
        synchronized (artifactCache) {
            Map<String, Artifact> existing = artifactCache.get(unit);
            if (existing != null && !existing.isEmpty()) {
                return;
            }
        }
        
        File container = getContainerFile(unit);
        if (!container.exists() || !container.isFile()) {
            return;
        }
        
        Map<String, byte[]> entries;
        try {
            entries = readContainerEntries(container);
        } catch (IOException e) {
            return;
        }
        
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String entryName = entry.getKey();
            if (entryName.endsWith(IR_EXT) && !PROJECT_INDEX_FILE_NAME.equals(entryName)) {
                String className = entryName;
                int lastSlash = className.lastIndexOf('/');
                if (lastSlash >= 0) {
                    className = className.substring(lastSlash + 1);
                }
                if (className.endsWith(IR_EXT)) {
                    className = className.substring(0, className.length() - IR_EXT.length());
                }
                
                try {
                    Artifact artifact = readArtifactFromBytes(entry.getValue());
                    if (artifact != null) {
                        putArtifactInCache(unit, className, artifact);
                        if (artifact.typeSnapshot != null) {
                            putTypeInCache(unit, className, artifact.typeSnapshot);
                        }
                    }
                } catch (IOException e) {
                    // Skip corrupted entry
                }
            }
        }
    }

    // ========== LOAD/SAVE METHODS ==========
    
    public Type load(String unit, String className) {
        if (unit == null || className == null) {
            return null;
        }

        Type cached = getTypeFromCache(unit, className);
        if (cached != null) {
            return cached;
        }

        Artifact artifact = loadArtifact(unit, className);
        if (artifact != null && artifact.typeSnapshot != null) {
            return artifact.typeSnapshot;
        }
        return null;
    }

    public void save(String unit, Type type) {
        if (type == null || unit == null || type.name == null) {
            return;
        }
        Artifact artifact = compiler.compile(unit, type);
        putArtifactInCache(unit, type.name, artifact);
        putTypeInCache(unit, type.name, type);
        writeArtifactToContainerAsync(unit, artifact.className, artifact);
    }

    public Artifact loadArtifact(String unit, String className) {
        if (unit == null || className == null) {
            return null;
        }

        Artifact cached = getArtifactFromCache(unit, className);
        if (cached != null) {
            return cached;
        }

        Artifact artifact = null;
        try {
            artifact = readArtifactFromContainer(unit, className);
        } catch (IOException e) {
            // Fall through to file read
        }
        
        if (artifact == null) {
            File file = getIRFile(unit, className);
            if (file.exists()) {
                try {
                    artifact = reader.readArtifact(file);
                } catch (IOException e) {
                    // Failed to read
                }
            }
        }
        
        if (artifact != null) {
            putArtifactInCache(unit, className, artifact);
            if (artifact.typeSnapshot != null) {
                putTypeInCache(unit, className, artifact.typeSnapshot);
            }
        }
        return artifact;
    }

    public Unit loadCodPTACUnit(String unit, String className) {
        Artifact artifact = loadArtifact(unit, className);
        return artifact != null ? artifact.unit : null;
    }

    public void saveArtifact(String unit, Artifact artifact) {
        if (artifact == null || unit == null || artifact.className == null) return;
        putArtifactInCache(unit, artifact.className, artifact);
        if (artifact.typeSnapshot != null) {
            putTypeInCache(unit, artifact.className, artifact.typeSnapshot);
        }
        writeArtifactToContainerAsync(unit, artifact.className, artifact);
    }

    // ========== INDEX METHODS ==========
    
    public String loadIndex(String unit) {
        if (unit == null || unit.isEmpty()) return null;
        
        String cached = getIndexFromCache(unit);
        if (cached != null) {
            return cached;
        }
        
        String entryName = getProjectIndexEntryName();
        byte[] data = null;
        try {
            data = readContainerEntry(unit, entryName);
        } catch (IOException e) {
            return null;
        }
        if (data == null) return null;
        
        String content = new String(data, StandardCharsets.UTF_8);
        putIndexInCache(unit, content);
        return content;
    }

    public void saveIndex(final String unit, String indexContent) {
        if (unit == null || unit.isEmpty() || indexContent == null) return;
        
        putIndexInCache(unit, indexContent);
        
        final String entryName = getProjectIndexEntryName();
        final byte[] data = indexContent.getBytes(StandardCharsets.UTF_8);
        Thread writerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    writeContainerEntry(unit, entryName, data);
                } catch (IOException e) {
                    // Silent fail - cache is still valid
                }
            }
        });
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<String, Object>();
        
        int typeTotal = 0;
        synchronized (typeCache) {
            for (Map<String, Type> unitCache : typeCache.values()) {
                typeTotal += unitCache.size();
            }
            stats.put("typeCacheUnits", typeCache.size());
            stats.put("typeCacheClasses", Integer.valueOf(typeTotal));
        }
        
        int artifactTotal = 0;
        synchronized (artifactCache) {
            for (Map<String, Artifact> unitCache : artifactCache.values()) {
                artifactTotal += unitCache.size();
            }
            stats.put("artifactCacheUnits", artifactCache.size());
            stats.put("artifactCacheClasses", Integer.valueOf(artifactTotal));
        }
        
        synchronized (indexCache) {
            stats.put("indexCacheUnits", Integer.valueOf(indexCache.size()));
        }
        
        return stats;
    }

    // ========== ASYNC DISK WRITE ==========
    
    private void writeArtifactToContainerAsync(final String unit, final String className, final Artifact artifact) {
        Thread writerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    writeArtifactToContainer(unit, className, artifact);
                } catch (IOException e) {
                    // Silent fail - cache is still valid
                }
            }
        });
        writerThread.setDaemon(true);
        writerThread.start();
    }

    // ========== PRIVATE HELPER METHODS ==========
    
    private void writeArtifactToContainer(String unit, String className, Artifact artifact) throws IOException {
        if (unit == null || className == null || artifact == null) return;
        writeContainerEntry(unit, getContainerEntryName(unit, className), writeArtifactToBytes(artifact));
    }

    private File getIRFile(String unit, String className) {
        String path = projectRoot + "/src/" + BIN_DIR + "/" + toUnitPath(unit) + "/" + className + IR_EXT;
        return new File(path);
    }

    private File getContainerFile(String unit) {
        String path = projectRoot + "/src/" + BIN_DIR + "/" + PROJECT_CONTAINER_NAME + CONTAINER_EXT;
        return new File(path);
    }

    private String getContainerEntryName(String unit, String className) {
        return toUnitPath(unit) + "/" + className + IR_EXT;
    }

    public static String toUnitPath(String unit) {
        if (unit == null) return "";
        return unit.replace('.', '/');
    }

    private String getProjectIndexEntryName() {
        return PROJECT_INDEX_FILE_NAME;
    }

    private Artifact readArtifactFromContainer(String unit, String className) throws IOException {
        byte[] data = readContainerEntry(unit, getContainerEntryName(unit, className));
        if (data == null) return null;
        return readArtifactFromBytes(data);
    }

    private byte[] readContainerEntry(String unit, String entryName) throws IOException {
        if (unit == null || entryName == null) return null;
        File container = getContainerFile(unit);
        if (!container.exists() || !container.isFile()) {
            return null;
        }

        ZipInputStream in = null;
        try {
            in = new ZipInputStream(new BufferedInputStream(new FileInputStream(container)));
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && entryName.equals(entry.getName())) {
                    return readAllBytes(in);
                }
            }
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private void writeContainerEntry(String unit, String entryName, byte[] entryData) throws IOException {
        if (unit == null || entryName == null || entryData == null) return;

        File container = getContainerFile(unit);
        File parent = container.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create IR container directory: " + parent.getAbsolutePath());
        }

        Object containerLock = getContainerLock(container);
        synchronized (containerLock) {
            Map<String, byte[]> entries = readContainerEntries(container);
            entries.put(entryName, entryData);

            File temp = new File(container.getAbsolutePath() + ".tmp");
            ZipOutputStream out = null;
            boolean moved = false;
            try {
                out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)));
                out.setLevel(0);
                for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                    byte[] value = e.getValue();
                    CRC32 crc = new CRC32();
                    crc.update(value);
                    ZipEntry zipEntry = new ZipEntry(e.getKey());
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(value.length);
                    zipEntry.setCompressedSize(value.length);
                    zipEntry.setCrc(crc.getValue());
                    out.putNextEntry(zipEntry);
                    out.write(value);
                    out.closeEntry();
                }
                out.finish();
                Files.move(temp.toPath(), container.toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {}
                }
                if (!moved && temp.exists()) {
                    try {
                        Files.delete(temp.toPath());
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    private Object getContainerLock(File container) {
        String key = container.getAbsolutePath();
        Object lock = CONTAINER_LOCKS.get(key);
        if (lock != null) return lock;
        Object created = new Object();
        Object existing = CONTAINER_LOCKS.putIfAbsent(key, created);
        return existing != null ? existing : created;
    }

    private Map<String, byte[]> readContainerEntries(File container) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        if (container == null || !container.exists() || !container.isFile()) {
            return entries;
        }

        ZipInputStream in = null;
        try {
            in = new ZipInputStream(new BufferedInputStream(new FileInputStream(container)));
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                entries.put(entry.getName(), readAllBytes(in));
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {}
            }
        }
        return entries;
    }

    private byte[] writeArtifactToBytes(Artifact artifact) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(baos);
            IRArtifactCodec.writeArtifact(out, artifact);
            out.flush();
            return baos.toByteArray();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private Artifact readArtifactFromBytes(byte[] data) throws IOException {
        if (data == null) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new ByteArrayInputStream(data));
            return IRArtifactCodec.readArtifact(in);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}