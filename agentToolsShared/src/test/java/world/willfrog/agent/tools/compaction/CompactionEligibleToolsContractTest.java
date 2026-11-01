package world.willfrog.agent.tools.compaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import world.willfrog.agent.tools.registry.AgentToolRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompactionEligibleTools 与 AgentToolRegistry 压缩资格元数据的一致性契约测试。
 */
class CompactionEligibleToolsContractTest {




    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "nonExistentTool"})
    void unknownOrBlank_isNotEligible(String toolName) {
        assertFalse(CompactionEligibleTools.isEligible(toolName));
    }

    @Test
    void eligibilityMatchesRegistryForAllDeclaredTools() {
        for (AgentToolRegistry.ToolDeclaration declaration : AgentToolRegistry.all()) {
            boolean eligible = declaration.compression() == AgentToolRegistry.Compression.ELIGIBLE;
            assertEquals(eligible, CompactionEligibleTools.isEligible(declaration.name()),
                    declaration.name() + " 的 isEligible 必须与注册表 compression 一致");
        }
    }
}
