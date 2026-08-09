package world.willfrog.agentlangchain.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class AgentLangchainOrchestrator {

    public static final String LINEAR_PIPELINE_READY = "LINEAR_PIPELINE_READY";
    public static final String PROVIDER_DISABLED = "PROVIDER_DISABLED";
    public static final String LINEAR_PIPELINE_UNAVAILABLE = "LINEAR_PIPELINE_UNAVAILABLE";

    private final ObjectProvider<LangchainLinearRunPipeline> linearRunPipelineProvider;

    public AgentLangchainOrchestrator(ObjectProvider<LangchainLinearRunPipeline> linearRunPipelineProvider) {
        this.linearRunPipelineProvider = linearRunPipelineProvider;
    }

    /** 返回稳定的编排能力状态；该值用于 readiness 告警，不替代进程 liveness。 */
    public String orchestrationStatus(boolean providerEnabled) {
        if (!providerEnabled) {
            return PROVIDER_DISABLED;
        }
        return linearRunPipelineProvider.getIfAvailable() == null
                ? LINEAR_PIPELINE_UNAVAILABLE
                : LINEAR_PIPELINE_READY;
    }
}
