package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.tools.registry.AgentToolRegistry;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ToolWeightedLimitService.countBatchItems 与 AgentToolRegistry batchCountKeys
 * 元数据的一致性只读 force-check。
 */
class ToolWeightedLimitForceCheckContractTest {

    private final ToolWeightedLimitService service = new ToolWeightedLimitService(
            new AgentLlmProperties(), new ObjectMapper());

    @Test
    void queryFamilyTools_countTwoValuesUnderTheirKeys() {
        for (String toolName : AgentToolRegistry.namesWithBatchCountKeys(AgentToolRegistry.BatchCountKeys.QUERY)) {
            Map<String, Object> params = Map.of("keyword", "a|b");
            int count = invokeCountBatchItems(toolName, params);
            assertEquals(2, count, toolName + " 的 QUERY 批量计数应为 2");
        }
    }

    @Test
    void tsCodeFamilyTools_countTwoValuesUnderTheirKeys() {
        for (String toolName : AgentToolRegistry.namesWithBatchCountKeys(AgentToolRegistry.BatchCountKeys.TS_CODE)) {
            Map<String, Object> params = Map.of("tsCode", "000001.SZ|600519.SH");
            int count = invokeCountBatchItems(toolName, params);
            assertEquals(2, count, toolName + " 的 TS_CODE 批量计数应为 2");
        }
    }

    @Test
    void datesFamilyTools_countTwoValuesUnderTheirKeys() {
        for (String toolName : AgentToolRegistry.namesWithBatchCountKeys(AgentToolRegistry.BatchCountKeys.DATES)) {
            Map<String, Object> params = Map.of("date", "20240102|20240103");
            int count = invokeCountBatchItems(toolName, params);
            assertEquals(2, count, toolName + " 的 DATES 批量计数应为 2");
        }
    }

    @Test
    void noneFamilyControlTools_countOne() {
        // 选取两个 batchCountKeys=NONE 的工具作为控制组
        for (String toolName : Set.of("checkParallelLimits", "resolveFinanceMethods")) {
            Map<String, Object> params = Map.of("keyword", "a|b");
            int count = invokeCountBatchItems(toolName, params);
            assertEquals(1, count, toolName + " 的 NONE 批量计数应为 1");
        }
    }

    private int invokeCountBatchItems(String toolName, Map<String, Object> params) {
        Object result = ReflectionTestUtils.invokeMethod(service, "countBatchItems", toolName, params);
        assert result != null;
        return (Integer) result;
    }
}
