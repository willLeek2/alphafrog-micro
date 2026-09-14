package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.artifact.RawPayloadLocator;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.compaction.ToolOutputCompactionService;
import world.willfrog.agent.tools.finance.FinanceMethodTools;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.market.advanced.AdvancedSearchRequest;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.agent.tools.search.SearchTools;
import world.willfrog.agent.tools.subagent.SubAgentControlHandler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工具调用统一路由器，是 LLM 决定调用工具后，所有业务工具的执行入口。
 *
 * <p>可路由工具名集合由 {@link AgentToolRegistry#declaredToolNames()} 派生，
 * 本路由器只负责分发已在注册表中声明的工具。spawnSubAgent / waitForSubAgent
 * 由 D06 的 {@link SubAgentControlHandler} 提供控制语义；声明、目录与路由同时生效。</p>
 *
 * <p>agentLangchainService 的 {@code ToolRouterToolExecutor} 只负责把 LC4j 的 tool call
 * 接入本类；真正的业务语义在本类：是否允许调用、是否超预算、是否命中缓存、
 * 结果如何写 observability、异常如何包装成统一 JSON。
 * 因此本类是「模型工具调用」通往「平台业务工具」的唯一入口。讲解要点见
 * {@code agent-working-docs/code-review/phase2/agent-run-overall/tool-routing-interview-points.md}。</p>
 *
 * <p>Agent V2 前端接入后，工具调用还多了一层实时事件契约：
 * {@code ToolRouterToolExecutor} 负责发 {@code TOOL_CALL_STARTED/FINISHED}，
 * 本类负责把同一次调用写入 observability。两边必须共享同一个 {@code tool_call_id}
 * （通过 {@link AgentContext} 传递），这样前端点击工具卡片时才能用
 * {@code runId + tool_call_id} 懒加载 safe detail，而不是扫描整份 trace。</p>
 *
 * <h3>位置与角色</h3>
 * 在 agentLangchainService 中，{@code ToolRouterToolExecutor} 将 LLM 返回的
 * {@code tool_calls} 交给 {@link #invokeWithMeta(String, Map)}。业务工具与子代理控制工具
 * 因而共享同一套预算、限流、缓存选择和观测入口，不依赖已删除的 legacy 执行器旁路。
 *
 * <h3>核心职责</h3>
 * <ol>
 *   <li><b>参数兼容</b>：不同 LLM 可能输出不同的参数键（{@code tsCode}、{@code ts_code}、
 *       {@code code}、{@code stock_code}、{@code arg0} 等），路由器统一在此层做别名兼容，
 *       下层工具实现只接收标准字段。</li>
 *   <li><b>能力校验</b>：在路由前检查 run 级能力开关（如 webSearch 未开启时拒绝 searchWeb）。</li>
 *   <li><b>预算检查</b>：调用 {@link AgentRunBudgetService#checkBeforeToolCall} 检查
 *       run 总额度/总耗时预算是否已用尽。</li>
 *   <li><b>结果缓存</b>：通过 {@link ToolResultCacheService} 对工具结果按 user/run scope
 *       做缓存复用（缺身份时 fail-closed 跳过共享缓存），节省重复调用成本。</li>
 *   <li><b>观测记录</b>：通过 {@link AgentRunObservabilityService#recordToolCall} 记录每一次
 *       工具调用 trace（参数、结果摘要、耗时、是否命中缓存等），供 run 观测视图和
 *       safe detail 懒加载使用。</li>
 *   <li><b>并发权重限制</b>：通过 {@link ToolWeightedLimitService} 对批量工具调用按有效权重限流，
 *       避免一次批量日线查询占满下游资源。</li>
 *   <li><b>故障注入</b>：根据 {@link StressTestProperties} 注入模拟延迟/失败，用于压测。</li>
 *   <li><b>指标采集</b>：通过 Micrometer Timer 记录每个工具的调用耗时分布。</li>
 * </ol>
 *
 * <h3>统一响应格式</h3>
 * 所有路由出口（成功/失败/不支持的工具）都返回标准 JSON 响应：
 * <pre>
 * { "ok": true|false,
 *   "tool": "xxx",
 *   "data": { ... },
 *   "error": { "code": "...", "message": "...", "details": {...} }
 * }
 * </pre>
 *
 * @see world.willfrog.agentlangchain.tools.ToolRouterToolExecutor
 * @see ToolResultCacheService
 * @see AgentRunObservabilityService
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolRouter {

    /** 行情数据工具集（个股、指数、基金、财报） */
    private final MarketDataTools marketDataTools;
    /** RAG 检索工具集（ragSearch、loadDocument） */
    private final RagTools ragTools;
    /** 网页搜索工具集（searchWeb），受 AgentContext.isWebSearchEnabled 能力开关控制 */
    private final SearchTools searchTools;
    /** Python 沙箱执行工具集（executePython） */
    private final PythonSandboxTools pythonSandboxTools;
    /** 金融方法建议工具（resolveFinanceMethods），只读建议工具。 */
    private final FinanceMethodTools financeMethodTools;
    /** 平台工具指南加载工具（loadToolGuide） */
    private final LoadToolGuideTool loadToolGuideTool;
    /** 260623-harness-optimization-02: 列出当前 agent run 已落盘 dataset / manifest（listMyData） */
    private final ListMyDataTool listMyDataTool;
    /** executePython 静态参数/代码预校验（B1） */
    private final PythonStaticPrecheckService pythonStaticPrecheckService;
    /** 运行时 LLM/执行配置（含 static-precheck-enabled） */
    private final AgentLlmProperties llmProperties;
    /** 工具结果缓存服务，按 toolName + params + scope 做去重缓存 */
    private final ToolResultCacheService toolResultCacheService;
    /** rawRef 重读工具 */
    private final RereadToolHandler rereadToolHandler;
    /** 观测数据服务，记录每次工具调用的 trace（参数、结果、耗时、缓存元数据等） */
    private final AgentRunObservabilityService observabilityService;
    /** JSON 序列化/反序列化，用于构建标准响应和判断工具成功状态 */
    private final ObjectMapper objectMapper;
    /** Micrometer 指标注册中心，用于按 toolName 标签上报工具调用耗时 */
    private final MeterRegistry meterRegistry;
    /** 压测开关，控制故障注入（模拟延迟、模拟失败） */
    private final StressTestProperties stressTestProperties;
    /** 按 toolName 缓存 Timer 实例，避免每次调用重新构建（线程安全） */
    private final ConcurrentHashMap<String, Timer> toolCallTimers = new ConcurrentHashMap<>();
    // D07：权重限流拒绝的独立低基数计数（toolName × layer，layer 当前恒为 weight_limit），
    // 与成功 toolCalls 累加器分离；LC4j 层拒绝发生在进入 Router 之前，不经本计数器
    private final ConcurrentHashMap<String, Counter> throttleRejectionCounters = new ConcurrentHashMap<>();

    /**
     * Run 级预算服务 — 可选注入（@Autowired(required = false)），
     * 避免在没有完整 Spring 上下文的单元测试环境中出错。
     * 若为 null，则跳过预算检查。
     */
    @Autowired(required = false)
    private AgentRunBudgetService budgetService;

    /**
     * 本地热加载配置 — 可选注入，用于覆盖 application.yml 中的 execution 开关。
     */
    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    /**
     * 工具级并发权重限制服务。
     *
     * <p>批量查询工具一次调用可能携带多个代码或关键词，run 级 toolCalls 只会计为一次。
     * 为了避免模型用少量批量调用压垮下游，这里再引入按「有效权重」计算的并发限制。
     * 该依赖可选，是为了让不需要限流能力的测试上下文仍可启动。</p>
     */
    @Autowired(required = false)
    private ToolWeightedLimitService toolWeightedLimitService;

    /**
     * D06 子代理控制实现。共享 Router 不依赖 LangChain4j；生产实现由
     * agentLangchainService 注入。缺少实现时返回稳定的不可用错误，绝不落到
     * UNSUPPORTED_TOOL，也不会启动临时线程或使用进程内假实现。
     */
    @Autowired(required = false)
    private SubAgentControlHandler subAgentControlHandler;

    /**
     * 简化入口：仅返回工具输出文本，丢弃成功标志、耗时、缓存元数据等。
     *
     * <p>保留此入口主要用于向后兼容老的调用点，新调用方应优先使用
     * {@link #invokeWithMeta(String, Map)} 以获取完整元数据。</p>
     *
     * @param toolName 工具名（如 "getStockInfo"、"searchWeb"）
     * @param params   工具参数 Map，键名兼容多种命名风格
     * @return 工具输出的标准 JSON 字符串
     */
    public String invoke(String toolName, Map<String, Object> params) {
        return invokeWithMeta(toolName, params).getOutput();
    }

    /**
     * 工具调用主入口：执行预算检查、故障注入、缓存路由、观测记录的完整流程。
     *
     * <h4>执行步骤</h4>
     * <ol>
     *   <li>调用 {@link AgentRunBudgetService#checkBeforeToolCall} 检查 run 预算（若已用尽则抛出）。</li>
     *   <li>若开启了压测延迟注入，则 sleep 指定毫秒数。</li>
     *   <li>若开启了压测失败注入且命中概率，直接返回模拟失败结果，仍记录观测。</li>
     *   <li>正常路径：调用 {@link ToolResultCacheService#executeWithCache} 走缓存逻辑，
     *       缓存未命中时回调 {@link #executeDirect} 真正执行工具。</li>
     *   <li>无论命中缓存与否，都记录观测 trace 和耗时 Timer。</li>
     *   <li>组装 {@link ToolInvocationResult} 返回给调用方。</li>
     * </ol>
     *
     * @param toolName 工具名
     * @param params   工具参数（来自 LLM tool_calls 的 arguments JSON）
     * @return 包含输出文本、成功标志、耗时、缓存元数据的封装对象
     */
    public ToolInvocationResult invokeWithMeta(String toolName, Map<String, Object> params) {
        /*
         * checkParallelLimits 是让模型查询工具并行限制的元工具。它应该暴露给模型，
         * 但不能消耗 run 的 toolCalls 预算，也不能写入业务 tool trace；否则模型每次
         * 遵守 prompt 先查限制，都会无意义地污染预算和统计。
         */
        // 预算检查：可能抛出 RunBudgetExceededException 中断本次工具调用
        if (budgetService != null && !"checkParallelLimits".equals(toolName)) {
            budgetService.checkBeforeToolCall();
        }
        debugLog("tool invoke request: runId={}, tool={}, params={}",
                AgentContext.getRunId(), nvl(toolName), safeJson(params));

        // Fault injection: simulated latency
        // 压测场景下注入固定延迟，用于观察上游对慢工具的处理是否正常
        if (stressTestProperties.isToolLatencyEnabled() && stressTestProperties.getToolLatencyMs() > 0) {
            try {
                Thread.sleep(stressTestProperties.getToolLatencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Fault injection: simulated failure
        // 按指定概率随机返回失败，用于观察 ReAct 重试与降级是否正常工作
        if (stressTestProperties.getToolFailureRate() > 0 && Math.random() < stressTestProperties.getToolFailureRate()) {
            String errorResult = invocationError(toolName, "Simulated failure for stress test");
            recordObservability(toolName, params, errorResult, 0, false, null);
            getOrCreateToolCallTimer(nvl(toolName)).record(0, TimeUnit.MILLISECONDS);
            return ToolInvocationResult.builder()
                    .output(errorResult)
                    .success(false)
                    .durationMs(0)
                    .cacheMeta(null)
                    .build();
        }

        /*
         * 批量工具调用的 run 级计数仍是一次，因此这里用 WeightLease 表示对下游容量的占用。
         * 获取失败时返回可解析的标准错误，让模型可以缩小批量或稍后重试；获取成功后必须
         * 在 finally 释放，避免异常路径造成容量泄漏。
         */
        Optional<ToolWeightedLimitService.WeightLease> weightLease = Optional.empty();
        if (toolWeightedLimitService != null) {
            // toolCalls 预算按“模型发起了一次工具调用”计数，但真实资源占用可能远大于 1：
            // 例如批量行情查询一次请求里带多个资产，executePython 会占用沙箱工作线程。
            // WeightLease 让这些工具按有效权重参与限流，同时保持对模型暴露的工具调用次数语义不变。
            Optional<ToolWeightedLimitService.WeightLease> acquired = toolWeightedLimitService.tryAcquire(toolName, params);
            if (acquired.isEmpty()) {
                int effectiveWeight = toolWeightedLimitService.previewEffectiveWeight(toolName, params);
                String errorResult = weightLimitExceeded(toolName, effectiveWeight);
                /*
                 * D07 口径：限流拒绝 ≠ 已执行工具调用。不调用 recordObservability——
                 * 拒绝不得抬高 observability summary.toolCalls、不得消耗 maxToolCalls
                 * 判定额度；权重层拒绝用独立的低基数 Micrometer 计数观测
                 * （tool.call.throttle.rejected{toolName, layer=weight_limit}），
                 * 与成功调用累加器完全分离。throttleRejected 标记随结果传给 executor，
                 * 由其在 TOOL_CALL_FINISHED payload 写 creditsConsumed=0。
                 */
                getOrCreateThrottleRejectionCounter(nvl(toolName), "weight_limit").increment();
                return ToolInvocationResult.builder()
                        .output(errorResult)
                        .success(false)
                        .durationMs(0)
                        .cacheMeta(null)
                        .throttleRejected(true)
                        .build();
            }
            weightLease = acquired;
        }

        try {
        // 主调用路径：走缓存装饰，由 ToolResultCacheService 决定命中或回源到 executeDirect
        ToolResultCacheService.CachedToolCallResult cached = toolResultCacheService.executeWithCache(
                toolName,
                params,
                resolveScope(),
                () -> executeDirect(toolName, params)
        );
        String result = nvl(cached.getResult());
        String observabilityResult = isBlank(cached.getObservabilityResult()) ? result : cached.getObservabilityResult();
        boolean success = cached.isSuccess();
        long durationMs = Math.max(0L, cached.getDurationMs());
        ToolResultCacheService.CacheMeta cacheMeta = cached.getCacheMeta();
        // 记录观测 trace（参数、结果摘要、耗时、缓存命中信息）。
        // AgentRunObservabilityService 会把大输出拆到 Redis detail blob，snapshot 中只保留安全索引。
        // checkParallelLimits 是工具目录自检，不计入 run 级 tool_calls 预算/统计，也不提供展开详情。
        if (!"checkParallelLimits".equals(toolName)) {
            recordObservability(toolName, params, observabilityResult, durationMs, success, cacheMeta);
        }

        // 按 toolName 分桶上报耗时指标
        getOrCreateToolCallTimer(nvl(toolName)).record(durationMs, TimeUnit.MILLISECONDS);

        debugLog("tool invoke response: runId={}, tool={}, success={}, durationMs={}, cache={}, resultPreview={}",
                AgentContext.getRunId(),
                nvl(toolName),
                success,
                durationMs,
                toolResultCacheService.toPayload(cacheMeta),
                preview(result));
        return ToolInvocationResult.builder()
                .output(result)
                .success(success)
                .durationMs(durationMs)
                .cacheMeta(cacheMeta)
                .build();
        } finally {
            weightLease.ifPresent(ToolWeightedLimitService.WeightLease::release);
        }
    }

    /**
     * 将工具调用的缓存元数据转换为事件流上报用的轻量 payload。
     *
     * <p>事件流中只需要展示是否命中、缓存来源等关键字段，不需要完整 CacheMeta 对象。</p>
     *
     * @param invocationResult 工具调用结果（可为 null）
     * @return 适合写入事件 JSON 的 Map
     */
    public Map<String, Object> toEventCachePayload(ToolInvocationResult invocationResult) {
        return toolResultCacheService.toPayload(invocationResult == null ? null : invocationResult.getCacheMeta());
    }

    /**
     * 返回路由器支持的全部工具名集合。
     *
     * <p>集合内容由 {@link AgentToolRegistry#declaredToolNames()} 派生，代表平台的
     * 生产声明面，而不是当前 run 一定可用的工具列表。 capability gate 以注册表元数据
     * 为准；实际执行仍由 executeDirect 中的能力校验决定。</p>
     *
     * <p>语义注意：searchWeb 还要受 AgentContext.isWebSearchEnabled 控制；
     * getEtfAdj 还要受 adjFactorEnabled 控制；checkParallelLimits 是元工具，
     * 返回当前配置下的批量上限。</p>
     *
     * @return 不可变的工具名集合
     */
    public Set<String> supportedTools() {
        return AgentToolRegistry.declaredToolNames();
    }

    /**
     * 获取或创建指定工具名的耗时 Timer。
     *
     * <p>使用 ConcurrentHashMap 的 computeIfAbsent 保证线程安全且每个 toolName 只注册一次。</p>
     */
    private Timer getOrCreateToolCallTimer(String toolName) {
        return toolCallTimers.computeIfAbsent(toolName, name ->
                Timer.builder("tool.call")
                        .tag("toolName", name)
                        .register(meterRegistry));
    }

    /**
     * 获取或创建权重限流拒绝计数器（D07）。
     *
     * <p>拒绝计数与 {@code tool.call} 执行计时分离：被拒绝的调用没有执行，不计时、
     * 不进成功累加器。tag 仅 toolName（注册表 25 名内）与 layer，基数有界。
     * 本计数器只覆盖权重层，layer 当前恒为 {@code weight_limit}；LC4j 前台
     * Semaphore 拒绝发生在进入本 Router 之前（ToolRouterToolExecutor 侧），其
     * 低基数观测由 LangchainToolConcurrencyThrottle 自带 per-node 计数
     * （timeoutCounts / waitMsTotal / waitCount，G7 冻结面）承担，不经本计数器。</p>
     */
    private Counter getOrCreateThrottleRejectionCounter(String toolName, String layer) {
        return throttleRejectionCounters.computeIfAbsent(toolName + "|" + layer, key ->
                Counter.builder("tool.call.throttle.rejected")
                        .tag("toolName", toolName)
                        .tag("layer", layer)
                        .register(meterRegistry));
    }

    /**
     * 真正执行工具调用的核心方法（不含缓存、观测、指标的装饰）。
     *
     * <p>由 {@link ToolResultCacheService#executeWithCache} 在缓存未命中时回调。</p>
     *
     * <h4>责任</h4>
     * <ul>
     *   <li>能力开关校验（如 webSearch 未开启时返回 CAPABILITY_DISABLED）。</li>
     *   <li>参数别名兼容：将 LLM 可能输出的不同键名（tsCode / ts_code / code / arg0 等）
     *       统一映射到工具实现的标准入参。</li>
     *   <li>switch 路由到具体工具实现。</li>
     *   <li>异常包装：任何工具内部抛出的异常都包装为标准失败 JSON。</li>
     *   <li>计算实际执行耗时（不含缓存开销）。</li>
     * </ul>
     *
     * @param toolName 工具名
     * @param params   原始参数 Map
     * @return 工具执行结果（含结果文本、耗时、成功标志）
     */
    private String invokeExecutePython(Map<String, Object> params) {
        /*
         * executePython 是最容易把上游数据、模型生成代码和沙箱执行耦合在一起的工具。
         * 260623-harness-optimization-02: dataset_ids / manifest_ids 是两个独立编号空间，
         * 这里先分别收集，再做静态预校验（要求至少一个非空），最后才交给 PythonSandboxTools
         * 的 5 形参 overload。这样可以在真正执行前拦截明显危险或无效的代码，
         * 失败结果也仍然走统一 JSON 格式。
         */
        String code = str(params.get("code"), params.get("arg0"));
        String datasetIds = collectExecutePythonDatasetIds(params);
        String manifestIds = collectExecutePythonManifestIds(params);
        if (isStaticPrecheckEnabled()) {
            PythonStaticPrecheckService.Result precheck =
                    pythonStaticPrecheckService.check(code, datasetIds, manifestIds, params);
            if (!precheck.isPassed()) {
                return precheckFailure("executePython", precheck);
            }
        }
        return pythonSandboxTools.executePython(
                code,
                datasetIds,
                manifestIds,
                str(params.get("libraries"), params.get("arg3")),
                toNullableInt(params.get("timeout_seconds"), params.get("timeoutSeconds"), params.get("arg4"))
        );
    }

    private boolean isStaticPrecheckEnabled() {
        if (localConfigLoader != null) {
            Boolean local = localConfigLoader.current()
                    .map(AgentLlmProperties::getRuntime)
                    .map(AgentLlmProperties.Runtime::getExecution)
                    .map(AgentLlmProperties.Execution::getStaticPrecheckEnabled)
                    .orElse(null);
            if (local != null) {
                return local;
            }
        }
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null) {
            Boolean enabled = llmProperties.getRuntime().getExecution().getStaticPrecheckEnabled();
            if (enabled != null) {
                return enabled;
            }
        }
        return true;
    }

    private boolean isAdjFactorEnabled() {
        if (localConfigLoader != null) {
            Boolean local = localConfigLoader.current()
                    .map(AgentLlmProperties::getRuntime)
                    .map(AgentLlmProperties.Runtime::getExecution)
                    .map(AgentLlmProperties.Execution::getAdjFactorEnabled)
                    .orElse(null);
            if (local != null) {
                return local;
            }
        }
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null) {
            Boolean enabled = llmProperties.getRuntime().getExecution().getAdjFactorEnabled();
            if (enabled != null) {
                return enabled;
            }
        }
        return false;
    }

    private String precheckFailure(String toolName, PythonStaticPrecheckService.Result precheck) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pre_validation_failed", true);
        if (precheck.getReport() != null) {
            details.put("report", precheck.getReport());
        }
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", nvl(precheck.getErrorCode()),
                        "message", nvl(precheck.getMessage()),
                        "details", details
                )
        ));
    }

    private ToolResultCacheService.ToolExecutionOutcome executeDirect(String toolName, Map<String, Object> params) {
        /*
         * executeDirect 是「去掉装饰层后的真实工具路由」：
         * invokeWithMeta 已经处理预算、压测、限流、缓存和观测；
         * 到这里以后只做能力校验、参数标准化、调用具体工具实现。
         *
         * 参数标准化集中在这一层，可以减少每个具体工具对 LLM 参数风格的感知，
         * 也方便后续新增别名时只改一个地方。
         */
        long startedAt = System.currentTimeMillis();
        String result;
        try {
            // 能力校验：searchWeb 必须显式开启 webSearch 能力，否则返回不可用响应
            if ("searchWeb".equals(toolName) && !AgentContext.isWebSearchEnabled()) {
                // webSearch 是按 run 配置开放的能力，不是全局默认工具。
                // 这里返回结构化不可用结果，让模型知道不能继续依赖搜索，而不是抛异常中断整个 run。
                result = writeJson(Map.of(
                        "ok", false,
                        "tool", "searchWeb",
                        "data", Map.of(),
                        "error", Map.of(
                                "code", "CAPABILITY_DISABLED",
                                "message", "webSearch is disabled for this run",
                                "details", Map.of()
                        )
                ));
                return ToolResultCacheService.ToolExecutionOutcome.builder()
                        .result(result)
                        .durationMs(Math.max(0L, System.currentTimeMillis() - startedAt))
                        .success(false)
                        .build();
            }
            // 统一入口负责兼容参数别名（ts_code/code 等），工具实现层只接收标准参数。
            result = switch (toolName) {
                // checkParallelLimits 是“给模型看的工具目录说明”，只读配置，不访问业务数据。
                // 它必须和其它工具走同一个路由入口，模型才能通过 tool-calling 正常调用。
                case "checkParallelLimits" -> marketDataTools.checkParallelLimits();
                case "getStockInfo" -> marketDataTools.getStockInfo(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("stock_code"), params.get("arg0"))
                );
                case "getStockDaily" -> marketDataTools.getStockDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("stock_code"), params.get("arg0")),
                        dateStr(params.get("startDateStr"), params.get("startDate"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDateStr"), params.get("endDate"), params.get("end_date"), params.get("arg2"))
                );
                case "getStockSwIndustryInfo" -> marketDataTools.getStockSwIndustryInfo(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("stock_code"), params.get("arg0"))
                );
                case "searchStock" -> marketDataTools.searchStock(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "searchFund" -> marketDataTools.searchFund(
                        str(params.get("keyword"), params.get("query"), params.get("arg0"))
                );
                case "getIndexInfo" -> marketDataTools.getIndexInfo(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("index_code"), params.get("arg0"))
                );
                case "getIndexDaily" -> marketDataTools.getIndexDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("index_code"), params.get("arg0")),
                        dateStr(params.get("startDateStr"), params.get("startDate"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDateStr"), params.get("endDate"), params.get("end_date"), params.get("arg2"))
                );
                case "searchIndex" -> AdvancedSearchRequest.isAdvancedMap(params)
                        ? marketDataTools.searchIndexAdvanced(params)
                        : marketDataTools.searchIndex(
                        str(params.get("keyword"), params.get("query"), params.get("arg0")),
                        null,
                        null
                );
                case "searchAssetInfo" -> AdvancedSearchRequest.isAdvancedMap(params)
                        ? marketDataTools.searchAssetInfoAdvanced(params)
                        : marketDataTools.searchAssetInfo(
                        str(params.get("query"), params.get("keyword"), params.get("arg0")),
                        str(params.get("assetTypes"), params.get("asset_types"), params.get("arg1")),
                        str(params.get("marketScope"), params.get("market_scope"), params.get("arg2"), "domestic"),
                        null,
                        null
                );
                case "getTradingDaysSummary" -> marketDataTools.getTradingDaysSummary(
                        dateStr(params.get("startDate"), params.get("start_date"), params.get("startDateStr"), params.get("arg0")),
                        dateStr(params.get("endDate"), params.get("end_date"), params.get("endDateStr"), params.get("arg1")),
                        str(params.get("exchange"), params.get("arg2"), "SSE")
                );
                case "isTradingDay" -> marketDataTools.isTradingDay(
                        dateStr(params.get("date"), params.get("dates"), params.get("tradeDate"), params.get("tradeDates"),
                                params.get("trade_date"), params.get("trade_dates"), params.get("arg0")),
                        str(params.get("exchange"), params.get("arg1"), "SSE")
                );
                case "getExchangeAssetDaily" -> AdvancedSearchRequest.isAdvancedMap(params)
                        ? marketDataTools.getExchangeAssetDailyAdvanced(
                        params,
                        str(params.get("assetType"), params.get("asset_type"), params.get("arg1")),
                        dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg2")),
                        dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg3")),
                        str(params.get("priceMode"), params.get("price_mode"), params.get("arg4"), "raw_ohlc")
                        )
                        : marketDataTools.getExchangeAssetDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("arg0")),
                        str(params.get("assetType"), params.get("asset_type"), params.get("arg1")),
                        dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg2")),
                        dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg3")),
                        str(params.get("priceMode"), params.get("price_mode"), params.get("arg4"), "raw_ohlc"),
                        null,
                        null
                );
                case "getOffExchangeAssetDaily" -> marketDataTools.getOffExchangeAssetDaily(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("arg0")),
                        dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg2"))
                );
                case "getEtfAdj" -> {
                    if (!isAdjFactorEnabled()) {
                        // ETF 复权因子是可灰度关闭的功能。禁用时返回标准 JSON，
                        // 保证前端和 LLM 都能读到明确的 CAPABILITY_DISABLED，而不是把它当作服务异常。
                        yield writeJson(Map.of(
                                "ok", false,
                                "tool", "getEtfAdj",
                                "data", Map.of(),
                                "error", Map.of(
                                        "code", "CAPABILITY_DISABLED",
                                        "message", "ETF adj factor is disabled (adjFactorEnabled=false)",
                                        "details", Map.of("adjFactorEnabled", false)
                                )
                        ));
                    }
                    yield marketDataTools.getEtfAdj(
                            str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("arg0")),
                            dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg1")),
                            dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg2"))
                    );
                }
                case "getListedAssetShareSize" -> marketDataTools.getListedAssetShareSize(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("arg0")),
                        dateStr(params.get("startDate"), params.get("startDateStr"), params.get("start_date"), params.get("arg1")),
                        dateStr(params.get("endDate"), params.get("endDateStr"), params.get("end_date"), params.get("arg2")),
                        str(params.get("exchange"), params.get("arg3"))
                );
                case "getFinancialReport" -> marketDataTools.getFinancialReport(
                        str(params.get("tsCode"), params.get("ts_code"), params.get("code"), params.get("ts code"), params.get("arg0")),
                        str(params.get("reportType"), params.get("report_type"), params.get("type"), params.get("report type"), params.get("arg1")),
                        dateStr(params.get("startPeriod"), params.get("start_period"), params.get("start"), params.get("arg2")),
                        dateStr(params.get("endPeriod"), params.get("end_period"), params.get("end"), params.get("arg3"))
                );
                case "ragSearch" -> ragTools.ragSearch(
                        str(params.get("queryText"), params.get("query_text"), params.get("query"), params.get("arg0")),
                        str(params.get("docType"), params.get("doc_type"), params.get("arg1")),
                        str(params.get("tsCode"), params.get("ts_code"), params.get("arg2")),
                        str(params.get("indName"), params.get("ind_name"), params.get("arg3")),
                        toIntWithDefault(5, params.get("topK"), params.get("top_k"), params.get("arg4"))
                );
                case "loadDocument" -> ragTools.loadDocument(
                        str(params.get("ossUrl"), params.get("oss_url"), params.get("url"), params.get("arg0"))
                );
                case "searchWeb" -> searchTools.searchWeb(
                        str(params.get("query"), params.get("arg0")),
                        str(params.get("scene"), params.get("arg1")),
                        str(params.get("backend"), params.get("arg2")),
                        str(params.get("strength"), params.get("arg3")),
                        toBool(params.get("skipHotCache"), params.get("skip_hot_cache"), params.get("arg4")),
                        toBool(params.get("skipRagPrefetch"), params.get("skip_rag_prefetch"), params.get("arg5")),
                        str(params.get("timeRangeStart"), params.get("time_range_start"), params.get("arg6")),
                        str(params.get("timeRangeEnd"), params.get("time_range_end"), params.get("arg7")),
                        toIntWithDefault(5, params.get("maxResults"), params.get("max_results"), params.get("arg8"))
                );
                case "resolveFinanceMethods" -> financeMethodTools.resolveFinanceMethods(
                        str(params.get("query"), params.get("arg0")),
                        str(params.get("context"), params.get("arg1"))
                );
                case "executePython" -> invokeExecutePython(params);
                case "loadToolGuide" -> loadToolGuideTool.loadToolGuide(
                        str(params.get("topic"), params.get("arg0"))
                );
                case "listMyData" -> listMyDataTool.listMyData(
                        str(params.get("query_type"), params.get("arg0")),
                        str(params.get("from_ts_code"), params.get("arg1")),
                        str(params.get("grep"), params.get("arg2")),
                        toIntOrNull(params.get("file_offset"), params.get("arg3")),
                        toIntOrNull(params.get("file_limit"), params.get("arg4")),
                        toIntOrNull(params.get("offset"), params.get("arg5")),
                        toIntOrNull(params.get("limit"), params.get("arg6")),
                        str(params.get("related_dataset_ids"), params.get("arg7"))
                );
                case "rereadToolResult" -> rereadToolHandler.reread(
                        str(params.get("rawRef"), params.get("raw_ref"), params.get("arg0")),
                        str(params.get("keyword"), params.get("arg1")),
                        toIntOrNull(params.get("offset"), params.get("arg2")),
                        toIntOrNull(params.get("limit"), params.get("arg3"))
                );
                case "spawnSubAgent" -> subAgentControlHandler == null
                        ? subAgentUnavailable(toolName)
                        : subAgentControlHandler.spawn(params);
                case "waitForSubAgent" -> subAgentControlHandler == null
                        ? subAgentUnavailable(toolName)
                        : subAgentControlHandler.waitFor(params);
                default -> unsupported(toolName);
            };
        } catch (ExternalToolJobPendingException pending) {
            // pending 是跨层控制信号，不是可缓存的工具失败结果。
            // PythonSandboxTools 在抛出前已经把后台任务、reservation 与 WAITING_TOOL_JOB handoff
            // 持久化；这里必须原样重抛，使 LangChain executor 能构造 suspended result 并让旧 worker
            // 退出。若落入下面的通用 Exception 分支，信号会被改写成 JSON，旧执行链将错误地继续。
            //
            // 此处分层的完整因果链如下：
            // 1. PythonSandboxTools 已经取得 operationId，并在 createTask 前抢占 PREPARING anchor；
            // 2. Sandbox 接受后台任务后，anchor 记录 taskId、estimate 与 reservation；
            // 3. fast-path 未得到终态时，单条 CAS 同时写 PENDING anchor 与 WAITING_TOOL_JOB；
            // 4. 只有上述 CAS 成功，工具层才构造这个 pending 异常；
            // 5. 当前 router 原样透传，禁止生成 ToolExecutionOutcome；
            // 6. 上层 todo executor 捕获稳定身份并返回 suspended workflow result；
            // 7. pipeline 保存 plan、completedTodos、dataset snapshot 与工具预算检查点；
            // 8. scheduler 的 Runnable finally 最终归还 Agent worker；
            // 9. terminal webhook/reconciler 后续独立接管结果并创建恢复租约；
            // 10. resume launcher 重新经过同一个有界 scheduler 获取新 worker。
            //
            // 因此这里还刻意不做四件事：
            // - 不记录普通失败指标，pending 并未失败；
            // - 不写工具结果缓存，当前没有 terminal result；
            // - 不清理 anchor，清理权属于带 token/version 的恢复消费者；
            // - 不释放 Sandbox reservation，它会在 finalizer 确认终态后准确释放。
            // 任一“方便的统一异常处理”都会破坏上述 durable handoff 顺序。
            throw pending;
        } catch (Exception e) {
            // 任意工具实现抛出的异常都收敛为标准失败 JSON，避免对 LLM 暴露 Java 异常信息
            // 具体堆栈仍在服务日志中，模型只拿到可解释的 error.message。
            debugLog("tool invoke exception: runId={}, tool={}, error={}",
                    AgentContext.getRunId(), nvl(toolName), nvl(e.getMessage()));
            result = invocationError(toolName, e.getMessage());
        }
        return ToolResultCacheService.ToolExecutionOutcome.builder()
                .result(result)
                .durationMs(Math.max(0L, System.currentTimeMillis() - startedAt))
                .success(isToolSuccess(result))
                .build();
    }

    private String subAgentUnavailable(String toolName) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "SUB_AGENT_UNAVAILABLE",
                        "message", "Sub-agent execution is unavailable in this runtime",
                        "details", Map.of())));
    }

    /**
     * 从多个候选值中取第一个非空白字符串。
     *
     * <p>用于参数别名兼容：例如 LLM 可能传入 tsCode、ts_code、code、arg0 任意之一，
     * 调用 {@code str(params.get("tsCode"), params.get("ts_code"), ...)} 即可取第一个有效值。</p>
     */
    private String str(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) {
                continue;
            }
            String s = String.valueOf(c).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    /**
     * 收集 executePython 的 datasetIds 参数（兼容多种命名风格）。
     *
     * <p>LLM 在不同 prompt 风格下可能输出：dataset_ids（数组）、datasetIds（驼峰）、
     * datasets、dataset_refs、单个 dataset_id 等多种写法，此处统一收集去重后
     * 用逗号拼接为字符串传给沙箱工具。</p>
     */
    private String collectExecutePythonDatasetIds(Map<String, Object> params) {
        LinkedHashSet<String> datasetIds = new LinkedHashSet<>();
        addDatasetIds(datasetIds,
                params.get("dataset_ids"),
                params.get("datasetIds"),
                params.get("datasets"),
                params.get("dataset_refs"),
                params.get("datasetRefs"),
                params.get("arg2"),
                params.get("dataset_id"),
                params.get("datasetId"),
                params.get("arg1")
        );
        return String.join(",", datasetIds);
    }

    /**
     * 260623-harness-optimization-02: 收集 executePython 的 manifestIds 参数（兼容多种命名风格）。
     *
     * <p>与 {@link #collectExecutePythonDatasetIds} 形态一致，但走 manifest 命名空间：
     * manifest_ids / manifestIds / manifests / manifest_refs / manifestRefs。
     * 拼接为逗号分隔字符串供 {@code PythonStaticPrecheckService.check} 与
     * {@code PythonSandboxTools.executePython} 5 形参 overload 使用。</p>
     *
     * <p><b>非对称契约（Cindy round 2 review cleanup 拍板）</b>：legacy 位置参数
     * {@code arg1} <b>不</b>进 manifest 命名空间 — 历史上 dataset / manifest 共用
     * {@code arg1} 时存在「同一 {@code arg1=1} 同时进 dataset_ids 和 manifest_ids」的
     * 歧义。修正后：
     * <ul>
     *   <li>{@code arg1} 只进 {@link #collectExecutePythonDatasetIds}（向后兼容老 prompt 风格）</li>
     *   <li>manifest_ids 只能由显式命名 key（{@code manifest_ids} / {@code manifestIds} /
     *       {@code manifests} / {@code manifest_refs} / {@code manifestRefs}）触发</li>
     * </ul>
     * 这样 {@code arg1=1} 不会意外 leak 到 manifest_ids 空间，避免模型把 dataset 编号
     * 错填成 manifest 编号。
     */
    private String collectExecutePythonManifestIds(Map<String, Object> params) {
        LinkedHashSet<String> manifestIds = new LinkedHashSet<>();
        addDatasetIds(manifestIds,
                params.get("manifest_ids"),
                params.get("manifestIds"),
                params.get("manifests"),
                params.get("manifest_refs"),
                params.get("manifestRefs")
                // arg1 故意不在此列表中 — 见 Javadoc 非对称契约
        );
        return String.join(",", manifestIds);
    }

    /**
     * 将候选对象按 {@link #parseDatasetIds} 解析后追加到收集器，保留首次出现顺序、自动去重。
     */
    private void addDatasetIds(LinkedHashSet<String> collector, Object... candidates) {
        if (collector == null || candidates == null || candidates.length == 0) {
            return;
        }
        for (Object candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            List<String> parsed = parseDatasetIds(String.valueOf(candidate));
            collector.addAll(parsed);
        }
    }

    /**
     * 将一个候选字符串解析为 dataset id 列表。
     *
     * <p>兼容多种形态：</p>
     * <ul>
     *   <li>JSON 数组字面量：{@code ["a","b","c"]} — 去掉外层方括号后按逗号拆分</li>
     *   <li>逗号分隔字符串：{@code a,b,c}</li>
     *   <li>带双引号的元素：自动剥离首尾引号</li>
     * </ul>
     * 解析结果会保留顺序并去重。
     */
    private List<String> parseDatasetIds(String datasetIds) {
        if (datasetIds == null || datasetIds.isBlank()) {
            return List.of();
        }
        String raw = datasetIds.trim();
        // 去掉外层 [ ]
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = nvl(part).trim();
            // 去掉单个元素首尾的双引号
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (!value.isBlank() && !ids.contains(value)) {
                ids.add(value);
            }
        }
        return ids;
    }

    /**
     * 从候选值中解析可空整数，无有效值或格式非法时返回 null。
     */
    private Integer toNullableInt(Object... candidates) {
        String value = str(candidates);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从候选值中解析整数，无有效值或格式非法时返回 {@code defaultValue}。
     */
    private int toIntWithDefault(int defaultValue, Object... candidates) {
        String value = str(candidates);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 解析可选整数参数，无有效值时返回 null。 */
    private Integer toIntOrNull(Object... candidates) {
        String value = str(candidates);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 从候选值中解析布尔值，无值时默认 false。
     */
    private boolean toBool(Object... candidates) {
        String value = str(candidates);
        if (value.isEmpty()) {
            return false;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 规范化日期字符串。
     *
     * <p>若候选值的纯数字部分长度为 8（YYYYMMDD）或 13（毫秒时间戳），
     * 则返回纯数字字符串；否则返回原始 trim 后的字符串，由下游自行解析。</p>
     */
    private String dateStr(Object... candidates) {
        String raw = str(candidates);
        if (raw.isEmpty()) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 8 || digits.length() == 13) {
            return digits;
        }
        return raw;
    }

    /**
     * 向观测服务写入一次工具调用 trace。
     *
     * <p>调用条件：必须存在有效的 runId。脱离 run 上下文（如冒烟测试）的调用不记录。
     * 字段映射：成功时不上报 errorMessage；缓存元数据若为 null 使用安全的占位值。</p>
     *
     * <p>{@code tool_call_id} 不在参数列表里出现，而是由上游 executor 写入
     * {@link AgentContext}。这样同一次工具调用的 SSE event 与 observability trace 可以对齐；
     * 后续前端调用 {@code /tool-calls/{toolCallId}/detail} 时才能命中同一条安全详情。</p>
     */
    private void recordObservability(String toolName,
                                     Map<String, Object> params,
                                     String result,
                                     long durationMs,
                                     boolean success,
                                     ToolResultCacheService.CacheMeta cacheMeta) {
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        String phase = AgentContext.getPhase();
        observabilityService.recordToolCall(
                runId,
                phase,
                toolName,
                params,
                result,
                durationMs,
                success,
                cacheMeta != null && cacheMeta.isEligible(),
                cacheMeta != null && cacheMeta.isHit(),
                cacheMeta == null ? "" : cacheMeta.getKey(),
                cacheMeta == null ? "" : cacheMeta.getSource(),
                cacheMeta == null ? -1L : cacheMeta.getTtlRemainingMs(),
                cacheMeta == null ? 0L : cacheMeta.getEstimatedSavedDurationMs(),
                success ? null : result
        );
    }

    /**
     * 根据标准响应 JSON 的 {@code ok} 字段判断工具是否成功。
     *
     * <p>所有工具的成功/失败都通过响应顶层 {@code ok: true|false} 表达，
     * 解析失败或非 JSON 输出一律视为失败。</p>
     */
    private boolean isToolSuccess(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(result);
            return node.path("ok").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建 "工具不支持" 的标准失败响应。
     */
    private String unsupported(String toolName) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "UNSUPPORTED_TOOL",
                        "message", "Unsupported tool",
                        "details", Map.of("tool", nvl(toolName))
                )
        ));
    }

    /**
     * 构建 "工具调用异常" 的标准失败响应。
     */
    private String invocationError(String toolName, String message) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "TOOL_INVOCATION_ERROR",
                        "message", nvl(message),
                        "details", Map.of()
                )
        ));
    }

    private String weightLimitExceeded(String toolName, int effectiveWeight) {
        return writeJson(Map.of(
                "ok", false,
                "tool", nvl(toolName),
                "data", Map.of(),
                "error", Map.of(
                        "code", "TOOL_WEIGHT_LIMIT_EXCEEDED",
                        "message", "Tool weighted concurrency limit exceeded, retry later",
                        "details", Map.of(
                                "effectiveWeight", Math.max(0, effectiveWeight),
                                "tool", nvl(toolName)
                        )
                )
        ));
    }

    /**
     * 序列化 payload 为 JSON 字符串。
     *
     * <p>若主序列化失败（极少见，例如循环引用），退化为返回包含
     * JSON_SERIALIZE_ERROR 错误码的兜底 JSON；若兜底 JSON 也序列化失败，
     * 最终返回硬编码字符串 {@code "{\"ok\":false}"}，确保始终返回合法 JSON。</p>
     */
    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("ok", false);
            fallback.put("tool", "unknown");
            fallback.put("data", Map.of());
            fallback.put("error", Map.of(
                    "code", "JSON_SERIALIZE_ERROR",
                    "message", nvl(e.getMessage()),
                    "details", Map.of()
            ));
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{\"ok\":false}";
            }
        }
    }

    /**
     * 解析工具结果缓存的 scope（D07 Risks 3.3.2 口径）。
     *
     * <p>优先按用户隔离（{@code user:<userId>}）；无用户上下文时按 run 隔离
     * （{@code run:<runId>}），不同匿名 run 互不命中；二者皆无则返回空串，
     * 由缓存层 fail-closed 跳过共享缓存读写，绝不退化为可跨租户串线的
     * {@code global} 兜底。AgentContext 当前无 session 身份概念，run 即匿名隔离单元。</p>
     */
    private String resolveScope() {
        String userId = AgentContext.getUserId();
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId.trim();
        }
        String runId = AgentContext.getRunId();
        if (runId != null && !runId.isBlank()) {
            return "run:" + runId.trim();
        }
        return "";
    }

    /** 空安全：null 转为空字符串。 */
    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 截取前 300 字符用于调试日志预览，避免长结果污染日志。 */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > 300) {
            return text.substring(0, 300);
        }
        return text;
    }

    /** 安全序列化为 JSON 字符串用于日志，失败时退化为 toString。 */
    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * 仅在 AgentContext.debugMode 为 true 时输出 info 级调试日志。
     *
     * <p>避免在生产环境中无差别打印工具参数和结果，节省日志量。</p>
     */
    private void debugLog(String pattern, Object... args) {
        if (!AgentContext.isDebugMode()) {
            return;
        }
        log.info("[agent-debug] " + pattern, args);
    }

    /**
     * 工具调用结果的完整封装。
     *
     * <p>包含执行输出、成功标志、耗时与缓存元数据，方便调用方判断是否触发重试、
     * 是否上报事件流缓存命中等。</p>
     */
    @Data
    @Builder
    public static class ToolInvocationResult {
        /** 工具输出的标准响应 JSON 字符串 */
        private String output;
        /** 是否成功（根据响应顶层 ok 字段判定） */
        private boolean success;
        /** 本次调用耗时（含缓存判断和实际执行） */
        private long durationMs;
        /** 缓存元数据（是否符合缓存条件、是否命中、来源、剩余 TTL 等） */
        private ToolResultCacheService.CacheMeta cacheMeta;
        /**
         * D07：是否因权重限流被拒绝而未真正执行（不消耗成功预算、工具 credit 为 0，
         * executor 据此写 FINISHED 契约字段 throttle_layer=weight_limit）。LC4j 前台
         * Semaphore 拒绝发生在进入 Router 之前，不经本标记传递，由 executor 直接标注
         * throttle_layer=lc4j_semaphore。
         */
        private boolean throttleRejected;
    }
}
