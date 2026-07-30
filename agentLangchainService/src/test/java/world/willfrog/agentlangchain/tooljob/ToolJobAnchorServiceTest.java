package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ToolJobAnchorServiceTest {

    @Mock
    private AgentRunMapper agentRunMapper;

    private ToolJobAnchorService anchorService;

    @BeforeEach
    void setUp() {
        anchorService = new ToolJobAnchorService(agentRunMapper);
    }

    @Test
    void shouldLoadAnchorFromRun() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setTaskId("task-123");

        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(AgentRunStatus.WAITING_TOOL_JOB);
        run.setToolJobAnchorJson(anchor.toJson());

        when(agentRunMapper.findById("run-1")).thenReturn(run);

        ToolJobAnchor loaded = anchorService.loadAnchor("run-1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOperationId()).isEqualTo("run-1:tc-1:1");
        assertThat(loaded.getTaskId()).isEqualTo("task-123");
    }

    @Test
    void shouldReturnNullWhenRunNotFound() {
        when(agentRunMapper.findById("run-1")).thenReturn(null);
        assertThat(anchorService.loadAnchor("run-1")).isNull();
    }

    @Test
    void shouldReturnNullWhenAnchorJsonIsBlank() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setToolJobAnchorJson("");

        when(agentRunMapper.findById("run-1")).thenReturn(run);
        assertThat(anchorService.loadAnchor("run-1")).isNull();
    }

    @Test
    void shouldUpdateAnchorWithCasStatus() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");

        when(agentRunMapper.updateToolJobAnchor(eq("run-1"), anyString(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(1);

        boolean result = anchorService.updateAnchor("run-1", anchor, AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(result).isTrue();
    }

    @Test
    void shouldReturnFalseWhenCasUpdateFails() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        when(agentRunMapper.updateToolJobAnchor(eq("run-1"), anyString(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(0);

        boolean result = anchorService.updateAnchor("run-1", anchor, AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(result).isFalse();
    }

    @Test
    void shouldCasUpdateStatus() {
        when(agentRunMapper.casUpdateStatus("run-1", AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB))
                .thenReturn(1);

        boolean result = anchorService.casUpdateStatus("run-1", AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(result).isTrue();
    }

    @Test
    void shouldReturnFalseWhenCasStatusFails() {
        when(agentRunMapper.casUpdateStatus("run-1", AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB))
                .thenReturn(0);

        boolean result = anchorService.casUpdateStatus("run-1", AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(result).isFalse();
    }

    @Test
    void shouldBindStatusAndOperationToProofGatedSynchronousClear() {
        when(agentRunMapper.clearSynchronouslyCompletedToolJobAnchor(
                "run-1", AgentRunStatus.EXECUTING, "run-1:tc-1:1"))
                .thenReturn(1);

        assertThat(anchorService.clearSynchronouslyCompleted(
                "run-1", AgentRunStatus.EXECUTING, "run-1:tc-1:1")).isTrue();

        verify(agentRunMapper).clearSynchronouslyCompletedToolJobAnchor(
                "run-1", AgentRunStatus.EXECUTING, "run-1:tc-1:1");
    }

    @Test
    void shouldBindOwnerAndExactLeaseToLiveDagSynchronousClear() {
        Instant expectedLease = Instant.parse("2026-07-30T07:00:00Z");
        when(agentRunMapper.clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
                "run-1", "run-1:tc-1:1", "worker-a", expectedLease.toString()))
                .thenReturn(1);

        assertThat(anchorService.clearLiveDagBlockingSynchronouslyCompleted(
                "run-1", "run-1:tc-1:1", "worker-a", expectedLease)).isTrue();

        verify(agentRunMapper).clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
                "run-1", "run-1:tc-1:1", "worker-a", expectedLease.toString());
    }

    @Test
    void shouldBindOwnerAndExactPreviousLeaseInLiveDagUpdate() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setRunDisposition("DAG_BLOCKING_NO_RESUME");
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId("worker-a");
        Instant expectedLease = Instant.parse("2026-07-30T07:00:00Z");
        anchor.setBlockingLeaseUntil(expectedLease.plusSeconds(30));
        when(agentRunMapper.updateLiveDagBlockingToolJobAnchor(
                eq("run-1"), anyString(), eq(AgentRunStatus.EXECUTING),
                eq("run-1:tc-1:1"), eq("worker-a"), eq(expectedLease.toString())))
                .thenReturn(1);

        assertThat(anchorService.updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", expectedLease)).isTrue();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(agentRunMapper).updateLiveDagBlockingToolJobAnchor(
                eq("run-1"), json.capture(), eq(AgentRunStatus.EXECUTING),
                eq("run-1:tc-1:1"), eq("worker-a"), eq(expectedLease.toString()));
        ToolJobAnchor persisted = ToolJobAnchor.fromJson(json.getValue());
        assertThat(persisted.getBlockingOwnerId()).isEqualTo("worker-a");
        assertThat(persisted.getBlockingLeaseUntil()).isEqualTo(expectedLease.plusSeconds(30));
    }

    @Test
    void shouldRejectLiveDagUpdateWithoutPreviousLease() {
        ToolJobAnchor anchor = new ToolJobAnchor();

        assertThat(anchorService.updateLiveDagBlocking(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", null)).isFalse();

        verify(agentRunMapper, never()).updateLiveDagBlockingToolJobAnchor(
                anyString(), anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldBindOwnerAndExactLeaseInLiveDagPreparingAbortBegin() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition("DAG_BLOCKING_PREPARING_ABORT");
        Instant expectedLease = Instant.parse("2026-07-30T07:00:00Z");
        when(agentRunMapper.beginLiveDagBlockingPreparingAbort(
                eq("run-1"), anyString(), eq(AgentRunStatus.EXECUTING),
                eq("run-1:tc-1:1"), eq("worker-a"), eq(expectedLease.toString())))
                .thenReturn(1);

        assertThat(anchorService.beginLiveDagBlockingPreparingAbort(
                "run-1", anchor, AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", expectedLease)).isTrue();

        verify(agentRunMapper).beginLiveDagBlockingPreparingAbort(
                eq("run-1"), anyString(), eq(AgentRunStatus.EXECUTING),
                eq("run-1:tc-1:1"), eq("worker-a"), eq(expectedLease.toString()));
    }

    @Test
    void shouldBindOwnerAndExactLeaseInLiveDagPreparingAbortCompletion() {
        Instant expectedLease = Instant.parse("2026-07-30T07:00:00Z");
        when(agentRunMapper.completeLiveDagBlockingPreparingAbort(
                "run-1", AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", expectedLease.toString()))
                .thenReturn(1);

        assertThat(anchorService.completeLiveDagBlockingPreparingAbort(
                "run-1", AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", expectedLease)).isTrue();

        verify(agentRunMapper).completeLiveDagBlockingPreparingAbort(
                "run-1", AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", expectedLease.toString());
    }

    @Test
    void shouldRejectLiveDagPreparingAbortWithoutLease() {
        assertThat(anchorService.beginLiveDagBlockingPreparingAbort(
                "run-1", new ToolJobAnchor(), AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", null)).isFalse();
        assertThat(anchorService.completeLiveDagBlockingPreparingAbort(
                "run-1", AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", null)).isFalse();

        verify(agentRunMapper, never()).beginLiveDagBlockingPreparingAbort(
                anyString(), anyString(), any(), anyString(), anyString(), anyString());
        verify(agentRunMapper, never()).completeLiveDagBlockingPreparingAbort(
                anyString(), any(), anyString(), anyString(), anyString());
    }

    // ---- CAS predicate binding tests (verify SQL WHERE clause arguments) ----

    @Test
    void shouldBindTokenVersionAndStateInClaimCas() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeToken("claim-token-xyz");
        anchor.setResumeLeaseVersion(7);

        when(agentRunMapper.casUpdateAnchorResumeState(eq("run-1"), anyString(),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("claim-token-xyz"), eq(6L)))
                .thenReturn(1);

        boolean result = anchorService.casResumeState("run-1", anchor,
                AgentRunStatus.RECEIVED, "READY", "claim-token-xyz", 6L);
        assertThat(result).isTrue();
    }

    @Test
    void shouldFailClaimWhenTokenMismatchInPredicate() {
        // DB has token-v2, but caller passes token-v1 → 0 rows matched
        when(agentRunMapper.casUpdateAnchorResumeState(eq("run-1"), anyString(),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-v1"), eq(5L)))
                .thenReturn(0);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setResumeToken("token-v1");
        anchor.setResumeLeaseVersion(5);

        boolean result = anchorService.casResumeState("run-1", anchor,
                AgentRunStatus.RECEIVED, "READY", "token-v1", 5L);
        assertThat(result).isFalse();
    }

    @Test
    void shouldFailClaimWhenVersionMismatchInPredicate() {
        // DB has version 8, but caller passes version 5 → 0 rows
        when(agentRunMapper.casUpdateAnchorResumeState(eq("run-1"), anyString(),
                eq(AgentRunStatus.RECEIVED), eq("LAUNCHING"), eq("token-v1"), eq(5L)))
                .thenReturn(0);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setResumeToken("token-v1");
        anchor.setResumeLeaseVersion(5);

        boolean result = anchorService.casResumeState("run-1", anchor,
                AgentRunStatus.RECEIVED, "LAUNCHING", "token-v1", 5L);
        assertThat(result).isFalse();
    }

    @Test
    void shouldDoubleClaimOnlyFirstWins() {
        // Two callers race on same READY anchor; first gets rows=1, second gets rows=0
        when(agentRunMapper.casUpdateAnchorResumeState(eq("run-1"), anyString(),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-race"), eq(3L)))
                .thenReturn(1)  // first caller wins
                .thenReturn(0); // second caller loses

        ToolJobAnchor a1 = new ToolJobAnchor();
        a1.setResumeToken("token-race");
        a1.setResumeLeaseVersion(3);

        ToolJobAnchor a2 = new ToolJobAnchor();
        a2.setResumeToken("token-race");
        a2.setResumeLeaseVersion(3);

        boolean first = anchorService.casResumeState("run-1", a1,
                AgentRunStatus.RECEIVED, "READY", "token-race", 3L);
        boolean second = anchorService.casResumeState("run-1", a2,
                AgentRunStatus.RECEIVED, "READY", "token-race", 3L);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void shouldBindStateTokenVersionInConsumedClear() {
        when(agentRunMapper.clearToolJobAnchorWithToken(
                eq("run-1"), eq("CONSUMED"), eq("clear-token-99"), eq(12L)))
                .thenReturn(1);

        boolean result = anchorService.clearAnchorWithToken("run-1", "CONSUMED",
                "clear-token-99", 12L);
        assertThat(result).isTrue();
    }

    @Test
    void shouldFailConsumedClearWhenStateMismatch() {
        // Anchor was re-claimed (state no longer CONSUMED) → 0 rows
        when(agentRunMapper.clearToolJobAnchorWithToken(
                eq("run-1"), eq("CONSUMED"), eq("old-token"), eq(5L)))
                .thenReturn(0);

        boolean result = anchorService.clearAnchorWithToken("run-1", "CONSUMED",
                "old-token", 5L);
        assertThat(result).isFalse();
    }

    @Test
    void shouldFailConsumedClearWhenVersionMismatch() {
        // Version was bumped by a new claim → 0 rows
        when(agentRunMapper.clearToolJobAnchorWithToken(
                eq("run-1"), eq("CONSUMED"), eq("token-v1"), eq(3L)))
                .thenReturn(0);

        boolean result = anchorService.clearAnchorWithToken("run-1", "CONSUMED",
                "token-v1", 3L);
        assertThat(result).isFalse();
    }
}
