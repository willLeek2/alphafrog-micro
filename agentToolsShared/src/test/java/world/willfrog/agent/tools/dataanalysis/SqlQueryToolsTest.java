package world.willfrog.agent.tools.dataanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * executeQuery 工具侧语义的单元测试：运行器渲染与 SQL 回填、信封到统一响应的映射。
 * 沙箱 RPC 面与生命周期编排的测试在 executePython 既有测试网与框架测试里。
 */
class SqlQueryToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SqlQueryTools tools = new SqlQueryTools(objectMapper);
    private final SqlQueryJobResultAdapter resultAdapter = new SqlQueryJobResultAdapter(objectMapper);

    @Test
    void renderRunner_embedsSpecAndExtractSqlRoundTrips() {
        AgentRunDatasetEntryBuilder datasets = new AgentRunDatasetEntryBuilder();
        String sql = "SELECT industry, avg(ret) FROM t1 JOIN t2 USING (code) GROUP BY industry";
        String rendered = tools.renderRunner(sql, "BACKGROUND", datasets.two(), 1536L * 1024 * 1024);

        assertFalse(rendered.contains(SqlQueryTools.SPEC_PLACEHOLDER), "占位符必须被替换掉");
        assertEquals(sql, SqlQueryJobRequestAdapter.extractSql(rendered), "渲染后的脚本必须能解回原 SQL");
        // 运行器脚本本体完整保留
        assertTrue(rendered.contains("def main()"), "渲染后仍是完整运行器脚本");
    }

    @Test
    void renderRunner_mountsViewsByRunLevelNumber() throws Exception {
        AgentRunDatasetEntryBuilder datasets = new AgentRunDatasetEntryBuilder();
        String rendered = tools.renderRunner("SELECT 1 FROM t2", "INTERACTIVE", datasets.two(), 512L * 1024 * 1024);
        // 规格 JSON 里两个数据集的视图别名与沙箱路径
        String specJson = new String(java.util.Base64.getDecoder().decode(
                java.util.regex.Pattern.compile("SPEC_B64 = \"([A-Za-z0-9+/=]+)\"")
                        .matcher(rendered).results().findFirst().orElseThrow().group(1)),
                java.nio.charset.StandardCharsets.UTF_8);
        JsonNode spec = objectMapper.readTree(specJson);
        assertEquals("INTERACTIVE", spec.path("tier").asText());
        assertEquals("/sandbox/input", spec.path("mount_dir").asText());
        assertEquals("512MB", spec.path("limits").path("memory_limit").asText());
        JsonNode mounts = spec.path("datasets");
        assertEquals(2, mounts.size());
        assertEquals("t1", mounts.get(0).path("alias").asText());
        assertEquals("/sandbox/input/_run_dataset_1/daily.csv", mounts.get(0).path("path").asText());
        assertEquals("csv", mounts.get(0).path("format").asText());
        assertEquals("t2", mounts.get(1).path("alias").asText());
        assertEquals("parquet", mounts.get(1).path("format").asText());
    }

    @Test
    void resultAdapter_successEnvelopeMapsToOkResponse() throws Exception {
        String envelope = "{\"status\":\"SUCCEEDED\",\"columns\":[\"a\"],\"rows\":[[1]],"
                + "\"row_count\":1,\"truncated\":false,\"gate\":{\"estimated_rows_max\":1}}";
        SandboxTerminalResultView view = view("SUCCEEDED", 0, "__EXECUTE_QUERY_RESULT__" + envelope + "\n", "");
        assertTrue(resultAdapter.isSuccess(view));
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(view, null));
        assertTrue(out.path("ok").asBoolean());
        assertEquals("executeQuery", out.path("tool").asText());
        assertEquals(1, out.path("data").path("row_count").asInt());
        assertEquals("a", out.path("data").path("columns").get(0).asText());
    }

    @Test
    void resultAdapter_planRejectionMapsToFailWithGateReport() throws Exception {
        String envelope = "{\"status\":\"PLAN_REJECTED\",\"error\":{\"code\":\"PLAN_CROSS_PRODUCT\","
                + "\"message\":\"查询包含笛卡尔积连接，准入拒绝\"},\"gate\":{\"checks\":[\"cross_product\"]}}";
        SandboxTerminalResultView view = view("SUCCEEDED", 0, "__EXECUTE_QUERY_RESULT__" + envelope + "\n", "");
        assertFalse(resultAdapter.isSuccess(view));
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(view, null));
        assertFalse(out.path("ok").asBoolean());
        assertEquals("PLAN_CROSS_PRODUCT", out.path("error").path("code").asText());
        assertTrue(out.path("error").path("details").path("retryable").asBoolean());
    }

    @Test
    void resultAdapter_statementTimeoutMapsToRetryableFail() throws Exception {
        String envelope = "{\"status\":\"STATEMENT_TIMEOUT\",\"gate\":{},\"limits\":{\"statement_timeout_s\":30}}";
        SandboxTerminalResultView view = view("SUCCEEDED", 0, "__EXECUTE_QUERY_RESULT__" + envelope + "\n", "");
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(view, null));
        assertFalse(out.path("ok").asBoolean());
        assertEquals("STATEMENT_TIMEOUT", out.path("error").path("details").path("status").asText());
    }

    @Test
    void resultAdapter_missingEnvelopeMapsToUnavailable() throws Exception {
        SandboxTerminalResultView view = view("SUCCEEDED", 0, "some stdout without marker\n", "boom");
        assertFalse(resultAdapter.isSuccess(view));
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(view, null));
        assertEquals("QUERY_RESULT_UNAVAILABLE", out.path("error").path("code").asText());
        assertEquals("boom", out.path("error").path("details").path("stderr_preview").asText());
    }

    @Test
    void resultAdapter_sandboxLevelCancelAndFailure() throws Exception {
        JsonNode canceled = objectMapper.readTree(resultAdapter.formatTerminalResult(
                view("CANCELED", null, "", ""), null));
        assertEquals("QUERY_CANCELED", canceled.path("error").path("code").asText());
        assertEquals("QUERY_CANCELED", resultAdapter.errorCodeOf(view("CANCELED", null, "", "")));

        JsonNode failed = objectMapper.readTree(resultAdapter.formatTerminalResult(
                view("FAILED", 1, "", "engine died"), null));
        assertEquals("QUERY_SANDBOX_FAILED", failed.path("error").path("code").asText());
    }

    private static SandboxTerminalResultView view(String status, Integer exitCode, String stdout, String stderr) {
        return new SandboxTerminalResultView(status, exitCode, stdout, stderr, null, null, null, null, null, null);
    }

    /** 构造两条 run 级数据集条目（1=CSV，2=Parquet）。 */
    private static final class AgentRunDatasetEntryBuilder {
        List<world.willfrog.agent.workflow.AgentRunDatasetEntry> two() {
            return List.of(
                    world.willfrog.agent.workflow.AgentRunDatasetEntry.forDataset(
                            1, "ds-1", "/persisted/ds-1", "000001.SZ", "daily.csv"),
                    world.willfrog.agent.workflow.AgentRunDatasetEntry.forDataset(
                            2, "ds-2", "/persisted/ds-2", "000002.SZ", "weekly.parquet"));
        }
    }
}
