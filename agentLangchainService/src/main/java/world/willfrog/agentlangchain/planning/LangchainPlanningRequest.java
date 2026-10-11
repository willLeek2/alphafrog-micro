package world.willfrog.agentlangchain.planning;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Builder;
import lombok.Data;
import world.willfrog.agent.workflow.PlanExecutionMode;

import java.util.List;

@Data
@Builder
public class LangchainPlanningRequest {

    private String runId;
    private String userId;
    /**
     * 这次规划属于第几代计划；认不出来的调用点可以不写（验收夹具按 0 处理）。
     *
     * <p>夹具按「调用身份」发模型回合，规划的身份里带上它：同一条 Run 重新规划之后是新一代，
     * 不能与上一代的规划共享同一个身份。</p>
     */
    private Integer planGeneration;
    private String userGoal;
    private String dialogueContext;
    private ChatModel model;
    private List<ToolSpecification> toolSpecifications;
    private PlanExecutionMode executionMode;
    private Integer maxTodos;
    /** Resolved planning endpoint (diagnostics). */
    private String planningEndpointName;
    /** Resolved planning model (diagnostics). */
    private String planningModelName;
    /** Provider order sent to OpenRouter for planning (diagnostics). */
    private List<String> planningProviderOrder;
}
