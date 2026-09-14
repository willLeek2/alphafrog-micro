package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 260623-harness-optimization-02: 锁定 listMyData 的 query / filter / pagination 行为。
 *
 * <p>MF5 新增 8 形参 overload 的 grep raw file content 测试（spec Q10）。
 * 6 形参 overload 走 originalId 子串匹配（保留 ToolRouter 入口旧行为）。
 */
class ListMyDataToolTest {

    private ListMyDataTool tool;
    private AgentRunDatasetRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        tool = new ListMyDataTool(mapper);
        registry = mock(AgentRunDatasetRegistry.class);
        tool.setAgentRunDatasetRegistry(registry);
        AgentContext.setRunId("run-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    private AgentRunDatasetSnapshot snapshot() {
        AgentRunDatasetEntry ds1 = AgentRunDatasetEntry.forDataset(
                1, "ds-alpha", "/data/database_fetched/ds-alpha/a.csv",
                "000300.SH", "a.csv");
        AgentRunDatasetEntry ds2 = AgentRunDatasetEntry.forDataset(
                2, "ds-beta", "/data/database_fetched/ds-beta/b.csv",
                "000300.SH#510300.SH", "b.csv");
        AgentRunDatasetEntry ds3 = AgentRunDatasetEntry.forDataset(
                3, "ds-gamma-uncertain", "/data/database_fetched/ds-gamma/g.csv",
                "UNCERTAIN", "g.csv");
        AgentRunDatasetEntry mf1 = AgentRunDatasetEntry.forManifest(
                1, "manifest-mix", "/data/manifests/v1/manifest-manifest-mix/m.json",
                "000300.SH", "manifest.json", List.of("1", "2"));
        AgentRunDatasetEntry mf2 = AgentRunDatasetEntry.forManifest(
                2, "manifest-gamma-only", "/data/manifests/v1/manifest-gamma-only/m.json",
                "UNCERTAIN", "manifest.json", List.of("3"));
        return new AgentRunDatasetSnapshot(
                List.of(ds1, ds2, ds3),
                List.of(mf1, mf2));
    }

    /**
     * 写一个真实文件到 tmpDir，返回 dataset entry（persistedPath 指向该文件）。
     */
    private AgentRunDatasetEntry writeDatasetFile(int number, String datasetId, String sortKey,
                                                 String fromTsCode, String content) throws IOException {
        Path file = tmp.resolve(datasetId + "-" + sortKey);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return AgentRunDatasetEntry.forDataset(
                number, datasetId, file.toString(), fromTsCode, sortKey);
    }

    // ----- 6 形参版（ToolRouter 入口，旧语义） -----
    // Cindy round 2 review MF-new-1：6 形参入口改名为 listMyData6（非 @Tool 注解），
    // 避免与唯一 LLM-facing 8 形参 @Tool 入口同名歧义；行为不变（grep 对 originalId 子串匹配）。

