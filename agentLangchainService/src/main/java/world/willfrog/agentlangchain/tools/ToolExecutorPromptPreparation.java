package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.agentlangchain.execution.ToolBudgetPromptRenderer;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

/** 工具执行器自己的纠正建议按实际分支读取版本，不替原工具正文猜测来源。 */
@Component
public final class ToolExecutorPromptPreparation {
    private final AgentPromptTextReader reader;
    private final ObjectMapper json;

    public ToolExecutorPromptPreparation(AgentPromptTextReader reader, ObjectMapper json) {
        this.reader = reader;
        this.json = json;
    }

    public PromptText requireOutput(String output, PromptText classified) {
        if (classified == null || !classified.text().equals(output)) {
            throw missing("工具原始结果未保留与正文一致的来源");
        }
        return classified;
    }

    PromptText repeatedError(LangchainRepeatedToolCallGuard.Decision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("code", "REPEATED_TOOL_CALL");
        payload.put("message", "repeated_tool_call: same tool and arguments were called repeatedly");
        payload.put("tool_name", decision.toolName() == null ? "" : decision.toolName());
        payload.put("repeat_count", decision.count());
        PromptText hint = repeatedAdvice(decision);
        payload.put("_retry_hint_", hint.text());
        try {
            return new PromptText(json.writeValueAsString(payload), hint.sources());
        } catch (Exception failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            throw missing("重复调用建议无法随工具结果编码");
        }
    }

    PromptText appendRepeatedAdvice(PromptText output, LangchainRepeatedToolCallGuard.Decision decision) {
        if (decision == null || !decision.repeated() || decision.blocked()) return output;
        return PromptText.concat(output, PromptText.runtimeData("\n\n_retry_hint_: "), repeatedAdvice(decision));
    }

    PromptText appendDatasetAdvice(PromptText output, String toolName, boolean registryUnavailable,
                                   Map<String, String> datasetRefs) {
        String name = toolName == null ? "" : toolName;
        boolean query = "executeQuery".equals(name);
        PromptText base = read("toolDatasetRetryAdvice", Map.of("toolName", name,
                "expectedIds", query ? "dataset_ids" : "dataset_ids / manifest_ids",
                "rawIdKinds", query ? "dataset_id" : "dataset_id / manifest_id"));
        PromptText registry = registryUnavailable ? read("toolDatasetRegistryUnavailableAdvice", Map.of())
                : PromptText.runtimeData("");
        PromptText tail = datasetRefs != null && !datasetRefs.isEmpty()
                ? read("toolDatasetObservedIdsAdvice", Map.of("rawIds", String.join(",", datasetRefs.keySet())))
                : read("toolDatasetNoDataAdvice", Map.of());
        return PromptText.concat(output, PromptText.runtimeData("\n\n_retry_hint_: "), base, registry, tail);
    }

    public PromptText budgetRejection(RunBudgetException budget) {
        return ToolBudgetPromptRenderer.rejection(reader, budget.getDimension(), budget.getActual(), budget.getLimit(), json);
    }

    private PromptText repeatedAdvice(LangchainRepeatedToolCallGuard.Decision decision) {
        String name = decision.toolName() == null || decision.toolName().isBlank() ? "the same tool" : decision.toolName();
        return read("toolRepeatedCallAdvice", Map.of("toolName", name, "count", String.valueOf(decision.count())));
    }

    private PromptText read(String fragment, Map<String, String> facts) {
        var body = reader.read(reader.current(), Map.of(fragment, fragment)).get(fragment);
        return reader.render(body, facts, Map.of());
    }

    static ModelSendBlockedException missing(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }
}
