package world.willfrog.agent.tools.catalog;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import world.willfrog.agent.platform.service.ToolDescriptionTexts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical LLM-visible specification for {@code listAssetTables}（014-new-02 方案 D5）.
 *
 * <p>零参数 {@code @Tool} 反射会被部分 provider 漏注册（已知坑，先例
 * {@link ParallelLimitsToolCatalog}）：本 helper 保证 {@code listAssetTables}
 * 始终以手工 canonical 规格出现在发给模型的 run 级目录里。
 * {@code queryAssetData} 带参数，反射规格可靠，不归本 helper 管。</p>
 */
public final class AssetSqlToolCatalog {

    public static final String TOOL_NAME = "listAssetTables";

    /**
     * 返回本 helper 以 canonical schema 覆盖的工具名，用于与 {@code AgentToolRegistry} 的
     * {@code canonicalSpec=ASSET_SQL} 声明做契约对照。
     */
    public static String canonicalToolName() {
        return TOOL_NAME;
    }

    private AssetSqlToolCatalog() {
    }

    public static ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(TOOL_NAME)
                .description(ToolDescriptionTexts.require(TOOL_NAME))
                .parameters(JsonObjectSchema.builder()
                        .additionalProperties(false)
                        .build())
                .build();
    }

    /**
     * Ensures {@link #TOOL_NAME} is present and uses the canonical schema (insert at front when missing).
     */
    public static void ensureRegistered(List<ToolSpecification> specifications) {
        if (specifications == null) {
            return;
        }
        boolean present = specifications.stream()
                .anyMatch(spec -> spec != null && TOOL_NAME.equals(spec.name()));
        if (!present) {
            specifications.add(0, specification());
        }
    }

    /**
     * Dedupes by tool name and always applies the canonical {@link #TOOL_NAME} specification last.
     */
    public static List<ToolSpecification> mergeCanonical(List<ToolSpecification> specifications) {
        Map<String, ToolSpecification> byName = new LinkedHashMap<>();
        if (specifications != null) {
            for (ToolSpecification spec : specifications) {
                if (spec == null || spec.name() == null || spec.name().isBlank()) {
                    continue;
                }
                byName.put(spec.name(), spec);
            }
        }
        byName.put(TOOL_NAME, specification());
        return List.copyOf(byName.values());
    }

    public static List<ToolSpecification> mergeCanonicalFromBeans(Object... toolBeans) {
        List<ToolSpecification> merged = new ArrayList<>();
        for (Object bean : toolBeans) {
            if (bean == null) {
                continue;
            }
            merged.addAll(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(bean));
        }
        return mergeCanonical(merged);
    }
}
