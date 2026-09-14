package world.willfrog.agent.platform.debug;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One debug observability session directory with rolling JSONL writers.
 */
final class DebugObservabilitySession {

    private static final long DEFAULT_MAX_SESSION_BYTES = 200L * 1024 * 1024;
    private static final long DEFAULT_ROLLING_FILE_BYTES = 50L * 1024 * 1024;

    private final String debugSessionId;
    private final Path sessionDir;
    private final ObjectMapper objectMapper;
    private final long maxSessionBytes;
    private final long rollingFileBytes;
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong linesWritten = new AtomicLong();
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicLong writerErrors = new AtomicLong();
    private volatile String stoppedReason;
    private volatile Path currentRunFile;
    private volatile BufferedWriter currentWriter;
    private volatile long currentFileBytes;

    DebugObservabilitySession(String debugSessionId,
                              Path sessionDir,
                              ObjectMapper objectMapper,
                              long maxSessionBytes,
                              long rollingFileBytes) throws IOException {
        this.debugSessionId = debugSessionId;
        this.sessionDir = sessionDir;
        this.objectMapper = objectMapper;
        this.maxSessionBytes = maxSessionBytes > 0 ? maxSessionBytes : DEFAULT_MAX_SESSION_BYTES;
        this.rollingFileBytes = rollingFileBytes > 0 ? rollingFileBytes : DEFAULT_ROLLING_FILE_BYTES;
        Files.createDirectories(sessionDir);
    }

    String debugSessionId() {
        return debugSessionId;
    }

    Path sessionDir() {
        return sessionDir;
    }

    long bytesWritten() {
        return bytesWritten.get();
    }

    long linesWritten() {
        return linesWritten.get();
    }

    long droppedEvents() {
        return droppedEvents.get();
    }

    long writerErrors() {
        return writerErrors.get();
    }

    String stoppedReason() {
        return stoppedReason;
    }

    void writeManifest(Map<String, Object> manifest) {
        try {
            Path manifestPath = sessionDir.resolve("manifest.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        } catch (Exception ignored) {
            writerErrors.incrementAndGet();
        }
    }

    synchronized void appendRunEvent(String runId, Map<String, Object> event) {
        if (stoppedReason != null) {
            droppedEvents.incrementAndGet();
            return;
        }
        if (bytesWritten.get() >= maxSessionBytes) {
            stoppedReason = "MAX_BYTES";
            droppedEvents.incrementAndGet();
            return;
        }
        try {
            ensureRunWriter(runId);
            String line = objectMapper.writeValueAsString(event);
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytesWritten.addAndGet(bytes.length) > maxSessionBytes) {
                stoppedReason = "MAX_BYTES";
                droppedEvents.incrementAndGet();
                return;
            }
            currentWriter.write(line);
            currentWriter.newLine();
            currentFileBytes += bytes.length;
            linesWritten.incrementAndGet();
            if (currentFileBytes >= rollingFileBytes) {
                rotateRunWriter(runId);
            }
        } catch (Exception e) {
            writerErrors.incrementAndGet();
            droppedEvents.incrementAndGet();
        }
    }

    synchronized void close() {
        try {
            if (currentWriter != null) {
                currentWriter.flush();
                currentWriter.close();
            }
        } catch (IOException ignored) {
            writerErrors.incrementAndGet();
        } finally {
            currentWriter = null;
            currentRunFile = null;
            currentFileBytes = 0;
        }
    }

    Map<String, Object> statusSnapshot() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("debugSessionId", debugSessionId);
        status.put("outputDir", sessionDir.toString());
        status.put("bytesWritten", bytesWritten.get());
        status.put("linesWritten", linesWritten.get());
        status.put("droppedEvents", droppedEvents.get());
        status.put("writerErrors", writerErrors.get());
        status.put("stoppedReason", stoppedReason);
        status.put("updatedAt", Instant.now().toString());
        return status;
    }

    private void ensureRunWriter(String runId) throws IOException {
        if (currentWriter != null && currentRunFile != null) {
            return;
        }
        String safeRunId = runId == null || runId.isBlank() ? "unknown" : runId;
        currentRunFile = sessionDir.resolve("sandbox-" + safeRunId + ".jsonl");
        currentWriter = Files.newBufferedWriter(currentRunFile, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        currentFileBytes = Files.exists(currentRunFile) ? Files.size(currentRunFile) : 0;
    }

    private void rotateRunWriter(String runId) throws IOException {
        close();
        String safeRunId = runId == null || runId.isBlank() ? "unknown" : runId;
        int index = (int) (linesWritten.get() / Math.max(1, rollingFileBytes / 1024));
        currentRunFile = sessionDir.resolve("sandbox-" + safeRunId + "-" + index + ".jsonl");
        currentWriter = Files.newBufferedWriter(currentRunFile, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        currentFileBytes = 0;
    }
}
