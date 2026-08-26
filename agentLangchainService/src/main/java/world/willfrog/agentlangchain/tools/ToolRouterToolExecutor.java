package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentSsePayloadSupport;
import world.willfrog.agent.workflow.DatasetRefRegistry;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.orchestration.ToolThrottleResult;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * LC4j {@link ToolExecutor}：模型在 tool loop 里发起一次 tool call 后，由此类把请求转给
 * legacy {@link ToolRouter}，并处理 langchain 路径特有的防护与提示。
 *
 * <p>与 {@link ToolRouterToolProvider} 的配合：Provider 负责「有哪些工具」；本类负责
 * 「选中某个工具后怎么跑」。所有工具名最终都进入 {@link ToolRouter#invokeWithMeta(String, Map)}，
 * 因此预算检查、observability trace、结果缓存、统一 JSON 响应格式都由 ToolRouter
 * 统一完成。</p>
 *
 * <p>单次调用的处理顺序（{@link #execute}）：</p>
 * <ol>
 *   <li>解析或生成 {@code tool_call_id}：优先复用 LC4j 请求中的 ID，若无则 fallback 到 UUID，
 *       确保 SSE 事件和 observability trace 的 tool call 归属一致；</li>
 *   <li>把 LC4j 传来的 arguments JSON 解析为 {@code Map<String, Object>}；</li>
 *   <li>{@link LangchainRepeatedToolCallGuard}：同一 run 内相同工具+相同参数重复超过阈值则直接返回错误文本
 *       （此路径跳过 STARTED 直接 emit FINISHED，避免前端 UI card 永远转圈）；</li>
 *   <li>emit <b>TOOL_CALL_STARTED</b> 事件（经 SSE + Redis），携带 tool_call_id、tool_name、arguments、phase；</li>
 *   <li>{@link ToolRouter#invokeWithMeta} 执行并取 output 字符串；</li>
 *   <li>emit <b>TOOL_CALL_FINISHED</b> 事件（经 SSE + Redis），携带结果、duration_ms、success；</li>
 *   <li>从 output 解析 {@code dataset_id}，写入 {@link DatasetRefRegistry} 与
 *       {@link LangchainDatasetRefContext}，供 DAG 下游 todo 或 executePython 引用；</li>
 *   <li>若 output 暗示 dataset 缺失/无效，或发生重复调用，在结果末尾追加 {@code _retry_hint_}
 *       引导模型改参（不抛异常，让模型在下一轮 tool loop 自行纠正）。</li>
 * </ol>
 *
 * <p>面试常考点：</p>
 * <ul>
 *   <li>「LC4j tool call 怎么落到 MarketDataTools？」→ 本类 → ToolRouter → 具体工具 Bean；</li>
 *   <li>「为什么 cancel 后还能拦住后续工具/LLM？」→ LC4j 层由
 *       {@link world.willfrog.agentlangchain.orchestration.LangchainRunExecutionGuard} 在发 LLM 前和工具前检查；
 *       {@link ToolRouter} 负责预算与工具运行时横切逻辑，不承担 cancel 状态机；</li>
 *   <li>「dataset 怎么跨 todo 传递？」→ 本类注册 ref + TodoNodeExecutor 把 refs 写进 user message。</li>
 * </ul>
 *
 * @see ToolRouterToolProvider 工具目录入口
 * @see world.willfrog.agent.tools.router.ToolRouter 统一执行与观测
 * @see LangchainRepeatedToolCallGuard 重复调用防护
 * @see world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor tool loop 宿主
 */
@RequiredArgsConstructor
@Slf4j
final class ToolRouterToolExecutor implements ToolExecutor {

    /** 事件 payload 中 output 预览的最大字符数，避免超大结果超出事件体大小限制。 */
    private static final int OUTPUT_PREVIEW_MAX_CHARS = 500;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolRouter toolRouter;
    private final ObjectMapper objectMapper;
    private final AgentEventService eventService;
    private final LangchainToolConcurrencyThrottle toolThrottle;
    private final PythonSandboxDispatchStore pythonSandboxDispatchStore;

    /**
     * 执行一次 LC4j tool call 并把结果返回给模型。
     *
     * <p>处理顺序：</p>
     * <ol>
     *   <li>解析/生成 tool_call_id 并写入 {@link AgentContext}；</li>
     *   <li>解析 arguments JSON；</li>
     *   <li>{@link LangchainRepeatedToolCallGuard} 拦截重复调用；</li>
     *   <li>发射 <b>TOOL_CALL_STARTED</b> SSE 事件；</li>
     *   <li>经 {@link LangchainToolConcurrencyThrottle} 获取 permit 后调用 {@link ToolRouter}；</li>
     *   <li>发射 <b>TOOL_CALL_FINISHED</b> SSE 事件；</li>
     *   <li>注册 dataset ref 并追加 retry hint。</li>
     * </ol>
     *
     * <p>{@link #executeWithContext} 是推荐入口，会先同步 {@link InvocationContext}
     * 中的 run 上下文到 {@link AgentContext}。</p>
     */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String toolCallId = resolveToolCallId(request);
        AgentContext.setToolCallId(toolCallId);
        try {
            Map<String, Object> params = parseArguments(request.arguments());
            LangchainRepeatedToolCallGuard.Decision repeatDecision =
                    LangchainRepeatedToolCallGuard.beforeInvoke(request.name(), params, objectMapper);
            if (repeatDecision.blocked()) {
                // 重复调用被拦截时也发射 FINISHED 事件，避免前端 UI card 一直转圈
                emitToolCallFinished(toolCallId, request.name(), params, false, repeatDecision.outputOrHint(), 0L);
                return repeatDecision.outputOrHint();
            }

            // emit STARTED
            emitToolCallStarted(toolCallId, request.name(), params);

            Instant start = Instant.now();
            String output = null;
            boolean success = true;
            // 限流拒绝（本层 LC4j Semaphore 或下游权重限流）时工具没有真正执行，
            // FINISHED 事件只用于前端展示收尾，必须带 creditsConsumed=0 和可区分的拒绝标记
            boolean throttleRejected = false;
            String throttleLayer = null;

            ToolThrottleResult throttleResult = toolThrottle.tryAcquire(request.name());
            if (!throttleResult.acquired() && throttleResult.failureReason() != null) {
                // 限流等待超时或被打断时，只把错误文本返回给模型，不把整个 run 判为失败
                String reason = throttleResult.failureReason();
                log.warn("Tool throttled: tool={} reason={}", request.name(), reason);
                output = reason;
                success = false;
                throttleRejected = true;
                throttleLayer = "lc4j_semaphore";
            } else {
                try {
                    // invokeWithMeta 可能直接返回结果；后台任务超时未完成时，会抛出专门的
                    // 挂起信号异常，通知上层任务已转后台、可以释放线程。
                    ToolRouter.ToolInvocationResult result = toolRouter.invokeWithMeta(request.name(), params);
                    // 只有真正的终态结果才走普通的 output/success 收尾流程。
                    output = result.getOutput();
                    success = result.isSuccess();
                    if (result.isThrottleRejected()) {
                        throttleRejected = true;
                        throttleLayer = "weight_limit";
                    }
                } catch (ExternalToolJobPendingException pending) {
                    // 挂起异常表示 Sandbox 后台任务仍在运行，等待终态事件到来。
                    // 这里不能转成字符串 output，否则 LLM 会误以为工具已经完成。
                    // 这里也不能写 TOOL_CALL_FINISHED；终态事件由 reconciler/finalizer
                    //（后台任务的进度对账与终态处理组件）负责写入。
                    // 原样重抛可保留 runId/toolCallId/attempt，供上层生成可恢复的挂起结果。
                    throw pending;
                } catch (Exception e) {
                    output = e.getMessage();
                    success = false;
                    log.warn("Tool invocation failed: tool={}, runId={}", request.name(), AgentContext.getRunId(), e);
                } finally {
                    if (throttleResult.acquired()) {
                        toolThrottle.release(throttleResult);
                    }
                }
            }

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            // 限流拒绝时工具没有真正执行，不计入执行耗时统计
            if (!throttleRejected) {
                toolThrottle.recordExecution(request.name(), durationMs);
            }
            emitToolCallFinished(toolCallId, request.name(), params, success, output, durationMs,
                    throttleRejected, throttleLayer);
            acknowledgeSynchronousPythonCompletion(toolCallId, request.name());

            Map<String, String> datasetRefs = LangchainDatasetRefContext.snapshot();
            DatasetRefRegistry.registerFromJson(output, datasetRefs);
            LangchainDatasetRefContext.set(datasetRefs);
            output = appendDatasetRetryHintIfNeeded(output, datasetRefs);
            return appendRepeatedToolCallHintIfNeeded(output, repeatDecision);
        } finally {
            AgentContext.clearToolCallId();
        }
    }

    /**
     * LC4j 带上下文执行：先把 {@link InvocationContext#invocationParameters()} 灌进
     * {@link world.willfrog.agent.platform.context.AgentContext}（经 {@link LangchainRunContextBridge}），再调用 {@link #execute}。
     */
    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        if (context != null) {
            LangchainRunContextBridge.apply(context.invocationParameters());
        }
        String output = execute(request, context == null ? null : context.chatMemoryId());
        return ToolExecutionResult.builder()
                .resultText(output)
                .build();
    }

    /** 解析模型输出的 tool arguments JSON；解析失败时保留 raw 字段避免整次调用 NPE。 */
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of("raw", arguments);
        }
    }

    /**
     * 解析或生成 tool_call_id，确保 SSE 事件与 observability trace 的归属一致。
     *
     * <p>优先级：LC4j 请求自带的 ID > 随机 UUID。
     * LC4j 在每次 tool loop 中会为 ToolExecutionRequest 分配稳定 ID（通常来自模型输出），
     * 复用该 ID 可保证前端 SSE 流中的 TOOL_CALL_STARTED/TOOL_CALL_FINISHED 与
     * observability timeline 里的 tool trace 一一对应。</p>
     *
     * @param request LC4j 的 tool 执行请求
     * @return 稳定的 tool_call_id 字符串
     */
    private String resolveToolCallId(ToolExecutionRequest request) {
        String id = request == null ? null : request.id();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * executePython 等工具若因 dataset_ids / manifest_ids 错误失败，把当前 run 已知的 ref 列表写进 hint，
     * 引导模型先用 listMyData 解析 run-level 整数 ID，避免继续重试 raw id / path / placeholder。
     */
    private String appendDatasetRetryHintIfNeeded(String output, Map<String, String> datasetRefs) {
        if (output == null || output.isBlank()) {
            return output;
        }
        String lower = output.toLowerCase();
        boolean datasetError = lower.contains("missing_dataset_ids")
                || lower.contains("missing dataset_ids")
                || lower.contains("invalid dataset_ids")
                || lower.contains("missing_manifest_ids")
                || lower.contains("missing manifest_ids")
                || lower.contains("invalid_manifest_ids")
                || lower.contains("invalid manifest_ids")
                || lower.contains("run_level_ids_unavailable")
                || lower.contains("dataset_id directory not found")
                || (lower.contains("dataset_ids") && containsFailureWord(lower))
                || (lower.contains("manifest_ids") && containsFailureWord(lower));
        if (!datasetError) {
            return output;
        }
        StringBuilder hint = new StringBuilder(output);
        hint.append("\n\n_retry_hint_: executePython failed because the run-level dataset_ids/manifest_ids are missing, invalid, or unavailable. ");
        hint.append("executePython expects current run-level integer dataset_ids / manifest_ids, not raw dataset_id / manifest_id strings, paths, or scope hashes. ");
        hint.append("Call listMyData first (query_type=dataset or query_type=manifest) to resolve the integer ids for this run before retrying. ");
        if (lower.contains("run_level_ids_unavailable")) {
            hint.append("RUN_LEVEL_IDS_UNAVAILABLE means the active run registry is not available; do not keep retrying the same raw ids. ");
        }
        if (datasetRefs != null && !datasetRefs.isEmpty()) {
            hint.append("Observed raw ids available for lookup: ");
            hint.append(String.join(",", datasetRefs.keySet()));
            hint.append(". Resolve them through listMyData instead of passing them directly.");
        } else {
            hint.append("If listMyData has no data, call a market data tool first, then listMyData, and do not use placeholders such as placeholder/data/test or hand-code market data.");
        }
        return hint.toString();
    }

    private boolean containsFailureWord(String lowerOutput) {
        return lowerOutput.contains("error")
                || lowerOutput.contains("failed")
                || lowerOutput.contains("failure")
                || lowerOutput.contains("missing")
                || lowerOutput.contains("invalid")
                || lowerOutput.contains("not found");
    }

    /** 未 blocked 但已重复调用时，在 output 末尾追加提示，供模型下一轮改参。 */
    private String appendRepeatedToolCallHintIfNeeded(String output,
                                                      LangchainRepeatedToolCallGuard.Decision repeatDecision) {
        if (repeatDecision == null || !repeatDecision.repeated() || repeatDecision.blocked()) {
            return output;
        }
        String base = output == null ? "" : output;
        return base + "\n\n_retry_hint_: " + repeatDecision.outputOrHint();
    }

    // ── Tool call event emission helpers ──

    /**
     * 发射 TOOL_CALL_STARTED 事件，经 SSE 推送 + Redis 持久化。
     *
     * <p>payload 包含 tool_call_id（与 observability traceId 对齐）、tool_name、arguments、
     * 以及 {@link AgentSsePayloadSupport} 注入的 workflow/phase/stage/todo_id 归属信息。
     * 若 AgentContext 中缺失 runId 或 userId（如单元测试场景），则跳过发射并打 warn 日志，
     * 避免 NPE 中断 tool loop。</p>
     *
     * @param toolCallId 本次 tool call 的稳定 ID
     * @param toolName   工具名
     * @param arguments  解析后的参数映射
     */
    private void emitToolCallStarted(String toolCallId, String toolName, Map<String, Object> arguments) {
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || userId == null) {
            log.warn("Skip TOOL_CALL_STARTED: missing runId or userId in AgentContext. tool={}", toolName);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("tool_call_id", toolCallId);
        payload.put("tool_name", toolName);
        payload.put("arguments", arguments);
        String phase = AgentContext.getPhase();
        if (phase != null && !phase.isBlank()) {
            payload.put("phase", phase);
        }
        AgentSsePayloadSupport.putExecutionAttribution(payload);
        eventService.append(runId, userId, "TOOL_CALL_STARTED", payload);
    }

    /**
     * 发射 TOOL_CALL_FINISHED 事件，经 SSE 推送 + Redis 持久化。
     *
     * <p>payload 包含 tool_call_id、执行结果（success/duration_ms）、result_preview（截断预览，
     * 避免超大结果超出事件体大小限制），以及 {@link AgentSsePayloadSupport} 注入的归属信息。
     * 重复调用被拦截时也会发射本事件，使前端 UI card 能从「loading」状态恢复为错误展示，
     * 不会一直转圈。</p>
     *
     * @param toolCallId  本次 tool call 的稳定 ID
     * @param toolName    工具名
     * @param arguments   解析后的参数映射
     * @param success     工具执行是否成功（ToolRouter 返回 isSuccess）
     * @param output      工具输出文本（可能为异常消息）
     * @param durationMs  工具执行耗时（毫秒）
     */
    private void emitToolCallFinished(String toolCallId, String toolName, Map<String, Object> arguments,
                                      boolean success, String output, long durationMs) {
        emitToolCallFinished(toolCallId, toolName, arguments, success, output, durationMs, false, null);
    }

    private void emitToolCallFinished(String toolCallId, String toolName, Map<String, Object> arguments,
                                      boolean success, String output, long durationMs,
                                      boolean throttleRejected, String throttleLayer) {
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId == null || userId == null) {
            log.warn("Skip TOOL_CALL_FINISHED: missing runId or userId in AgentContext. tool={}", toolName);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("tool_call_id", toolCallId);
        payload.put("tool_name", toolName);
        payload.put("arguments", arguments);
        payload.put("success", success);
        payload.put("result_preview", preview(output));
        payload.put("duration_ms", durationMs);
        if (throttleRejected) {
            /*
             * 限流拒绝时工具没有真正执行，显式写 creditsConsumed=0，让
             * AgentCreditService 不按默认工具单价扣费；rejected_by_throttle /
             * throttle_layer 供前端与汇总侧区分「执行失败」与「限流拒绝」。
             */
            payload.put("creditsConsumed", 0);
            payload.put("rejected_by_throttle", true);
            payload.put("throttle_layer", throttleLayer);
        }
        String phase = AgentContext.getPhase();
        if (phase != null && !phase.isBlank()) {
            payload.put("phase", phase);
        }
        AgentSsePayloadSupport.putExecutionAttribution(payload);
        if ("executePython".equals(toolName)) {
            String dedupeKey = runId + ":" + toolCallId + ":logical_terminal";
            eventService.appendOnce(runId, userId, "TOOL_CALL_FINISHED", dedupeKey, payload);
        } else {
            eventService.append(runId, userId, "TOOL_CALL_FINISHED", payload);
        }
    }

    private void acknowledgeSynchronousPythonCompletion(String toolCallId, String toolName) {
        if (!"executePython".equals(toolName) || pythonSandboxDispatchStore == null) {
            return;
        }
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        String operationId = new DataAnalysisOperationIdentity(runId, toolCallId, 1).operationId();
        if (!pythonSandboxDispatchStore.clearSynchronouslyCompleted(runId, operationId)) {
            log.debug("No proof-complete synchronous Python anchor cleared for run={}, operationId={}",
                    runId, operationId);
        }
    }

    /**
     * 截断工具输出文本，用于事件 payload 的 result_preview 字段。
     *
     * <p>防止超大结果（如包含数千行的日线数据）直接塞进 SSE 事件体导致 payload 过大。
     * 完整输出会先由 {@code AgentObservabilityService} 写入 Redis detail blob；
     * 持久化后的 observability trace 只保留 outputPreview / detailBlobStored 等索引字段，
     * 前端通过 safe detail API 按需读取，过期则返回 expired/unavailable。</p>
     *
     * @param text 原始工具输出
     * @return 截断后的预览文本，若未超限则原样返回
     */
    private String preview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= OUTPUT_PREVIEW_MAX_CHARS) {
            return text;
        }
        return text.substring(0, OUTPUT_PREVIEW_MAX_CHARS) + "... (truncated, length=" + text.length() + ")";
    }
}
