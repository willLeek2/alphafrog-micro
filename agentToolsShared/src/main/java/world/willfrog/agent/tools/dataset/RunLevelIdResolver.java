package world.willfrog.agent.tools.dataset;

import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * run 级局部编号的拆分与解析归集：executePython 与 executeQuery 共用同一份语义。
 *
 * <p>编号语义：模型传入的是当前 Run 内由 listMyData 分配的整数编号（dataset 与 manifest
 * 两套独立编号空间），不是持久化层的原始 ID，也不是磁盘路径。拆分兼容逗号分隔纯数字串
 * 与 JSON 数组字符串两种形态；解析把编号映射为 {@link AgentRunDatasetEntry}，无法解析的
 * token 记入 illegal 列表供调用方回填错误提示。</p>
 */
public final class RunLevelIdResolver {

    private RunLevelIdResolver() {
    }

    /**
     * 把调用方传入的编号字符串拆成 token 数组。
     * 兼容逗号分隔的纯数字串与 JSON 数组字符串（含可选的双引号包裹）。
     * 去重并保持首次出现顺序，避免重复挂载同一 dataset。
     */
    public static String[] parseIds(String datasetIds) {
        if (datasetIds == null) {
            return new String[0];
        }
        String trimmed = datasetIds.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        // 去掉 JSON 数组外层的方括号，后续仍按逗号拆分。
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .map(item -> {
                    String id = item;
                    // 去掉 JSON 字符串元素两侧的双引号。
                    if (id.startsWith("\"") && id.endsWith("\"") && id.length() >= 2) {
                        id = id.substring(1, id.length() - 1).trim();
                    }
                    return id;
                })
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * 把 token 数组解析成 run 级条目。
     *
     * @param tokens           经 {@link #parseIds} 拆分后的字符串，可能含非数字
     * @param kind             "dataset" 或 "manifest"，决定查哪一套编号空间
     * @param allowEmptyTokens manifest 路径允许空 token（静默跳过），dataset 路径不允许
     */
    public static void resolveRunLevelNumbers(
            String[] tokens,
            AgentRunDatasetRegistry registry,
            String runId,
            String kind,
            List<AgentRunDatasetEntry> resolved,
            List<Map<String, Object>> illegal,
            boolean allowEmptyTokens) {
        for (String token : tokens) {
            if (!allowEmptyTokens && (token == null || token.isBlank())) {
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(token);
            } catch (NumberFormatException nfe) {
                // 非整数 token 无法对应 run 级编号，记入 illegal 并继续处理后续 token。
                illegal.add(Map.of("input", token, "reason", "not_an_integer"));
                continue;
            }
            Optional<AgentRunDatasetEntry> hit = "manifest".equals(kind)
                    ? registry.findManifestByNumber(runId, number)
                    : registry.findDatasetByNumber(runId, number);
            if (hit.isPresent()) {
                resolved.add(hit.get());
            } else {
                // 整数合法但当前 run 的对应编号空间里不存在该编号。
                illegal.add(Map.of(
                        "input", token,
                        "reason", "no_" + kind + "_with_this_run_level_number"
                ));
            }
        }
    }
}
