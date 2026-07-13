package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.tooljob.ToolJobAnchorService;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LangchainRunControlServiceTest {

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentEventService eventService = mock(AgentEventService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);
    private final LangchainLinearRunPipeline pipeline = mock(LangchainLinearRunPipeline.class);
    private final AgentRunCreditSettlementService creditSettlementService = mock(AgentRunCreditSettlementService.class);
    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final LangchainRunControlService service = new LangchainRunControlService(
            readService, runMapper, eventService, stateStore, observabilityService, pipeline,
            creditSettlementService, anchorService);

    @Test
    void cancelFlushesObservabilityAndMarksCanceled() {
        AgentRun running = run(AgentRunStatus.EXECUTING);
        AgentRun canceled = run(AgentRunStatus.CANCELED);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(canceled);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.CANCELED))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));

        var response = service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        assertEquals("CANCELED", response.getStatus());
        verify(observabilityService).forceFlush("r1");
        verify(runMapper).updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.CANCELED), anyString(), eq(false), isNull());
        verify(eventService).append(eq("r1"), eq("u1"), eq("CANCELED"), anyMap());
    }

    @Test
    void resumeRelaunchesPipelineForWaitingRun() {
        AgentRun waiting = run(AgentRunStatus.WAITING);
        AgentRun received = run(AgentRunStatus.RECEIVED);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(waiting);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(received);
        when(eventService.nextTtlExpiresAt()).thenReturn(OffsetDateTime.now().plusHours(1));

        var response = service.resumeRun(ResumeAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        assertEquals("RECEIVED", response.getStatus());
        verify(runMapper).resetForResume(eq("r1"), eq("u1"), any());
        verify(pipeline).launchAsync(received);
    }

    @Test
    void cancelWithActiveAnchorReturnsCanceledStatusEvenThoughDbStaysWaiting() {
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        AgentRun refreshed = run(AgentRunStatus.WAITING_TOOL_JOB); // DB still WAITING
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);
        when(readService.requireReadableRun("r1", "u1")).thenReturn(refreshed);
        when(observabilityService.attachObservabilityToSnapshot("r1", "{}", AgentRunStatus.CANCELED))
                .thenReturn("{\"observability\":{}}");
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));

        // Anchor exists and CAS succeeds
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("r1:tc-1:1");
        when(anchorService.loadAnchor("r1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("r1"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);

        var response = service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build());

        // Oracle: API response shows CANCELED even though DB is WAITING_TOOL_JOB
        assertEquals("CANCELED", response.getStatus());
        // Oracle: anchor disposition was set
        verify(anchorService).updateAnchor(eq("r1"), argThat(a ->
                !a.isAutoResume() && "CANCELED".equals(a.getRunDisposition())),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        // Oracle: snapshot updated with current status (not CANCELED)
        verify(runMapper).updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.WAITING_TOOL_JOB),
                anyString(), eq(false), isNull());
    }

    @Test
    void cancelThrowsWhenAnchorCasFails() {
        AgentRun running = run(AgentRunStatus.WAITING_TOOL_JOB);
        when(readService.requireWritableRun("r1", "u1")).thenReturn(running);

        // Anchor exists but CAS returns false
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("r1:tc-1:1");
        when(anchorService.loadAnchor("r1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("r1"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(false);

        // Oracle: throws to prevent capacity leak (fail-closed)
        assertThrows(IllegalStateException.class, () ->
                service.cancelRun(CancelAgentRunRequest.newBuilder().setUserId("u1").setId("r1").build()));
    }

    private AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setStatus(status);
        run.setSnapshotJson("{}");
        return run;
    }
}
