package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.dataset.ManifestWriter;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LangchainToolCatalogServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        LangchainToolCatalogService service = new LangchainToolCatalogService(
                marketDataTools,
                null,
                null,
                null,
                new ListMyDataTool(objectMapper),
                null,
                null,
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
        assertTrue(searchIndexProps.has("advancedQuery"));
        assertTrue(searchIndexProps.path("advancedQuery").path("properties").has("conditions"));

        AgentToolMessage searchAssetInfo = tools.stream()
                .filter(tool -> "searchAssetInfo".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        JsonNode searchAssetProps = objectMapper.readTree(searchAssetInfo.getParametersJson()).path("properties");
        assertTrue(searchAssetProps.has("query"));
        assertTrue(searchAssetProps.has("advancedQuery"));
        assertTrue(searchAssetProps.path("advancedQuery").path("properties").has("asset_type"));
        assertTrue(searchAssetProps.path("advancedQuery").path("properties").has("conditions"));
        assertTrue(searchAssetProps.has("assetTypes"));
        assertTrue(searchAssetProps.has("mode"));
    }

    @Test
    void listToolMessages_shouldExposeListMyData() throws Exception {
        LangchainToolCatalogService service = new LangchainToolCatalogService(
                null,
                null,
                null,
                null,
                new ListMyDataTool(objectMapper),
                null,
                null,
                objectMapper
        );

        AgentToolMessage listMyData = service.listToolMessages().stream()
                .filter(tool -> "listMyData".equals(tool.getName()))
                .findFirst()
                .orElseThrow();

        JsonNode props = objectMapper.readTree(listMyData.getParametersJson()).path("properties");
        assertTrue(props.has("query_type"));
        assertTrue(props.has("grep"));
        assertTrue(props.has("related_dataset_ids"));
    }

    @Test
    void listToolMessages_shouldExposeResolveFinanceMethodsAlways() throws Exception {
        LangchainToolCatalogService service = new LangchainToolCatalogService(
                null,
                null,
                null,
                null,
                new ListMyDataTool(objectMapper),
                null,
                null,
                objectMapper
        );

        AgentToolMessage resolve = service.listToolMessages().stream()
                .filter(tool -> "resolveFinanceMethods".equals(tool.getName()))
                .findFirst()
                .orElseThrow();

        JsonNode props = objectMapper.readTree(resolve.getParametersJson()).path("properties");
        assertTrue(props.has("query"));
        assertTrue(props.has("context"));
        assertTrue(resolve.getDescription().contains("raw natural-language"));
    }
}
