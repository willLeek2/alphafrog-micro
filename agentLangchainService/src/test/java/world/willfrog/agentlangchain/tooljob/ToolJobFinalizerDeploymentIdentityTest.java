package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolJobFinalizerDeploymentIdentityTest {

    @Test
    void terminalHandlerRejectsRunOwnedByAnotherGenerationBeforeSideEffects() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRun run = new AgentRun();
        run.setId("run-old");
        run.setUserId("user-1");
        run.setDeploymentId("beta-a");
        run.setDeploymentGenerationId("gen-" + "b".repeat(64));
        when(runMapper.findByIdForDeployment(
                "run-old", "beta-a", "gen-" + "a".repeat(64))).thenReturn(null);
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService,
                mock(ToolJobRedisCache.class),
                mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class),
                mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class),
                mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class),
                runMapper,
                mock(AgentRunFinalizationService.class));
        DeploymentIdentityProvider provider = () -> new DeploymentIdentity(
                "beta-a", "gen-" + "a".repeat(64));
        ReflectionTestUtils.setField(finalizer, "deploymentIdentityProvider", provider);

        ToolJobFinalizer.FinalizerOutcome outcome = finalizer.handleTerminal(
                "run-old", new ToolJobAnchor(), "SUCCEEDED", null, true);

        assertThat(outcome.done()).isFalse();
        assertThat(outcome.reason()).isEqualTo("deployment_generation_inactive");
        verifyNoInteractions(anchorService);
    }
}
