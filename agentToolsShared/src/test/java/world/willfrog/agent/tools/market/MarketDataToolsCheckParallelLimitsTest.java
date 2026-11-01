package world.willfrog.agent.tools.market;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：checkParallelLimits 的四例钉住测试（task #109）。
 * 锁默认值、热加载优先、clamp 边界与 Spring 兜底；组内 tools 清单与文案逐字钉住。
 */
class MarketDataToolsCheckParallelLimitsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentLlmLocalConfigLoader localConfigLoader;
    private AgentLlmProperties llmProperties;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        DatasetRegistry datasetRegistry = mock(DatasetRegistry.class);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        llmProperties = new AgentLlmProperties();
        tools = configure(new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                llmProperties, objectMapper));
    }

    private Map<String, Object> invoke() throws Exception {
        return objectMapper.readValue(tools.checkParallelLimits(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> groupOf(Map<String, Object> data, String group) {
        return (Map<String, Object>) data.get(group);
    }

    private static int maxItems(Map<String, Object> group) {
        return ((Number) group.get("maxItems")).intValue();
    }




}
