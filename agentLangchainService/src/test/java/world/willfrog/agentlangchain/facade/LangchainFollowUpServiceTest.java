package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LangchainFollowUpServiceTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final AgentMessageService messageService = mock(AgentMessageService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final LangchainLinearRunPipeline pipeline = mock(LangchainLinearRunPipeline.class);
    private final DeploymentIdentityProvider identityProvider =
            () -> new DeploymentIdentity("stable", GENERATION);
    private final LangchainFollowUpService service = new LangchainFollowUpService(
            readService, runMapper, eventService, messageService, stateStore, pipeline, identityProvider);

    @Test
    void sendMessageRejectsNonCompletedRun() {
        AgentRun running = run(AgentRunStatus.EXECUTING);
        when(runMapper.findByIdAndUserForDeployment("r1", "u1", "stable", GENERATION))
                .thenReturn(running);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);

        var response = service.sendMessage(SendAgentMessageRequest.newBuilder()
                .setUserId("u1")
                .setRunId("r1")
                .setContent("follow up")
                .setDeploymentId("stable")
                .setDeploymentGenerationId(GENERATION)
                .build());

        assertEquals("rejected", response.getStatus());
        verify(pipeline, never()).launchAsync(any());
    }

    @Test
    void sendMessageAcceptsCompletedRunAndRelaunchesPipeline() {
        AgentRun completed = run(AgentRunStatus.COMPLETED);
        AgentRun received = run(AgentRunStatus.RECEIVED);
        AgentRunMessage userMessage = new AgentRunMessage();
        userMessage.setId(99L);
        userMessage.setSeq(2);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(completed);
        when(runMapper.findByIdAndUserForDeployment("r1", "u1", "stable", GENERATION))
                .thenReturn(received);
        when(eventService.shouldMarkExpired(completed)).thenReturn(false);
        when(messageService.buildMetaJson(any(), any(), any(), any())).thenReturn("{}");
        when(messageService.createUserMessage(eq("r1"), eq("follow up"), any())).thenReturn(userMessage);
        when(eventService.nextTtlExpiresAt()).thenReturn(OffsetDateTime.now().plusHours(1));
        when(runMapper.admitFollowUpForDeployment(
                eq("r1"), eq("u1"), eq("stable"), eq(GENERATION), any())).thenReturn(1);

        var response = service.sendMessage(SendAgentMessageRequest.newBuilder()
                .setUserId("u1")
                .setRunId("r1")
                .setContent("follow up")
                .setDeploymentId("stable")
                .setDeploymentGenerationId(GENERATION)
                .build());

        assertEquals("accepted", response.getStatus());
        verify(runMapper).admitFollowUpForDeployment(
                eq("r1"), eq("u1"), eq("stable"), eq(GENERATION), any());
        verify(pipeline).launchAsync(received);
    }

    @Test
    void sendMessageRejectsInactiveGenerationBeforeAnyMessageWrite() {
        AgentRun completed = run(AgentRunStatus.COMPLETED);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(completed);

        var response = service.sendMessage(SendAgentMessageRequest.newBuilder()
                .setUserId("u1")
                .setRunId("r1")
                .setContent("follow up")
                .setDeploymentId("stable")
                .setDeploymentGenerationId("gen-" + "b".repeat(64))
                .build());

        assertEquals("rejected", response.getStatus());
        assertEquals("原测试部署已停用", response.getRejectReason());
        verify(readService, never()).requireWritableRun(anyString(), anyString());
        verify(messageService, never()).createUserMessage(anyString(), anyString(), anyString());
        verify(runMapper, never()).admitFollowUpForDeployment(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void sendMessageRejectsRunOwnedByAnotherGenerationBeforeAnyMessageWrite() {
        AgentRun completed = run(AgentRunStatus.COMPLETED);
        completed.setDeploymentGenerationId("gen-" + "b".repeat(64));
        when(readService.requireWritableRun("r1", "u1")).thenReturn(completed);

        var response = service.sendMessage(SendAgentMessageRequest.newBuilder()
                .setUserId("u1")
                .setRunId("r1")
                .setContent("follow up")
                .setDeploymentId("stable")
                .setDeploymentGenerationId(GENERATION)
                .build());

        assertEquals("rejected", response.getStatus());
        assertEquals("原测试部署已停用", response.getRejectReason());
        verify(readService, never()).requireWritableRun(anyString(), anyString());
        verify(messageService, never()).createUserMessage(anyString(), anyString(), anyString());
        verify(runMapper, never()).admitFollowUpForDeployment(
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void followUpTransactionCommitsBeforeScheduling() {
        AgentRun completed = run(AgentRunStatus.COMPLETED);
        AgentRun received = run(AgentRunStatus.RECEIVED);
        AgentRunMessage userMessage = new AgentRunMessage();
        userMessage.setId(99L);
        userMessage.setSeq(2);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(completed);
        when(runMapper.findByIdAndUserForDeployment("r1", "u1", "stable", GENERATION))
                .thenReturn(completed, received);
        when(eventService.shouldMarkExpired(completed)).thenReturn(false);
        when(messageService.buildMetaJson(any(), any(), any(), any())).thenReturn("{}");
        when(messageService.createUserMessage(eq("r1"), eq("follow up"), any()))
                .thenReturn(userMessage);
        when(eventService.nextTtlExpiresAt()).thenReturn(OffsetDateTime.now().plusHours(1));
        when(runMapper.admitFollowUpForDeployment(
                eq("r1"), eq("u1"), eq("stable"), eq(GENERATION), any())).thenReturn(1);

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        AtomicBoolean committedBeforeScheduling = new AtomicBoolean();
        doAnswer(invocation -> {
            committedBeforeScheduling.set(true);
            return null;
        }).when(transactionManager).commit(transactionStatus);
        doAnswer(invocation -> {
            assertEquals(true, committedBeforeScheduling.get());
            return null;
        }).when(pipeline).launchAsync(received);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);

        var response = service.sendMessage(SendAgentMessageRequest.newBuilder()
                .setUserId("u1")
                .setRunId("r1")
                .setContent("follow up")
                .setDeploymentId("stable")
                .setDeploymentGenerationId(GENERATION)
                .build());

        assertEquals("accepted", response.getStatus());
        assertEquals(true, committedBeforeScheduling.get());
        verify(transactionManager).commit(transactionStatus);
        verify(pipeline).launchAsync(received);
    }

    private AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setDeploymentId("stable");
        run.setDeploymentGenerationId(GENERATION);
        run.setStatus(status);
        run.setExt("{}");
        return run;
    }
}
