package world.willfrog.agent.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.tools.search.SearchTools;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.dataset.ManifestWriter;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentToolCatalogServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listToolMessages_shouldExposeSearchWebFromToolAnnotations() throws Exception {
        AgentToolCatalogService service = new AgentToolCatalogService(
                null,
                null,
                new SearchTools(objectMapper, mock(SearchEvidenceJudgeService.class)),
                null,
                mock(ListMyDataTool.class),
                objectMapper
        );

        List<AgentToolMessage> tools = service.listToolMessages();

        AgentToolMessage searchWeb = tools.stream()
                .filter(tool -> "searchWeb".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        assertTrue(searchWeb.getDescription().contains("通用网络搜索工具"));
        JsonNode required = objectMapper.readTree(searchWeb.getParametersJson()).path("required");
        assertEquals(1, required.size());
        assertEquals("query", required.get(0).asText());
    }

    @Test
    void listToolMessages_shouldExposeAdvancedMarketDataSchemasAndKeepSimpleFields() throws Exception {
        MarketDataTools marketDataTools = new MarketDataTools(
                mock(DatasetWriter.class),
                mock(DatasetRegistry.class),
                mock(ManifestWriter.class),
                mock(AgentLlmLocalConfigLoader.class),
                new AgentLlmProperties(),
                objectMapper
        );
        AgentToolCatalogService service = new AgentToolCatalogService(
                marketDataTools,
                null,
                null,
                null,
                mock(ListMyDataTool.class),
                objectMapper
        );

        List<AgentToolMessage> tools = service.listToolMessages();
        AgentToolMessage searchIndex = tools.stream()
                .filter(tool -> "searchIndex".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        JsonNode searchIndexProps = objectMapper.readTree(searchIndex.getParametersJson()).path("properties");
        assertTrue(searchIndexProps.has("keyword"));
        assertTrue(searchIndexProps.has("mode"));
        assertTrue(searchIndexProps.path("query").path("properties").has("conditions"));

        AgentToolMessage searchAssetInfo = tools.stream()
                .filter(tool -> "searchAssetInfo".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        JsonNode searchAssetProps = objectMapper.readTree(searchAssetInfo.getParametersJson()).path("properties");
        assertTrue(searchAssetProps.has("query"));
        assertTrue(searchAssetProps.path("query").path("anyOf").get(1).path("properties").has("conditions"));
        assertTrue(searchAssetProps.has("assetTypes"));
        assertTrue(searchAssetProps.has("asset_type"));
        assertTrue(searchAssetProps.has("conditions"));
    }

    /**
     * 260623-harness-optimization-02 MF-new-1（Cindy round 2 review 拍板）：
     * AgentToolCatalogService 必须在 tool catalog 中暴露 listMyData（来自 ListMyDataTool 的 @Tool 注解）。
     */
    @Test
    void listToolMessages_shouldExposeListMyDataInCatalog() throws Exception {
        // 用真实 ListMyDataTool 拿 @Tool 注解（不能 mock，否则读不到注解）
        ListMyDataTool listMyDataTool = new ListMyDataTool(objectMapper);
        AgentToolCatalogService service = new AgentToolCatalogService(
                null,
                null,
                null,
                null,
                listMyDataTool,
                objectMapper
        );

        List<AgentToolMessage> tools = service.listToolMessages();
        AgentToolMessage listMyData = tools.stream()
                .filter(tool -> "listMyData".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        // description 应包含 "raw file content grep" 等关键字
        assertTrue(listMyData.getDescription().contains("raw file content grep"),
                "listMyData description should mention raw file content grep; got: " + listMyData.getDescription());
        // 8 形参 schema：properties 应包含 query_type / from_ts_code / grep / file_offset / file_limit / offset / limit / related_dataset_ids
        JsonNode props = objectMapper.readTree(listMyData.getParametersJson()).path("properties");
        assertTrue(props.has("query_type"), "listMyData schema must have query_type");
        assertTrue(props.has("from_ts_code"), "listMyData schema must have from_ts_code");
        assertTrue(props.has("grep"), "listMyData schema must have grep");
        assertTrue(props.has("file_offset"), "listMyData schema must have file_offset");
        assertTrue(props.has("file_limit"), "listMyData schema must have file_limit");
        assertTrue(props.has("offset"), "listMyData schema must have offset");
        assertTrue(props.has("limit"), "listMyData schema must have limit");
        assertTrue(props.has("related_dataset_ids"), "listMyData schema must have related_dataset_ids");
        // query_type 必填
        JsonNode required = objectMapper.readTree(listMyData.getParametersJson()).path("required");
        assertTrue(required.toString().contains("query_type"),
                "query_type should be required; got: " + required);
    }
}
