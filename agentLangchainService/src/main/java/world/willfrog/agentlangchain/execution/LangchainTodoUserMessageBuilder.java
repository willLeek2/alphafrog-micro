package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolSpecification;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.workflow.PlanDependencyCheckReport;

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

    /** 从保存报告生成本次请求的步骤说明，不更新原报告时间或检查状态。 */
    static String dependencyCheckInstruction(PlanDependencyCheckReport report,
                                             Integer currentPlanGeneration, String todoId) {
        if (report == null) {
            return "依赖检查：本计划尚无可核实的检查报告；服务、实际业务调用和数据覆盖均未检查。";
        }
        StringBuilder note = new StringBuilder("依赖检查事实：原检查时间 ")
                .append(report.getCheckedAt() == null ? "未知" : report.getCheckedAt()).append("。\n");
        Integer checkedGeneration = report.getCheckedPlanGeneration();
        if (checkedGeneration == null || currentPlanGeneration == null) {
            note.append("检查代次或当前代次未知，不能认定该报告是本轮重新检查的结果。\n");
        } else if (!checkedGeneration.equals(currentPlanGeneration)) {
            note.append("沿用旧检查、恢复后未重查：检查代次 ").append(checkedGeneration)
                    .append("，当前执行代次 ").append(currentPlanGeneration).append("。\n");
        }
        boolean matched = false;
        if (report.getHints() != null) {
            for (PlanDependencyCheckReport.Hint hint : report.getHints()) {
                if (hint == null || (hint.todoId() != null && !hint.todoId().isBlank()
                        && !hint.todoId().equals(todoId))) {
                    continue;
                }
                matched = true;
                if (note.length() > 1600) {
                    note.append("其余说明见保存报告，未显示的部分不能据此判断可用。\n");
                    break;
                }
                String status = hint.status() == null ? "未检查" : switch (hint.status()) {
                    case AVAILABLE -> "可用";
                    case UNAVAILABLE -> "不可用";
                    case UNCHECKED -> "未检查";
                };
                if (hint.toolName() != null && !hint.toolName().isBlank()) {
                    note.append(hint.toolName()).append("：");
                }
                note.append(hint.dependencyLabel() == null ? "依赖" : hint.dependencyLabel())
                        .append("——").append(status).append("；")
                        .append(hint.reason() == null ? "没有检查依据。" : hint.reason()).append('\n');
            }
        }
        if (!matched) {
            note.append("本报告未覆盖当前步骤；其工具依赖未检查。\n");
        }
        if (report.getOmittedCount() > 0) {
            note.append("报告有省略部分，不能据此认定依赖已经全部检查。\n");
        }
        note.append("预计工具仅供检查，请继续按当前工具目录和上下文选择；实际业务成败以该次工具结果为准。");
        return note.toString();
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
