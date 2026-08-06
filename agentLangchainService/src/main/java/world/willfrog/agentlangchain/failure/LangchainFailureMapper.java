package world.willfrog.agentlangchain.failure;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.exception.ProviderChatException;
import world.willfrog.agent.platform.exception.ProviderFailureCategory;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 失败分类器 —— 把 agent run 中捕获的异常/错误信息映射为结构化的失败决策。
 *
 * <h2>为什么需要这个类</h2>
 * agent 执行过程中会产生各种各样的失败：LLM 调用超时、工具参数错误、HTTP 5xx、
 * 预算耗尽、空输出等。如果所有失败都笼统地归类为 WORKFLOW_FAILED，排障时
 * 只能靠肉眼扫日志，无法做自动重试、针对性告警或 observability 聚合。
 *
 * <h2>分类路径</h2>
 * <ol>
 *   <li><b>Typed exception path（优先）</b>：
 *       {@link ProviderChatException} / {@link RunBudgetException} 直接映射为稳定分类。</li>
 *   <li><b>Cause chain path（兼容）</b>：遍历 cause 最多 3 层，寻找 typed exception。</li>
 *   <li><b>Legacy string path（兼容）</b>：按关键词匹配历史错误文本。</li>
 * </ol>
 *
 * <h2>失败分类</h2>
 * <ol>
 *   <li>{@code BUDGET_EXCEEDED} — wall clock / tokens / tool calls 超限，不可重试</li>
 *   <li>{@code REPEATED_TOOL_CALL} — 同 tool 同参数重复调用被拦截，可重试</li>
 *   <li>{@code PARAM_RETRY_WITH_HINT} — 参数错误（缺 dataset_id、schema 校验失败等），
 *       可重试并提示修正方向</li>
 *   <li>{@code INFRA_RETRY} — 基础设施错误（HTTP 5xx、连接重置、超时），可重试</li>
 *   <li>{@code TOOL_ERROR} — 工具执行失败（非参数原因），不可重试</li>
 *   <li>{@code EMPTY_OUTPUT} — LLM 返回空内容，不可重试</li>
 *   <li>Provider 细分：{@code PROVIDER_TRANSIENT} / {@code PROVIDER_RATE_LIMIT} /
 *       {@code PROVIDER_BAD_REQUEST} / {@code PROVIDER_MODEL_UNAVAILABLE} /
 *       {@code PROVIDER_AUTH_REJECTED} / {@code PROVIDER_UNKNOWN}</li>
 *   <li>Budget 维度细分：{@code BUDGET_EXCEEDED_*}（llm_calls / tokens / tool_calls /
 *       wall_clock / http_attempts）</li>
 * </ol>
 * 兜底分类为 {@code UNKNOWN}。
 *
 * <h2>稳定消费字段</h2>
 * 事件 payload 新增 {@code failure_category}（lower_snake_case），例如
 * {@code provider_transient_network}、{@code budget_exceeded_llm_calls}。
 * 旧 {@code category} 字段保留兼容。下游 harness 优先读 {@code failure_category}，
 * 读不到再 fallback {@code category}。
 */
@Component
public class LangchainFailureMapper {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BUDGET_PATTERN = Pattern.compile("RUN_BUDGET_EXCEEDED:([^:]+):(-?\\d+)/(\\d+)");

