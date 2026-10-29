package world.willfrog.agent.tools.dataanalysis;

import world.willfrog.agent.platform.failure.ToolFailureCategory;
import world.willfrog.agent.platform.prompt.TerminalResultPromptTexts;
import world.willfrog.agent.tools.sandboxjob.SandboxJobResultAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobResponses;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

/**
 * executeQuery 的结果适配器：把固定运行器的 JSON 信封翻成模型看到的统一工具响应。
 *
 * <p>两层状态分开处理：沙箱任务终态（SUCCEEDED/FAILED/CANCELED，由 Dubbo 通道给出）
 * 与运行器信封状态（stdout 里 {@code __EXECUTE_QUERY_RESULT__} 一行的 JSON）。
 * 沙箱 SUCCEEDED 只代表运行器正常跑完，业务成败以信封为准——计划拒绝、语句超时、
 * 引擎或挂载失败都装在信封里（运行器对这些一律正常退出，退出码留给运行器真崩溃）。</p>
 */
public final class SqlQueryJobResultAdapter implements SandboxJobResultAdapter {

    /** 与运行器脚本里的 RESULT_MARKER 对齐，Java 侧只认这一行。 */
    private static final String RESULT_MARKER = "__EXECUTE_QUERY_RESULT__";

    private final ObjectMapper objectMapper;
    private final TerminalResultPromptTexts promptTexts;

    public SqlQueryJobResultAdapter(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public SqlQueryJobResultAdapter(ObjectMapper objectMapper, TerminalResultPromptTexts promptTexts) {
        this.objectMapper = objectMapper;
        this.promptTexts = promptTexts;
    }

    @Override
    public boolean isSuccess(SandboxTerminalResultView result) {
        return result.succeeded() && Integer.valueOf(0).equals(result.exitCode())
                && "SUCCEEDED".equals(envelopeStatus(result.stdout()));
    }

    @Override
    public String errorCodeOf(SandboxTerminalResultView result) {
        return resolveTerminal(result, null).errorCode();
    }

    @Override
    public String formatTerminalResult(SandboxTerminalResultView result, Object formatContext) {
        return resolveTerminal(result, formatContext).output();
    }

    /** 运行器给出的数据和错误事实不带指令版本；处理建议在实际分支重新读取版本。 */
    @Override
    public PromptText formatTerminalResultText(SandboxTerminalResultView result, Object formatContext) {
        return resolveTerminalText(result, formatContext).output();
    }

    /** 正文来源、业务状态和错误码由同一次信封解析得出。 */
    @Override
    public ResolvedPromptResult resolveTerminalText(SandboxTerminalResultView result, Object formatContext) {
        if (!result.succeeded() || !Integer.valueOf(0).equals(result.exitCode())) {
            if ("CANCELED".equals(result.statusName())) {
                return failureText("QUERY_CANCELED", PromptText.runtimeData("Query task was canceled"),
                        Map.of("retryable", false), null);
            }
            return failureText("QUERY_SANDBOX_FAILED",
                    PromptText.runtimeData("Sandbox task failed before producing a query result"),
                    Map.of("stderr_preview", preview(result.stderr()),
                            "detail", result.errorDetail() == null ? "" : result.errorDetail(),
                            "retryable", !"RESULT_LOST".equals(result.statusName())
                                    && Boolean.TRUE.equals(result.retryable())), null);
        }
        JsonNode envelope = findEnvelope(result.stdout());
        if (envelope == null) {
            boolean crashed = result.stderr() != null && !result.stderr().isBlank();
            PromptText message = crashed
                    ? PromptText.runtimeData("Query runner crashed before producing the result envelope")
                    : instruction("sqlQueryOutputCapacityMessage");
            return failureText("QUERY_RESULT_UNAVAILABLE", message,
                    Map.of("stderr_preview", preview(result.stderr()), "retryable", !crashed),
                    crashed ? ToolFailureCategory.UPSTREAM_OR_SYSTEM : ToolFailureCategory.RATE_LIMITED_OR_CAPACITY);
        }
        String status = envelope.path("status").asText("");
        if ("SUCCEEDED".equals(status)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("columns", objectMapper.convertValue(envelope.path("columns"), List.class));
            data.put("rows", objectMapper.convertValue(envelope.path("rows"), List.class));
            data.put("row_count", envelope.path("row_count").asInt(0));
            data.put("truncated", envelope.path("truncated").asBoolean(false));
            return new ResolvedPromptResult(PromptText.runtimeData(
                    SandboxJobResponses.ok(objectMapper, "executeQuery", data)), true, null);
        }
        JsonNode error = envelope.path("error");
        String code = error.path("code").asText("QUERY_EXECUTION_FAILED");
        PromptText message = switch (code) {
            case "PLAN_GLOBAL_SORT" -> PromptText.concat(
                    PromptText.runtimeData("查询包含无 LIMIT 的全局排序，准入拒绝；"), instruction("sqlGlobalSortAdvice"));
            case "PLAN_FULL_SCAN" -> fullScanMessage(error.path("facts"));
            case "QUERY_RESULT_TOO_LARGE" -> PromptText.concat(
                    PromptText.runtimeData("结果集超过单次返回体积上限，"), instruction("sqlOutputTooLargeAdvice"));
            default -> PromptText.runtimeData(error.path("message").asText("query failed"));
        };
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status);
        if (envelope.has("stage")) details.put("stage", envelope.path("stage").asText());
        if (envelope.has("gate")) details.put("gate", objectMapper.convertValue(envelope.path("gate"), Map.class));
        if (envelope.has("limits")) details.put("limits", objectMapper.convertValue(envelope.path("limits"), Map.class));
        boolean platformStage = "configure".equals(envelope.path("stage").asText(""))
                || "mount".equals(envelope.path("stage").asText(""));
        details.put("retryable", !platformStage);
        return failureText(code, message, details, null);
    }

