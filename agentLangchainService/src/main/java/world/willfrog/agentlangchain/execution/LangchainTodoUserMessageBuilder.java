package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolSpecification;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds todo / final user messages aligned with legacy {@code ReactTodoExecutor} / {@code LinearWorkflowExecutor}.
 */
final class LangchainTodoUserMessageBuilder {

    private LangchainTodoUserMessageBuilder() {
    }

    static PromptText buildTodoUserPrompt(AgentPromptService promptService,
                                         String userGoal,
                                         List<LangchainCompletedTodo> completedTodos,
                                         Map<String, String> datasetRefs,
                                         String todoDescription,
                                         List<ToolSpecification> toolSpecifications,
                                         PromptText renderedToolCapabilities) {
        List<PromptText> parts = new java.util.ArrayList<>();
        parts.add(promptService.dagReactStageInstructionText(renderedToolCapabilities));
        parts.add(PromptText.runtimeData("\n\n"));
        parts.add(promptService.dynamicContextPrefixText());
        StringBuilder facts = new StringBuilder("\n\n用户目标：").append(safe(userGoal)).append("\n\n");
        Set<String> toolNames = resolveToolNames(toolSpecifications);
        if (!toolNames.isEmpty()) facts.append("当前可用工具：").append(String.join(", ", toolNames)).append("\n\n");
        if (completedTodos != null) {
            for (LangchainCompletedTodo todo : completedTodos) {
                facts.append("已完成: ").append(safe(todo.getDescription())).append("\n");
                facts.append("摘要: ");
                parts.add(PromptText.runtimeData(facts.toString()));
                facts.setLength(0);
                parts.add(todo.attributedText(safe(todo.getSummary()) + "\n输出: "
                        + safe(todo.displayOutput()) + "\n\n"));
            }
        }
        facts.append("当前任务: ").append(safe(todoDescription)).append("\n\n");
        if (datasetRefs != null && !datasetRefs.isEmpty()) {
            facts.append("已有原始数据引用（来自前序工具输出，仅用于定位前序产出）:\n");
            datasetRefs.keySet().forEach(id -> facts.append("  - ").append(id).append("\n"));
            facts.append("\n");
        }
        parts.add(PromptText.runtimeData(facts.toString()));
        return PromptText.concat(parts);
    }

    static PromptText buildFinalUserPrompt(AgentPromptService promptService,
                                          String userGoal,
                                          List<LangchainCompletedTodo> completedTodos) {
        List<PromptText> parts = new java.util.ArrayList<>();
        parts.add(promptService.finalAnswerStageInstructionText());
        parts.add(PromptText.runtimeData("\n\n"));
        parts.add(promptService.dynamicContextPrefixText());
        StringBuilder facts = new StringBuilder("\n\n用户问题：").append(safe(userGoal)).append("\n\n已完成的任务：\n");
        if (completedTodos != null) {
            for (LangchainCompletedTodo todo : completedTodos) {
                facts.append("- ").append(safe(todo.getDescription())).append(": ");
                parts.add(PromptText.runtimeData(facts.toString()));
                facts.setLength(0);
                String result = safe(todo.getSummary()) + "\n";
                if (!todo.displayOutput().isBlank()) result += "  输出: " + todo.displayOutput() + "\n";
                parts.add(todo.attributedText(result));
            }
        }
        parts.add(PromptText.runtimeData(facts.toString()));
        return PromptText.concat(parts);
    }

    static String buildTodoUserMessage(AgentPromptService promptService,
                                       String userGoal,
                                       List<LangchainCompletedTodo> completedTodos,
                                       Map<String, String> datasetRefs,
                                       String todoDescription,
                                       List<ToolSpecification> toolSpecifications) {
        Set<String> names = resolveToolNames(toolSpecifications);
        return buildTodoUserMessage(
                promptService,
                userGoal,
                completedTodos,
                datasetRefs,
                todoDescription,
                toolSpecifications,
                promptService.renderToolCapabilities(names));
    }

    static String buildTodoUserMessage(AgentPromptService promptService,
                                       String userGoal,
                                       List<LangchainCompletedTodo> completedTodos,
                                       Map<String, String> datasetRefs,
                                       String todoDescription,
                                       List<ToolSpecification> toolSpecifications,
                                       String renderedToolCapabilities) {
        StringBuilder message = new StringBuilder();
        message.append(promptService.dagReactStageInstruction(renderedToolCapabilities)).append("\n\n");
        message.append(promptService.dynamicContextPrefix()).append("\n\n");
        message.append("用户目标：").append(safe(userGoal)).append("\n\n");
        Set<String> toolNames = resolveToolNames(toolSpecifications);
        if (!toolNames.isEmpty()) {
            message.append("当前可用工具：").append(String.join(", ", toolNames)).append("\n\n");
        }
        if (completedTodos != null) {
            for (LangchainCompletedTodo todo : completedTodos) {
                message.append("已完成: ").append(safe(todo.getDescription())).append("\n");
                message.append("摘要: ").append(safe(todo.getSummary())).append("\n");
                message.append("输出: ").append(safe(todo.displayOutput())).append("\n\n");
            }
        }
        message.append("当前任务: ").append(safe(todoDescription)).append("\n\n");
        if (datasetRefs != null && !datasetRefs.isEmpty()) {
            message.append("已有原始数据引用（来自前序工具输出，仅用于定位前序产出）:\n");
            datasetRefs.keySet().forEach(id -> message.append("  - ").append(id).append("\n"));
            message.append("\n");
        }
        return message.toString();
    }

    static String buildFinalUserMessage(AgentPromptService promptService,
                                        String userGoal,
                                        List<LangchainCompletedTodo> completedTodos) {
        StringBuilder context = new StringBuilder();
        context.append(promptService.finalAnswerStageInstruction()).append("\n\n");
        context.append(promptService.dynamicContextPrefix()).append("\n\n");
        context.append("用户问题：").append(safe(userGoal)).append("\n\n");
        context.append("已完成的任务：\n");
        if (completedTodos != null) {
            for (LangchainCompletedTodo todo : completedTodos) {
                context.append("- ").append(safe(todo.getDescription())).append(": ")
                        .append(safe(todo.getSummary())).append("\n");
                if (!todo.displayOutput().isBlank()) {
                    context.append("  输出: ").append(todo.displayOutput()).append("\n");
                }
            }
        }
        return context.toString();
    }

    static Map<String, String> newDatasetRefMap() {
        return new LinkedHashMap<>();
    }

    private static Set<String> resolveToolNames(List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return Set.of();
        }
        return specifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
