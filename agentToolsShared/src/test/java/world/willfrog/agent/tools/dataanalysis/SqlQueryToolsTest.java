package world.willfrog.agent.tools.dataanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * executeQuery 工具侧语义的单元测试：入口参数校验、运行器渲染与 SQL 回填、
 * 信封到统一响应的映射。沙箱 RPC 面与生命周期编排的测试在 executePython 既有测试网
 * 与框架测试里。
 */
class SqlQueryToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SqlQueryTools tools = new SqlQueryTools(objectMapper);
    private final SqlQueryJobResultAdapter resultAdapter = new SqlQueryJobResultAdapter(objectMapper);

    // ---------- 入口参数校验（先于任何上下文访问，纯构造即可测） ----------

    @Test
    void entryRejectsUnknownTier() throws Exception {
        JsonNode out = objectMapper.readTree(tools.executeQuery("SELECT 1", "1", "TURBO"));
        assertFalse(out.path("ok").asBoolean());
        assertEquals("UNKNOWN_TIER", out.path("error").path("code").asText());
        assertTrue(out.path("error").path("details").path("retryable").asBoolean());
        JsonNode legal = out.path("error").path("details").path("legal_tiers");
        assertTrue(legal.toString().contains("INTERACTIVE") && legal.toString().contains("BACKGROUND"),
                "拒绝信息必须带合法档位名单");
    }

    @Test
    void entryRejectsBlankSql() throws Exception {
        JsonNode out = objectMapper.readTree(tools.executeQuery("  ", "1", "INTERACTIVE"));
        assertFalse(out.path("ok").asBoolean());
        assertEquals("MISSING_SQL", out.path("error").path("code").asText());
        assertTrue(out.path("error").path("details").path("retryable").asBoolean());
    }

    @Test
    void entryRejectsEmptyDatasetIds() throws Exception {
        for (String ids : new String[]{"", "[]", null}) {
            JsonNode out = objectMapper.readTree(tools.executeQuery("SELECT 1", ids, null));
            assertFalse(out.path("ok").asBoolean());
            assertEquals("MISSING_IDS", out.path("error").path("code").asText(), "ids=" + ids);
            assertTrue(out.path("error").path("details").path("retryable").asBoolean());
        }
    }

    // ---------- 运行器渲染 ----------

    @Test
    void renderRunner_embedsSpecAndExtractSqlRoundTrips() {
        AgentRunDatasetEntryBuilder datasets = new AgentRunDatasetEntryBuilder();
        String sql = "SELECT industry, avg(ret) FROM t1 JOIN t2 USING (code) GROUP BY industry";
        String rendered = tools.renderRunner(sql, "BACKGROUND",
                SqlQueryTools.PRODUCT_TIERS.get("BACKGROUND"), datasets.two(),
                1536L * 1024 * 1024, 600_000L);

        assertFalse(rendered.contains(SqlQueryTools.SPEC_PLACEHOLDER), "占位符必须被替换掉");
        assertEquals(sql, SqlQueryJobRequestAdapter.extractSql(rendered), "渲染后的脚本必须能解回原 SQL");
        // 运行器脚本本体完整保留
        assertTrue(rendered.contains("def main()"), "渲染后仍是完整运行器脚本");
    }

    @Test
    void renderRunner_mountsViewsByRunLevelNumber() throws Exception {
        AgentRunDatasetEntryBuilder datasets = new AgentRunDatasetEntryBuilder();
        String rendered = tools.renderRunner("SELECT 1 FROM t2", "INTERACTIVE",
                SqlQueryTools.PRODUCT_TIERS.get("INTERACTIVE"), datasets.two(),
                512L * 1024 * 1024, 200_000L);
        // 规格 JSON 里两个数据集的视图别名与沙箱路径
        JsonNode spec = decodeSpec(rendered);
        assertEquals("INTERACTIVE", spec.path("tier").asText());
        assertEquals("/sandbox/input", spec.path("mount_dir").asText());
        JsonNode limits = spec.path("limits");
        assertEquals("512MB", limits.path("memory_limit").asText());
        // 档位数值全部由 Java 侧下发：语句超时、返回行数上限、估计行数上限
        assertEquals(30, limits.path("statement_timeout_s").asInt());
        assertEquals(100, limits.path("row_cap").asInt());
        assertEquals(200_000L, limits.path("estimated_row_cap").asLong());
        JsonNode mounts = spec.path("datasets");
        assertEquals(2, mounts.size());
        assertEquals("t1", mounts.get(0).path("alias").asText());
        assertEquals("/sandbox/input/_run_dataset_1/daily.csv", mounts.get(0).path("path").asText());
        assertEquals("csv", mounts.get(0).path("format").asText());
        assertEquals("t2", mounts.get(1).path("alias").asText());
        assertEquals("parquet", mounts.get(1).path("format").asText());
    }

    @Test
    void renderRunner_backgroundTierCarriesBackgroundLimits() throws Exception {
        AgentRunDatasetEntryBuilder datasets = new AgentRunDatasetEntryBuilder();
        String rendered = tools.renderRunner("SELECT 1", "BACKGROUND",
                SqlQueryTools.PRODUCT_TIERS.get("BACKGROUND"), datasets.two(),
                1536L * 1024 * 1024, 600_000L);
        JsonNode limits = decodeSpec(rendered).path("limits");
        assertEquals(120, limits.path("statement_timeout_s").asInt());
        assertEquals(1000, limits.path("row_cap").asInt());
        assertEquals(600_000L, limits.path("estimated_row_cap").asLong());
        assertEquals("1536MB", limits.path("memory_limit").asText());
    }

    // ---------- 信封 → 统一响应 ----------

    @Test
    void resultAdapter_successEnvelopeMapsToOkResponse() throws Exception {
        String envelope = "{\"status\":\"SUCCEEDED\",\"columns\":[\"a\"],\"rows\":[[1],[2]],"
                + "\"row_count\":2,\"truncated\":false,\"gate\":{\"estimated_rows_max\":1}}";
        SandboxTerminalResultView view = view("SUCCEEDED", 0, "__EXECUTE_QUERY_RESULT__" + envelope + "\n", "");
        assertTrue(resultAdapter.isSuccess(view));
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(view, null));
        assertTrue(out.path("ok").asBoolean());
        assertEquals("executeQuery", out.path("tool").asText());
        JsonNode data = out.path("data");
        assertEquals(2, data.path("row_count").asInt());
        assertEquals("a", data.path("columns").get(0).asText());
        // 数据行与截断标记必须原样透传，模型据此判断结果完整性
        assertEquals(1, data.path("rows").get(0).get(0).asInt());
        assertEquals(2, data.path("rows").get(1).get(0).asInt());
        assertFalse(data.path("truncated").asBoolean());
    }

    @Test
    void resultAdapter_successEnvelopePreservesTruncatedFlag() throws Exception {
        String envelope = "{\"status\":\"SUCCEEDED\",\"columns\":[\"a\"],\"rows\":[[1]],"
                + "\"row_count\":1,\"truncated\":true}";
        SandboxTerminalResultView view = view("SUCCEEDED", 0, "__EXECUTE_QUERY_RESULT__" + envelope + "\n", "");
        assertTrue(resultAdapter.isSuccess(view), "截断仍是业务成功，只是行数到上限");
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(view, null));
        assertTrue(out.path("data").path("truncated").asBoolean());
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
        JsonNode details = out.path("error").path("details");
        // 准入报告必须透传给模型（改写 SQL 的依据），且准入拒绝改写后可重试
        assertEquals("cross_product", details.path("gate").path("checks").get(0).asText());
        assertTrue(details.path("retryable").asBoolean());
    }

    @Test
    void resultAdapter_platformStageFailureIsNotRetryable() throws Exception {
        // 引擎配置/挂载失败是平台侧故障：重试同样的调用没有意义，必须标 retryable=false
        String envelope = "{\"status\":\"FAILED\",\"stage\":\"configure\","
                + "\"error\":{\"code\":\"ENGINE_CONFIGURE_FAILED\",\"message\":\"boom\"}}";
        SandboxTerminalResultView view = view("SUCCEEDED", 0, "__EXECUTE_QUERY_RESULT__" + envelope + "\n", "");
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(view, null));
        assertFalse(out.path("ok").asBoolean());
        assertEquals("ENGINE_CONFIGURE_FAILED", out.path("error").path("code").asText());
        JsonNode details = out.path("error").path("details");
        assertEquals("configure", details.path("stage").asText());
        assertFalse(details.path("retryable").asBoolean());
    }

    @Test
    void resultAdapter_statementTimeoutMapsToRetryableFail() throws Exception {
        String envelope = "{\"status\":\"STATEMENT_TIMEOUT\","
                + "\"error\":{\"code\":\"STATEMENT_TIMEOUT\",\"message\":\"语句超过 30 秒档位上限被中断\"},"
                + "\"gate\":{},\"limits\":{\"statement_timeout_s\":30}}";
        SandboxTerminalResultView view = view("SUCCEEDED", 0, "__EXECUTE_QUERY_RESULT__" + envelope + "\n", "");
        assertFalse(resultAdapter.isSuccess(view));
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(view, null));
        assertFalse(out.path("ok").asBoolean());
        // 独立错误码直达 error.code，Java 侧与模型都不必从笼统的 QUERY_EXECUTION_FAILED 里猜
        assertEquals("STATEMENT_TIMEOUT", out.path("error").path("code").asText());
        JsonNode details = out.path("error").path("details");
        assertEquals("STATEMENT_TIMEOUT", details.path("status").asText());
        assertEquals(30, details.path("limits").path("statement_timeout_s").asInt());
        assertTrue(details.path("retryable").asBoolean(), "收窄查询或换 BACKGROUND 档后可重试");
    }

    @Test
    void resultAdapter_missingEnvelopeMapsToUnavailable() throws Exception {
        // stderr 非空 = 运行器在打印信封前崩溃，重试同样的调用没有意义
        SandboxTerminalResultView crashed = view("SUCCEEDED", 0, "some stdout without marker\n", "boom");
        assertFalse(resultAdapter.isSuccess(crashed));
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(crashed, null));
        assertEquals("QUERY_RESULT_UNAVAILABLE", out.path("error").path("code").asText());
        assertEquals("boom", out.path("error").path("details").path("stderr_preview").asText());
        assertFalse(out.path("error").path("details").path("retryable").asBoolean());
    }

    @Test
    void resultAdapter_missingEnvelopeWithCleanStderrIsRetryable() throws Exception {
        // stderr 为空 = stdout 在输出上限处被截断（结果集过大），收窄查询后可重试
        SandboxTerminalResultView truncated = view("SUCCEEDED", 0, "partial output\n", "");
        JsonNode out = objectMapper.readTree(resultAdapter.formatTerminalResult(truncated, null));
        assertEquals("QUERY_RESULT_UNAVAILABLE", out.path("error").path("code").asText());
        assertTrue(out.path("error").path("details").path("retryable").asBoolean());
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
        // 等待组成员失败映射的兜底码：非取消一律 QUERY_EXECUTION_FAILED
        assertEquals("QUERY_EXECUTION_FAILED", resultAdapter.errorCodeOf(view("FAILED", 1, "", "engine died")));
    }

    private JsonNode decodeSpec(String rendered) throws Exception {
        String specJson = new String(java.util.Base64.getDecoder().decode(
                java.util.regex.Pattern.compile("SPEC_B64 = \"([A-Za-z0-9+/=]+)\"")
                        .matcher(rendered).results().findFirst().orElseThrow().group(1)),
                java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(specJson);
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
