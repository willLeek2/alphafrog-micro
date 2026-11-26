package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolSpecification;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.workflow.PlanDependencyCheckReport;
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

    /** 只呈现保存报告中的检查事实；执行规则由原根的阶段正文提供。 */
    static PromptText dependencyCheckFacts(PlanDependencyCheckReport report,
                                          Integer currentPlanGeneration, String todoId) {
        if (report == null) {
            return PromptText.runtimeData("依赖检查：本计划没有可核实的检查报告；服务、实际业务调用和数据覆盖均未检查。");
        }
        StringBuilder facts = new StringBuilder("依赖检查事实：原检查时间 ")
                .append(report.getCheckedAt() == null ? "未知" : report.getCheckedAt()).append("。\n");
        Integer checkedGeneration = report.getCheckedPlanGeneration();
        if (checkedGeneration == null || currentPlanGeneration == null) {
            facts.append("原检查代次或当前执行代次未知；没有本轮重新检查的事实。\n");
        } else if (!checkedGeneration.equals(currentPlanGeneration)) {
            facts.append("沿用旧检查、恢复后未重查：检查代次 ").append(checkedGeneration)
                    .append("，当前执行代次 ").append(currentPlanGeneration).append("。\n");
        }
        boolean matched = false;
        if (report.getHints() != null) {
            for (PlanDependencyCheckReport.Hint hint : report.getHints()) {
                if (hint == null) continue;
                boolean sameTodo = hint.todoId() != null && !hint.todoId().isBlank() && hint.todoId().equals(todoId);
                boolean common = (hint.todoId() == null || hint.todoId().isBlank())
                        && ("execution.model.config".equals(hint.dependencyId())
                        || "report.omitted".equals(hint.dependencyId()));
                if (!sameTodo && !common) continue;
                matched |= sameTodo;
                if (facts.length() > 1600) {
                    facts.append("当前展示已省略后续检查事实；完整内容保存在原报告中。\n");
                    break;
                }
                String status = hint.status() == null ? "未检查" : switch (hint.status()) {
                    case AVAILABLE -> "可用";
                    case UNAVAILABLE -> "不可用";
                    case UNCHECKED -> "未检查";
                };
                if (hint.toolName() != null && !hint.toolName().isBlank()) facts.append(hint.toolName()).append("：");
                facts.append(hint.dependencyLabel() == null ? "依赖" : hint.dependencyLabel())
                        .append("：").append(status).append("；")
                        .append(dependencyBasisFact(hint.basis())).append('\n');
            }
        }
        if (!matched) facts.append("本报告未覆盖当前步骤；其工具依赖未检查。\n");
        if (report.getOmittedCount() > 0) facts.append("原报告省略提示数：").append(report.getOmittedCount()).append("。\n");
        return PromptText.runtimeData(facts.toString());
    }

    /** 旧报告原因可能含应用规则，模型投影只读取结构化依据，原报告保持不变。 */
    private static String dependencyBasisFact(PlanDependencyCheckReport.Basis basis) {
        if (basis == null) return "报告没有记录检查依据。";
        return switch (basis) {
            case REFERENCE_STATE -> "检查依据为已有服务引用的本地状态；实际业务调用未检查。";
            case CONFIG_PRESENT -> "检查依据为本地配置对象；实际模型请求未检查。";
            case TOOL_AVAILABILITY -> "检查依据为本运行实际开放的工具集合。";
            case NONE -> "报告没有记录已检查的依据。";
        };
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
