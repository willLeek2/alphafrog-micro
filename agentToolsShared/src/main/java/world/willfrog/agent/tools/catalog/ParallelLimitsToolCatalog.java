package world.willfrog.agent.tools.catalog;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import world.willfrog.agent.platform.service.ToolDescriptionTexts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical LLM-visible specification for {@code checkParallelLimits}.
 *
 * <p>Some providers reject or drop zero-arg {@code @Tool} reflections; this helper guarantees the
 * tool is always present in run-scoped catalogs that are sent to the model.</p>
 */
public final class ParallelLimitsToolCatalog {

    public static final String TOOL_NAME = "checkParallelLimits";

    /**
     * 返回本 helper 以 canonical schema 覆盖的工具名，用于与 {@code AgentToolRegistry} 的
     * {@code canonicalSpec=PARALLEL_LIMITS} 声明做契约对照。
     */
    public static String canonicalToolName() {
        return TOOL_NAME;
    }

    private ParallelLimitsToolCatalog() {
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
