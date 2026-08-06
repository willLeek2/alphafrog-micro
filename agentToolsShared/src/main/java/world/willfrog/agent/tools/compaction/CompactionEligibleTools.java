package world.willfrog.agent.tools.compaction;

import java.util.Set;

/**
 * 纳入 tool result 截断/摘要机制的纯 DB 工具白名单。
 */
public final class CompactionEligibleTools {

    private static final Set<String> ELIGIBLE = Set.of(
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

    private static final Set<String> EXCLUDED = Set.of(
            "executePython",
            "searchWeb"
    );

    private CompactionEligibleTools() {
    }

    public static boolean isEligible(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (EXCLUDED.contains(toolName)) {
            return false;
        }
        return ELIGIBLE.contains(toolName);
    }
}
