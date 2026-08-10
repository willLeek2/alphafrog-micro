package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 高频 status 查询的只读聚合器；不拥有 Run 权限校验或控制写路径。 */
final class LangchainRunStatusReadModel {

    private final AgentEventService eventService;
    private final AgentRunStateStore stateStore;
    private final AgentObservabilityService observabilityService;
    private final AgentCreditService creditService;
    private final ObjectMapper objectMapper;
    private final LangchainDataAnalysisReadOverlay dataAnalysisOverlay;

    LangchainRunStatusReadModel(AgentEventService eventService,
                                AgentRunStateStore stateStore,
                                AgentObservabilityService observabilityService,
                                AgentCreditService creditService,
                                ObjectMapper objectMapper,
                                LangchainDataAnalysisReadOverlay dataAnalysisOverlay) {
        this.eventService = eventService;
        this.stateStore = stateStore;
        this.observabilityService = observabilityService;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.dataAnalysisOverlay = dataAnalysisOverlay;
    }

    AgentRunStatusMessage build(AgentRun run) {
        AgentRunEvent latestEvent = eventService.findLatestByRunId(run.getId());
        String planJson = nvl(run.getPlanJson());
        var cachedPlan = stateStore.loadPlan(run.getId());
        if (cachedPlan.isPresent()) {
            planJson = cachedPlan.get();
        }
        String progressJson = planJson.isBlank() ? "" : stateStore.buildProgressJson(run.getId(), planJson);
        String observabilitySummaryJson = observabilityService.loadObservabilitySummaryJson(
                run.getId(), run.getSnapshotJson());
        observabilitySummaryJson = dataAnalysisOverlay.mergeStatus(run, observabilitySummaryJson);
        boolean fullAvailable = observabilityService.isFullObservabilityAvailable(run.getId(), run.getSnapshotJson());
        int totalCredits = creditService.calculateRunTotalCredits(
                run, eventService.listByRunId(run.getId()), observabilitySummaryJson);
        Integer maxSeq = eventService.findMaxSeq(run.getId());
        return toMessage(run, latestEvent, planJson, progressJson, observabilitySummaryJson,
                fullAvailable, totalCredits, maxSeq == null ? 0 : maxSeq);
    }

    private AgentRunStatusMessage toMessage(AgentRun run,
                                            AgentRunEvent lastEvent,
                                            String planJson,
                                            String progressJson,
                                            String observabilitySummaryJson,
                                            boolean observabilityFullAvailable,
                                            int totalCreditsConsumed,
                                            int eventCount) {
        String lastEventType = lastEvent == null ? "" : nvl(lastEvent.getEventType());
        return AgentRunStatusMessage.newBuilder()
                .setId(nvl(run.getId()))
                .setStatus(run.getStatus() == null ? "" : run.getStatus().name())
                .setPhase(resolvePhase(run.getStatus(), lastEventType))
                .setCurrentTool(resolveCurrentTool(lastEventType,
                        lastEvent == null ? null : lastEvent.getPayloadJson()))
                .setLastEventType(lastEventType)
                .setLastEventAt(lastEvent == null || lastEvent.getCreatedAt() == null
                        ? "" : lastEvent.getCreatedAt().toString())
                .setLastEventPayloadJson(lastEvent == null ? "" : nvl(lastEvent.getPayloadJson()))
                .setPlanJson(nvl(planJson))
                .setProgressJson(nvl(progressJson))
                .setObservabilityJson("")
                .setObservabilitySummaryJson(nvl(observabilitySummaryJson))
                .setObservabilityFullAvailable(observabilityFullAvailable)
                .setTotalCreditsConsumed(Math.max(0, totalCreditsConsumed))
                .setEventCount(eventCount)
                .setStartedAtMs(toEpochMillis(run.getStartedAt()))
                .setCompletedAtMs(toEpochMillis(run.getCompletedAt()))
                .setElapsedMs(computeElapsedMs(run, System.currentTimeMillis()))
                .build();
    }

    private String resolvePhase(AgentRunStatus status, String lastEventType) {
        if (status == null) {
            return "";
        }
        if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED) {
            return status.name();
        }
        if (status == AgentRunStatus.WAITING) {
            return "PAUSED";
        }
        if (status == AgentRunStatus.WAITING_TOOL_JOB) {
            return "WAITING_TOOL_JOB";
        }
        if ("PLAN_READY".equals(lastEventType) || "PLANNING_STARTED".equals(lastEventType)
                || "TODO_LIST_CREATED".equals(lastEventType)) {
            return "PLANNING";
        }
        if ("FINAL_ANSWER_GENERATING".equals(lastEventType) || "SUMMARIZING_STARTED".equals(lastEventType)) {
            return "SUMMARIZING";
        }
        if ("TOOL_CALL_STARTED".equals(lastEventType)) {
            return "EXECUTING_TOOL";
        }
        if ("EXECUTION_STARTED".equals(lastEventType) || "TODO_STARTED".equals(lastEventType)
                || "TODO_FINISHED".equals(lastEventType) || "WORKFLOW_RESUMED".equals(lastEventType)) {
            return "EXECUTING";
        }
        return status.name();
    }

    private String resolveCurrentTool(String lastEventType, String payloadJson) {
        if (!"TOOL_CALL_STARTED".equals(lastEventType) || payloadJson == null || payloadJson.isBlank()) {
            return "";
        }
        Map<String, Object> payload = readJsonMap(payloadJson);
        return firstNonBlank(stringValue(payload.get("tool_name")), stringValue(payload.get("tool")));
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private long toEpochMillis(OffsetDateTime time) {
        return time == null ? 0L : time.toInstant().toEpochMilli();
    }

    private long computeElapsedMs(AgentRun run, long nowMs) {
        if (run.getStartedAt() == null) {
            return 0L;
        }
        long startMs = run.getStartedAt().toInstant().toEpochMilli();
        return run.getCompletedAt() == null
                ? Math.max(0L, nowMs - startMs)
                : Math.max(0L, run.getCompletedAt().toInstant().toEpochMilli() - startMs);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : nvl(second);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
