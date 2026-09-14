package world.willfrog.agent.platform.debug;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared JSONL append helper for gateway and other cross-service writers.
 */
public final class DebugObservabilityJsonlAppender {

    private static final ConcurrentHashMap<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private DebugObservabilityJsonlAppender() {
    }

    public static void append(Path sessionDir,
                              String runId,
                              String sessionId,
                              String service,
                              ObjectMapper objectMapper,
                              Map<String, Object> fields) {
        if (sessionDir == null || objectMapper == null || fields == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("service", service == null || service.isBlank() ? "unknown" : service);
        event.put("host", safeHost());
        if (sessionId != null && !sessionId.isBlank()) {
            event.put("debugSessionId", sessionId);
        }
        if (runId != null && !runId.isBlank()) {
            event.put("runId", runId);
        }
        event.putAll(fields);
        DebugObservabilityService.sanitize(event);
        writeLine(sessionDir, runId, objectMapper, event);
    }

    static void writeLine(Path sessionDir, String runId, ObjectMapper objectMapper, Map<String, Object> event) {
        String safeRunId = runId == null || runId.isBlank() ? "unknown" : runId;
        Path runFile = sessionDir.resolve("sandbox-" + safeRunId + ".jsonl");
        Object lock = FILE_LOCKS.computeIfAbsent(runFile.toString(), key -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(sessionDir);
                String line = objectMapper.writeValueAsString(event);
                Files.writeString(runFile, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // debug path must not affect RPC/tool execution
            }
        }
    }

    private static String safeHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
