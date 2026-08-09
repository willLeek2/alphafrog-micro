package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.frontend.model.agent.TimelineResponse;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 遍历安全 trace 视图，并将其合并到事件 timeline。 */
@Service
@RequiredArgsConstructor
public class AgentTimelineMergeService {

    private static final int MAX_OBSERVABILITY_BYTES = 5 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public void mergeTraceItems(String observabilityJson,
                                List<TimelineResponse.TimelineItem> items,
                                String minEventTime,
                                String maxEventTime,
                                int maxAdditionalItems) {
        if (maxAdditionalItems <= 0 || observabilityJson == null || observabilityJson.isBlank()
                || observabilityJson.getBytes(StandardCharsets.UTF_8).length > MAX_OBSERVABILITY_BYTES) {
            return;
        }
        try {
            Map<String, Object> observability = objectMapper.readValue(observabilityJson, Map.class);
            Map<String, Object> diagnostics = observability.get("diagnostics") instanceof Map
                    ? (Map<String, Object>) observability.get("diagnostics") : Map.of();
            AtomicInteger traceSeq = new AtomicInteger(1);
            append(items, diagnostics.get("llmTraces"), "llm", minEventTime, maxEventTime,
                    traceSeq, maxAdditionalItems);
            append(items, diagnostics.get("toolTraces"), "tool", minEventTime, maxEventTime,
                    traceSeq, maxAdditionalItems);
        } catch (Exception ignored) {
            // timeline 的 trace 补充信息是可选项，解析失败时不得暴露原始数据作为降级结果。
        }
    }

    private void append(List<TimelineResponse.TimelineItem> items,
                        Object tracesObject,
                        String traceType,
                        String minEventTime,
                        String maxEventTime,
                        AtomicInteger traceSeq,
                        int maxAdditionalItems) {
        if (!(tracesObject instanceof List<?> traces)) {
            return;
        }
        for (Object item : traces) {
            if (traceSeq.get() > maxAdditionalItems) {
                return;
            }
            if (!(item instanceof Map<?, ?> trace)) {
                continue;
            }
            Object mappedTrace = AgentExternalObservabilityMapper.sanitize(
                    trace, AgentExternalObservabilityMapper.View.EVENT);
            if (!(mappedTrace instanceof Map<?, ?> safeTrace)) {
                continue;
            }
            String traceTime = safeString(safeTrace.get("time"), 128);
            if (!withinWindow(traceTime, minEventTime, maxEventTime)) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("trace_id", safeString(safeTrace.get("traceId"), 512));
            detail.put("phase", safeString(safeTrace.get("phase"), 512));
            detail.put("todo_id", emptyToNull(safeString(safeTrace.get("todoId"), 512)));
            detail.put("duration_ms", longValue(safeTrace.get("durationMs")));
            if ("llm".equals(traceType)) {
                detail.put("model", safeString(safeTrace.get("model"), 512));
                detail.put("endpoint", safeString(safeTrace.get("endpoint"), 2000));
                detail.put("has_error", boolValue(safeTrace.get("hasError")));
                detail.put("input_tokens", nullableLong(safeTrace.get("inputTokens")));
                detail.put("output_tokens", nullableLong(safeTrace.get("outputTokens")));
            } else {
                detail.put("tool_name", safeString(safeTrace.get("toolName"), 512));
                detail.put("success", boolValue(safeTrace.get("success")));
                detail.put("cache_hit", boolValue(safeTrace.get("cacheHit")));
            }
            String traceId = safeString(safeTrace.get("traceId"), 512);
            items.add(new TimelineResponse.TimelineItem(
                    -traceSeq.getAndIncrement(),
                    "trace",
                    traceId,
                    traceType,
                    traceTime,
                    traceTitle(traceType, safeTrace),
                    longValue(safeTrace.get("durationMs")),
                    detail
            ));
        }
    }

    private boolean withinWindow(String time, String minEventTime, String maxEventTime) {
        if (time == null || time.isBlank()) {
            return false;
        }
        if (minEventTime == null || maxEventTime == null) {
            return true;
        }
        return time.compareTo(minEventTime) >= 0 && time.compareTo(maxEventTime) <= 0;
    }

    private String traceTitle(String traceType, Map<?, ?> trace) {
        String phase = safeString(trace.get("phase"), 512);
        long durationMs = longValue(trace.get("durationMs"));
        if ("llm".equals(traceType)) {
            return safeString("LLM " + phase + " " + safeString(trace.get("model"), 512)
                    + " " + durationMs + "ms", 120);
        }
        return safeString("Tool " + phase + " " + safeString(trace.get("toolName"), 512)
                + " " + durationMs + "ms", 120);
    }

    private String safeString(Object value, int maxChars) {
        String safe = AgentExternalObservabilityMapper.safePreview(value, maxChars);
        return safe == null ? "" : safe;
    }

    private long longValue(Object value) {
        Long parsed = nullableLong(value);
        return parsed == null ? 0L : parsed;
    }

    private Long nullableLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean boolValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