    /**
     * 主分类入口。
     *
     * <p>匹配顺序：typed exception → cause chain → legacy keyword。
     * 预算类仍排最前，避免被后续 INFRA_RETRY / TOOL_ERROR 误覆盖。</p>
     */
    public LangchainFailureDecision map(String phase,
                                        String todoId,
                                        String toolName,
                                        String failureReason,
                                        String toolOutput,
                                        Throwable throwable,
                                        Integer toolCallsUsed) {
        // 1. Typed path
        if (throwable instanceof ProviderChatException pce) {
            return mapProviderChatException(pce, phase, todoId, toolName, toolCallsUsed);
        }
        if (throwable instanceof RunBudgetException rbe) {
            return mapRunBudgetException(rbe, phase, todoId, toolName, toolCallsUsed);
        }

        // 2. Cause chain path（兼容 wrapper）
        Throwable typedCause = findTypedCause(throwable, 3);
        if (typedCause instanceof ProviderChatException pce) {
            return mapProviderChatException(pce, phase, todoId, toolName, toolCallsUsed);
        }
        if (typedCause instanceof RunBudgetException rbe) {
            return mapRunBudgetException(rbe, phase, todoId, toolName, toolCallsUsed);
        }

        // 3. Legacy string path
        String text = collect(failureReason, toolOutput, throwable);
        String lower = text.toLowerCase(Locale.ROOT);
        String errorCode = extractErrorCode(text);

        if (containsAny(lower, "run_budget_exceeded", "wall_clock_ms", "budget_exceeded", "max tokens",
                "max_llm_calls", "max_tool_calls")) {
            String dimension = parseBudgetDimension(text);
            return decision("RUN_BUDGET_EXCEEDED", LangchainFailureCategory.BUDGET_EXCEEDED, false,
                    "RunBudgetExceeded", text, phase, todoId, toolName, errorCode, toolCallsUsed,
                    dimension, null, null, null, null, null);
        }
        if (containsAny(lower, "repeated_tool_call", "repeated tool")) {
            return decision("TOOL_ERROR", LangchainFailureCategory.REPEATED_TOOL_CALL, true,
                    "RepeatedToolCall", text, phase, todoId, toolName, errorCode, toolCallsUsed,
                    null, null, null, null, null, null);
        }
        if (containsAny(lower, "missing_dataset_ids", "dataset_id directory not found", "dataset_ids",
                "schema", "validation", "missing required", "required parameter", "invalid parameter",
                "参数名", "keyword")) {
            return decision("TOOL_ERROR", LangchainFailureCategory.PARAM_RETRY_WITH_HINT, true,
                    "ParameterRetryWithHint", text, phase, todoId, toolName, errorCode, toolCallsUsed,
                    null, null, null, null, null, null);
        }
        if (containsAny(lower, "http_error_5", "http 500", "http 502", "http 503", "http 504",
                "timeout", "timed out", "connection reset", "connection refused", "upstream unavailable",
                "internal server error")) {
            return decision("TOOL_ERROR", LangchainFailureCategory.INFRA_RETRY, true,
                    "InfrastructureRetry", text, phase, todoId, toolName, errorCode, toolCallsUsed,
                    null, null, null, null, null, null);
        }
        if (containsAny(lower, "tool_error", "tool execution", "failed to execute tool")) {
            return decision("TOOL_ERROR", LangchainFailureCategory.TOOL_ERROR, false,
                    "ToolError", text, phase, todoId, toolName, errorCode, toolCallsUsed,
                    null, null, null, null, null, null);
        }
        if (containsAny(lower, "empty_final_answer", "empty_todo_output", "blank output")) {
            return decision("WORKFLOW_FAILED", LangchainFailureCategory.EMPTY_OUTPUT, false,
                    "EmptyOutput", text, phase, todoId, toolName, errorCode, toolCallsUsed,
                    null, null, null, null, null, null);
        }
        return decision("WORKFLOW_FAILED", LangchainFailureCategory.UNKNOWN, false,
                "WorkflowFailed", text, phase, todoId, toolName, errorCode, toolCallsUsed,
                null, null, null, null, null, null);
    }

    public LangchainFailureDecision map(String failureReason) {
        return map(null, null, null, failureReason, null, null, null);
    }

