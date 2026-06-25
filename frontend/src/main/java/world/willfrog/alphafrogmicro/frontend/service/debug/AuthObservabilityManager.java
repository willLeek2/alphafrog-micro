package world.willfrog.alphafrogmicro.frontend.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthObservabilityManager {

    private static final DateTimeFormatter DIR_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper;
    private final AuthObservabilityProperties properties;

    private final AtomicReference<AuthObservabilitySession> activeSession = new AtomicReference<>();
    private final ConcurrentHashMap<String, AuthObservabilitySession> sessionsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthObservabilityWriter> writersById = new ConcurrentHashMap<>();

    public EnableResult enable(String operator,
                               String reason,
                               Integer requestedTtlSeconds,
                               AuthObservabilityScope scope,
                               boolean force,
                               boolean forceAllUsers) {
        AuthObservabilitySession current = activeSession.get();
        if (current != null && current.isEnabled() && !current.isExpired(System.currentTimeMillis()) && !force) {
            return EnableResult.conflict(current);
        }

        if (scope == null) {
            scope = new AuthObservabilityScope();
        }
        if (scope.isEmpty() && !forceAllUsers) {
            return EnableResult.error("Scope is empty or equivalent to all users; set forceAllUsers=true to enable.");
        }

        int ttlSeconds = requestedTtlSeconds == null
                ? properties.getAuth().getDefaultTtlSeconds()
                : Math.min(requestedTtlSeconds, properties.getAuth().getDefaultTtlSeconds());
        if (ttlSeconds <= 0) {
            return EnableResult.error("ttlSeconds must be positive.");
        }

        String debugSessionId = generateSessionId();
        String outputDir = buildOutputDir(debugSessionId);
        long now = System.currentTimeMillis();
        AuthObservabilitySession session = new AuthObservabilitySession(
                debugSessionId, now, now + ttlSeconds * 1000L, outputDir, operator, reason, scope);
        sessionsById.put(debugSessionId, session);
        AuthObservabilityWriter writer = new AuthObservabilityWriter(objectMapper, session, properties);
        writersById.put(debugSessionId, writer);

        if (current != null) {
            disable(current.getDebugSessionId(), "REPLACED_BY_NEW_SESSION");
        }
        activeSession.set(session);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operator", operator);
        payload.put("reason", reason);
        payload.put("ttlSeconds", ttlSeconds);
        payload.put("scope", scope);
        writer.writeEvent(AuthObservabilityEvent.DEBUG_SESSION_ENABLED.name(), payload);

        return EnableResult.success(session);
    }

    public boolean disable(String debugSessionId, String reason) {
        AuthObservabilitySession session;
        AuthObservabilityWriter writer;
        if (debugSessionId == null || debugSessionId.isBlank()) {
            session = activeSession.get();
            if (session == null) {
                return false;
            }
            debugSessionId = session.getDebugSessionId();
        } else {
            session = sessionsById.get(debugSessionId);
        }
        if (session == null) {
            return false;
        }
        writer = writersById.get(debugSessionId);
        if (writer != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", reason);
            writer.writeEvent(AuthObservabilityEvent.DEBUG_SESSION_DISABLED.name(), payload);
            writer.closeWithReason(reason);
        }
        session.setEnabled(false);
        session.setStoppedReason(reason);
        if (activeSession.get() == session) {
            activeSession.compareAndSet(session, null);
        }
        return true;
    }

    public AuthObservabilitySession getActiveSession() {
        AuthObservabilitySession session = activeSession.get();
        if (session != null && session.isEnabled() && session.isExpired(System.currentTimeMillis())) {
            disable(session.getDebugSessionId(), "TTL_EXPIRED");
            return null;
        }
        return session;
    }

    public Map<String, Object> buildStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        AuthObservabilitySession session = getActiveSession();
        if (session == null) {
            status.put("active", false);
        } else {
            status.put("active", true);
            status.put("debugSessionId", session.getDebugSessionId());
            status.put("createdAt", Instant.ofEpochMilli(session.getCreatedAt()).toString());
            status.put("ttlDeadline", Instant.ofEpochMilli(session.getTtlDeadline()).toString());
            status.put("remainingTtlSeconds", session.getRemainingTtlSeconds(System.currentTimeMillis()));
            status.put("operator", session.getOperator());
            status.put("reason", session.getReason());
            status.put("outputDir", session.getOutputDir());
            status.put("scope", session.getScope());
            status.put("bytesWritten", session.getBytesWritten().get());
            status.put("linesWritten", session.getLinesWritten().get());
            status.put("droppedByCapacity", session.getDroppedByCapacity().get());
            status.put("droppedBySensitiveFilter", session.getDroppedBySensitiveFilter().get());
            status.put("writerErrorCount", session.getWriterErrorCount().get());
            status.put("stoppedReason", session.getStoppedReason());
        }
        return status;
    }

    public void emitAuthContextRejected(String requestId,
                                         String path,
                                         String method,
                                         String username,
                                         boolean authHeaderPresent,
                                         String tokenSource,
                                         String tokenHashPrefix,
                                         boolean jwtValid,
                                         Long jwtExpDeltaMs,
                                         String rejectReason,
                                         Boolean loginStatusKeyExists,
                                         Long loginStatusTtlMs,
                                         String redisErrorClass) {
        AuthObservabilityWriter writer = resolveWriterForRequest(username, path);
        if (writer == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.put("path", stripQuery(path));
        payload.put("method", method);
        payload.put("username", username);
        payload.put("authHeaderPresent", authHeaderPresent);
        payload.put("tokenSource", tokenSource);
        payload.put("tokenHashPrefix", tokenHashPrefix);
        payload.put("jwtValid", jwtValid);
        payload.put("jwtExpDeltaMs", jwtExpDeltaMs);
        payload.put("rejectReason", rejectReason);
        payload.put("loginStatusKeyExists", loginStatusKeyExists);
        payload.put("loginStatusTtlMs", loginStatusTtlMs);
        payload.put("redisErrorClass", redisErrorClass);
        payload.put("securityContextSet", false);
        payload.put("entryPointExpected", true);
        writer.writeEvent(AuthObservabilityEvent.AUTH_CONTEXT_REJECTED.name(), payload);
    }

    public void emitLoginStatusWrite(String username,
                                      boolean redisSetCalled,
                                      boolean redisSetSuccess,
                                      Object redisSetReturnValue,
                                      Long configuredTtlMinutes,
                                      Long actualTtlMs,
                                      Boolean keyExistsAfterWrite,
                                      Long ttlAfterWriteMs,
                                      String errorClass) {
        AuthObservabilityWriter writer = resolveWriterForUser(username);
        if (writer == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("redisSetCalled", redisSetCalled);
        payload.put("redisSetSuccess", redisSetSuccess);
        payload.put("redisSetReturnValue", redisSetReturnValue);
        payload.put("configuredTtlMinutes", configuredTtlMinutes);
        payload.put("actualTtlMs", actualTtlMs);
        payload.put("keyExistsAfterWrite", keyExistsAfterWrite);
        payload.put("ttlAfterWriteMs", ttlAfterWriteMs);
        payload.put("errorClass", errorClass);
        writer.writeEvent(AuthObservabilityEvent.LOGIN_STATUS_WRITE.name(), payload);
    }

    public void emitLoginStatusCheck(String username,
                                      boolean keyExists,
                                      Long ttlMs,
                                      String errorClass) {
        AuthObservabilityWriter writer = resolveWriterForUser(username);
        if (writer == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("keyExists", keyExists);
        payload.put("ttlMs", ttlMs);
        payload.put("errorClass", errorClass);
        writer.writeEvent(AuthObservabilityEvent.LOGIN_STATUS_CHECK.name(), payload);
    }

    private AuthObservabilityWriter resolveWriterForRequest(String username, String path) {
        AuthObservabilitySession session = getActiveSession();
        if (session == null) {
            return null;
        }
        if (!matchesScope(session.getScope(), username, path)) {
            return null;
        }
        return writersById.get(session.getDebugSessionId());
    }

    private AuthObservabilityWriter resolveWriterForUser(String username) {
        return resolveWriterForRequest(username, null);
    }

    private boolean matchesScope(AuthObservabilityScope scope, String username, String path) {
        if (scope == null || scope.isEmpty()) {
            return true;
        }
        if (username != null && !username.isBlank()) {
            if (scope.getSampleUsers() != null && scope.getSampleUsers().contains(username)) {
                return true;
            }
            if (scope.getUsernamePattern() != null && !scope.getUsernamePattern().isBlank()) {
                try {
                    if (Pattern.compile(scope.getUsernamePattern()).matcher(username).find()) {
                        return true;
                    }
                } catch (Exception e) {
                    log.warn("Invalid usernamePattern in auth observability scope: {}", scope.getUsernamePattern(), e);
                }
            }
        }
        if (path != null && !path.isBlank() && scope.getPathIncludes() != null) {
            String stripped = stripQuery(path);
            for (String include : scope.getPathIncludes()) {
                if (stripped.contains(include)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildOutputDir(String debugSessionId) {
        String timestamp = LocalDateTime.now().format(DIR_TIME_FORMATTER);
        Path root = Paths.get(properties.getOutputRoot());
        return root.resolve(timestamp + "_" + debugSessionId).toString();
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String stripQuery(String path) {
        if (path == null) {
            return null;
        }
        int idx = path.indexOf('?');
        return idx >= 0 ? path.substring(0, idx) : path;
    }

    public static String hashToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(6, hash.length); i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    @RequiredArgsConstructor
    public static class EnableResult {
        private final boolean success;
        private final boolean conflict;
        private final String error;
        private final AuthObservabilitySession session;

        public static EnableResult success(AuthObservabilitySession session) {
            return new EnableResult(true, false, null, session);
        }

        public static EnableResult conflict(AuthObservabilitySession session) {
            return new EnableResult(false, true, null, session);
        }

        public static EnableResult error(String message) {
            return new EnableResult(false, false, message, null);
        }

        public boolean isSuccess() { return success; }
        public boolean isConflict() { return conflict; }
        public String getError() { return error; }
        public AuthObservabilitySession getSession() { return session; }
    }
}
