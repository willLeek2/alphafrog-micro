package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 260623-harness-optimization-02: 锁定 listMyData 的 query / filter / pagination 行为。
 */
class ListMyDataToolTest {

    private ListMyDataTool tool;
    private AgentRunDatasetRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

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
                "000300.SH", "manifest.json", List.of("ds-alpha", "ds-beta"));
        AgentRunDatasetEntry mf2 = AgentRunDatasetEntry.forManifest(
                2, "manifest-gamma-only", "/data/manifests/v1/manifest-gamma-only/m.json",
                "UNCERTAIN", "manifest.json", List.of("ds-gamma-uncertain"));
        return new AgentRunDatasetSnapshot(
                List.of(ds1, ds2, ds3),
                List.of(mf1, mf2));
    }

    @Test
    void listDatasetShouldReturnAllDatasetsByDefault() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData("dataset", null, null, null, null, null);
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
        String result = tool.listMyData("manifest", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals("manifest", root.path("data").path("query_type").asText());
        assertEquals(2, root.path("data").path("total_matched").asInt());
        JsonNode entries = root.path("data").path("entries");
        // manifest entries 应带 relatedDatasetIds 字段
        for (JsonNode e : entries) {
            assertTrue(e.has("relatedDatasetIds"));
        }
    }

    @Test
    void filterByFromTsCodeShouldMatchSubstring() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        // ds-beta 的 fromTsCode 是 "000300.SH#510300.SH"，子串 "510300" 应命中
        String result = tool.listMyData("dataset", "510300", null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("data").path("total_matched").asInt());
        assertEquals("ds-beta", root.path("data").path("entries").get(0).path("originalId").asText());
    }

    @Test
    void filterByGrepShouldBeCaseInsensitive() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        // 子串 "ALPHA" 大写应匹配 "ds-alpha"
        String result = tool.listMyData("dataset", null, "ALPHA", null, null, null);
        JsonNode root = mapper.readTree(result);
        assertTrue(root.path("ok").asBoolean());
        assertEquals(1, root.path("data").path("total_matched").asInt());
        assertEquals("ds-alpha", root.path("data").path("entries").get(0).path("originalId").asText());
    }

    @Test
    void paginationShouldRespectOffsetAndLimit() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData("dataset", null, null, 1, 1, null);
        JsonNode root = mapper.readTree(result);
        assertEquals(3, root.path("data").path("total_matched").asInt());
        assertEquals(1, root.path("data").path("returned_count").asInt());
        assertEquals("ds-beta", root.path("data").path("entries").get(0).path("originalId").asText());
    }

    @Test
    void limitShouldBeCappedAtMax() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData("dataset", null, null, 0, 9999, null);
        JsonNode root = mapper.readTree(result);
        assertEquals(200, root.path("data").path("limit").asInt(),
                "limit should be clamped to MAX_LIMIT=200");
    }

    @Test
    void invalidQueryTypeShouldReturnError() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData("bogus", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("INVALID_QUERY_TYPE", root.path("error").path("code").asText());
    }

    @Test
    void blankRunIdShouldReturnRunLevelIdsUnavailable() throws Exception {
        AgentContext.clear();
        String result = tool.listMyData("dataset", null, null, null, null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("RUN_LEVEL_IDS_UNAVAILABLE", root.path("error").path("code").asText());
    }

    @Test
    void relatedDatasetIdsShouldFilterManifests() throws Exception {
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        // 只匹配 related 包含 ds-alpha 的 manifest → manifest-mix
        String result = tool.listMyData("manifest", null, null, null, null, "ds-alpha");
        JsonNode root = mapper.readTree(result);
        assertEquals(1, root.path("data").path("total_matched").asInt());
        assertEquals("manifest-mix", root.path("data").path("entries").get(0).path("originalId").asText());
    }

    @Test
    void relatedDatasetIdsShouldIgnoreForDatasetQuery() throws Exception {
        // dataset 查询传 related_dataset_ids 应当被忽略（不报错也不过滤）
        when(registry.snapshot("run-1")).thenReturn(snapshot());
        String result = tool.listMyData("dataset", null, null, null, null, "ds-alpha");
        JsonNode root = mapper.readTree(result);
        assertEquals(3, root.path("data").path("total_matched").asInt(),
                "related_dataset_ids 不应影响 dataset 查询");
    }
}
