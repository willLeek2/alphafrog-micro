package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.LangchainRunRejectedException;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentLangchainRunServiceTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    @Mock
    private ObjectProvider<AgentRunEventService> eventServiceProvider;
    @Mock
    private ObjectProvider<LangchainLinearRunPipeline> pipelineProvider;
    @Mock
    private AgentRunEventService eventService;
    @Mock
    private LangchainLinearRunPipeline pipeline;
    @Mock
    private LangchainRunConcurrencyScheduler scheduler;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentCreditService creditService;
    @Mock
    private UserDao userDao;
    @Mock
    private DeploymentIdentityProvider deploymentIdentityProvider;

    private AgentLangchainRunService runService;

    @BeforeEach
    void setUp() {
        runService = new AgentLangchainRunService(eventServiceProvider, pipelineProvider, scheduler, runMapper,
                creditService, userDao, deploymentIdentityProvider);
        lenient().when(creditService.hasPositiveCredit(anyString())).thenReturn(true);
        lenient().when(deploymentIdentityProvider.current())
                .thenReturn(new DeploymentIdentity("stable", GENERATION));
    }

    @Test
    void createRunLaunchesLinearPipeline() {
        when(eventServiceProvider.getIfAvailable()).thenReturn(eventService);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);
        LangchainRunConcurrencyScheduler.Reservation reservation =
                mock(LangchainRunConcurrencyScheduler.Reservation.class);
        when(scheduler.reserve()).thenReturn(reservation);

        AgentRun run = new AgentRun();
        run.setId("run123");
        run.setUserId("u1");
        run.setStatus(AgentRunStatus.RECEIVED);
        when(eventService.createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(),
                anyBoolean(), anyBoolean())).thenReturn(run);
        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("analyze stocks")
                .setDeploymentId("stable")
                .setDeploymentGenerationId(GENERATION)
                .setGenerateArtifacts(true)
                .build();

        var message = runService.createRun(request);
        assertEquals("run123", message.getId());
        verify(eventService).createRun(eq("u1"), eq("analyze stocks"), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), eq("stable"), eq(GENERATION),
                eq(true), anyBoolean());
        verify(pipeline).launchAsync(run, reservation);
    }

    @Test
    void createRunRejectedBeforeCreateRunDoesNotPersistRun() {
        when(eventServiceProvider.getIfAvailable()).thenReturn(eventService);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);
        when(scheduler.reserve()).thenThrow(new LangchainRunRejectedException("agent_run_executor_queue_full"));

        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("analyze stocks")
                .setDeploymentId("stable")
                .setDeploymentGenerationId(GENERATION)
                .build();

        assertThrows(LangchainRunRejectedException.class, () -> runService.createRun(request));
        verify(eventService, never()).createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(),
                anyBoolean(), anyBoolean());
        verify(pipeline, never()).launchAsync(any(), any());
    }

    @Test
    void createRunRequiresUserId() {
        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setMessage("hello")
                .build();
        assertThrows(IllegalArgumentException.class, () -> runService.createRun(request));
    }

    @Test
    void createRunRejectsRequestRoutedToDifferentDeploymentBeforeWriting() {
        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("hello")
                .setDeploymentId("beta-test")
                .setDeploymentGenerationId(GENERATION)
                .build();

        assertThrows(IllegalStateException.class, () -> runService.createRun(request));
        verify(eventServiceProvider, never()).getIfAvailable();
    }
}
