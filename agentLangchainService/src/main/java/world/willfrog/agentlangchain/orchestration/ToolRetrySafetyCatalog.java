package world.willfrog.agentlangchain.orchestration;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前工具的显式重试安全清单。
 *
 * <p>这里逐项登记已经确认的工具，不使用 get/search 等名字前缀推断。新增工具如果没有
 * 同步登记，会自然落到 UNSAFE，从而禁止自动重试和崩溃重放。</p>
 */
@Component
public class ToolRetrySafetyCatalog {

    private static final Map<String, ToolRetrySafety> SAFETY_BY_TOOL;

    static {
        Map<String, ToolRetrySafety> safety = new LinkedHashMap<>();
        // 市场数据、文档、检索、数据清单与元数据查询均不产生外部写入。
        for (String name : new String[]{
                "getStockInfo", "getStockDaily", "getStockSwIndustryInfo", "searchStock", "searchFund",
                "getIndexInfo", "getIndexDaily", "searchIndex", "searchAssetInfo", "checkParallelLimits",
                "getTradingDaysSummary", "isTradingDay", "getExchangeAssetDaily", "getOffExchangeAssetDaily",
                "getEtfAdj", "getListedAssetShareSize", "getFinancialReport", "ragSearch", "loadDocument",
                "searchWeb", "resolveFinanceMethods", "loadToolGuide", "rereadToolResult", "listMyData",
                "waitForSubAgent"
        }) {
            safety.put(name, ToolRetrySafety.READ_ONLY);
        }
        // Sandbox 执行被限制在当前 Run 的隔离目录；相同逻辑重放不会产生外部系统写入。
        safety.put("executePython", ToolRetrySafety.IDEMPOTENT);
        // 创建子 Agent 会启动新的执行单元，重复调用可能重复消耗资源，明确禁止自动重放。
        safety.put("spawnSubAgent", ToolRetrySafety.UNSAFE);
        SAFETY_BY_TOOL = Map.copyOf(safety);
    }

    public ToolRetrySafety safetyOf(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return ToolRetrySafety.UNSAFE;
        }
        return SAFETY_BY_TOOL.getOrDefault(toolName, ToolRetrySafety.UNSAFE);
    }

    public boolean canReplay(String toolName) {
        return safetyOf(toolName) != ToolRetrySafety.UNSAFE;
    }

    public Map<String, ToolRetrySafety> declaredSafety() {
        return SAFETY_BY_TOOL;
    }
}
