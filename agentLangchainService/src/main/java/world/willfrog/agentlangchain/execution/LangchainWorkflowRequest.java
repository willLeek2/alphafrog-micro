package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Builder;
import lombok.Data;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次工作流执行的上下文：run 身份、模型、工具目录、用户目标。
 *
 * <p>这个对象在规划完成前就会造出来，LINEAR 和 DAG 两条执行器共用。
 * 名字不再带 Linear，避免读代码的人以为它绑定了线性模式。</p>
 */
@Data
@Builder
public class LangchainWorkflowRequest {
    private String runId;
    private String userId;
    /**
     * 这条 Run 当前是第几代计划；验收夹具按调用身份发模型回合时要带它。
     *
     * <p>不认识这个值的调用点可以不写，读的一方按 0 处理（{@link #planGenerationOrDefault()}）。</p>
     */
    private Integer planGeneration;
    private String userGoal;
    private String dialogueContext;
    private ChatModel model;
    private ChatModel planningModel;
    private ChatModel executionModel;
    private ChatModel finalAnswerModel;
    @Builder.Default
    private List<ToolSpecification> toolSpecifications = new ArrayList<>();
    private Integer maxTodos;
    private Integer maxToolRoundTrips;
    private Boolean webSearchEnabled;
    private Boolean codeInterpreterEnabled;
    private String planningEndpointName;
    private String planningModelName;
    private List<String> planningProviderOrder;
    /**
     * 验收夹具的结果放行策略；不带夹具编号的 Run 为空。
     *
     * <p>节点执行器在工具当场完成、直接给成员写终态那一步要用它（被点名的成员按失败收尾）。
     * 走结果接收路径的后台成员自己按 Run 查策略，不依赖这个字段。</p>
     */
    private AcceptanceReleasePolicy acceptanceReleasePolicy;

    public ChatModel planningModelOrDefault() {
        return planningModel == null ? model : planningModel;
    }

    public ChatModel executionModelOrDefault() {
        return executionModel == null ? model : executionModel;
    }

    public ChatModel finalAnswerModelOrDefault() {
        return finalAnswerModel == null ? model : finalAnswerModel;
    }

    /** 计划代际；调用点没写时按 0：夹具 Run 一定是双池那套，代际从 0 起。 */
    public int planGenerationOrDefault() {
        return planGeneration == null ? 0 : Math.max(0, planGeneration);
    }
}
