package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
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
}
