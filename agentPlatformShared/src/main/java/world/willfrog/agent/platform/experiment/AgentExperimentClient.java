package world.willfrog.agent.platform.experiment;

import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.racing.RacingExperimentClient;
import world.willfrog.racing.idl.EvaluationValue;
import world.willfrog.racing.idl.ExperimentUsageReceipt;
import world.willfrog.racing.idl.RecordExperimentUsageRequest;

/** 工具取值通过持久根绑定建立上下文，子运行沿用原根范围。 */
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

    /** 调用编号用于关联本次使用记录与工具调用。 */
    public ExperimentUsageReceipt recordUsage(String runId, String toolCallId, RecordExperimentUsageRequest request) {
        return experiments.recordUsage(contexts.current(runId, toolCallId), request);
    }
}
