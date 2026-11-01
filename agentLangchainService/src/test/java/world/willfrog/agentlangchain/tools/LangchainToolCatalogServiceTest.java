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



}
