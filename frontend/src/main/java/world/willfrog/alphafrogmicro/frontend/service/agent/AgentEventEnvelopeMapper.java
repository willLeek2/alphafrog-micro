package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunEventResponse;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D20 REST/SSE event schema 的单一映射器。
 *
 * <p>数据库 replay、REST 与 Redis live 都在这里变成同一 schemaVersion=1 envelope。
 * payload 总是 object；畸形 JSON 返回脱敏哨兵而不是回显原始存储字符串。</p>
 */
public final class AgentEventEnvelopeMapper {

    public static final int SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "agent.event";
    public static final String INVALID_JSON_VALUE = "INVALID_JSON";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private AgentEventEnvelopeMapper() {
    }

    public static AgentRunEventResponse fromEventMessage(ObjectMapper objectMapper,
                                                          AgentRunEventMessage event) {
        Long id = event.getId() > 0 ? event.getId() : null;
        String createdAt = emptyToNull(event.getCreatedAt());
        return new AgentRunEventResponse(
                SCHEMA_VERSION,
                EVENT_TYPE,
                id,
                event.getRunId(),
                event.getSeq(),
                event.getEventType(),
                payloadFromJson(objectMapper, event.getPayloadJson()),
                createdAt,
                createdAtEpochMs(createdAt),
                true
        );
    }

    public static AgentRunEventResponse fromRedisEnvelope(ObjectMapper objectMapper,
                                                           String envelopeJson) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid agent event envelope JSON", e);
        }
        int sourceSeq = intValue(envelope.get("seq"));
        boolean durable = sourceSeq >= 1
                && booleanValue(envelope.get("durable"), true);
        int seq = durable ? sourceSeq : 0;
        Long id = durable ? longValue(envelope.get("id")) : null;
        String createdAt = emptyToNull(stringValue(envelope.get("createdAt")));
        Map<String, Object> payload = envelope.containsKey("payload")
                ? payloadFromValue(envelope.get("payload"))
                : payloadFromJson(objectMapper, stringValue(envelope.get("payloadJson")));
        return new AgentRunEventResponse(
                SCHEMA_VERSION,
                EVENT_TYPE,
                id != null && id > 0 ? id : null,
                stringValue(envelope.get("runId")),
                seq,
                stringValue(envelope.get("eventType")),
                payload,
                createdAt,
                createdAtEpochMs(createdAt),
                durable
        );
    }

    public static Map<String, Object> payloadFromJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return payloadFromValue(objectMapper.readValue(json, Object.class));
        } catch (Exception ignored) {
            Map<String, Object> invalid = new LinkedHashMap<>();
            invalid.put("value", INVALID_JSON_VALUE);
            return invalid;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> payloadFromValue(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        Object safe = AgentExternalObservabilityMapper.sanitize(
                value, AgentExternalObservabilityMapper.View.EVENT);
        if (safe instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", safe);
        return wrapped;
    }

    static long createdAtEpochMs(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return 0L;
        }
        String value = createdAt.trim();
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
