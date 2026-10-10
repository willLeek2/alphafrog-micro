package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import world.willfrog.agent.platform.dataanalysis.SessionQueryAdmissionException;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ToolJobAnchorServiceTest {

    @Mock
    private AgentRunMapper agentRunMapper;

    @Mock
    private AgentRunFinalizationService finalizationService;

    private ToolJobAnchorService anchorService;

    @BeforeEach
    void setUp() {
        anchorService = new ToolJobAnchorService(agentRunMapper);
        ReflectionTestUtils.setField(anchorService, "finalizationService", finalizationService);
    }


    @Test
    void replayRenewalLocksBeforeCheckingOtherRunsAndRefreshesOnlyOriginalProof() {
        AgentRun run = new AgentRun();
        run.setUserId("user-1");
        ToolJobAnchor original = new ToolJobAnchor();
        original.setToolName("executeQuery");
        original.setOperationId("original-op");
        original.setAnchorState("PREPARING");
        run.setToolJobAnchorJson(original.toJson());
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(agentRunMapper.countInFlightExecuteQueryByUser("user-1", "run-1", "executeQuery", 600)).thenReturn(0);
        when(agentRunMapper.renewExecuteQueryReplayClaim("run-1", 7, "member-1", "original-op",
                "fp", "saved-request", 2, 5)).thenReturn(1);

        assertThat(anchorService.renewExecuteQueryReplayClaim("run-1", 7, "member-1", "original-op",
                "fp", "saved-request", 2, 5)).isTrue();

        var order = inOrder(agentRunMapper);
        order.verify(agentRunMapper).findById("run-1");
        order.verify(agentRunMapper).lockExecuteQuerySession("user-1");
        order.verify(agentRunMapper).findById("run-1");
        order.verify(agentRunMapper).countInFlightExecuteQueryByUser("user-1", "run-1", "executeQuery", 600);
        order.verify(agentRunMapper).renewExecuteQueryReplayClaim("run-1", 7, "member-1", "original-op",
                "fp", "saved-request", 2, 5);
    }

    @Test
    void replayBusySessionNeverRenewsOrReplacesTheOriginalAnchor() {
        AgentRun run = new AgentRun();
        run.setUserId("user-1");
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(agentRunMapper.countInFlightExecuteQueryByUser("user-1", "run-1", "executeQuery", 600)).thenReturn(1);

        assertThatThrownBy(() -> anchorService.renewExecuteQueryReplayClaim("run-1", 7, "member-1",
                "original-op", "fp", "saved-request", 2, 5))
                .isInstanceOf(SessionQueryAdmissionException.class)
                .satisfies(error -> {
                    var busy = (SessionQueryAdmissionException) error;
                    assertThat(busy.code()).isEqualTo("SESSION_QUERY_IN_PROGRESS");
                    assertThat(busy.retryable()).isTrue();
                });
        verify(agentRunMapper, never()).renewExecuteQueryReplayClaim(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void publishesCanceledEventOnlyAfterWorkspaceRefusalClosesRun() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("123");
        run.setStatus(AgentRunStatus.CANCELED);
        when(agentRunMapper.completeWorkspaceRefusal(
                "run-1", "run-1:tc-1:1", "fingerprint", "WORKSPACE_DIRTY"))
                .thenReturn(1);
        when(agentRunMapper.findById("run-1")).thenReturn(run);

        assertThat(anchorService.completeWorkspaceRefusal(
                "run-1", "run-1:tc-1:1", "fingerprint", "WORKSPACE_DIRTY"))
                .isTrue();

        verify(finalizationService).publishFinalizedEvent("run-1", "123", "CANCELED");
    }

    @Test
    void capturesTerminalStatusBeforeConcurrentResumeAndPublishesAfterCommit() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("123");
        run.setStatus(AgentRunStatus.FAILED);
        when(agentRunMapper.completeWorkspaceRefusal(
                "run-1", "run-1:tc-1:1", "fingerprint", "WORKSPACE_DIRTY"))
                .thenReturn(1);
        when(agentRunMapper.findById("run-1")).thenReturn(run);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(anchorService.completeWorkspaceRefusal(
                    "run-1", "run-1:tc-1:1", "fingerprint", "WORKSPACE_DIRTY"))
                    .isTrue();
            verifyNoInteractions(finalizationService);

            run.setStatus(AgentRunStatus.RECEIVED);
            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(finalizationService).publishFinalizedEvent("run-1", "123", "FAILED");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void doesNotPublishTerminalEventWhenRefusalCasLoses() {
        when(agentRunMapper.completeWorkspaceRefusal(
                "run-1", "run-1:tc-1:1", "fingerprint", "WORKSPACE_DIRTY"))
                .thenReturn(0);

        assertThat(anchorService.completeWorkspaceRefusal(
                "run-1", "run-1:tc-1:1", "fingerprint", "WORKSPACE_DIRTY"))
                .isFalse();

        verifyNoInteractions(finalizationService);
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
    void shouldReturnNullAfterWorkspaceRefusalClearsAnchorToEmptyJson() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setToolJobAnchorJson("{}");
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
    void shouldBindOldOwnerAndLeaseWhenClaimingAbortCleanup() {
        ToolJobAnchor cleanup = new ToolJobAnchor();
        cleanup.setAnchorState("CLEARING");
        Instant expectedLease = Instant.parse("2026-07-30T07:00:00Z");
        when(agentRunMapper.claimLiveDagBlockingPreparingAbortCleanup(
                eq("run-1"),
                anyString(),
                eq("run-1:tc-1:1"),
                eq("worker-a"),
                eq(expectedLease.toString()))).thenReturn(1);

        assertThat(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                "run-1",
                cleanup,
                "run-1:tc-1:1",
                "worker-a",
                expectedLease)).isTrue();

        verify(agentRunMapper).claimLiveDagBlockingPreparingAbortCleanup(
                eq("run-1"),
                anyString(),
                eq("run-1:tc-1:1"),
                eq("worker-a"),
                eq(expectedLease.toString()));
    }

    @Test
    void shouldRejectLiveDagPreparingAbortWithoutLease() {
        assertThat(anchorService.beginLiveDagBlockingPreparingAbort(
                "run-1", new ToolJobAnchor(), AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", null)).isFalse();
        assertThat(anchorService.completeLiveDagBlockingPreparingAbort(
                "run-1", AgentRunStatus.EXECUTING,
                "run-1:tc-1:1", "worker-a", null)).isFalse();
        assertThat(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                "run-1", new ToolJobAnchor(),
                "run-1:tc-1:1", "worker-a", null)).isFalse();

        verify(agentRunMapper, never()).beginLiveDagBlockingPreparingAbort(
                anyString(), anyString(), any(), anyString(), anyString(), anyString());
        verify(agentRunMapper, never()).completeLiveDagBlockingPreparingAbort(
                anyString(), any(), anyString(), anyString(), anyString());
        verify(agentRunMapper, never()).claimLiveDagBlockingPreparingAbortCleanup(
                anyString(), anyString(), anyString(), anyString(), anyString());
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

    // ===== 260818: CANCELED 终态收口专用 CAS（取消可落在恢复执行期） =====

    @Test
    void cancelFromStatusesDelegatesWithAnchorOperationIdFence() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-9:2");
        when(agentRunMapper.cancelToolJobAnchorFromStatuses(
                eq("run-1"), anyString(), eq(AgentRunStatus.CANCELED),
                eq("run-1:tc-9:2")))
                .thenReturn(1);

        assertThat(anchorService.cancelFromStatuses("run-1", anchor, AgentRunStatus.CANCELED))
                .isTrue();
        verify(agentRunMapper).cancelToolJobAnchorFromStatuses(
                eq("run-1"), anyString(), eq(AgentRunStatus.CANCELED), eq("run-1:tc-9:2"));
    }

    @Test
    void cancelFromStatusesRejectsNonCanceledTargetStatus() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-9:2");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> anchorService.cancelFromStatuses("run-1", anchor, AgentRunStatus.FAILED));
        verify(agentRunMapper, never()).cancelToolJobAnchorFromStatuses(
                anyString(), anyString(), any(), anyString());
    }

    // ===== 260819: 终态 Run 残留取消锚点兜底收口 =====

    @Test
    void closeResidualCanceledAnchorDelegatesWithOperationIdFence() {
        when(agentRunMapper.closeResidualCanceledAnchorOnTerminalRun("run-1", "run-1:tc-9:2"))
                .thenReturn(1);

        assertThat(anchorService.closeResidualCanceledAnchor("run-1", "run-1:tc-9:2"))
                .isTrue();
        verify(agentRunMapper).closeResidualCanceledAnchorOnTerminalRun("run-1", "run-1:tc-9:2");
    }

    @Test
    void closeResidualCanceledAnchorRejectsBlankOperationId() {
        assertThat(anchorService.closeResidualCanceledAnchor("run-1", null)).isFalse();
        assertThat(anchorService.closeResidualCanceledAnchor("run-1", " ")).isFalse();
        verifyNoInteractions(agentRunMapper);
    }

    @Test
    void closeResidualCanceledAnchorReturnsFalseWhenFenceRejects() {
        when(agentRunMapper.closeResidualCanceledAnchorOnTerminalRun("run-1", "run-1:tc-9:2"))
                .thenReturn(0);

        assertThat(anchorService.closeResidualCanceledAnchor("run-1", "run-1:tc-9:2"))
                .isFalse();
    }

    @Test
    void executePythonClaimDoesNotTakeSessionLock() {
        ToolJobAnchor python = new ToolJobAnchor();
        python.setToolName(ToolJobAnchor.EXECUTE_PYTHON_TOOL);
        python.setAnchorState("PREPARING");
        when(agentRunMapper.claimPreparingToolJobAnchor(eq("run-1"), anyString(), eq(AgentRunStatus.EXECUTING)))
                .thenReturn(1);

        assertThat(anchorService.claimPreparing("run-1", python, AgentRunStatus.EXECUTING)).isTrue();

        verify(agentRunMapper, never()).lockExecuteQuerySession(anyString());
        verify(agentRunMapper, never()).countInFlightExecuteQueryByUser(
                anyString(), anyString(), anyString(), anyInt());
        verify(agentRunMapper, never()).findById("run-1");
        verify(agentRunMapper).claimPreparingToolJobAnchor(eq("run-1"), anyString(), eq(AgentRunStatus.EXECUTING));
    }

    @Test
    void executeQueryClaimLocksThenCountsThenUpdates() {
        ToolJobAnchor query = new ToolJobAnchor();
        query.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        query.setAnchorState("PREPARING");
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-9");
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(agentRunMapper.countInFlightExecuteQueryByUser(
                "user-9", "run-1", ToolJobAnchor.EXECUTE_QUERY_TOOL, 600)).thenReturn(0);
        when(agentRunMapper.claimPreparingToolJobAnchor(eq("run-1"), anyString(), eq(AgentRunStatus.EXECUTING)))
                .thenReturn(1);

        assertThat(anchorService.claimPreparing("run-1", query, AgentRunStatus.EXECUTING)).isTrue();

        var order = inOrder(agentRunMapper);
        order.verify(agentRunMapper).findById("run-1");
        order.verify(agentRunMapper).lockExecuteQuerySession("user-9");
        order.verify(agentRunMapper).findById("run-1");
        order.verify(agentRunMapper).countInFlightExecuteQueryByUser(
                "user-9", "run-1", ToolJobAnchor.EXECUTE_QUERY_TOOL, 600);
        order.verify(agentRunMapper).claimPreparingToolJobAnchor(
                eq("run-1"), anyString(), eq(AgentRunStatus.EXECUTING));
    }

    @Test
    void sameRunDifferentQueryIsRetryableBusyAfterLockedReread() {
        ToolJobAnchor first = new ToolJobAnchor();
        first.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        first.setOperationId("run-1:first:1");
        first.setAnchorState("ATTACHED");
        ToolJobAnchor second = new ToolJobAnchor();
        second.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        second.setOperationId("run-1:second:1");
        AgentRun beforeLock = new AgentRun();
        beforeLock.setUserId("user-9");
        AgentRun afterLock = new AgentRun();
        afterLock.setUserId("user-9");
        afterLock.setToolJobAnchorJson(first.toJson());
        when(agentRunMapper.findById("run-1")).thenReturn(beforeLock, afterLock);

        assertThatThrownBy(() -> anchorService.claimPreparing("run-1", second, AgentRunStatus.EXECUTING))
                .isInstanceOf(SessionQueryAdmissionException.class).satisfies(thrown -> {
                    SessionQueryAdmissionException busy = (SessionQueryAdmissionException) thrown;
                    assertThat(busy.code()).isEqualTo("SESSION_QUERY_IN_PROGRESS");
                    assertThat(busy.retryable()).isTrue();
                });
        var order = inOrder(agentRunMapper);
        order.verify(agentRunMapper).findById("run-1");
        order.verify(agentRunMapper).lockExecuteQuerySession("user-9");
        order.verify(agentRunMapper).findById("run-1");
        verify(agentRunMapper, never()).claimPreparingToolJobAnchor(any(), any(), any());
    }

    @Test
    void consumedTerminalQueryAllowsFencedResumeToClaimNextQuery() {
        ToolJobAnchor previous = new ToolJobAnchor();
        previous.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        previous.setOperationId("run-1:previous:1");
        previous.setAnchorState("TERMINAL");
        previous.setResumeState("ACCEPTED");
        previous.setResultConsumed(true);
        previous.setResumeToken("resume-token");
        previous.setResumeLeaseVersion(2L);
        ToolJobAnchor next = new ToolJobAnchor();
        next.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        next.setOperationId("run-1:next:1");
        AgentRun run = new AgentRun();
        run.setUserId("user-9");
        run.setToolJobAnchorJson(previous.toJson());
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(agentRunMapper.claimPreparingToolJobAnchorFromResume(eq("run-1"), any(),
                eq("resume-token"), eq(2L))).thenReturn(1);

        assertThat(anchorService.claimPreparingFromResume("run-1", next, "resume-token", 2L)).isTrue();

        verify(agentRunMapper).lockExecuteQuerySession("user-9");
        verify(agentRunMapper).claimPreparingToolJobAnchorFromResume(eq("run-1"), any(),
                eq("resume-token"), eq(2L));
        verify(agentRunMapper, never()).claimPreparingToolJobAnchor(any(), any(), any());
    }

    @Test
    void executeQueryClaimRejectsWhenAnotherSessionQueryIsInFlight() {
        ToolJobAnchor query = new ToolJobAnchor();
        query.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        AgentRun run = new AgentRun();
        run.setUserId("user-9");
        when(agentRunMapper.findById("run-2")).thenReturn(run);
        when(agentRunMapper.countInFlightExecuteQueryByUser(
                "user-9", "run-2", ToolJobAnchor.EXECUTE_QUERY_TOOL, 600)).thenReturn(1);

        assertThatThrownBy(() -> anchorService.claimPreparing("run-2", query, AgentRunStatus.EXECUTING))
                .isInstanceOf(SessionQueryAdmissionException.class)
                .satisfies(thrown -> {
                    SessionQueryAdmissionException ex = (SessionQueryAdmissionException) thrown;
                    assertThat(ex.code()).isEqualTo("SESSION_QUERY_IN_PROGRESS");
                    assertThat(ex.retryable()).isTrue();
                });
        verify(agentRunMapper, never()).claimPreparingToolJobAnchor(anyString(), anyString(), any());
    }

    @Test
    void executeQueryClaimPassesConfiguredStaleSeconds() {
        ToolJobAnchor query = new ToolJobAnchor();
        query.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        AgentRun run = new AgentRun();
        run.setUserId("user-9");
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(agentRunMapper.countInFlightExecuteQueryByUser(
                "user-9", "run-1", ToolJobAnchor.EXECUTE_QUERY_TOOL, 900)).thenReturn(0);
        when(agentRunMapper.claimPreparingToolJobAnchor(eq("run-1"), anyString(), eq(AgentRunStatus.EXECUTING)))
                .thenReturn(1);

        new ToolJobAnchorService(agentRunMapper, 900)
                .claimPreparing("run-1", query, AgentRunStatus.EXECUTING);

        verify(agentRunMapper).countInFlightExecuteQueryByUser(
                "user-9", "run-1", ToolJobAnchor.EXECUTE_QUERY_TOOL, 900);
    }

    @Test
    void executeQueryClaimRejectsMissingUserId() {
        ToolJobAnchor query = new ToolJobAnchor();
        query.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        AgentRun run = new AgentRun();
        run.setUserId("  ");
        when(agentRunMapper.findById("run-1")).thenReturn(run);

        assertThatThrownBy(() -> anchorService.claimPreparing("run-1", query, AgentRunStatus.EXECUTING))
                .isInstanceOf(SessionQueryAdmissionException.class)
                .satisfies(thrown -> {
                    SessionQueryAdmissionException ex = (SessionQueryAdmissionException) thrown;
                    assertThat(ex.code()).isEqualTo("SESSION_USER_ID_MISSING");
                    assertThat(ex.retryable()).isFalse();
                });
        verify(agentRunMapper, never()).lockExecuteQuerySession(anyString());
        verify(agentRunMapper, never()).claimPreparingToolJobAnchor(anyString(), anyString(), any());
    }
}
