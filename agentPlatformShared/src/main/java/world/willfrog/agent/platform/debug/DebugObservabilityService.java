package world.willfrog.agent.platform.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run-scoped debug observability JSONL writer. Off-path is zero I/O.
 */
@Service
public class DebugObservabilityService {

    static final String ENV_ROOT = "AF_DEBUG_OBSERVABILITY_ROOT";
    static final String DEFAULT_ROOT = "/app/logs/agent-debug-observability";

    private final ObjectMapper objectMapper;
    private final Path rootDir;
    private final ConcurrentHashMap<String, DebugObservabilitySession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> runToSession = new ConcurrentHashMap<>();

    public DebugObservabilityService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String configured = System.getenv(ENV_ROOT);
        this.rootDir = Path.of(configured == null || configured.isBlank() ? DEFAULT_ROOT : configured);
    }

    Path rootDir() {
        return rootDir;
    }

    public DebugObservabilityRequest parseFromExt(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return DebugObservabilityRequest.disabled();
        }
        try {
            var root = objectMapper.readTree(extJson);
            if (root.has(DebugObservabilityRequest.EXT_KEY)) {
                return DebugObservabilityRequest.parse(root.get(DebugObservabilityRequest.EXT_KEY), objectMapper);
            }
            return DebugObservabilityRequest.disabled();
        } catch (Exception e) {
            return DebugObservabilityRequest.disabled();
        }
    }

    public void openRunSession(DebugObservabilityRequest request, String runId, String userId) {
        if (request == null || !request.enabled()) {
            return;
        }
        try {
            String sessionId = request.resolveSessionId(runId);
            DebugObservabilitySession session = sessions.computeIfAbsent(sessionId, id -> {
                try {
                    String dirName = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .withZone(ZoneOffset.UTC)
                            .format(Instant.now()) + "_" + id;
                    Path sessionDir = rootDir().resolve(dirName);
                    return new DebugObservabilitySession(id, sessionDir, objectMapper, 0, 0);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to create debug observability session", e);
                }
            });
            runToSession.put(runId, sessionId);
            AgentContext.setDebugObservabilitySessionId(sessionId);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("debugSessionId", sessionId);
            manifest.put("operator", "run-create");
            manifest.put("scopeRunId", runId);
            manifest.put("userId", userId);
            manifest.put("stressBatchId", request.stressBatchId());
            manifest.put("service", "agentLangchainService");
            manifest.put("host", safeHost());
            manifest.put("startedAt", Instant.now().toString());
            manifest.put("files", java.util.List.of("sandbox-" + runId + ".jsonl"));
            session.writeManifest(manifest);

            emit(Map.of(
                    "eventType", "debug_session_opened",
                    "status", "OK"
            ));
        } catch (Exception ignored) {
            // debug path must not affect run execution
        }
    }

    public void closeRunSession(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        try {
            emit(Map.of(
                    "eventType", "debug_session_closed",
                    "status", "OK"
            ));
            String sessionId = runToSession.remove(runId);
            if (sessionId != null) {
                DebugObservabilitySession session = sessions.get(sessionId);
                if (session != null) {
                    session.writeManifest(session.statusSnapshot());
                    session.close();
                }
            }
        } catch (Exception ignored) {
            // no-op
        } finally {
            AgentContext.clearDebugObservabilitySessionId();
        }
    }

    public boolean isEnabled() {
        String sessionId = AgentContext.getDebugObservabilitySessionId();
        return sessionId != null && sessions.containsKey(sessionId);
    }

    public String sessionDirFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        DebugObservabilitySession session = sessions.get(sessionId);
        return session == null ? null : session.sessionDir().toString();
    }

    public void emit(Map<String, Object> fields) {
        String sessionId = AgentContext.getDebugObservabilitySessionId();
        if (sessionId == null) {
            return;
        }
        DebugObservabilitySession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        String runId = AgentContext.getRunId();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("debugSessionId", sessionId);
        event.put("service", "agentLangchainService");
        event.put("host", safeHost());
        if (runId != null) {
            event.put("runId", runId);
        }
        String userId = AgentContext.getUserId();
        if (userId != null) {
            event.put("userId", userId);
        }
        String phase = AgentContext.getPhase();
        if (phase != null) {
            event.put("phase", phase);
        }
        String stage = AgentContext.getStage();
        if (stage != null) {
            event.put("stage", stage);
        }
        String todoId = AgentContext.getTodoId();
        if (todoId != null) {
            event.put("todoId", todoId);
        }
        Integer todoSeq = AgentContext.getTodoSequence();
        if (todoSeq != null) {
            event.put("todoSequence", todoSeq);
        }
        String toolCallId = AgentContext.getToolCallId();
        if (toolCallId != null) {
            event.put("toolCallId", toolCallId);
        }
        if (fields != null) {
            event.putAll(fields);
        }
        sanitize(event);
        session.appendRunEvent(runId, event);
    }

    static void sanitize(Map<String, Object> event) {
        for (String key : java.util.List.copyOf(event.keySet())) {
            Object value = event.get(key);
            if (value instanceof String s && looksLikeSecret(s)) {
                event.put(key, "<redacted>");
            }
        }
    }

    static boolean looksLikeSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("Bearer ")
                || trimmed.startsWith("eyJ")
                || trimmed.contains("Authorization:")
                || trimmed.startsWith("sk_")
                || trimmed.startsWith("sk-");
    }

    private static String safeHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