    private PromptText fullScanMessage(JsonNode facts) {
        JsonNode estimated = facts.get("estimatedRows");
        JsonNode cap = facts.get("rowCap");
        if (!facts.isObject() || estimated == null || cap == null
                || (!estimated.isNull() && (!estimated.isIntegralNumber() || !estimated.canConvertToLong()
                        || estimated.longValue() < 0))
                || !cap.isIntegralNumber() || !cap.canConvertToLong() || cap.longValue() <= 0) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                    "SQL范围建议缺少独立的估计行数和返回行数事实");
        }
        String rows = estimated.isNull() ? "未知" : estimated.asText();
        return PromptText.concat(PromptText.runtimeData("查询顶层输出估计 " + rows
                + " 行，超过返回行数上限 " + cap.asText() + "，准入拒绝；"), instruction("sqlFullScanAdvice"));
    }

    private PromptText instruction(String fragment) {
        if (promptTexts == null) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                    "SQL终态操作建议未配置正文来源");
        }
        return promptTexts.instruction(fragment);
    }

    private ResolvedPromptResult failureText(String code, PromptText message, Map<String, Object> details,
                                   ToolFailureCategory category) {
        return new ResolvedPromptResult(new PromptText(SandboxJobResponses.fail(objectMapper, "executeQuery", code,
                message.text(), details, Map.of(), category), message.sources()), false, code);
    }

    /** 一次解析生成正文和失败分类，等待回传与同步返回使用相同业务结论。 */
    @Override
    public ResolvedResult resolveTerminal(SandboxTerminalResultView result, Object formatContext) {
        if (!result.succeeded() || !Integer.valueOf(0).equals(result.exitCode())) {
            if ("CANCELED".equals(result.statusName())) {
                return failure("QUERY_CANCELED", "Query task was canceled", Map.of("retryable", false));
            }
            return failure("QUERY_SANDBOX_FAILED", "Sandbox task failed before producing a query result",
                    Map.of("stderr_preview", preview(result.stderr()),
                            "detail", result.errorDetail() == null ? "" : result.errorDetail(),
                            "retryable", !"RESULT_LOST".equals(result.statusName())
                                    && Boolean.TRUE.equals(result.retryable())));
        }
        JsonNode envelope = findEnvelope(result.stdout());
        if (envelope == null) {
            boolean crashed = result.stderr() != null && !result.stderr().isBlank();
            // 同一码两个分支的已知事实不同：运行器崩溃属沙箱/运行器侧故障；信封缺失且 stderr 干净
            // 对应结果超限，属容量语义——显式覆盖，不靠码兜底。
            return failure("QUERY_RESULT_UNAVAILABLE",
                    crashed ? "Query runner crashed before producing the result envelope"
                            : "Query result exceeded the output size limit; narrow the query and retry",
                    Map.of("stderr_preview", preview(result.stderr()), "retryable", !crashed),
                    crashed ? ToolFailureCategory.UPSTREAM_OR_SYSTEM
                            : ToolFailureCategory.RATE_LIMITED_OR_CAPACITY);
        }
        String status = envelope.path("status").asText("");
        if ("SUCCEEDED".equals(status)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("columns", objectMapper.convertValue(envelope.path("columns"), List.class));
            data.put("rows", objectMapper.convertValue(envelope.path("rows"), List.class));
            data.put("row_count", envelope.path("row_count").asInt(0));
            data.put("truncated", envelope.path("truncated").asBoolean(false));
            return new ResolvedResult(SandboxJobResponses.ok(objectMapper, "executeQuery", data), true, null);
        }
        JsonNode error = envelope.path("error");
        String code = error.path("code").asText("QUERY_EXECUTION_FAILED");
        String message = error.path("message").asText("query failed");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status);
        if (envelope.has("stage")) details.put("stage", envelope.path("stage").asText());
        if (envelope.has("gate")) details.put("gate", objectMapper.convertValue(envelope.path("gate"), Map.class));
        if (envelope.has("limits")) details.put("limits", objectMapper.convertValue(envelope.path("limits"), Map.class));
        boolean platformStage = "configure".equals(envelope.path("stage").asText(""))
                || "mount".equals(envelope.path("stage").asText(""));
        details.put("retryable", !platformStage);
        return failure(code, message, details);
    }

    private ResolvedResult failure(String code, String message, Map<String, Object> details) {
        return failure(code, message, details, null);
    }

    /** 显式类别的失败构造；{@code category} 传 null 时由 SandboxJobResponses 按码兜底。 */
    private ResolvedResult failure(String code, String message, Map<String, Object> details,
                                   ToolFailureCategory category) {
        return new ResolvedResult(
                SandboxJobResponses.fail(objectMapper, "executeQuery", code, message, details, Map.of(), category),
                false, code);
    }

    /** 取 stdout 里最后一行信封；运行器保证最多打印一次，取不到返回 null。 */
    private JsonNode findEnvelope(String stdout) {
        if (stdout == null) {
            return null;
        }
        int marker = stdout.lastIndexOf(RESULT_MARKER);
        if (marker < 0) {
            return null;
        }
        String line = stdout.substring(marker + RESULT_MARKER.length());
        int eol = line.indexOf('\n');
        if (eol >= 0) {
            line = line.substring(0, eol);
        }
        try {
            return objectMapper.readTree(line.trim());
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private String envelopeStatus(String stdout) {
        JsonNode envelope = findEnvelope(stdout);
        return envelope == null ? null : envelope.path("status").asText(null);
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }
}
