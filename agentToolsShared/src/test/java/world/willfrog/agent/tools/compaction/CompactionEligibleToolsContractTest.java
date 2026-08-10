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

    @Test
    void allDeclaredEligibleNames_areEligible() {
        for (String name : AgentToolRegistry.namesWithCompression(AgentToolRegistry.Compression.ELIGIBLE)) {
            assertTrue(CompactionEligibleTools.isEligible(name),
                    name + " 注册为 ELIGIBLE，isEligible 必须为 true");
        }
    }

    @Test
    void allDeclaredExcludedNames_areNotEligible() {
        for (String name : AgentToolRegistry.namesWithCompression(AgentToolRegistry.Compression.EXCLUDED)) {
            assertFalse(CompactionEligibleTools.isEligible(name),
                    name + " 注册为 EXCLUDED，isEligible 必须为 false");
        }
    }

    @Test
    void allDeclaredExemptNames_areNotEligible() {
        for (String name : AgentToolRegistry.namesWithCompression(AgentToolRegistry.Compression.EXEMPT)) {
            assertFalse(CompactionEligibleTools.isEligible(name),
                    name + " 注册为 EXEMPT，isEligible 必须为 false");
        }
    }

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
