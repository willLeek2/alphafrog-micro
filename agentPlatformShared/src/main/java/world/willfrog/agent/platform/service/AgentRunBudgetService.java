package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.exception.RunBudgetException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run 级资源预算（budget）检查服务 —— 从四个维度限制单个 agent run 的资源消耗。
 *
 * <h2>四维预算</h2>
 * <ol>
 *   <li><b>wall_clock_ms</b>（挂钟时间）：从 run 启动到当前的毫秒数，超限即终止。</li>
 *   <li><b>llm_calls</b>（LLM 调用次数）：仅 LLM 调用（非工具调用）触发检查，超过上限时拒绝后续 LLM 请求。</li>
 *   <li><b>tool_calls</b>（工具调用次数）：仅工具调用触发检查。</li>
 *   <li><b>tokens</b>（token 消耗）：每次 LLM 调用前后都检查，
 *       因为 token 的增长不发生在 LLM 调用中（调用前已消耗），也不是工具调用中（工具不消耗 token），
 *       所以两个入口都有。这是四维中最容易超的维度——复杂回测场景单个 LLM 请求就可能上报数十万 token。</li>
 * </ol>
 *
 * <h2>配置加载优先级</h2>
 * <ol>
 *   <li>Nacos 热加载：{@code agent-llm.local.json → runtime.runBudget.*}</li>
 *   <li>Spring 静态配置：{@code agent.llm.runtime.runBudget.*}</li>
 *   <li>启动默认值：{@code agent.run.budget.*}（本类 @Value 注解），硬编码备用路径</li>
 * </ol>
 *
 * <h2>检查时机</h2>
 * <ul>
 *   <li><b>LLM 调用前</b>：{@code OpenRouterProviderRoutedChatModel} 和 {@code DashScopeChatModel}
 *       在发 HTTP 请求之前调用 {@link #checkBeforeLlmCall()}。</li>
 *   <li><b>工具调用前</b>：{@code ToolRouter#invoke} 在执行工具之前调用
 *       {@link #checkBeforeToolCall()}。</li>
 *   <li><b>HTTP 重试检查</b>：{@link #checkHttpAttempt} 限制单次逻辑 LLM 调用的最大 HTTP 重试次数，
 *       防止网络抖动导致无限重试消耗资源。</li>
 * </ul>
 *
 * <h2>预算超限后的行为</h2>
 * <p>抛出 {@code IllegalStateException} 消息格式 {@code RUN_BUDGET_EXCEEDED:<维度>:<实际值>/<上限>}，
 * 同时向事件总线写入 {@code RUN_BUDGET_EXCEEDED} 事件（含维度、实际值、上限等 payload）。
 * 这个异常会被工具层的 {@code LangchainTerminalToolErrorHandler} 识别为不可重试的致命信号，
 * 并被 {@code LangchainFailureMapper} 映射为 {@code RunBudgetExceeded} 的可观测失败类型。</p>
 *
 * <h2>{@code EffectiveRunBudget} 记录</h2>
 * <p>封装了生效的五个预算维度值，每次调用 {@code check()} 时动态计算并读取。
 * 支持 Nacos 热加载——下一轮检查即可生效，无需重启 run。</p>
 *
 * <p>讲解材料见 {@code agent-working-docs/code-review/phase2/agent-run-overall/interview-comments-migrated.md}。</p>
 *
 * @see OpenRouterProviderRoutedChatModel LLM 调用前检查的消费方
 * @see world.willfrog.agent.tools.router.ToolRouter 工具调用前检查的消费方
 * @see world.willfrog.agentlangchain.failure.LangchainFailureMapper 预算超限异常的分类映射
 */
@Service
@RequiredArgsConstructor
public class AgentRunBudgetService {

    private final AgentRunStateStore stateStore;
    private final AgentRunEventService eventService;
    private final ObjectMapper objectMapper;
    private final AgentLlmProperties llmProperties;
    private final AgentPromptService promptService;

    /**
     * Nacos 热加载配置器（optional），用于读取 {@code runtime.runBudget.*}。
     * 如果当前环境未注入（如没有 Nacos 的场景），则 fallback 到 Spring 静态配置。
     */
    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    /** 挂钟时间硬上限（毫秒），默认 600000ms = 10 分钟 */
    @Value("${agent.run.budget.max-wall-clock-ms:600000}")
    private long defaultMaxWallClockMs;

    /** LLM 调用次数硬上限，默认 50 */
    @Value("${agent.run.budget.max-llm-calls:50}")
    private long defaultMaxLlmCalls;

    /** 工具调用次数硬上限，默认 30 */
    @Value("${agent.run.budget.max-tool-calls:30}")
    private long defaultMaxToolCalls;

    /** Token 消耗硬上限，默认 300000 */
    @Value("${agent.run.budget.max-tokens:300000}")
    private long defaultMaxTokens;

    /** 单次逻辑 LLM 调用的最大 HTTP attempt 总数，默认 3（首次 + 最多 2 次重试） */
    @Value("${agent.run.budget.max-http-attempts-per-logical-call:3}")
    private int defaultMaxHttpAttemptsPerLogicalCall;

    /**
     * 在每次 LLM 调用前检查预算（由 {@code OpenRouterProviderRoutedChatModel} 调用）。
     * 此入口会检查 wallClock、llm_calls 和 tokens 三个维度；tool_calls 维度不会在这里触发。
     */
    public void checkBeforeLlmCall() {
        check("llm_call");
    }

    /**
     * 在每次工具调用前检查预算（由 {@code ToolRouter#invoke} 调用）。
     * 此入口会检查 wallClock、tool_calls 和 tokens 三个维度；llm_calls 维度不会在这里触发。
     */
    public void checkBeforeToolCall() {
        check("tool_call");
    }

    /** 返回生效的单次逻辑 LLM 调用最大 HTTP 重试次数（至少为 1）。 */
    public int maxHttpAttemptsPerLogicalCall() {
        return Math.max(1, effectiveConfig().maxHttpAttemptsPerLogicalCall());
    }

    /**
     * 计算当前生效的预算配置，按三优先级合并：Nacos 热加载 > Spring 静态 > 启动 @Value 默认值。
     *
     * @return 包含五个维度值的 {@link EffectiveRunBudget} 记录
     */
    public EffectiveRunBudget effectiveConfig() {
        AgentLlmProperties.RunBudget local = resolveLocalRunBudget();
        AgentLlmProperties.RunBudget spring = resolveSpringRunBudget();
        return new EffectiveRunBudget(
                resolveLong(local, spring, defaultMaxWallClockMs, AgentLlmProperties.RunBudget::getMaxWallClockMs),
                resolveLong(local, spring, defaultMaxLlmCalls, AgentLlmProperties.RunBudget::getMaxLlmCalls),
                resolveLong(local, spring, defaultMaxToolCalls, AgentLlmProperties.RunBudget::getMaxToolCalls),
                resolveLong(local, spring, defaultMaxTokens, AgentLlmProperties.RunBudget::getMaxTokens),
                resolveInt(local, spring, defaultMaxHttpAttemptsPerLogicalCall,
                        AgentLlmProperties.RunBudget::getMaxHttpAttemptsPerLogicalCall)
        );
    }

    /**
     * 检查 HTTP 重试次数是否超过限制。
     * 每次重试前调用，防止网络不稳定导致同一个逻辑 LLM 请求无限重试。
     *
     * @param nextAttempt 下一轮尝试的序号（1-based）
     * @throws IllegalStateException 如果 nextAttempt 超过上限
     */
    public void checkHttpAttempt(int nextAttempt) {
        int max = maxHttpAttemptsPerLogicalCall();
        if (nextAttempt > max) {
            throw exceeded("http_attempts_per_logical_call", nextAttempt, max);
        }
    }

    /**
     * 四维预算检查的核心方法。每次 LLM 调用或工具调用前都会触发。
     * 按 wall_clock → llm_calls / tool_calls → tokens 的顺序逐维度检查，任一超限即抛异常。
     * 检查顺序有意把 wall_clock 放第一位——如果挂钟时间已超，后续维度检查无意义。
     *
     * @param operation "llm_call" 或 "tool_call"，决定是否检查 llm_calls / tool_calls 维度
     */
    private void check(String operation) {
        String runId = AgentContext.getRunId();
        // runId 为空时直接跳过（例如非 run 上下文或测试环境），不抛异常
        if (runId == null || runId.isBlank()) {
            return;
        }
        String userId = AgentContext.getUserId();
        EffectiveRunBudget budget = effectiveConfig();
        Map<String, Object> summary = loadSummary(runId);
        // startedAtMillis 由 Pipeline 在 run 开始时写入 observability，用于计算挂钟时间
        long startedAt = toLong(summary.get("startedAtMillis"));
        long elapsed = startedAt <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - startedAt);
        // 0. 预算进度告警（80% 阈值）：先发 BUDGET_PROGRESS，再做超限检查，
        //    这样在跨过 80% 的同一轮既能产出进度事件，也能在后续真正超限时正常抛 exceeded
        emitBudgetProgressIfNeeded(runId, userId, summary, budget, elapsed);
        // 0b. 90% last-mile 提示：跨过 90% 时写 BUDGET_LAST_MILE 事件 + 写入 AgentContext.lastMileHint，
        //     下一次 LLM 调用时 chatRequestTransformer 会读取并作为 UserMessage 注入，保持 System 稳定
        emitBudgetLastMileIfNeeded(runId, userId, summary, budget, elapsed);
        // 1. 挂钟时间检查：从 run 启动到当前的毫秒数
        if (budget.maxWallClockMs() > 0 && elapsed > budget.maxWallClockMs()) {
            throw exceeded("wall_clock_ms", elapsed, budget.maxWallClockMs());
        }
        // 2. LLM 调用次数检查：仅 llm_call 入口触发，tool_call 入口跳过
        long llmCalls = toLong(summary.get("llmCalls"));
        if ("llm_call".equals(operation) && budget.maxLlmCalls() > 0 && llmCalls >= budget.maxLlmCalls()) {
            throw exceeded("llm_calls", llmCalls, budget.maxLlmCalls());
        }
        // 3. 工具调用次数检查：仅 tool_call 入口触发，llm_call 入口跳过
        long toolCalls = toLong(summary.get("toolCalls"));
        if ("tool_call".equals(operation) && budget.maxToolCalls() > 0 && toolCalls >= budget.maxToolCalls()) {
            throw exceeded("tool_calls", toolCalls, budget.maxToolCalls());
        }
        // 4. Token 检查：无论什么入口都检查，因为 token 消耗可能在 LLM 调用后上报、也可能在工具调用中被附带
        long tokens = toLong(summary.get("totalTokens"));
        if (budget.maxTokens() > 0 && tokens >= budget.maxTokens()) {
            throw exceeded("tokens", tokens, budget.maxTokens());
        }
    }

    /**
     * 构造预算超限异常，同时向事件总线写入 RUN_BUDGET_EXCEEDED 事件。
     * <p>异常消息格式：{@code RUN_BUDGET_EXCEEDED:<维度>:<实际值>/<上限>}，
     * 例如 {@code RUN_BUDGET_EXCEEDED:tool_calls:30/30}。</p>
     */
    private IllegalStateException exceeded(String dimension, long actual, long limit) {
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dimension", dimension);
        payload.put("actual", actual);
        payload.put("limit", limit);
        if (runId != null && userId != null) {
            eventService.append(runId, userId, "RUN_BUDGET_EXCEEDED", payload);
        }
        return new RunBudgetException(dimension, actual, limit, false);
    }

    /**
     * 80% 预算进度告警 —— 对四个维度逐一检查首次跨过 80% 阈值的情况，
     * 对未告警过的 dimension 写入 {@code BUDGET_PROGRESS} 事件并打点去重标记。
     * 阈值逻辑：{@code ratio ∈ [0.80, 1.00)} 触发一次；
     * ratio < 0.80 或 limit <= 0 时跳过；ratio >= 1.00 由后续 exceeded 检查接管（不再触发本事件）。
     *
     * <p>为什么 ratio >= 1.00 不发 BUDGET_PROGRESS：
     * 此时已处于"超限态"，下一步将立即抛 {@link RunBudgetException} 并写入 {@code RUN_BUDGET_EXCEEDED} 事件。
     * 为了避免在同一个 check 轮内同维度发 2 条事件（BUDGET_PROGRESS + RUN_BUDGET_EXCEEDED），
     * 此处只覆盖 80%~99% 这段"接近但尚未超限"的告警窗口。</p>
     */
    private void emitBudgetProgressIfNeeded(String runId, String userId,
                                            Map<String, Object> summary,
                                            EffectiveRunBudget budget, long elapsed) {
        if (runId == null || userId == null) {
            return;
        }
        checkDimension(runId, userId, "wall_clock_ms", elapsed, budget.maxWallClockMs());
        checkDimension(runId, userId, "llm_calls", toLong(summary.get("llmCalls")), budget.maxLlmCalls());
        checkDimension(runId, userId, "tool_calls", toLong(summary.get("toolCalls")), budget.maxToolCalls());
        checkDimension(runId, userId, "tokens", toLong(summary.get("totalTokens")), budget.maxTokens());
    }

    /**
     * 单维度 80% 阈值检查：未告警且 {@code ratio ∈ [0.80, 1.00)} 时写 {@code BUDGET_PROGRESS} 并打标。
     * 去重基于 {@link AgentRunStateStore#tryMarkBudgetProgressWarned} 的 Redis SADD 原子语义，
     * 保证同一 {@code (runId, dimension)} 组合在并发场景（并行 DAG 节点、并发 LLM/tool 入口）下也只发一次事件。
     * <p>不能用 {@code hasBudgetProgressWarned → markBudgetProgressWarned} 两步走——
     * 两步之间会被其他线程插队，导致同维度重复发 {@code BUDGET_PROGRESS}。</p>
     */
    private void checkDimension(String runId, String userId, String dimension, long actual, long limit) {
        if (limit <= 0 || actual < 0) {
            return;
        }
        double ratio = (double) actual / (double) limit;
        if (ratio < 0.80 || ratio >= 1.00) {
            return;
        }
        if (!stateStore.tryMarkBudgetProgressWarned(runId, dimension)) {
            // 已被其它线程抢先标记 → 本轮跳过，不再发事件
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dimension", dimension);
        payload.put("actual", actual);
        payload.put("limit", limit);
        payload.put("ratio", ratio);
        eventService.append(runId, userId, "BUDGET_PROGRESS", payload);
    }

    /**
     * 90% last-mile 提示 —— 对四个维度逐一检查首次跨过 90% 阈值的情况，
     * 对未提示过的 dimension 写入 {@code BUDGET_LAST_MILE} 事件并写入 {@link AgentContext#setLastMileHint} ThreadLocal，
     * 促使 {@code LangchainTodoNodeExecutor} 下一次 {@code chatRequestTransformer} 把 hint 作为 UserMessage 注入，
     * 保持 System 稳定。
     * 阈值逻辑：{@code ratio ∈ [0.90, 1.00)} 触发一次；其它情况跳过。
     */
    private void emitBudgetLastMileIfNeeded(String runId, String userId,
                                            Map<String, Object> summary,
                                            EffectiveRunBudget budget, long elapsed) {
        if (runId == null || userId == null) {
            return;
        }
        checkDimension90(runId, userId, "wall_clock_ms", elapsed, budget.maxWallClockMs());
        checkDimension90(runId, userId, "llm_calls", toLong(summary.get("llmCalls")), budget.maxLlmCalls());
        checkDimension90(runId, userId, "tool_calls", toLong(summary.get("toolCalls")), budget.maxToolCalls());
        checkDimension90(runId, userId, "tokens", toLong(summary.get("totalTokens")), budget.maxTokens());
    }

    /**
     * 单维度 90% 阈值检查：未提示且 {@code ratio ∈ [0.90, 1.00)} 时写 {@code BUDGET_LAST_MILE} 事件 + 写 last-mile hint。
     * 去重基于 {@link AgentRunStateStore#tryMarkBudgetLastMileWarned} 的原子语义（同 80%）。
     */
    private void checkDimension90(String runId, String userId, String dimension, long actual, long limit) {
        if (limit <= 0 || actual < 0) {
            return;
        }
        double ratio = (double) actual / (double) limit;
        if (ratio < 0.90 || ratio >= 1.00) {
            return;
        }
        if (!stateStore.tryMarkBudgetLastMileWarned(runId, dimension)) {
            return;
        }
        long ratioPct = (long) Math.floor(ratio * 100);
        AgentContext.setLastMileHint(buildLastMileHint(dimension, actual, limit, ratioPct));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dimension", dimension);
        payload.put("actual", actual);
        payload.put("limit", limit);
        payload.put("ratio", ratio);
        payload.put("ratioPct", ratioPct);
        eventService.append(runId, userId, "BUDGET_LAST_MILE", payload);
    }

    /**
     * 拼装 90% last-mile hint 中文文本。
     * 模板：{@code [last_mile_hint] 本 run 已使用 <pct>% <dim> 预算（<act>/<limit>），<advice>}
     * advice 按维度区分：tokens 提示精简输出；tool_calls 提示精简工具调用；llm_calls 提示本轮直接给结论；wall_clock_ms 提示尽快完成。
     */
    private String buildLastMileHint(String dimension, long actual, long limit, long ratioPct) {
        return promptService.budgetLastMileStageInstruction(
                dimension, actual, limit, ratioPct);
    }

    /** 从 Nacos 热加载配置读取 {@code runtime.runBudget}。 */
    private AgentLlmProperties.RunBudget resolveLocalRunBudget() {
        if (localConfigLoader == null) {
            return null;
        }
        return localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getRunBudget)
                .orElse(null);
    }

    /** 从 Spring 静态配置读取 {@code agent.llm.runtime.runBudget}。 */
    private AgentLlmProperties.RunBudget resolveSpringRunBudget() {
        if (llmProperties.getRuntime() == null) {
            return null;
        }
        return llmProperties.getRuntime().getRunBudget();
    }

    /** Long 型配置值按三优先级解析：Nacos > Spring > @Value 默认。 */
    private long resolveLong(AgentLlmProperties.RunBudget local,
                             AgentLlmProperties.RunBudget spring,
                             long applicationDefault,
                             java.util.function.Function<AgentLlmProperties.RunBudget, Long> getter) {
        if (local != null && getter.apply(local) != null) {
            return getter.apply(local);
        }
        if (spring != null && getter.apply(spring) != null) {
            return getter.apply(spring);
        }
        return applicationDefault;
    }

    /** Int 型配置值按三优先级解析。 */
    private int resolveInt(AgentLlmProperties.RunBudget local,
                           AgentLlmProperties.RunBudget spring,
                           int applicationDefault,
                           java.util.function.Function<AgentLlmProperties.RunBudget, Integer> getter) {
        if (local != null && getter.apply(local) != null) {
            return getter.apply(local);
        }
        if (spring != null && getter.apply(spring) != null) {
            return getter.apply(spring);
        }
        return applicationDefault;
    }

    /**
     * 从 observability JSON 中加载当前 run 的累计统计（llmCalls / toolCalls / totalTokens / startedAtMillis）。
     * 数据由 {@code AgentRunObservabilityService} 在每次 LLM 调用后写入。
     */
    private Map<String, Object> loadSummary(String runId) {
        try {
            String json = stateStore.loadObservability(runId).orElse("");
            if (json.isBlank()) {
                return Map.of();
            }
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            Object summary = root.get("summary");
            if (summary instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out;
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.of();
    }

    /** 安全地将 Object 转为 long，非数字或异常时返回 0。 */
    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 生效的预算配置记录——封装五个维度的最终上限值。
     * 每次 {@link #check} 时动态计算，支持 Nacos 热加载无需重启。
     */
    public record EffectiveRunBudget(
            long maxWallClockMs,
            long maxLlmCalls,
            long maxToolCalls,
            long maxTokens,
            int maxHttpAttemptsPerLogicalCall
    ) {
    }
}