    private LangchainFailureDecision mapProviderChatException(ProviderChatException pce,
                                                              String phase,
                                                              String todoId,
                                                              String toolName,
                                                              Integer toolCallsUsed) {
        LangchainFailureCategory category;
        String observabilityType;
        boolean retryable;
        switch (pce.getCategory()) {
            case TRANSIENT_NETWORK -> {
                category = LangchainFailureCategory.PROVIDER_TRANSIENT;
                observabilityType = "ProviderTransientNetwork";
                retryable = true;
            }
            case RATE_LIMIT -> {
                category = LangchainFailureCategory.PROVIDER_RATE_LIMIT;
                observabilityType = "ProviderRateLimit";
                retryable = true;
            }
            case BAD_REQUEST_TOKEN_LIMIT -> {
                category = LangchainFailureCategory.PROVIDER_BAD_REQUEST;
                observabilityType = "ProviderBadRequestTokenLimit";
                retryable = false;
            }
            case MODEL_UNAVAILABLE -> {
                category = LangchainFailureCategory.PROVIDER_MODEL_UNAVAILABLE;
                observabilityType = "ProviderModelUnavailable";
                retryable = false;
            }
            case AUTH_REJECTED -> {
                category = LangchainFailureCategory.PROVIDER_AUTH_REJECTED;
                observabilityType = "ProviderAuthRejected";
                retryable = false;
            }
            default -> {
                category = LangchainFailureCategory.PROVIDER_UNKNOWN;
                observabilityType = "ProviderUnknown";
                retryable = false;
            }
        }

        String reason = pce.getMessage();
        if (!pce.getRawProviderMessage().isBlank()) {
            reason = reason + "\nraw=" + pce.getRawProviderMessage();
        }

        return decision("WORKFLOW_FAILED", category, retryable, observabilityType, reason, phase, todoId,
                toolName, pce.getErrorCode(), toolCallsUsed, null, null, null, null, null, pce)
                .toBuilder()
                .failureSubCategory(pce.getErrorCode())
                .providerOrder(pce.getProviderOrder())
                .model(pce.getModelName())
                .endpoint(pce.getEndpointName())
                .rawMessage(pce.getRawProviderMessage())
                .build();
    }

    private LangchainFailureDecision mapRunBudgetException(RunBudgetException rbe,
                                                           String phase,
                                                           String todoId,
                                                           String toolName,
                                                           Integer toolCallsUsed) {
        LangchainFailureCategory category = mapBudgetDimensionToCategory(rbe.getDimension());
        String observabilityType = category.name().charAt(0) + category.name().substring(1).toLowerCase(Locale.ROOT)
                .replace("_", "");
        // e.g. "BudgetExceededLlmCalls"

        String reason = rbe.getMessage();
        return decision("RUN_BUDGET_EXCEEDED", category, false, observabilityType, reason, phase, todoId,
                toolName, "", toolCallsUsed, rbe.getDimension(), rbe.getActual(), rbe.getLimit(),
                rbe.getRatio(), rbe.isPartial(), null);
    }

    private LangchainFailureCategory mapBudgetDimensionToCategory(String dimension) {
        return switch (dimension) {
            case "tokens" -> LangchainFailureCategory.BUDGET_EXCEEDED_TOKENS;
            case "tool_calls" -> LangchainFailureCategory.BUDGET_EXCEEDED_TOOL_CALLS;
            case "wall_clock_ms" -> LangchainFailureCategory.BUDGET_EXCEEDED_WALL_CLOCK;
            case "http_attempts_per_logical_call" -> LangchainFailureCategory.BUDGET_EXCEEDED_HTTP_ATTEMPTS;
            default -> LangchainFailureCategory.BUDGET_EXCEEDED_LLM_CALLS;
        };
    }

