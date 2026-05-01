// DebugSystem.java
package cod.debug;

import java.util.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DebugSystem {
    public enum Level {
        OFF(0),
        ERROR(1),
        WARN(2),
        INFO(3),
        DEBUG(4),
        TRACE(5);

        private final int level;

        Level(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }
    }

    private static Level currentLevel = Level.INFO;
    private static boolean showTimestamp = true;
    private static boolean showThread = false;
    private static Map<String, Long> timers = new HashMap<String, Long>(); // Stores nanoseconds
    private static SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final boolean BENCHMARK_MODE = parseBenchmarkMode();
    
    // Level-based timers (new)
    private static Map<String, Long> levelTimers = new HashMap<String, Long>();
    private static Map<String, Level> timerLevels = new HashMap<String, Level>();

    private static boolean parseBenchmarkMode() {
        String raw = System.getProperty("cod.benchmark.mode");
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv("COD_BENCHMARK_MODE");
        }
        return raw != null && "true".equalsIgnoreCase(raw.trim());
    }

    public static boolean isBenchmarkMode() {
        return BENCHMARK_MODE;
    }

    public static void setLevel(Level level) {
        currentLevel = level;
    }

    public static void setShowTimestamp(boolean show) {
        showTimestamp = show;
    }

    public static void setShowThread(boolean show) {
        showThread = show;
    }

    public static void error(String category, String message) {
        log(Level.ERROR, category, message);
    }

    public static void warn(String category, String message) {
        log(Level.WARN, category, message);
    }

    public static void info(String category, String message) {
        log(Level.INFO, category, message);
    }

    public static void debug(String category, String message) {
        log(Level.DEBUG, category, message);
    }

    public static void trace(String category, String message) {
        log(Level.TRACE, category, message);
    }

    public static void methodEntry(String methodName, Map<String, Object> params) {
        if (shouldLog(Level.DEBUG)) {
            debug("METHODS", "→ " + methodName + "(" + params + ")");
        }
    }

    public static void methodExit(String methodName, Object result) {
        if (shouldLog(Level.DEBUG)) {
            debug("METHODS", "← " + methodName + " = " + result);
        }
    }

    public static void slotUpdate(String slotName, Object oldValue, Object newValue) {
        if (shouldLog(Level.DEBUG)) {
            debug("SLOTS", "Slot " + slotName + ": " + oldValue + " → " + newValue);
        }
    }

    public static void fieldUpdate(String fieldName, Object value) {
        if (shouldLog(Level.DEBUG)) {
            debug("FIELDS", "Field " + fieldName + " = " + value);
        }
    }

    // Original timer - unchanged
    public static void startTimer(String name) {
        timers.put(name, System.nanoTime());
    }

    // New level-based timer
    public static void startTimer(Level level, String name) {
        if (shouldLog(level)) {
            levelTimers.put(name, System.nanoTime());
            timerLevels.put(name, level);
        }
    }

    // Unified stopTimer - works for both original and level-based timers
    public static double stopTimer(String name) {
        // Check level-based timers first
        Long levelStart = levelTimers.remove(name);
        if (levelStart != null) {
            Level originalLevel = timerLevels.remove(name);
            long durationNs = System.nanoTime() - levelStart;
            double durationMs = durationNs / 1_000_000.0;
            
            if (originalLevel != null && shouldLog(originalLevel)) {
                log(originalLevel, "PERF", String.format("%s took %.3f ms", name, durationMs));
            }
            return durationMs;
        }
        
        // Original timer behavior
        Long start = timers.remove(name);
        if (start != null) {
            long durationNs = System.nanoTime() - start;
            double durationMs = durationNs / 1_000_000.0;
            
            if (shouldLog(Level.DEBUG)) {
                debug("PERF", String.format("%s took %.3f ms", name, durationMs));
            }
            return durationMs;
        }
        return -1.0;
    }

    public static double getTimerDuration(String name) {
        // Check level-based timers first
        Long levelStart = levelTimers.get(name);
        if (levelStart != null) {
            long durationNs = System.nanoTime() - levelStart;
            return durationNs / 1_000_000.0;
        }
        
        // Original timer
        Long start = timers.get(name);
        if (start != null) {
            long durationNs = System.nanoTime() - start;
            return durationNs / 1_000_000.0;
        }
        return -1.0;
    }

    public static void astBuilding(String nodeType, String details) {
        if (shouldLog(Level.TRACE)) {
            trace("AST", "Building " + nodeType + ": " + details);
        }
    }

    public static void astComplete(String nodeType, String summary) {
        if (shouldLog(Level.DEBUG)) {
            debug("AST", "Built " + nodeType + " → " + summary);
        }
    }

    private static void log(Level level, String category, String message) {
        if (shouldLog(level)) {
            StringBuilder logLine = new StringBuilder();

            if (showTimestamp) {
                logLine.append("[")
                        .append(timeFormat.format(new Date()))
                        .append("] ");
            }

            logLine.append("[").append(level).append("] ");

            if (showThread) {
                logLine.append("[")
                        .append(Thread.currentThread().getName())
                        .append("] ");
            }

            logLine.append(category).append(": ").append(message);

            System.out.println(logLine.toString());
        }
    }

    private static boolean shouldLog(Level level) {
        if (currentLevel == Level.OFF) return false;
        return level.getLevel() <= currentLevel.getLevel();
    }

    public static Level getLevel() {
        return currentLevel;
    }
}