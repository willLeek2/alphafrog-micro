package world.willfrog.agentlangchain.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
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
 * 不再对特定 Bean 做硬编码条件拼接，保证运行时目录、对外 API 目录和注册表单一真相源一致。
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
        // fail-closed：任何最终进入目录的名字必须已在注册表声明。
        result.forEach(spec -> AgentToolRegistry.require(spec.name()));
        return result;
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
                .description("Read-only advisor for financial indicators or calculation methods. "
                        + "When a question involves a financial metric or computation, pass the user's raw natural-language "
                        + "expression directly to this tool; do not first rewrite it into fixed fields such as method name, "
                        + "year, or period count. If the result contains unresolvedTerms, clarify the boundary before computing. "
                        + "Compatible public-library samples should be preferred but are not mandatory. "
                        + "When later calling report() or report_custom(), pass the resolverToolCallId from this tool's result "
                        + "as source_resolver_tool_call_id.")
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
                    .description("Start one bounded child agent for an independent goal. "
                            + "The child shares this run's total budget, cancellation and observability. "
                            + "It cannot create another child agent. Call waitForSubAgent with the returned id.")
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
                    .description("Wait for one or more child agents from this run. Returns one structured state per id. "
                            + "A wait timeout does not cancel unfinished children; call this tool again to continue waiting.")
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