    @Test
    void listDatasetShouldReturnAllDatasetsByDefault() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData6("dataset", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean(), "expected ok=true; got: " + result);
        assertEquals("dataset", root.path("data").path("query_type").asText());
        assertEquals(3, root.path("data").path("total_matched").asInt());
        assertEquals(3, root.path("data").path("returned_count").asInt());
        assertEquals(3, root.path("data").path("entries").size());
    }

    @Test
    void listManifestShouldReturnAllManifestsByDefault() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData6("manifest", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals("manifest", root.path("data").path("query_type").asText());
        assertEquals(2, root.path("data").path("total_matched").asInt());
        JsonNode entries = root.path("data").path("entries");
        // manifest entries 只暴露 run-level related dataset numbers，不泄漏内部 ids。
        for (JsonNode e : entries) {
            assertTrue(e.has("relatedDatasetNumbers"));
            assertFalse(e.has("relatedDatasetIds"));
        }
    }

    @Test
    void filterByFromTsCodeShouldMatchSubstring() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        // ds-beta 的 fromTsCode 是 "000300.SH#510300.SH"，子串 "510300" 应命中
        String result = tool.listMyData6("dataset", "510300", null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("data").path("total_matched").asInt());
        assertEquals(2, root.path("data").path("entries").get(0).path("number").asInt());
    }

    @Test
    void filterByGrepShouldBeCaseInsensitive() throws Exception {
        // 6 形参版语义：grep 对 originalId 做大小写不敏感子串匹配（保留旧行为）
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        // 子串 "ALPHA" 大写应匹配 "ds-alpha"
        String result = tool.listMyData6("dataset", null, "ALPHA", null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("data").path("total_matched").asInt());
        assertEquals(1, root.path("data").path("entries").get(0).path("number").asInt());
    }

    @Test
    void paginationShouldRespectOffsetAndLimit() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData6("dataset", null, null, 1, 1, null);
        JsonNode root = mapper.readTree(result);
        assertEquals(3, root.path("data").path("total_matched").asInt());
        assertEquals(1, root.path("data").path("returned_count").asInt());
        assertEquals(2, root.path("data").path("entries").get(0).path("number").asInt());
    }

    @Test
    void limitShouldBeCappedAtMax() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData6("dataset", null, null, 0, 9999, null);
        JsonNode root = mapper.readTree(result);
        assertEquals(200, root.path("data").path("limit").asInt(),
                "limit should be clamped to MAX_LIMIT=200");
    }

    @Test
    void invalidQueryTypeShouldReturnError() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData6("bogus", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("INVALID_QUERY_TYPE", root.path("error").path("code").asText());
    }

    @Test
    void blankRunIdShouldReturnRunLevelIdsUnavailable() throws Exception {
        AgentContext.clear();
        String result = tool.listMyData6("dataset", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("RUN_LEVEL_IDS_UNAVAILABLE", root.path("error").path("code").asText());
    }

    @Test
    void relatedDatasetIdsShouldFilterManifests() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        // 只匹配 related 包含 ds-alpha 的 manifest → manifest-mix
        String result = tool.listMyData6("manifest", null, null, null, null, "1");
        JsonNode root = mapper.readTree(result);
        assertEquals(1, root.path("data").path("total_matched").asInt());
        assertEquals(1, root.path("data").path("entries").get(0).path("number").asInt());
    }

    @Test
    void relatedDatasetIdsShouldIgnoreForDatasetQuery() throws Exception {
        // dataset 查询传 related_dataset_ids 应当被忽略（不报错也不过滤）
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData6("dataset", null, null, null, null, "1");
        JsonNode root = mapper.readTree(result);
        assertEquals(3, root.path("data").path("total_matched").asInt(),
                "related_dataset_ids 不应影响 dataset 查询");
    }

    // ----- MF5: 8 形参版 grep raw file content（spec Q10） -----

    @Test
    void grepReturnsMatchedDatasetsWithCounts() throws Exception {
        AgentRunDatasetEntry ds1 = writeDatasetFile(1, "ds-alpha", "a.csv", "000300.SH",
                "line1: hello world\nline2: alpha here\nline3: alpha again\nline4: end\n");
        AgentRunDatasetEntry ds2 = writeDatasetFile(2, "ds-beta", "b.csv", "510300.SH",
                "line1: beta no match\nline2: end\n");
        AgentRunDatasetEntry ds3 = writeDatasetFile(3, "ds-gamma", "g.csv", "UNCERTAIN",
                "line1: gamma\nline2: alpha once\n");
        when(registry.snapshot("run-1")).thenReturn(new AgentRunDatasetSnapshot(
                List.of(ds1, ds2, ds3), List.of()));
        String result = tool.listMyData("dataset", null, "alpha", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean(), "expected ok=true; got: " + result);
        JsonNode data = root.path("data");
        assertEquals("dataset", data.path("query_type").asText());
        assertEquals(2, data.path("matched_count").asInt(), "alpha 出现在 ds1 (2 行) + ds3 (1 行)");
        JsonNode matches = data.path("matches");
        assertEquals(2, matches.size());
        // 第一名应是 ds-alpha（match_count=2）
        assertEquals(1, matches.get(0).path("dataset_number").asInt());
        assertEquals(2, matches.get(0).path("match_count").asInt());
        assertEquals("000300.SH", matches.get(0).path("from_ts_code").asText());
        assertTrue(matches.get(0).path("snippet_preview").asText().contains("alpha"));
        // 第二名应是 ds-gamma
        assertEquals(3, matches.get(1).path("dataset_number").asInt());
        assertEquals(1, matches.get(1).path("match_count").asInt());
    }

    @Test
    void grepSortByMatchCountDesc() throws Exception {
        // 两个 dataset 都命中，但 ds1 命中多行 → ds1 排前
        AgentRunDatasetEntry ds1 = writeDatasetFile(1, "ds-many", "a.csv", "000300.SH",
                "hit\nhit\nhit\nhit\n");
        AgentRunDatasetEntry ds2 = writeDatasetFile(2, "ds-few", "b.csv", "510300.SH",
                "hit\nend\n");
        when(registry.snapshot("run-1")).thenReturn(new AgentRunDatasetSnapshot(
                List.of(ds1, ds2), List.of()));
        String result = tool.listMyData("dataset", null, "hit", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        JsonNode matches = root.path("data").path("matches");
        assertEquals(2, matches.size());
        assertEquals(1, matches.get(0).path("dataset_number").asInt());
        assertEquals(4, matches.get(0).path("match_count").asInt());
        assertEquals(2, matches.get(1).path("dataset_number").asInt());
        assertEquals(1, matches.get(1).path("match_count").asInt());
    }

    @Test
    void grepRespectsFileOffsetAndLimit() throws Exception {
        AgentRunDatasetEntry ds1 = writeDatasetFile(1, "ds-one", "a.csv", "000300.SH", "hit\n");
        AgentRunDatasetEntry ds2 = writeDatasetFile(2, "ds-two", "b.csv", "000300.SH", "hit\nhit\n");
        AgentRunDatasetEntry ds3 = writeDatasetFile(3, "ds-three", "c.csv", "000300.SH", "hit\nhit\nhit\n");
        when(registry.snapshot("run-1")).thenReturn(new AgentRunDatasetSnapshot(
                List.of(ds1, ds2, ds3), List.of()));
        // 跳过 ds1，限制只扫 ds2 + ds3（file_offset=1, file_limit=2）
        String result = tool.listMyData("dataset", null, "hit", 1, 2, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("data").path("file_offset").asInt());
        assertEquals(2, root.path("data").path("file_limit").asInt());
        // ds1 被跳过；ds2(2) + ds3(3) 都命中
        JsonNode matches = root.path("data").path("matches");
        assertEquals(2, matches.size());
        assertEquals(3, matches.get(0).path("dataset_number").asInt(), "match_count=3 排前");
        assertEquals(2, matches.get(1).path("dataset_number").asInt());
    }

    @Test
    void grepNoMatchesReturnsEmpty() throws Exception {
        AgentRunDatasetEntry ds1 = writeDatasetFile(1, "ds-alpha", "a.csv", "000300.SH",
                "line1: hello\nline2: world\n");
        when(registry.snapshot("run-1")).thenReturn(new AgentRunDatasetSnapshot(
                List.of(ds1), List.of()));
        String result = tool.listMyData("dataset", null, "nosuchstring", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals(0, root.path("data").path("matched_count").asInt());
        assertEquals(0, root.path("data").path("matches").size());
    }

    @Test
    void grepCaseInsensitive() throws Exception {
        // 大写 query 应匹配小写文件内容
        AgentRunDatasetEntry ds1 = writeDatasetFile(1, "ds-alpha", "a.csv", "000300.SH",
                "line1: hello\nline2: ALPHA here\n");
        when(registry.snapshot("run-1")).thenReturn(new AgentRunDatasetSnapshot(
                List.of(ds1), List.of()));
        String result = tool.listMyData("dataset", null, "alpha", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertEquals(1, root.path("data").path("matched_count").asInt());
        assertEquals(1, root.path("data").path("matches").get(0).path("match_count").asInt());
    }

    @Test
    void grepWithFromTsCodeFilter() throws Exception {
        // grep + from_ts_code 联合过滤
        AgentRunDatasetEntry ds1 = writeDatasetFile(1, "ds-a", "a.csv", "000300.SH", "hit\n");
        AgentRunDatasetEntry ds2 = writeDatasetFile(2, "ds-b", "b.csv", "510300.SH", "hit\n");
        when(registry.snapshot("run-1")).thenReturn(new AgentRunDatasetSnapshot(
                List.of(ds1, ds2), List.of()));
        // 限定 ts_code 包含 "510300"
        String result = tool.listMyData("dataset", "510300", "hit", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertEquals(1, root.path("data").path("matched_count").asInt());
        assertEquals(2, root.path("data").path("matches").get(0).path("dataset_number").asInt());
    }

    @Test
    void grepOnManifestShouldFallThroughToNonGrepPath() throws Exception {
        // manifest 模式 + grep 命中：8 形参版 manifest 模式不走 grep 分支，行为同 6 形参版
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData("manifest", null, "alpha", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        // manifest 模式不进入 grep 分支，按 manifest 全量返回
        assertEquals(2, root.path("data").path("total_matched").asInt());
        assertFalse(root.path("data").has("matches"),
                "manifest 模式不应输出 matches 字段");
    }

    @Test
    void grepMissingFileIsSilentlySkipped() throws Exception {
        // persistedPath 指向不存在的文件 — 不报错，只是不命中
        AgentRunDatasetEntry ds1 = AgentRunDatasetEntry.forDataset(
                1, "ds-missing", "/nonexistent/path/missing.csv", "000300.SH", "missing.csv");
        AgentRunDatasetEntry ds2 = writeDatasetFile(2, "ds-real", "a.csv", "000300.SH", "hit\n");
        when(registry.snapshot("run-1")).thenReturn(new AgentRunDatasetSnapshot(
                List.of(ds1, ds2), List.of()));
        String result = tool.listMyData("dataset", null, "hit", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("data").path("matched_count").asInt(),
                "missing file 应被静默跳过，ds2 命中");
    }

    @Test
    void defaultViewShouldHideInternalIdsAndHostPaths() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());

        JsonNode root = mapper.readTree(tool.listMyData6("dataset", null, null, null, null, null));
        JsonNode entry = root.path("data").path("entries").get(0);

        assertFalse(entry.has("originalId"));
        assertFalse(entry.has("persistedPath"));
        assertFalse(entry.has("sortKey"));
        assertEquals(1, entry.path("number").asInt());
        assertTrue(entry.has("bytes"));
        assertTrue(entry.has("metadataStatus"));
    }
}
