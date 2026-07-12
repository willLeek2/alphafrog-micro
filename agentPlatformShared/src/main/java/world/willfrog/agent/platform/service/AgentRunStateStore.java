package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.workflow.TodoStatus;
import world.willfrog.agent.workflow.WorkflowState;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunStateStore {

    private static final String PREFIX = "agent:run:";
    private static final String PLAN_KEY = ":plan";
    private static final String PLAN_VALID_KEY = ":plan_valid";
    private static final String PLAN_OVERRIDE_KEY = ":plan_override";
    private static final String STATUS_KEY = ":status";
    private static final String OBSERVABILITY_KEY = ":observability";
    private static final String DATA_ANALYSIS_OBSERVABILITY_KEY = ":data_analysis_observability";
    private static final String DATA_ANALYSIS_SUMMARY_KEY = ":data_analysis_observability:summary";
    private static final String DETAIL_LLM_KEY = ":detail:llm:";
    private static final String DETAIL_TOOL_KEY = ":detail:tool:";
    private static final String RAW_DETAIL_SUFFIX = ":raw";
    private static final String RAW_DETAIL_META_SUFFIX = ":raw:meta";

    private static final String WORKFLOW_STATE_KEY = ":workflow_state";
    private static final String TOOL_CALL_COUNT_KEY = ":tool_call_count";
    private static final String PATCHED_PLAN_KEY = ":patched_plan";

    /**
     * 预算进度告警去重 key（Set 结构，元素为已发过 80% 告警的 dimension 名）。
     * 用于保证 {@code BUDGET_PROGRESS} 事件在单个 run 内对每个 dimension 只发一次（首次跨过 80% 阈值时触发）。
     */
    private static final String BUDGET_PROGRESS_WARNED_KEY = ":budget_progress_warned";

    /**
     * 90% last-mile 提示去重 key（Set 结构，元素为已发过 90% 提示的 dimension 名）。
     * 用于保证 {@code BUDGET_LAST_MILE} 事件 + {@code AgentContext.lastMileHint} 注入在单个 run 内对每个 dimension 只触发一次（首次跨过 90% 阈值时触发）。
     */
    private static final String BUDGET_LAST_MILE_WARNED_KEY = ":budget_last_mile_warned";

    // legacy keys for read compatibility
    private static final String TASK_INDEX_KEY = ":tasks";
    private static final String TASK_KEY_PREFIX = ":task:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    /** Micrometer 指标注册中心（可选，若服务未引入 actuator 则为 null） */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /** Counter 缓存，避免每次操作都重建 Counter */
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    @Value("${agent.flow.hitl.state-ttl-seconds:7200}")
    private long ttlSeconds;

    /** TTL for per-call detail blobs (independent from run state / observability TTL).
     *  Default 6h to keep thinking/reasoning content short-lived while still useful for debugging. */
    @Value("${agent.call-detail.ttl-seconds:21600}")
    private long callDetailTtlSeconds;

    /** Hot-reloadable via agent-llm.json agent.call-raw-content.ttl-seconds; this is only fallback. */
    @Value("${agent.call-raw-content.ttl-seconds:7200}")
    private long callRawContentTtlSeconds;

    public void recordPlan(String runId, String planJson, boolean valid) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(planKey(runId), nvl(planJson));
        redisTemplate.opsForValue().set(planValidKey(runId), String.valueOf(valid));
        touch(planKey(runId));
        touch(planValidKey(runId));
    }

    public void storePlanOverride(String runId, String planJson) {
        if (blank(runId)) {
            return;
        }
        recordPlan(runId, planJson, false);
        markPlanOverride(runId, true);
    }

    public void markPlanOverride(String runId, boolean override) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(planOverrideKey(runId), String.valueOf(override));
        touch(planOverrideKey(runId));
    }

    public void clearPlanOverride(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(planOverrideKey(runId));
    }

    public void clearPlanCache(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(planKey(runId));
        redisTemplate.delete(planValidKey(runId));
        redisTemplate.delete(planOverrideKey(runId));
    }

    public Optional<String> loadPlan(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String planJson = redisTemplate.opsForValue().get(planKey(runId));
        if (planJson == null || planJson.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(planJson);
    }

    public Optional<Boolean> loadPlanValid(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(planValidKey(runId));
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(value));
    }

    public boolean isPlanOverride(String runId) {
        if (blank(runId)) {
            return false;
        }
        String value = redisTemplate.opsForValue().get(planOverrideKey(runId));
        return Boolean.parseBoolean(value);
    }

    public void markRunStatus(String runId, String status) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(statusKey(runId), nvl(status));
        touch(statusKey(runId));
    }

    public Optional<String> loadRunStatus(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String status = redisTemplate.opsForValue().get(statusKey(runId));
        if (status == null || status.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(status);
    }

    public void saveObservability(String runId, String observabilityJson) {
        if (blank(runId)) {
            return;
        }
        String key = observabilityKey(runId);
        try {
            redisTemplate.opsForValue().set(key, nvl(observabilityJson));
            recordRedisMetric("set", true);
            touch(key);
        } catch (Exception e) {
            recordRedisMetric("set", false);
            log.warn("saveObservability redis set failed: runId={}, key={}, error={}", runId, key, e.getMessage());
            throw e;
        }
    }

    public Optional<String> loadObservability(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String key = observabilityKey(runId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            recordRedisMetric("get", true);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(json);
        } catch (Exception e) {
            recordRedisMetric("get", false);
            log.warn("loadObservability redis get failed: runId={}, key={}, error={}", runId, key, e.getMessage());
            return Optional.empty();
        }
    }

    public void saveDataAnalysisObservability(String runId, String snapshotJson, String summaryJson) {
        if (blank(runId)) {
            return;
        }
        String fullKey = dataAnalysisObservabilityKey(runId);
        String summaryKey = dataAnalysisSummaryKey(runId);
        redisTemplate.opsForValue().set(fullKey, nvl(snapshotJson));
        redisTemplate.opsForValue().set(summaryKey, nvl(summaryJson));
        touch(fullKey);
        touch(summaryKey);
    }

    public void saveDataAnalysisObservabilitySummary(String runId, String summaryJson) {
        if (blank(runId)) {
            return;
        }
        String key = dataAnalysisSummaryKey(runId);
        redisTemplate.opsForValue().set(key, nvl(summaryJson));
        touch(key);
    }

    public Optional<String> loadDataAnalysisObservability(String runId) {
        return loadNonBlankValue(dataAnalysisObservabilityKey(runId));
    }

    public Optional<String> loadDataAnalysisObservabilitySummary(String runId) {
        return loadNonBlankValue(dataAnalysisSummaryKey(runId));
    }

    private Optional<String> loadNonBlankValue(String key) {
        if (blank(key)) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null || json.isBlank() ? Optional.empty() : Optional.of(json);
        } catch (Exception e) {
            log.warn("load data-analysis observability failed: key={}, error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void saveLlmCallDetail(String runId, String llmCallId, String detailJson) {
        saveCallDetail(runId, llmCallId, detailJson, true);
    }

    public void saveToolCallDetail(String runId, String toolCallId, String detailJson) {
        saveCallDetail(runId, toolCallId, detailJson, false);
    }

    public void saveLlmCallRawContent(String runId, String llmCallId, String rawJson) {
        if (blank(runId) || blank(llmCallId) || blank(rawJson)) {
            return;
        }
        long ttl = resolveCallRawContentTtlSeconds();
        long createdAtMillis = System.currentTimeMillis();
        long expiresAtMillis = createdAtMillis + ttl * 1000L;
        String rawKey = llmCallRawContentKey(runId, llmCallId);
        String metaKey = llmCallRawMetaKey(runId, llmCallId);
        redisTemplate.opsForValue().set(rawKey, nvl(rawJson), Duration.ofSeconds(ttl));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "llm_raw_meta");
        meta.put("runId", runId);
        meta.put("traceId", llmCallId);
        meta.put("createdAtMillis", createdAtMillis);
        meta.put("expiresAtMillis", expiresAtMillis);
        meta.put("ttlSeconds", ttl);
        redisTemplate.opsForValue().set(metaKey, safeWrite(meta), Duration.ofSeconds(rawMetaTtlSeconds(ttl)));
    }

    public Optional<String> loadLlmCallDetail(String runId, String llmCallId) {
        return loadCallDetail(runId, llmCallId, true);
    }

    public Optional<String> loadToolCallDetail(String runId, String toolCallId) {
        return loadCallDetail(runId, toolCallId, false);
    }

    public Optional<String> loadLlmCallRawContent(String runId, String llmCallId) {
        return loadRaw(rawContentKeyOrBlank(runId, llmCallId));
    }

    public Optional<String> loadLlmCallRawMeta(String runId, String llmCallId) {
        return loadRaw(rawMetaKeyOrBlank(runId, llmCallId));
    }

    /** Public for frontend Redis reader — keep in sync with detail key layout. */
    public String llmCallDetailKey(String runId, String llmCallId) {
        return PREFIX + runId + DETAIL_LLM_KEY + nvl(llmCallId);
    }

    /** Public for frontend Redis reader — keep in sync with raw detail key layout. */
    public String llmCallRawContentKey(String runId, String llmCallId) {
        return llmCallDetailKey(runId, llmCallId) + RAW_DETAIL_SUFFIX;
    }

    /** Public for frontend Redis reader — keep in sync with raw detail key layout. */
    public String llmCallRawMetaKey(String runId, String llmCallId) {
        return llmCallDetailKey(runId, llmCallId) + RAW_DETAIL_META_SUFFIX;
    }

    /** Public for frontend Redis reader — keep in sync with detail key layout. */
    public String toolCallDetailKey(String runId, String toolCallId) {
        return PREFIX + runId + DETAIL_TOOL_KEY + nvl(toolCallId);
    }

    public String dataAnalysisObservabilityKey(String runId) {
        return blank(runId) ? "" : PREFIX + runId + DATA_ANALYSIS_OBSERVABILITY_KEY;
    }

    public String dataAnalysisSummaryKey(String runId) {
        return blank(runId) ? "" : PREFIX + runId + DATA_ANALYSIS_SUMMARY_KEY;
    }

    private void saveCallDetail(String runId, String callId, String detailJson, boolean llm) {
        if (blank(runId) || blank(callId)) {
            return;
        }
        String key = llm ? llmCallDetailKey(runId, callId) : toolCallDetailKey(runId, callId);
        redisTemplate.opsForValue().set(key, nvl(detailJson));
        touchCallDetail(key);
    }

    private Optional<String> loadCallDetail(String runId, String callId, boolean llm) {
        if (blank(runId) || blank(callId)) {
            return Optional.empty();
        }
        String key = llm ? llmCallDetailKey(runId, callId) : toolCallDetailKey(runId, callId);
        return loadRaw(key);
    }

    private Optional<String> loadRaw(String key) {
        if (blank(key)) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(json);
    }

    private String rawContentKeyOrBlank(String runId, String llmCallId) {
        return blank(runId) || blank(llmCallId) ? "" : llmCallRawContentKey(runId, llmCallId);
    }

    private String rawMetaKeyOrBlank(String runId, String llmCallId) {
        return blank(runId) || blank(llmCallId) ? "" : llmCallRawMetaKey(runId, llmCallId);
    }

    private long resolveCallRawContentTtlSeconds() {
        Long hotValue = localConfigLoader == null ? null : localConfigLoader.current()
                .map(AgentLlmProperties::getAgent)
                .map(AgentLlmProperties.Agent::getCallRawContent)
                .map(AgentLlmProperties.CallRawContent::getTtlSeconds)
                .filter(value -> value != null && value > 0)
                .orElse(null);
        if (hotValue != null) {
            return hotValue;
        }
        return callRawContentTtlSeconds > 0 ? callRawContentTtlSeconds : 7200L;
    }

    private long rawMetaTtlSeconds(long rawTtlSeconds) {
        long detailTtl = callDetailTtlSeconds > 0 ? callDetailTtlSeconds : 86400L;
        return Math.max(rawTtlSeconds, detailTtl);
    }

    public void saveWorkflowState(String runId, WorkflowState state) {
        if (blank(runId) || state == null) {
            return;
        }
        redisTemplate.opsForValue().set(workflowStateKey(runId), safeWrite(state));
        touch(workflowStateKey(runId));
    }

    public Optional<WorkflowState> loadWorkflowState(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(workflowStateKey(runId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, WorkflowState.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void clearWorkflowState(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(workflowStateKey(runId));
    }

    public void savePatchedPlan(String runId, String planJson) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(patchedPlanKey(runId), nvl(planJson));
        touch(patchedPlanKey(runId));
    }

    public Optional<String> loadPatchedPlan(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(patchedPlanKey(runId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(json);
    }

    public void clearPatchedPlan(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(patchedPlanKey(runId));
    }

    public int incrementToolCallCount(String runId, int delta) {
        if (blank(runId)) {
            return 0;
        }
        int safeDelta = Math.max(0, delta);
        if (safeDelta == 0) {
            return getToolCallCount(runId);
        }
        Long value = redisTemplate.opsForValue().increment(toolCallCountKey(runId), safeDelta);
        touch(toolCallCountKey(runId));
        return value == null ? 0 : Math.max(0, value.intValue());
    }

    public int getToolCallCount(String runId) {
        if (blank(runId)) {
            return 0;
        }
        String value = redisTemplate.opsForValue().get(toolCallCountKey(runId));
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (Exception e) {
            return 0;
        }
    }

    public void resetToolCallCount(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(toolCallCountKey(runId));
    }

    public void setToolCallCount(String runId, int count) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.opsForValue().set(toolCallCountKey(runId), String.valueOf(Math.max(0, count)));
        touch(toolCallCountKey(runId));
    }

    /**
     * 原子地尝试标记某个 dimension 在本 run 已发过 80% 预算进度告警。
     * 利用 Redis {@code SADD} 的原子语义（元素不存在时返回 1，已存在时返回 0），
     * 保证同 {@code (runId, dimension)} 组合在并发场景（并行 DAG 节点、并发 LLM/tool 入口）下也只发一次 {@code BUDGET_PROGRESS} 事件。
     * <p>调用方应只在返回 {@code true} 时发出事件；返回 {@code false} 表示已被其它线程抢先标记，应跳过。</p>
     *
     * @return true 表示本次新加入（应当前调用方发事件）；false 表示已存在（应跳过）
     */
    public boolean tryMarkBudgetProgressWarned(String runId, String dimension) {
        if (blank(runId) || blank(dimension)) {
            return false;
        }
        Long added = redisTemplate.opsForSet().add(budgetProgressWarnedKey(runId), dimension);
        touch(budgetProgressWarnedKey(runId));
        return added != null && added > 0;
    }

    /**
     * 非破坏性查询某 dimension 是否已发过 80% 预算进度告警。
     * 不修改 Redis 状态，用于监控/测试的只读场景。
     * <p>注意：发事件路径请使用 {@link #tryMarkBudgetProgressWarned} 原子 gate，
     * 不要先 {@code has} 再 {@code mark} 两步走——并发下两步之间会被其他线程插队导致重复事件。</p>
     *
     * @return true 表示已发过，false 表示尚未发过
     */
    public boolean hasBudgetProgressWarned(String runId, String dimension) {
        if (blank(runId) || blank(dimension)) {
            return false;
        }
        Boolean member = redisTemplate.opsForSet().isMember(budgetProgressWarnedKey(runId), dimension);
        return Boolean.TRUE.equals(member);
    }

    /**
     * 清空本 run 的 80% 预算进度告警去重 Set。
     * 由 {@link #clear} 在 run 结束时统一调用；单独暴露用于测试和运维手工清理。
     */
    public void clearBudgetProgressWarned(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(budgetProgressWarnedKey(runId));
    }

    /**
     * 原子地尝试标记某个 dimension 在本 run 已发过 90% last-mile 提示。
     * 与 {@link #tryMarkBudgetProgressWarned} 同语义（Redis SADD 返回值做 check-and-set），
     * 保证同 {@code (runId, dimension)} 在并发场景下只触发一次 {@code BUDGET_LAST_MILE} 事件 + {@code AgentContext.lastMileHint} 写入。
     *
     * @return true 表示本次新加入（应当前调用方发事件 + 写 hint）；false 表示已存在（应跳过）
     */
    public boolean tryMarkBudgetLastMileWarned(String runId, String dimension) {
        if (blank(runId) || blank(dimension)) {
            return false;
        }
        Long added = redisTemplate.opsForSet().add(budgetLastMileWarnedKey(runId), dimension);
        touch(budgetLastMileWarnedKey(runId));
        return added != null && added > 0;
    }

    /**
     * 非破坏性查询某 dimension 是否已发过 90% last-mile 提示。
     * 不修改 Redis 状态，用于监控/测试的只读场景。
     */
    public boolean hasBudgetLastMileWarned(String runId, String dimension) {
        if (blank(runId) || blank(dimension)) {
            return false;
        }
        Boolean member = redisTemplate.opsForSet().isMember(budgetLastMileWarnedKey(runId), dimension);
        return Boolean.TRUE.equals(member);
    }

    /**
     * 清空本 run 的 90% last-mile 提示去重 Set。
     * 由 {@link #clear} 在 run 结束时统一调用；单独暴露用于测试和运维手工清理。
     */
    public void clearBudgetLastMileWarned(String runId) {
        if (blank(runId)) {
            return;
        }
        redisTemplate.delete(budgetLastMileWarnedKey(runId));
    }

    public String buildProgressJson(String runId, String planJson) {
        JsonNode root = parseJson(planJson);
        if (root != null && root.path("items").isArray()) {
            return buildTodoProgressJson(runId, root.path("items"));
        }
        if (root != null && root.path("tasks").isArray()) {
            return buildLegacyProgressJson(runId, root.path("tasks"));
        }
        return "{}";
    }

    public void clear(String runId) {
        if (blank(runId)) {
            return;
        }
        clearTasks(runId);
        redisTemplate.delete(planKey(runId));
        redisTemplate.delete(planValidKey(runId));
        redisTemplate.delete(planOverrideKey(runId));
        redisTemplate.delete(statusKey(runId));
        redisTemplate.delete(observabilityKey(runId));
        redisTemplate.delete(workflowStateKey(runId));
        redisTemplate.delete(toolCallCountKey(runId));
        redisTemplate.delete(patchedPlanKey(runId));
        redisTemplate.delete(budgetProgressWarnedKey(runId));
        redisTemplate.delete(budgetLastMileWarnedKey(runId));
    }

    public void clearTasks(String runId) {
        if (blank(runId)) {
            return;
        }
        // clear new workflow checkpoint
        redisTemplate.delete(workflowStateKey(runId));
        // clear legacy task states
        Set<String> taskIds = redisTemplate.opsForSet().members(taskIndexKey(runId));
        if (taskIds != null) {
            for (String taskId : taskIds) {
                redisTemplate.delete(taskKey(runId, taskId));
            }
        }
        redisTemplate.delete(taskIndexKey(runId));
    }

    private String buildTodoProgressJson(String runId, JsonNode itemsNode) {
        Optional<WorkflowState> workflowState = loadWorkflowState(runId);
        Map<String, String> completedStatusById = new HashMap<>();
        Set<String> runningNodeIds = Set.of();
        int workflowToolCallsUsed = 0;
        int currentIndex = -1;
        if (workflowState.isPresent()) {
            currentIndex = workflowState.get().getCurrentIndex();
            workflowToolCallsUsed = Math.max(0, workflowState.get().getToolCallsUsed());
            if (workflowState.get().getRunningNodeIds() != null) {
                runningNodeIds = workflowState.get().getRunningNodeIds();
            }
            for (var item : workflowState.get().getCompletedItems()) {
                String key = nvl(item.getId());
                if (key.isBlank()) {
                    continue;
                }
                TodoStatus status = item.getStatus() == null ? TodoStatus.COMPLETED : item.getStatus();
                completedStatusById.put(key, status.name());
            }
        }

        int total = 0;
        int completed = 0;
        int failed = 0;
        int running = 0;
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();

        int idx = 0;
        for (JsonNode node : itemsNode) {
            total++;
            String id = nvl(node.path("id").asText("todo_" + (idx + 1)));
            String status = completedStatusById.get(id);
            if (status == null || status.isBlank()) {
                status = runningNodeIds.contains(id) || idx == currentIndex ? "RUNNING" : "PENDING";
            }

            if ("COMPLETED".equals(status)) {
                completed++;
            } else if ("FAILED".equals(status) || "SKIPPED".equals(status)) {
                failed++;
            } else if ("RUNNING".equals(status)) {
                running++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("sequence", node.path("sequence").asInt(idx + 1));
            row.put("type", nvl(node.path("type").asText("")));
            row.put("tool", nvl(node.path("toolName").asText("")));
            row.put("status", status);
            tasks.add(row);
            idx++;
        }

        int pending = Math.max(0, total - completed - failed - running);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("completed", completed);
        payload.put("failed", failed);
        payload.put("running", running);
        payload.put("pending", pending);
        payload.put("tasks", tasks);
        payload.put("tool_calls_used", workflowState.isPresent() ? workflowToolCallsUsed : getToolCallCount(runId));
        return safeWrite(payload);
    }

    private String buildLegacyProgressJson(String runId, JsonNode tasksNode) {
        Set<String> taskIds = redisTemplate.opsForSet().members(taskIndexKey(runId));
        Map<String, String> legacyStatus = new HashMap<>();
        if (taskIds != null) {
            for (String taskId : taskIds) {
                JsonNode taskState = parseJson(redisTemplate.opsForValue().get(taskKey(runId, taskId)));
                if (taskState != null) {
                    legacyStatus.put(taskId, nvl(taskState.path("status").asText("PENDING")));
                }
            }
        }

        int total = 0;
        int completed = 0;
        int failed = 0;
        int running = 0;
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();

        for (JsonNode node : tasksNode) {
            total++;
            String id = nvl(node.path("id").asText(""));
            String status = legacyStatus.getOrDefault(id, "PENDING");
            if ("COMPLETED".equals(status)) {
                completed++;
            } else if ("FAILED".equals(status)) {
                failed++;
            } else if ("RUNNING".equals(status)) {
                running++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("type", nvl(node.path("type").asText("")));
            row.put("tool", nvl(node.path("tool").asText("")));
            row.put("status", status);
            tasks.add(row);
        }

        int pending = Math.max(0, total - completed - failed - running);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("completed", completed);
        payload.put("failed", failed);
        payload.put("running", running);
        payload.put("pending", pending);
        payload.put("tasks", tasks);
        return safeWrite(payload);
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private void touch(String key) {
        touchWithTtl(key, ttlSeconds);
    }

    private void touchCallDetail(String key) {
        touchWithTtl(key, callDetailTtlSeconds);
    }

    private void touchWithTtl(String key, long ttl) {
        if (ttl <= 0 || key == null || key.isBlank()) {
            return;
        }
        try {
            redisTemplate.expire(key, Duration.ofSeconds(ttl));
            recordRedisMetric("expire", true);
        } catch (Exception e) {
            log.warn("touch redis key failed: key={}, ttl={}s, error={}", key, ttl, e.getMessage());
            recordRedisMetric("expire", false);
        }
    }

    /**
     * 记录 Redis 操作指标（Micrometer Counter）。
     * 当 meterRegistry 不可用时静默跳过，不影响业务逻辑。
     */
    private void recordRedisMetric(String operation, boolean success) {
        if (meterRegistry == null) {
            return;
        }
        String cacheKey = operation + "_" + success;
        Counter counter = counterCache.computeIfAbsent(cacheKey, k ->
                Counter.builder("redis.operation")
                        .description("Redis operation count")
                        .tag("operation", operation)
                        .tag("result", success ? "success" : "failure")
                        .register(meterRegistry)
        );
        counter.increment();
    }

    private String safeWrite(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private String planKey(String runId) {
        return PREFIX + runId + PLAN_KEY;
    }

    private String planValidKey(String runId) {
        return PREFIX + runId + PLAN_VALID_KEY;
    }

    private String planOverrideKey(String runId) {
        return PREFIX + runId + PLAN_OVERRIDE_KEY;
    }

    private String statusKey(String runId) {
        return PREFIX + runId + STATUS_KEY;
    }

    private String observabilityKey(String runId) {
        return PREFIX + runId + OBSERVABILITY_KEY;
    }

    private String workflowStateKey(String runId) {
        return PREFIX + runId + WORKFLOW_STATE_KEY;
    }

    private String toolCallCountKey(String runId) {
        return PREFIX + runId + TOOL_CALL_COUNT_KEY;
    }

    private String patchedPlanKey(String runId) {
        return PREFIX + runId + PATCHED_PLAN_KEY;
    }

    private String budgetProgressWarnedKey(String runId) {
        return PREFIX + runId + BUDGET_PROGRESS_WARNED_KEY;
    }

    private String budgetLastMileWarnedKey(String runId) {
        return PREFIX + runId + BUDGET_LAST_MILE_WARNED_KEY;
    }

    private String taskIndexKey(String runId) {
        return PREFIX + runId + TASK_INDEX_KEY;
    }

    private String taskKey(String runId, String taskId) {
        return PREFIX + runId + TASK_KEY_PREFIX + taskId;
    }
}
