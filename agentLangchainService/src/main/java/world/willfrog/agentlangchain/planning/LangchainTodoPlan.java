package world.willfrog.agentlangchain.planning;

import lombok.Builder;
import lombok.Data;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.PlanDependencyCheckReport;
import world.willfrog.agent.workflow.TodoItem;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class LangchainTodoPlan {

    private String analysis;
    @Builder.Default
    private List<TodoItem> items = new ArrayList<>();
    @Builder.Default
    private List<String> extractedEntities = new ArrayList<>();
    private PlanExecutionMode executionMode;
    /** 计划形成时保存的检查事实；旧计划可以没有报告。 */
    private PlanDependencyCheckReport dependencyCheck;
}
