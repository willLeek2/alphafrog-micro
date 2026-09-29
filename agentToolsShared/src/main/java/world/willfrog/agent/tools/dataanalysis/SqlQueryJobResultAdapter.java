package world.willfrog.agent.tools.dataanalysis;

import world.willfrog.agent.tools.sandboxjob.SandboxJobResultAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobResponses;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public SqlQueryJobResultAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 业务成功 = 沙箱 SUCCEEDED 且退出码 0 且信封 SUCCEEDED，三层缺一不可。 */
    @Override
    public boolean isSuccess(SandboxTerminalResultView result) {
        return result.succeeded()
                && result.exitCode() != null && result.exitCode() == 0
                && envelopeStatus(result.stdout()) != null
                && "SUCCEEDED".equals(envelopeStatus(result.stdout()));
    }

    /** 等待组成员失败映射用的错误码；executeQuery 第一期不接等待组，给出语义化兜底。 */
    @Override
    public String errorCodeOf(SandboxTerminalResultView result) {
        return "CANCELED".equals(result.statusName()) ? "QUERY_CANCELED" : "QUERY_EXECUTION_FAILED";
    }

    @Override
    public String formatTerminalResult(SandboxTerminalResultView result, Object formatContext) {
        // formatContext 是终态副作用钩子的产出；executeQuery 没有副作用钩子，这里始终为 null。
        if (!result.succeeded()) {
            return formatSandboxFailure(result);
        }
        JsonNode envelope = findEnvelope(result.stdout());
        if (envelope == null) {
            // 沙箱成功但拿不到信封：运行器在打印信封前崩溃，或 stdout 被输出上限截断。
            return SandboxJobResponses.fail(objectMapper, "executeQuery", "QUERY_RESULT_UNAVAILABLE",
                    "Sandbox task finished but the query result envelope is missing",
                    Map.of("stderr_preview", preview(result.stderr()),
                            "retryable", false));
        }
        String status = envelope.path("status").asText("");
        if ("SUCCEEDED".equals(status)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("columns", objectMapper.convertValue(envelope.path("columns"), List.class));
            data.put("rows", objectMapper.convertValue(envelope.path("rows"), List.class));
            data.put("row_count", envelope.path("row_count").asInt(0));
            data.put("truncated", envelope.path("truncated").asBoolean(false));
            return SandboxJobResponses.ok(objectMapper, "executeQuery", data);
        }
        JsonNode error = envelope.path("error");
        String code = error.path("code").asText("QUERY_EXECUTION_FAILED");
        String message = error.path("message").asText("query failed");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status);
        if (envelope.has("stage")) {
            details.put("stage", envelope.path("stage").asText());
        }
        if (envelope.has("gate")) {
            details.put("gate", objectMapper.convertValue(envelope.path("gate"), Map.class));
        }
        // 引擎配置与挂载失败是平台侧故障，重试同样的调用没有意义；其余失败模型改写 SQL 后可重试。
        boolean platformStage = "configure".equals(envelope.path("stage").asText(""))
                || "mount".equals(envelope.path("stage").asText(""));
        details.put("retryable", !platformStage);
        return SandboxJobResponses.fail(objectMapper, "executeQuery", code, message, details);
    }

    /** 沙箱层失败（没跑到运行器信封）：取消与基础设施失败在这里映射。 */
    private String formatSandboxFailure(SandboxTerminalResultView result) {
        if ("CANCELED".equals(result.statusName())) {
            return SandboxJobResponses.fail(objectMapper, "executeQuery", "QUERY_CANCELED",
                    "Query task was canceled", Map.of("retryable", false));
        }
        return SandboxJobResponses.fail(objectMapper, "executeQuery", "QUERY_SANDBOX_FAILED",
                "Sandbox task failed before producing a query result",
                Map.of("stderr_preview", preview(result.stderr()),
                        "detail", result.errorDetail() == null ? "" : result.errorDetail(),
                        "retryable", Boolean.TRUE.equals(result.retryable())));
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
