package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agentlangchain.acceptance.AcceptanceControlGate;
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureGate;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.LangchainRunRejectedException;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.control.dualpool.SchedulerVersionPolicy;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

import java.util.Map;

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
    @Mock
    private SchedulerVersionPolicy schedulerVersionPolicy;
    @Mock
    private DualPoolRunAdmissionRegistry dualPoolRunAdmissionRegistry;
    @Mock
    private AcceptanceFixtureGate acceptanceFixtureGate;
    @Mock
    private AcceptanceControlGate acceptanceControlGate;

    private AgentLangchainRunService runService;

    @BeforeEach
    void setUp() {
        runService = new AgentLangchainRunService(eventServiceProvider, pipelineProvider, scheduler, runMapper,
                creditService, userDao,
                world.willfrog.agentlangchain.gateway.GatewayTestFixtures.
                        withIdentity(runMapper, "stable", GENERATION),
                schedulerVersionPolicy, dualPoolRunAdmissionRegistry, acceptanceFixtureGate,
                acceptanceControlGate);
        lenient().when(schedulerVersionPolicy.versionForNewRun())
                .thenReturn(SchedulerVersionPolicy.LEGACY);
        // 版本家族判断按版本名如实回答：假的策略不能把双池版本说成不是双池，否则这里测的就不是创建路径了。
        lenient().when(schedulerVersionPolicy.isDualPoolFamily(anyString()))
                .thenAnswer(invocation -> {
                    String version = invocation.getArgument(0);
                    return SchedulerVersionPolicy.DUAL_POOL_V1.equals(version)
                            || SchedulerVersionPolicy.DUAL_POOL_V2.equals(version);
                });
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
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(), any(),
                anyString(), anyBoolean(), anyBoolean())).thenReturn(
                new AgentRunEventService.RunCreation(run, true));
        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("analyze stocks")
                .setDeploymentId("stable")
                .setDeploymentGenerationId(GENERATION)
                .setGenerateArtifacts(true)
                .build();

        LaneContext.setTrafficScopeId("lane-a");
        var message = runService.createRun(request);
        LaneContext.clear();
        assertEquals("run123", message.getId());
        verify(eventService).createRun(eq("u1"), eq("analyze stocks"), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), eq("stable"), eq(GENERATION), eq("lane-a"),
                eq(SchedulerVersionPolicy.LEGACY), eq(true), anyBoolean());
        verify(pipeline).launchAsync(run, reservation);
    }

    @Test
    void dualPoolRunFreezesVersionWithoutReservingLegacyScheduler() {
        when(schedulerVersionPolicy.versionForNewRun())
                .thenReturn(SchedulerVersionPolicy.DUAL_POOL_V1);
        when(dualPoolRunAdmissionRegistry.admitNewRun(eq("run-dual"), anyString())).thenReturn(true);
        when(eventServiceProvider.getIfAvailable()).thenReturn(eventService);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);
        AgentRun run = new AgentRun();
        run.setId("run-dual");
        run.setUserId("u1");
        run.setSchedulerVersion(SchedulerVersionPolicy.DUAL_POOL_V1);
        run.setStatus(AgentRunStatus.RECEIVED);
        when(eventService.createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(), any(),
                eq(SchedulerVersionPolicy.DUAL_POOL_V1), anyBoolean(), anyBoolean())).thenReturn(
                new AgentRunEventService.RunCreation(run, true));

        runService.createRun(CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("dual pool")
                .build());

        verify(scheduler, never()).reserve();
        verify(dualPoolRunAdmissionRegistry).admitNewRun(eq("run-dual"), anyString());
        verify(pipeline).launchAsync(run, null);
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
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(), any(),
                anyString(), anyBoolean(), anyBoolean());
        verify(pipeline, never()).launchAsync(any(), any());
    }

    /**
     * 带了验收夹具编号但夹具用不了：当场报错，既不预留调度名额，也不创建 Run。
     * 「不创建」是硬要求——静默退回普通规划会让一次本该失败的验收看起来跑过了。
     */
    @Test
    void rejectedAcceptanceFixtureStopsBeforeAnyRunIsCreated() {
        when(eventServiceProvider.getIfAvailable()).thenReturn(eventService);
        when(acceptanceFixtureGate.admitRequestContext(any(), any(), any()))
                .thenThrow(new LangchainRunRejectedException("acceptance_fixture_expired: fx-1",
                        "acceptance_fixture_expired"));

        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("acceptance run")
                .setContextJson("{\"acceptanceFixtureId\":\"fx-1\"}")
                .build();

        assertThrows(LangchainRunRejectedException.class, () -> runService.createRun(request));
        verify(scheduler, never()).reserve();
        verify(eventService, never()).createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(), any(),
                anyString(), anyBoolean(), anyBoolean());
        verify(pipeline, never()).launchAsync(any(), any());
    }

    @Test
    void createRunRequiresUserId() {
        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setMessage("hello")
                .build();
        assertThrows(IllegalArgumentException.class, () -> runService.createRun(request));
    }

    /**
     * 幂等键命中旧 Run 时：本次不启动 pipeline、不占用业务准入名额，为这次临时预留下的名额当场还回去。
     * 同一次请求被重复提交，不应该让模型和工具再跑一遍。
     */
    @Test
    void idempotentReadBackNeverLaunchesThePipelineAgain() {
        when(eventServiceProvider.getIfAvailable()).thenReturn(eventService);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);
        LangchainRunConcurrencyScheduler.Reservation reservation =
                mock(LangchainRunConcurrencyScheduler.Reservation.class);
        when(scheduler.reserve()).thenReturn(reservation);
        AgentRun existing = new AgentRun();
        existing.setId("run-original");
        existing.setUserId("u1");
        when(eventService.createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(), any(),
                anyString(), anyBoolean(), anyBoolean())).thenReturn(
                new AgentRunEventService.RunCreation(existing, false));

        var message = runService.createRun(CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("analyze stocks")
                .build());

        assertEquals("run-original", message.getId());
        verify(pipeline, never()).launchAsync(any(), any());
        verify(dualPoolRunAdmissionRegistry, never()).admitNewRun(anyString(), anyString());
        verify(scheduler).release(reservation);
    }

    @Test
    void createRunIgnoresCallerDeploymentIdentityAndUsesReceivingAgentIdentity() {
        when(eventServiceProvider.getIfAvailable()).thenReturn(eventService);
        AgentRun run = new AgentRun();
        run.setId("run-local");
        run.setUserId("u1");
        when(eventService.createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(), any(),
                anyString(), anyBoolean(), anyBoolean())).thenReturn(
                new AgentRunEventService.RunCreation(run, true));
        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setMessage("hello")
                .setDeploymentId("beta-test")
                .setDeploymentGenerationId(GENERATION)
                .build();

        runService.createRun(request);
        verify(eventService).createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), eq("stable"), eq(GENERATION), isNull(),
                eq(SchedulerVersionPolicy.LEGACY), anyBoolean(), anyBoolean());
    }

    @Test
    void describeFailureWalksBlankOuterMessageToCause() {
        RuntimeException cause = new IllegalArgumentException("argument type mismatch");
        org.mybatis.spring.MyBatisSystemException wrapped =
                new org.mybatis.spring.MyBatisSystemException(cause);
        String text = AgentLangchainRunService.describeFailure(wrapped);
        assertTrue(text.contains("MyBatisSystemException"));
        assertTrue(text.contains("argument type mismatch"));
    }

    @Test
    void dualPoolAdmitFailureWritesCauseChainIntoEnqueueFailedEvent() {
        when(schedulerVersionPolicy.versionForNewRun())
                .thenReturn(SchedulerVersionPolicy.DUAL_POOL_V2);
        when(eventServiceProvider.getIfAvailable()).thenReturn(eventService);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);
        AgentRun run = new AgentRun();
        run.setId("run-v2");
        run.setUserId("u1");
        run.setSchedulerVersion(SchedulerVersionPolicy.DUAL_POOL_V2);
        run.setStatus(AgentRunStatus.RECEIVED);
        when(eventService.createRun(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), anyInt(), anyBoolean(), any(), anyString(), anyString(), any(),
                eq(SchedulerVersionPolicy.DUAL_POOL_V2), anyBoolean(), anyBoolean())).thenReturn(
                new AgentRunEventService.RunCreation(run, true));
        org.mybatis.spring.MyBatisSystemException failure =
                new org.mybatis.spring.MyBatisSystemException(
                        new IllegalArgumentException("argument type mismatch"));
        when(dualPoolRunAdmissionRegistry.admitNewRun(eq("run-v2"), anyString())).thenThrow(failure);

        assertThrows(org.mybatis.spring.MyBatisSystemException.class, () ->
                runService.createRun(CreateAgentRunRequest.newBuilder()
                        .setUserId("u1")
                        .setMessage("v2")
                        .build()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-v2"), eq("u1"), eq("RUN_ENQUEUE_FAILED"), captor.capture());
        assertTrue(String.valueOf(captor.getValue().get("reason")).contains("argument type mismatch"));
        verify(pipeline, never()).launchAsync(any(), any());
        verify(dualPoolRunAdmissionRegistry).forgetFailedAdmission("run-v2");
    }
}
