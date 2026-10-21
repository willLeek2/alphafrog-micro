package world.willfrog.agentlangchain.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agentlangchain.execution.AgentRunPipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLangchainOrchestratorTest {

    @Test
    void orchestrationStatus_shouldReportDisabledBeforeInspectingPipeline() {
        ObjectProvider<AgentRunPipeline> provider = mock(ObjectProvider.class);
        AgentLangchainOrchestrator orchestrator = new AgentLangchainOrchestrator(provider);

        assertThat(orchestrator.orchestrationStatus(false))
                .isEqualTo(AgentLangchainOrchestrator.PROVIDER_DISABLED);
    }

    @Test
    void orchestrationStatus_shouldReportReadyWhenEnabledPipelineExists() {
        ObjectProvider<AgentRunPipeline> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(AgentRunPipeline.class));
        AgentLangchainOrchestrator orchestrator = new AgentLangchainOrchestrator(provider);

        assertThat(orchestrator.orchestrationStatus(true))
                .isEqualTo(AgentLangchainOrchestrator.LINEAR_PIPELINE_READY);
    }

    @Test
    void orchestrationStatus_shouldReportUnavailableWhenEnabledPipelineMissing() {
        ObjectProvider<AgentRunPipeline> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        AgentLangchainOrchestrator orchestrator = new AgentLangchainOrchestrator(provider);

        assertThat(orchestrator.orchestrationStatus(true))
                .isEqualTo(AgentLangchainOrchestrator.LINEAR_PIPELINE_UNAVAILABLE);
    }
}
