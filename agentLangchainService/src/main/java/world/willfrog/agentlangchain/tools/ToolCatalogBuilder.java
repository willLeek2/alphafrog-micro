package world.willfrog.agentlangchain.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import world.willfrog.agent.platform.service.ToolDescriptionTexts;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.catalog.MarketDataAdvancedToolCatalog;
import world.willfrog.agent.tools.catalog.ParallelLimitsToolCatalog;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.agent.tools.registry.AgentToolRegistry.CapabilityGate;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 构建 run 级工具目录。能力过滤统一取自 {@link AgentToolRegistry} 的门控元数据，
 * 保证运行时目录、对外 API 目录与注册表的单一真相源一致。
 * 写给模型的工具说明在拼装完成后覆盖成 classpath 权威文件，反射只提供参数结构。
 */
final class ToolCatalogBuilder {

    private ToolCatalogBuilder() {
    }

    static List<ToolSpecification> buildSpecifications(MarketDataTools marketDataTools,
                                                       RagTools ragTools,
                                                       SearchTools searchTools,
                                                       PythonSandboxTools pythonSandboxTools,
                                                       ListMyDataTool listMyDataTool,
                                                       LoadToolGuideTool loadToolGuideTool,
                                                       RereadToolHandler rereadToolHandler,
                                                       boolean webSearchEnabled,
                                                       boolean codeInterpreterEnabled) {
        List<ToolSpecification> specifications = new ArrayList<>();
        addSpecsIfPresent(specifications, marketDataTools);
        addSpecsIfPresent(specifications, ragTools);
        addSpecsIfPresent(specifications, searchTools);
        addSpecsIfPresent(specifications, pythonSandboxTools);
        addSpecsIfPresent(specifications, listMyDataTool);
        addSpecsIfPresent(specifications, loadToolGuideTool);
        addSpecsIfPresent(specifications, rereadToolHandler);

        List<ToolSpecification> filtered = specifications.stream()
                .filter(spec -> isCapabilityEnabled(AgentToolRegistry.require(spec.name()).capabilityGate(),
                        webSearchEnabled, codeInterpreterEnabled))
                .collect(Collectors.toCollection(ArrayList::new));

        List<ToolSpecification> merged = MarketDataAdvancedToolCatalog.mergeCanonical(
                ParallelLimitsToolCatalog.mergeCanonical(filtered));
        List<ToolSpecification> result = addSubAgentControlToolsIfAbsent(
                addResolveFinanceMethodsIfAbsent(merged));
        // 目录里的每个工具名都必须已在注册表登记，未登记就直接抛错，避免漏进未登记工具。
        result.forEach(spec -> AgentToolRegistry.require(spec.name()));
        return applyAuthorityDescriptions(result);
    }

    /**
     * 反射只负责参数结构；写给模型的说明一律覆盖成 classpath 权威文件正文。
     * 权威文件缺失时直接失败，不再在 Java 里放备用副本。
     */
    private static List<ToolSpecification> applyAuthorityDescriptions(List<ToolSpecification> specifications) {
        List<ToolSpecification> result = new ArrayList<>(specifications.size());
        for (ToolSpecification spec : specifications) {
            result.add(spec.toBuilder().description(ToolDescriptionTexts.require(spec.name())).build());
        }
        return List.copyOf(result);
    }

    private static void addSpecsIfPresent(List<ToolSpecification> target, Object toolBean) {
        if (toolBean != null) {
            target.addAll(ToolSpecifications.toolSpecificationsFrom(toolBean));
        }
    }

    private static boolean isCapabilityEnabled(CapabilityGate gate, boolean webSearchEnabled, boolean codeInterpreterEnabled) {
        return switch (gate) {
            case NONE -> true;
            case WEB_SEARCH -> webSearchEnabled;
            case CODE_INTERPRETER -> codeInterpreterEnabled;
            case ADJ_FACTOR -> true;
        };
    }

    static ToolSpecification resolveFinanceMethodsSpecification() {
        return ToolSpecification.builder()
                .name("resolveFinanceMethods")
                .description(ToolDescriptionTexts.require("resolveFinanceMethods"))
                .parameters(JsonObjectSchema.builder()
                        .addProperty("query", JsonStringSchema.builder()
                                .description("The user's raw natural-language financial question (required).")
                                .build())
                        .addProperty("context", JsonStringSchema.builder()
                                .description("Optional natural-language context, e.g. already-fetched data or missing boundary hints.")
                                .build())
                        .required(List.of("query"))
                        .build())
                .build();
    }

    public static List<ToolSpecification> addResolveFinanceMethodsIfAbsent(List<ToolSpecification> specifications) {
        boolean exists = specifications.stream().anyMatch(spec -> "resolveFinanceMethods".equals(spec.name()));
        if (exists) {
            return specifications;
        }
        List<ToolSpecification> result = new ArrayList<>(specifications);
        result.add(resolveFinanceMethodsSpecification());
        return result;
    }

    static List<ToolSpecification> addSubAgentControlToolsIfAbsent(List<ToolSpecification> specifications) {
        List<ToolSpecification> result = new ArrayList<>(specifications);
        if (result.stream().noneMatch(spec -> "spawnSubAgent".equals(spec.name()))) {
            result.add(ToolSpecification.builder()
                    .name("spawnSubAgent")
                    .description(ToolDescriptionTexts.require("spawnSubAgent"))
                    .parameters(JsonObjectSchema.builder()
                            .addProperty("goal", JsonStringSchema.builder()
                                    .description("Concrete child-agent goal. Required and non-blank.")
                                    .build())
                            .addProperty("context", JsonStringSchema.builder()
                                    .description("Optional bounded context that helps complete the goal.")
                                    .build())
                            .required(List.of("goal"))
                            .additionalProperties(false)
                            .build())
                    .build());
        }
        if (result.stream().noneMatch(spec -> "waitForSubAgent".equals(spec.name()))) {
            result.add(ToolSpecification.builder()
                    .name("waitForSubAgent")
                    .description(ToolDescriptionTexts.require("waitForSubAgent"))
                    .parameters(JsonObjectSchema.builder()
                            .addProperty("subAgentIds", JsonArraySchema.builder()
                                    .description("One or more ids returned by spawnSubAgent.")
                                    .items(JsonStringSchema.builder().build())
                                    .build())
                            .addProperty("timeoutMillis", JsonIntegerSchema.builder()
                                    .description("Optional wait duration in milliseconds; the server applies a bounded maximum.")
                                    .build())
                            .required(List.of("subAgentIds"))
                            .additionalProperties(false)
                            .build())
                    .build());
        }
        return result;
    }
}
