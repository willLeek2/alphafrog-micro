package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Builder;
import lombok.Data;

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

    public ChatModel planningModelOrDefault() {
        return planningModel == null ? model : planningModel;
    }

    public ChatModel executionModelOrDefault() {
        return executionModel == null ? model : executionModel;
    }

    public ChatModel finalAnswerModelOrDefault() {
        return finalAnswerModel == null ? model : finalAnswerModel;
    }
}
