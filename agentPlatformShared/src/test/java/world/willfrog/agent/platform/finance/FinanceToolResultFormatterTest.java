package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter.FailureDetail;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter.FinanceModelResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinanceToolResultFormatterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FinanceToolResultFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new FinanceToolResultFormatter(objectMapper);
    }

    @Test
    void successUsesTheExactPublicAllowlistAndStableFieldOrder() throws Exception {
        String json = formatter.formatSuccess(
                "rows=5",
                List.of(new FinanceModelResult(
                        "复合年均增长率", 0.12468265, "ratio",
                        "按已明确的起止值和实际区间长度计算复合增长速度")),
                List.of(new FinanceRecordExtractionResult.ModelNotice(
                        "FINANCE_RESULT_REJECTED", "部分记录没有被接收", "检查参数后重试")));

        assertThat(json).isEqualTo("{\"ok\":true,\"tool\":\"executePython\",\"data\":{"
                + "\"stdout\":\"rows=5\",\"results\":[{\"method\":\"复合年均增长率\","
                + "\"value\":0.12468265,\"unit\":\"ratio\",\"howCalculated\":"
                + "\"按已明确的起止值和实际区间长度计算复合增长速度\"}],"
                + "\"warnings\":[{\"code\":\"FINANCE_RESULT_REJECTED\","
                + "\"message\":\"部分记录没有被接收\",\"action\":\"检查参数后重试\"}]},"
                + "\"error\":null}");
        assertPublicAllowlist(objectMapper.readTree(json));
    }

    @Test
    void successOmitsEmptyOptionalDataFields() throws Exception {
        String json = formatter.formatSuccess("", List.of(), List.of());

        assertThat(json).isEqualTo(
                "{\"ok\":true,\"tool\":\"executePython\",\"data\":{},\"error\":null}");
        assertThat(objectMapper.readTree(json).path("data").size()).isZero();
    }

    @Test
    void structuredOverflowKeepsCompleteJsonAndAddsActionableWarning() throws Exception {
        String how = "计算说明".repeat(700);
        List<FinanceModelResult> results = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new FinanceModelResult(
                        "方法" + index, index + 0.5, "ratio", how))
                .toList();

        String first = formatter.formatSuccess("ordinary", results, List.of());
        String second = formatter.formatSuccess("ordinary", results, List.of());
        JsonNode root = objectMapper.readTree(first);

        assertThat(first).isEqualTo(second);
        assertThat(first.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES);
        assertThat(root.path("data").path("results").size()).isLessThan(results.size());
        assertThat(root.path("data").path("warnings").toString())
                .contains("FINANCE_RESULT_SUMMARY_TRUNCATED", "分批执行");
        assertPublicAllowlist(root);
    }

    @Test
    void stdoutOverflowIsUtf8SafeAndDoesNotTruncateJsonBytes() throws Exception {
        String stdout = "行情输出🙂\"\\\n".repeat(8_000);

        String json = formatter.formatSuccess(stdout, List.of(), List.of());
        JsonNode root = objectMapper.readTree(json);
        String fitted = root.path("data").path("stdout").asText();

        assertThat(json.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES);
        assertThat(fitted).endsWith("…[truncated]").doesNotContain("�");
    }

    @Test
    void failurePrioritizesActionableErrorAndStderrWithinTheSameBound() throws Exception {
        String stderr = "NameError: 未定义变量🙂\n".repeat(4_000);
        String stdout = "rows=5\n".repeat(4_000);
        FailureDetail failure = new FailureDetail(
                "PYTHON_EXECUTION_FAILED",
                "代码执行失败，请根据错误信息修正后重试",
                true,
                "定义缺失变量或检查输入后重试");

        String json = formatter.formatFailure(stdout, stderr, failure);
        JsonNode root = objectMapper.readTree(json);

        assertThat(json.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES);
        assertThat(root.path("ok").asBoolean()).isFalse();
        assertThat(root.path("error").path("code").asText())
                .isEqualTo("PYTHON_EXECUTION_FAILED");
        assertThat(root.path("error").path("retryable").asBoolean()).isTrue();
        assertThat(root.path("error").path("action").asText()).contains("重试");
        assertThat(root.path("data").path("stderr").asText())
                .endsWith("…[truncated]")
                .doesNotContain("�");
        assertThat(root.path("data").has("stdout")).isFalse();
        assertPublicAllowlist(root);
    }

    @Test
    void financeMarkerIsRejectedInsteadOfEnteringModelJson() {
        assertThatThrownBy(() -> formatter.formatSuccess(
                "prefix " + FinanceRecordDecoder.MARKER_V1 + "{}", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finance marker");

        assertThatThrownBy(() -> formatter.formatFailure(
                "", FinanceRecordDecoder.MARKER_FAMILY + "v9__{}",
                new FailureDetail("FAILED", "failed", false, "retry")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finance marker");

        assertThatThrownBy(() -> formatter.formatSuccess(
                "",
                List.of(new FinanceModelResult(
                        "method", 1, "ratio",
                        "unsafe " + FinanceRecordDecoder.MARKER_V1 + "{}")),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finance marker");

        assertThatThrownBy(() -> formatter.formatSuccess(
                "", List.of(),
                List.of(new FinanceRecordExtractionResult.ModelNotice(
                        "NOTICE", "unsafe " + FinanceRecordDecoder.MARKER_FAMILY,
                        "retry"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finance marker");
    }

    private void assertPublicAllowlist(JsonNode root) {
        assertThat(root.fieldNames()).toIterable()
                .containsExactly("ok", "tool", "data", "error");
        assertThat(root.path("data").fieldNames()).toIterable()
                .allMatch(name -> List.of("stdout", "stderr", "results", "warnings").contains(name));
        assertThat(root.toString()).doesNotContain(
                "taskId", "datasetDir", "rawRef", "toolCallId", "runId", "todoId",
                "specDigest", "recordDigest", "environmentId", "imageDigest",
                "librarySetDigest", "packageApis", "evidence", "renderable");
    }
}
