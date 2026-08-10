package world.willfrog.agent.tools.compaction;

import world.willfrog.agent.tools.registry.AgentToolRegistry;

import java.util.Set;

/**
 * 纳入 tool result 截断/摘要机制的工具白名单。
 *
 * <p>ELIGIBLE / EXCLUDED 集合由 {@link AgentToolRegistry} 的 compression 元数据派生，
 * 保证注册表与白名单单一真相源一致。EXEMPT 条目在注册表中携带可审查理由；
 * 例如 getStockSwIndustryInfo 作为 S3A-005 漂移样本保留豁免，行为不变。</p>
 */
public final class CompactionEligibleTools {

    private static final Set<String> ELIGIBLE = AgentToolRegistry.namesWithCompression(
            AgentToolRegistry.Compression.ELIGIBLE);

    private static final Set<String> EXCLUDED = AgentToolRegistry.namesWithCompression(
            AgentToolRegistry.Compression.EXCLUDED);

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
