package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.agent.idl.AgentLlmCallCostMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunCostMessage;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunCostService {

    private static final String COST_EXT_KEY = "openrouter_run_cost";

    private final AgentRunMapper runMapper;
    private final ObjectMapper objectMapper;

    public AgentRunCostMessage buildAndPersist(AgentRun run, String observabilityJson) {
        AgentRunCostMessage projected = build(run, observabilityJson, false);
        boolean persisted = persistRunTotal(run, projected);
        if (persisted == projected.getPersisted()) {
            return projected;
        }
        return projected.toBuilder().setPersisted(persisted).build();
    }

    public AgentRunCostMessage build(AgentRun run, String observabilityJson, boolean persisted) {
        AgentRunCostMessage.Builder builder = AgentRunCostMessage.newBuilder()
                .setId(run == null ? "" : nvl(run.getId()))
                .setCurrency("USD")
                .setSource("openrouter_observability")
                .setUpdatedAt(OffsetDateTime.now().toString())
                .setPersisted(persisted);

        Map<String, Object> obs = readObject(observabilityJson);
        Map<String, Object> diagnostics = asMap(obs.get("diagnostics"));
        Object tracesObj = diagnostics.get("llmTraces");
        if (!(tracesObj instanceof List<?> traces)) {
            return builder.build();
        }

        double totalCost = 0D;
        double upstreamCost = 0D;
        double cacheDiscount = 0D;
        boolean hasTotal = false;
        boolean hasUpstream = false;
        boolean hasCacheDiscount = false;
        int totalCalls = 0;
        int costedCalls = 0;

        for (Object item : traces) {
            if (!(item instanceof Map<?, ?> trace)) {
                continue;
            }
            totalCalls++;
            AgentLlmCallCostMessage.Builder call = AgentLlmCallCostMessage.newBuilder()
                    .setTraceId(str(trace.get("traceId")))
                    .setGenerationId(str(trace.get("generationId")))
                    .setPhase(str(trace.get("phase")))
                    .setTodoId(str(trace.get("todoId")))
                    .setEndpoint(str(trace.get("endpoint")))
                    .setModel(str(trace.get("model")))
                    .setStartedAtMs(nonNegativeLong(trace.get("startedAtMillis")))
                    .setCompletedAtMs(nonNegativeLong(trace.get("completedAtMillis")))
                    .setSource("openrouter_observability");

            Double actual = dbl(trace.get("actualCost"));
            if (actual != null) {
                call.setActualCost(actual).setHasActualCost(true);
                totalCost += Math.max(0D, actual);
                hasTotal = true;
                costedCalls++;
            }
            Double upstream = dbl(trace.get("upstreamCost"));
            if (upstream != null) {
                call.setUpstreamInferenceCost(upstream).setHasUpstreamInferenceCost(true);
                upstreamCost += Math.max(0D, upstream);
                hasUpstream = true;
            }
            Double discount = dbl(trace.get("cacheDiscount"));
            if (discount != null) {
                call.setCacheDiscount(discount).setHasCacheDiscount(true);
                cacheDiscount += Math.max(0D, discount);
                hasCacheDiscount = true;
            }
            Boolean byok = bool(trace.get("isByok"));
            if (byok != null) {
                call.setIsByok(byok).setHasIsByok(true);
            }
            builder.addCalls(call);
        }

        builder.setTotalCallCount(totalCalls)
                .setCostedCallCount(costedCalls)
                .setComplete(totalCalls > 0 && costedCalls == totalCalls);
        if (hasTotal) {
            builder.setTotalCost(totalCost).setHasTotalCost(true);
        }
        if (hasUpstream) {
            builder.setUpstreamInferenceCost(upstreamCost).setHasUpstreamInferenceCost(true);
        }
        if (hasCacheDiscount) {
            builder.setCacheDiscount(cacheDiscount).setHasCacheDiscount(true);
        }
        return builder.build();
    }

    private boolean persistRunTotal(AgentRun run, AgentRunCostMessage cost) {
        if (run == null || run.getId() == null || run.getId().isBlank()
                || run.getUserId() == null || run.getUserId().isBlank()
                || !cost.getHasTotalCost()) {
            return false;
        }
        Map<String, Object> ext = readExtObject(run);
        if (ext == null) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total_cost", cost.getTotalCost());
        payload.put("upstream_inference_cost", cost.getHasUpstreamInferenceCost()
                ? cost.getUpstreamInferenceCost() : null);
        payload.put("cache_discount", cost.getHasCacheDiscount() ? cost.getCacheDiscount() : null);
        payload.put("currency", cost.getCurrency());
        payload.put("costed_call_count", cost.getCostedCallCount());
        payload.put("total_call_count", cost.getTotalCallCount());
        payload.put("complete", cost.getComplete());
        payload.put("source", cost.getSource());
        payload.put("updated_at", cost.getUpdatedAt());
        ext.put(COST_EXT_KEY, payload);
        return runMapper.updateExt(run.getId(), run.getUserId(), write(ext)) > 0;
    }

    private Map<String, Object> readExtObject(AgentRun run) {
        String json = run.getExt();
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Skip persisting run cost because run ext is invalid JSON: runId={} error={}",
                    run.getId(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> readObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private Double dbl(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private long nonNegativeLong(Object value) {
        if (value instanceof Number n) {
            return Math.max(0L, n.longValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Math.max(0L, Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
