package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.registry.AgentToolRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MarketDataTools.checkParallelLimits 响应中的并行说明组名单
 * 与 AgentToolRegistry parallelGroups 元数据的一致性 force-check。
 */
class CheckParallelLimitsForceCheckContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        when(datasetWriter.isEnabled()).thenReturn(false);
        tools = new MarketDataTools(
                datasetWriter,
                mock(DatasetRegistry.class),
                null,
                null,
                new AgentLlmProperties(),
                objectMapper
        );
    }

    @Test
    void checkParallelLimits_groupsMatchRegistry() throws Exception {
        String response = tools.checkParallelLimits();
        Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) root.get("data");

        assertGroupEquals(data, "search", AgentToolRegistry.ParallelGroup.SEARCH);
        assertGroupEquals(data, "daily", AgentToolRegistry.ParallelGroup.DAILY);
        assertGroupEquals(data, "calendar", AgentToolRegistry.ParallelGroup.CALENDAR);
        assertAdvancedGroupEquals(data, "advanced", AgentToolRegistry.ParallelGroup.ADVANCED);
    }

    private void assertGroupEquals(Map<String, Object> data, String groupKey,
                                   AgentToolRegistry.ParallelGroup registryGroup) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) data.get(groupKey);
        @SuppressWarnings("unchecked")
        List<String> actualTools = (List<String>) group.get("tools");
        Set<String> actual = new HashSet<>(actualTools);
        Set<String> expected = AgentToolRegistry.namesInParallelGroup(registryGroup);

        for (String name : expected) {
            assertTrue(actual.contains(name),
                    groupKey + " 组必须包含 registry 成员: " + name);
        }
        for (String name : actual) {
            assertTrue(expected.contains(name),
                    groupKey + " 组出现非 registry 成员: " + name);
        }
    }

    private void assertAdvancedGroupEquals(Map<String, Object> data, String groupKey,
                                             AgentToolRegistry.ParallelGroup registryGroup) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) data.get(groupKey);
        @SuppressWarnings("unchecked")
        List<String> actualTools = (List<String>) group.get("tools");
        Set<String> actual = actualTools.stream()
                .map(t -> t.replace("(mode=advanced)", "").trim())
                .collect(Collectors.toSet());
        Set<String> expected = AgentToolRegistry.namesInParallelGroup(registryGroup);

        for (String name : expected) {
            assertTrue(actual.contains(name),
                    groupKey + " 组必须包含 registry 成员: " + name);
        }
        for (String name : actual) {
            assertTrue(expected.contains(name),
                    groupKey + " 组出现非 registry 成员: " + name);
        }
    }
}
