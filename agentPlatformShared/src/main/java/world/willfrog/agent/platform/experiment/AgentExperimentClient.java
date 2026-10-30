package world.willfrog.agent.platform.experiment;

import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.racing.RacingExperimentClient;
import world.willfrog.racing.idl.EvaluationValue;
import world.willfrog.racing.idl.ExperimentUsageReceipt;
import world.willfrog.racing.idl.RecordExperimentUsageRequest;

/** 工具取值始终从持久根绑定建立调用上下文，子运行不会另建实验范围。 */
@Component
public final class AgentExperimentClient {
    private final ExperimentRunContextFactory contexts;
    private final RacingExperimentClient experiments;

    public AgentExperimentClient(ExperimentRunContextFactory contexts, RacingExperimentClient experiments) {
        this.contexts = contexts;
        this.experiments = experiments;
    }

    public EvaluationValue evaluate(String runId, String experimentId,
            String ordinaryDefaultJson, String ordinaryDefaultSourceJson) {
        return experiments.evaluate(contexts.current(runId, "evaluate:" + runId + ":" + experimentId),
                experimentId, ordinaryDefaultJson, ordinaryDefaultSourceJson);
    }

    /** 调用编号只用于把这次使用和工具调用连起来，不作为全局去重键。 */
    public ExperimentUsageReceipt recordUsage(String runId, String toolCallId, RecordExperimentUsageRequest request) {
        return experiments.recordUsage(contexts.current(runId, toolCallId), request);
    }
}
