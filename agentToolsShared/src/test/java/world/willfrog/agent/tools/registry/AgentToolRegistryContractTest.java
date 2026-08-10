package world.willfrog.agent.tools.registry;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentToolRegistry 单一声明源契约测试。
 *
 * <p>本测试把注册表的当前内容「冻住」：任何新增、删除、重分类只要与下方硬编码集合不一致
 * 就会失败，迫使改动方显式更新契约并评审影响面。</p>
 */
class AgentToolRegistryContractTest {

    @Test
    void declaredToolNames_hasExactly27UniqueNames() {
        Set<String> names = AgentToolRegistry.declaredToolNames();
        assertEquals(27, names.size(), "生产声明面应保持 27 个工具名");
        assertEquals(27, names.stream().distinct().count(), "工具名必须唯一");
    }

    @Test
    void declaredToolNames_containsD06SubAgentControls() {
        Set<String> names = AgentToolRegistry.declaredToolNames();
        assertTrue(names.contains("spawnSubAgent"), "D06 必须登记 spawnSubAgent");
        assertTrue(names.contains("waitForSubAgent"), "D06 必须登记 waitForSubAgent");
    }

    @Test
    void everyExemptEntry_hasNonBlankReason() {
        for (AgentToolRegistry.ToolDeclaration declaration : AgentToolRegistry.all()) {
            if (declaration.compression() == AgentToolRegistry.Compression.EXEMPT) {
                String reason = declaration.compressionExemptionReason();
                assertTrue(reason != null && !reason.isBlank(),
                        declaration.name() + " 的 EXEMPT 理由不可为空");
            }
        }
    }

