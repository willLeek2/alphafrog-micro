package world.willfrog.agentlangchain.execution;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.tools.registry.AgentToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRetrySafetyCatalogTest {

    private final ToolRetrySafetyCatalog catalog = new ToolRetrySafetyCatalog();

    @Test
    void everyCurrentToolHasAnExplicitSafetyDeclaration() {
        assertThat(catalog.declaredSafety().keySet())
                .containsExactlyInAnyOrderElementsOf(AgentToolRegistry.declaredToolNames());
    }

    @Test
    void unknownToolsFailClosedAndSpawnIsUnsafe() {
        assertThat(catalog.safetyOf("futureWriteTool")).isEqualTo(ToolRetrySafety.UNSAFE);
        assertThat(catalog.safetyOf("spawnSubAgent")).isEqualTo(ToolRetrySafety.UNSAFE);
        assertThat(catalog.canReplay("searchWeb")).isTrue();
        assertThat(catalog.canReplay("executePython")).isTrue();
    }
}
