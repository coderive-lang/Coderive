package cod.ir;

import cod.ast.node.*;
import cod.math.AutoStackingNumber;
import cod.parser.MainParser.ProgramType;
import cod.lexer.TokenType.Keyword;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IRCodec {
    static final int MAGIC = 0xAC0D1EB1;
    static final int VERSION = 1;

    private static final int MAX_DEPTH = 512;
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;
    private static final int MAX_COLLECTION_SIZE = 1_000_000;
    private static final int MAX_FIELDS = 1_000;

    private static final byte TAG_NULL = 0;
    private static final byte TAG_STRING = 1;
    private static final byte TAG_BOOL = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_DOUBLE = 5;
    private static final byte TAG_ENUM = 6;
    private static final byte TAG_LIST = 7;
    private static final byte TAG_MAP = 8;
    private static final byte TAG_NODE = 9;
    private static final byte TAG_AUTO_STACKING = 10;

    private static final String NODE_PACKAGE_PREFIX = "cod.ast.node.";
    private static final Map<String, byte[]> STRING_BYTES_CACHE = new ConcurrentHashMap<String, byte[]>();
    private static final int STRING_BYTES_CACHE_LIMIT = 512;
    private static final int MAX_IDENTIFIER_LIKE_STRING_LENGTH = 64;
    private static final int HASH_MASK_BYTES = 8;
    private static final Object STRING_BYTES_CACHE_GUARD = new Object();
    private static final long NULL_STRING_HASH = 0x9AE16A3B2F90404FL;
    private static final long FNV64_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV64_PRIME = 0x100000001b3L;

    private IRCodec() {}

    static void writeHeader(DataOutput out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
    }

    static void readHeader(DataInput in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid IR magic number: expected 0x" + Integer.toHexString(MAGIC) +
                    ", got 0x" + Integer.toHexString(magic));
        }

        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported IR version: expected " + VERSION + ", got " + version);
        }
    }

    static void writeValue(DataOutput out, Object value, int depth) throws IOException {
        ensureDepth(depth);

        if (value == null) {
            out.writeByte(TAG_NULL);
            return;
        }

        if (value instanceof String) {
            out.writeByte(TAG_STRING);
            writeString(out, (String) value);
            return;
        }

        if (value instanceof Boolean) {
            out.writeByte(TAG_BOOL);
            out.writeBoolean(((Boolean) value).booleanValue());
            return;
        }

        if (value instanceof Integer) {
            out.writeByte(TAG_INT);
            out.writeInt(((Integer) value).intValue());
            return;
        }

        if (value instanceof Long) {
            out.writeByte(TAG_LONG);
            out.writeLong(((Long) value).longValue());
            return;
        }

        if (value instanceof Float) {
            out.writeByte(TAG_DOUBLE);
            out.writeDouble(((Float) value).doubleValue());
            return;
        }

        if (value instanceof Double) {
            out.writeByte(TAG_DOUBLE);
            out.writeDouble(((Double) value).doubleValue());
            return;
        }

        if (value instanceof Enum) {
            out.writeByte(TAG_ENUM);
            Enum<?> e = (Enum<?>) value;
            writeString(out, e.getDeclaringClass().getName());
            writeString(out, e.name());
            return;
        }

        if (value instanceof AutoStackingNumber) {
            out.writeByte(TAG_AUTO_STACKING);
            writeString(out, value.toString());
            return;
        }

        if (value instanceof List) {
            out.writeByte(TAG_LIST);
            List<?> list = (List<?>) value;
            ensureCollectionSize(list.size(), "list");
            out.writeInt(list.size());
            for (Object item : list) {
                writeValue(out, item, depth + 1);
            }
            return;
        }

        if (value instanceof Map) {
            out.writeByte(TAG_MAP);
            Map<?, ?> map = (Map<?, ?>) value;
            ensureCollectionSize(map.size(), "map");
            out.writeInt(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String)) {
                    throw new IOException("Only String map keys are supported in IR, got: " +
                            (key == null ? "null" : key.getClass().getName()));
                }
                writeString(out, (String) key);
                writeValue(out, entry.getValue(), depth + 1);
            }
            return;
        }

        if (value instanceof Base) {
            writeNode(out, (Base) value, depth + 1);
            return;
        }

        throw new IOException("Unsupported IR value type: " + value.getClass().getName());
    }

    static Object readValue(DataInput in, int depth) throws IOException {
        ensureDepth(depth);
        byte tag = in.readByte();

        switch (tag) {
            case TAG_NULL:
                return null;
            case TAG_STRING:
                return readString(in);
            case TAG_BOOL:
                return Boolean.valueOf(in.readBoolean());
            case TAG_INT:
                return Integer.valueOf(in.readInt());
            case TAG_LONG:
                return Long.valueOf(in.readLong());
            case TAG_DOUBLE:
                return Double.valueOf(in.readDouble());
            case TAG_ENUM:
                return readEnum(in);
            case TAG_LIST:
                return readList(in, depth + 1);
            case TAG_MAP:
                return readMap(in, depth + 1);
            case TAG_NODE:
                return readNode(in, depth + 1);
            case TAG_AUTO_STACKING:
                return AutoStackingNumber.valueOf(readString(in));
            default:
                throw new IOException("Unknown IR tag: " + tag);
        }
    }

    private static void writeNode(DataOutput out, Base node, int depth) throws IOException {
        out.writeByte(TAG_NODE);

        if (node instanceof ValueExpr) {
            writeNodeStart(out, "ValueExpr", 1);
            writeNodeField(out, "value", ((ValueExpr) node).getValue(), depth);
            return;
        }

        try {
            new SerializationVisitor(out, depth).write(node);
        } catch (SerializationVisitor.SerializationException e) {
            throw e.io;
        }
    }

    private static Base readNode(DataInput in, int depth) throws IOException {
        String className = readString(in);
        if (!className.startsWith(NODE_PACKAGE_PREFIX)) {
            throw new IOException("Invalid IR node class: " + className);
        }

        int fieldCount = in.readInt();
        if (fieldCount < 0 || fieldCount > MAX_FIELDS) {
            throw new IOException("Invalid IR field count: " + fieldCount + " for " + className);
        }

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fieldCount; i++) {
            String fieldName = readString(in);
            Object value = readValue(in, depth + 1);
            values.put(fieldName, value);
        }

        String simpleClassName = className.substring(NODE_PACKAGE_PREFIX.length());
        return DeserializationVisitor.readNode(simpleClassName, values);
    }

    private static Enum<?> readEnum(DataInput in) throws IOException {
        String enumClassName = readString(in);
        String enumName = readString(in);

        if (Keyword.class.getName().equals(enumClassName)) {
            try {
                return Keyword.valueOf(enumName);
            } catch (IllegalArgumentException e) {
                throw new IOException("Unknown enum constant '" + enumName + "' for " + enumClassName, e);
            }
        }

        if (ProgramType.class.getName().equals(enumClassName)) {
            try {
                return ProgramType.valueOf(enumName);
            } catch (IllegalArgumentException e) {
                throw new IOException("Unknown enum constant '" + enumName + "' for " + enumClassName, e);
            }
        }

        throw new IOException("Unknown enum class in IR: " + enumClassName);
    }

    private static List<Object> readList(DataInput in, int depth) throws IOException {
        int size = in.readInt();
        ensureCollectionSize(size, "list");
        List<Object> list = new ArrayList<Object>(size);
        for (int i = 0; i < size; i++) {
            list.add(readValue(in, depth + 1));
        }
        return list;
    }

    private static Map<String, Object> readMap(DataInput in, int depth) throws IOException {
        int size = in.readInt();
        ensureCollectionSize(size, "map");
        Map<String, Object> map = new LinkedHashMap<String, Object>(size);
        for (int i = 0; i < size; i++) {
            String key = readString(in);
            Object value = readValue(in, depth + 1);
            map.put(key, value);
        }
        return map;
    }

    private static void writeString(DataOutput out, String value) throws IOException {
        if (value == null) {
            out.writeLong(NULL_STRING_HASH);
            out.writeInt(-1);
            return;
        }
        byte[] bytes = cachedUtf8Bytes(value);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("String exceeds IR limit: " + bytes.length + " bytes");
        }
        long hash = hash64(bytes);
        out.writeLong(hash);
        out.writeInt(bytes.length);
        out.write(obfuscate(bytes, hash));
    }

    private static String readString(DataInput in) throws IOException {
        long storedHash = in.readLong();
        int len = in.readInt();
        if (len == -1) {
            if (storedHash != NULL_STRING_HASH) {
                throw new IOException("Invalid null string hash in IR: 0x" + Long.toHexString(storedHash));
            }
            return null;
        }
        if (len < 0 || len > MAX_STRING_BYTES) {
            throw new IOException("Invalid string length in IR: " + len);
        }
        byte[] encoded = new byte[len];
        in.readFully(encoded);
        byte[] bytes = obfuscate(encoded, storedHash);
        long actualHash = hash64(bytes);
        if (actualHash != storedHash) {
            throw new IOException("IR string hash mismatch: expected 0x" + Long.toHexString(storedHash)
                + ", got 0x" + Long.toHexString(actualHash));
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void ensureDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("IR structure exceeds maximum depth of " + MAX_DEPTH);
        }
    }

    private static void ensureCollectionSize(int size, String kind) throws IOException {
        if (size < 0 || size > MAX_COLLECTION_SIZE) {
            throw new IOException("Invalid " + kind + " size in IR: " + size);
        }
    }

    static void writeNodeStart(DataOutput out, String nodeName, int fieldCount) throws IOException {
        ensureCollectionSize(fieldCount, "fields");
        writeString(out, nodeClassName(nodeName));
        out.writeInt(fieldCount);
    }

    static void writeNodeField(DataOutput out, String fieldName, Object value, int depth) throws IOException {
        writeString(out, fieldName);
        writeValue(out, value, depth + 1);
    }

    private static String nodeClassName(String nodeName) {
        return NODE_PACKAGE_PREFIX + nodeName;
    }

    private static byte[] cachedUtf8Bytes(String value) {
        byte[] cached = STRING_BYTES_CACHE.get(value);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (value.startsWith(NODE_PACKAGE_PREFIX) || isIdentifierLike(value)) {
            synchronized (STRING_BYTES_CACHE_GUARD) {
                if (STRING_BYTES_CACHE.size() < STRING_BYTES_CACHE_LIMIT) {
                    STRING_BYTES_CACHE.putIfAbsent(value, bytes);
                }
            }
        }
        return bytes;
    }

    private static boolean isIdentifierLike(String value) {
        int len = value.length();
        if (len == 0 || len > MAX_IDENTIFIER_LIKE_STRING_LENGTH) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static long hash64(byte[] bytes) {
        long hash = FNV64_OFFSET_BASIS;
        for (int i = 0; i < bytes.length; i++) {
            hash ^= (bytes[i] & 0xFFL);
            hash *= FNV64_PRIME;
        }
        return hash;
    }

    private static byte[] obfuscate(byte[] bytes, long hash) {
        byte[] out = new byte[bytes.length];
        // Lightweight obfuscation only (not encryption); integrity is enforced via hash verification on read.
        // XOR is its own inverse, so this method safely serves both encode and decode paths.
        int[] masks = new int[HASH_MASK_BYTES];
        for (int i = 0; i < masks.length; i++) {
            masks[i] = (int) ((hash >>> (i * 8)) & 0xFFL);
        }
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) (bytes[i] ^ masks[i & (HASH_MASK_BYTES - 1)]);
        }
        return out;
    }

}