    @Test
    void eligibleSet_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of(
                "getStockInfo",
                "getStockDaily",
                "searchStock",
                "searchFund",
                "getIndexInfo",
                "getIndexDaily",
                "searchIndex",
                "searchAssetInfo",
                "getTradingDaysSummary",
                "isTradingDay",
                "getExchangeAssetDaily",
                "getOffExchangeAssetDaily",
                "getEtfAdj",
                "getListedAssetShareSize",
                "getFinancialReport",
                "ragSearch",
                "loadDocument"
        );
        Set<String> actual = AgentToolRegistry.namesWithCompression(AgentToolRegistry.Compression.ELIGIBLE);
        assertEquals(expected, actual, "ELIGIBLE 集合必须与注册表现状一致");
    }

    @Test
    void excludedSet_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of(
                "executePython",
                "searchWeb",
                "spawnSubAgent",
                "waitForSubAgent"
        );
        Set<String> actual = AgentToolRegistry.namesWithCompression(AgentToolRegistry.Compression.EXCLUDED);
        assertEquals(expected, actual, "EXCLUDED 集合必须与注册表现状一致");
    }

    @Test
    void cacheSearchSet_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("searchStock", "searchFund", "searchIndex", "searchAssetInfo");
        Set<String> actual = AgentToolRegistry.namesInCacheFamily(AgentToolRegistry.CacheFamily.SEARCH);
        assertEquals(expected, actual);
    }

    @Test
    void cacheInfoSet_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("getStockInfo", "getIndexInfo");
        Set<String> actual = AgentToolRegistry.namesInCacheFamily(AgentToolRegistry.CacheFamily.INFO);
        assertEquals(expected, actual);
    }

    @Test
    void cacheDatasetSet_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of(
                "getStockDaily",
                "getIndexDaily",
                "getExchangeAssetDaily",
                "getOffExchangeAssetDaily",
                "getListedAssetShareSize",
                "getEtfAdj"
        );
        Set<String> actual = AgentToolRegistry.namesInCacheFamily(AgentToolRegistry.CacheFamily.DATASET);
        assertEquals(expected, actual);
    }

    @Test
    void parallelSearchGroup_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of(
                "getStockInfo",
                "getStockSwIndustryInfo",
                "searchStock",
                "searchFund",
                "getIndexInfo",
                "searchIndex",
                "searchAssetInfo"
        );
        Set<String> actual = AgentToolRegistry.namesInParallelGroup(AgentToolRegistry.ParallelGroup.SEARCH);
        assertEquals(expected, actual);
    }

    @Test
    void parallelDailyGroup_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of(
                "getStockDaily",
                "getIndexDaily",
                "getExchangeAssetDaily"
        );
        Set<String> actual = AgentToolRegistry.namesInParallelGroup(AgentToolRegistry.ParallelGroup.DAILY);
        assertEquals(expected, actual);
    }

    @Test
    void parallelCalendarGroup_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("isTradingDay");
        Set<String> actual = AgentToolRegistry.namesInParallelGroup(AgentToolRegistry.ParallelGroup.CALENDAR);
        assertEquals(expected, actual);
    }

    @Test
    void parallelAdvancedGroup_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("searchIndex", "searchAssetInfo", "getExchangeAssetDaily");
        Set<String> actual = AgentToolRegistry.namesInParallelGroup(AgentToolRegistry.ParallelGroup.ADVANCED);
        assertEquals(expected, actual);
    }

    @Test
    void batchQueryKeys_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("searchStock", "searchFund", "searchIndex", "searchAssetInfo");
        Set<String> actual = AgentToolRegistry.namesWithBatchCountKeys(AgentToolRegistry.BatchCountKeys.QUERY);
        assertEquals(expected, actual);
    }

    @Test
    void batchTsCodeKeys_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("getStockDaily", "getIndexDaily", "getExchangeAssetDaily");
        Set<String> actual = AgentToolRegistry.namesWithBatchCountKeys(AgentToolRegistry.BatchCountKeys.TS_CODE);
        assertEquals(expected, actual);
    }

    @Test
    void batchDatesKeys_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("isTradingDay");
        Set<String> actual = AgentToolRegistry.namesWithBatchCountKeys(AgentToolRegistry.BatchCountKeys.DATES);
        assertEquals(expected, actual);
    }

    @Test
    void canonicalMarketAdvanced_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("searchIndex", "searchAssetInfo", "getExchangeAssetDaily");
        Set<String> actual = AgentToolRegistry.namesWithCanonicalSpec(AgentToolRegistry.CanonicalSpec.MARKET_ADVANCED);
        assertEquals(expected, actual);
    }

    @Test
    void canonicalParallelLimits_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("checkParallelLimits");
        Set<String> actual = AgentToolRegistry.namesWithCanonicalSpec(AgentToolRegistry.CanonicalSpec.PARALLEL_LIMITS);
        assertEquals(expected, actual);
    }

    @Test
    void canonicalManualFinance_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("resolveFinanceMethods");
        Set<String> actual = AgentToolRegistry.namesWithCanonicalSpec(AgentToolRegistry.CanonicalSpec.MANUAL_FINANCE);
        assertEquals(expected, actual);
    }

    @Test
    void canonicalManualSubAgent_matchesFrozenGroundTruth() {
        Set<String> expected = Set.of("spawnSubAgent", "waitForSubAgent");
        Set<String> actual = AgentToolRegistry.namesWithCanonicalSpec(
                AgentToolRegistry.CanonicalSpec.MANUAL_SUB_AGENT);
        assertEquals(expected, actual);
    }

    @Test
    void capabilityGates_matchFrozenGroundTruth() {
        assertEquals(Set.of("searchWeb"),
                AgentToolRegistry.namesWithCapabilityGate(AgentToolRegistry.CapabilityGate.WEB_SEARCH));
        assertEquals(Set.of("executePython"),
                AgentToolRegistry.namesWithCapabilityGate(AgentToolRegistry.CapabilityGate.CODE_INTERPRETER));
        assertEquals(Set.of("getEtfAdj"),
                AgentToolRegistry.namesWithCapabilityGate(AgentToolRegistry.CapabilityGate.ADJ_FACTOR));
    }

    @Test
    void require_onUnknownName_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AgentToolRegistry.require("nonExistentTool"));
        assertTrue(ex.getMessage().contains("nonExistentTool"));
    }
}
