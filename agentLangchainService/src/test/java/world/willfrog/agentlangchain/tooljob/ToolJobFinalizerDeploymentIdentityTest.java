package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agentlangchain.gateway.GatewayTestFixtures;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolJobFinalizerDeploymentIdentityTest {

    private static final String GENERATION_A = "gen-" + "a".repeat(64);
    private static final String GENERATION_B = "gen-" + "b".repeat(64);



    private static AgentRun runOwnedBy(String generationId) {
        AgentRun run = new AgentRun();
        run.setId("run-old");
        run.setUserId("user-1");
        run.setDeploymentId("beta-a");
        run.setDeploymentGenerationId(generationId);
        return run;
    }

    private static ToolJobFinalizer finalizer(ToolJobAnchorService anchorService,
                                              AgentRunMapper runMapper,
                                              RunOwnershipGateway ownershipGateway) {
        return new ToolJobFinalizer(
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
                mock(AgentRunFinalizationService.class),
                ownershipGateway);
    }
}
