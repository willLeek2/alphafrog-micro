package world.willfrog.agent.platform.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * Run 级阶段配置：包含 planning / execution / sub_agent 三阶段的 LLM 配置。
 */
@Data
public class RunStageConfig {
    private StageLlmConfig planning;
    private StageLlmConfig execution;
    @JsonAlias({"final_answer", "finalAnswer"})
    private StageLlmConfig finalAnswer;
    @JsonAlias({"search_judge", "searchJudge"})
    private StageLlmConfig searchJudge;
    private SubAgentStageConfig subAgent;
}