    private String parseBudgetDimension(String text) {
        Matcher matcher = BUDGET_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Throwable findTypedCause(Throwable throwable, int maxDepth) {
        Throwable current = throwable == null ? null : throwable.getCause();
        int depth = 0;
        while (current != null && depth < maxDepth) {
            if (current instanceof ProviderChatException || current instanceof RunBudgetException) {
                return current;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    /**
     * 构造结构化的失败决策对象。
     *
     * <p>payload 中同时保留旧 {@code category} 与新 {@code failure_category}，
     * 下游 harness 优先消费 {@code failure_category}。</p>
     */
    private LangchainFailureDecision decision(String eventType,
                                              LangchainFailureCategory category,
                                              boolean retryable,
                                              String observabilityFailureType,
                                              String reason,
                                              String phase,
                                              String todoId,
                                              String toolName,
                                              String errorCode,
                                              Integer toolCallsUsed,
                                              String dimension,
                                              Long actual,
                                              Long limit,
                                              Double ratio,
                                              Boolean partial,
                                              ProviderChatException providerChatException) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", nvl(reason));
        payload.put("category", category.name());
        String stableCategory = toStableFailureCategory(category, dimension);
        if (!stableCategory.isBlank()) {
            payload.put("failure_category", stableCategory);
        }
        payload.put("retryable", retryable);
        putIfNotBlank(payload, "phase", phase);
        putIfNotBlank(payload, "todo_id", todoId);
        putIfNotBlank(payload, "tool_name", toolName);
        putIfNotBlank(payload, "error_code", errorCode);
        putIfNotBlank(payload, "dimension", dimension);
        if (actual != null) {
            payload.put("actual", actual);
        }
        if (limit != null) {
            payload.put("limit", limit);
        }
        if (ratio != null) {
            payload.put("ratio", ratio);
        }
        if (partial != null) {
            payload.put("partial", partial);
        }
        if (toolCallsUsed != null) {
            payload.put("tool_calls_used", Math.max(0, toolCallsUsed));
        }
        if (providerChatException != null) {
            putIfNotBlank(payload, "failure_sub_category", providerChatException.getErrorCode());
            if (!providerChatException.getProviderOrder().isEmpty()) {
                payload.put("provider_order", providerChatException.getProviderOrder());
            }
            putIfNotBlank(payload, "model", providerChatException.getModelName());
            putIfNotBlank(payload, "endpoint", providerChatException.getEndpointName());
            putIfNotBlank(payload, "raw_message", providerChatException.getRawProviderMessage());
        }

        LangchainFailureDecision.LangchainFailureDecisionBuilder builder = LangchainFailureDecision.builder()
                .runStatus(AgentRunStatus.FAILED)
                .eventType(eventType)
                .reason(nvl(reason))
                .category(category)
                .retryable(retryable)
                .observabilityFailureType(observabilityFailureType)
                .failureCategory(stableCategory)
                .eventPayload(payload);
        if (!stableCategory.isBlank()) {
            builder.failureCategory(stableCategory);
        }
        return builder.build();
    }

    /**
     * 把 {@link LangchainFailureCategory} 转成稳定的 lower_snake_case 消费字段。
     *
     * <p>对旧分类（未细分的 BUDGET_EXCEEDED / INFRA_RETRY 等）返回空串，
     * 避免 harness 把不稳定的枚举名当作消费契约。</p>
     */
    private String toStableFailureCategory(LangchainFailureCategory category, String dimension) {
        return switch (category) {
            case PROVIDER_TRANSIENT -> "provider_transient_network";
            case PROVIDER_RATE_LIMIT -> "provider_rate_limit";
            case PROVIDER_BAD_REQUEST -> "provider_bad_request_token_limit";
            case PROVIDER_MODEL_UNAVAILABLE -> "provider_model_unavailable";
            case PROVIDER_AUTH_REJECTED -> "provider_auth_rejected";
            case PROVIDER_UNKNOWN -> "provider_unknown";
            case BUDGET_EXCEEDED_LLM_CALLS -> "budget_exceeded_llm_calls";
            case BUDGET_EXCEEDED_TOKENS -> "budget_exceeded_tokens";
            case BUDGET_EXCEEDED_TOOL_CALLS -> "budget_exceeded_tool_calls";
            case BUDGET_EXCEEDED_WALL_CLOCK -> "budget_exceeded_wall_clock";
            case BUDGET_EXCEEDED_HTTP_ATTEMPTS -> "budget_exceeded_http_attempts";
            case BUDGET_EXCEEDED -> {
                // 兼容 legacy 字符串路径：尽量用解析出的 dimension
                if ("tokens".equals(dimension)) {
                    yield "budget_exceeded_tokens";
                }
                if ("tool_calls".equals(dimension)) {
                    yield "budget_exceeded_tool_calls";
                }
                if ("wall_clock_ms".equals(dimension)) {
                    yield "budget_exceeded_wall_clock";
                }
                if ("http_attempts_per_logical_call".equals(dimension)) {
                    yield "budget_exceeded_http_attempts";
                }
                yield "budget_exceeded_llm_calls";
            }
            default -> "";
        };
    }

    /**
     * 收集所有可能包含失败信息的文本片段，并遍历 cause chain 2-3 层。
     */
    private String collect(String failureReason, String toolOutput, Throwable throwable) {
        StringBuilder text = new StringBuilder();
        append(text, failureReason);
        append(text, toolOutput);
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 3) {
            append(text, current.getClass().getSimpleName());
            append(text, current.getMessage());
            current = current.getCause();
            depth++;
        }
        return text.toString().trim();
    }

    private void append(StringBuilder text, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(value);
    }

    private String extractErrorCode(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = ERROR_CODE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
