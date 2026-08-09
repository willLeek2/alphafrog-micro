package world.willfrog.agentlangchain.tools;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.tools.catalog.MarketDataAdvancedToolCatalog;
import world.willfrog.agent.tools.catalog.ParallelLimitsToolCatalog;
import world.willfrog.agent.tools.registry.AgentToolRegistry;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Catalog helper 的 canonical 覆盖名与 AgentToolRegistry 声明的契约对照。
 */
class CatalogHelperForceCheckContractTest {

    @Test
    void marketAdvancedHelperOverridesMatchRegistryCanonicalSpec() {
        Set<String> helperNames = MarketDataAdvancedToolCatalog.overriddenCanonicalNames();
        Set<String> registryNames = AgentToolRegistry.namesWithCanonicalSpec(
                AgentToolRegistry.CanonicalSpec.MARKET_ADVANCED);

        assertEquals(registryNames, helperNames,
                "MarketDataAdvancedToolCatalog 覆盖的名字必须与注册表 MARKET_ADVANCED canonical 声明一致");
        assertEquals(3, helperNames.size());
    }

    @Test
    void parallelLimitsHelperCanonicalNameMatchesRegistryCanonicalSpec() {
        String helperName = ParallelLimitsToolCatalog.canonicalToolName();
        Set<String> registryNames = AgentToolRegistry.namesWithCanonicalSpec(
                AgentToolRegistry.CanonicalSpec.PARALLEL_LIMITS);

        assertEquals(registryNames, Set.of(helperName),
                "ParallelLimitsToolCatalog 覆盖的名字必须与注册表 PARALLEL_LIMITS canonical 声明一致");
        assertEquals("checkParallelLimits", helperName);
    }
}
