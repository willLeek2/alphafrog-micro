package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolJobResumeServiceTest {

    @Mock
    private ToolJobAnchorService anchorService;

    @Mock
    private ToolJobRedisCache redisCache;

    @Mock
    private ToolJobResumeLauncher resumeLauncher;

    @Mock
    private ToolJobConfig config;

    private ToolJobResumeService resumeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(config.getLaunchingStaleSeconds()).thenReturn(120L);
        lenient().when(config.getResumeLauncherLeaseSeconds()).thenReturn(30L);
        resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, objectMapper, "owner-a");
        setLauncher(resumeService, resumeLauncher);
    }

    private void setLauncher(ToolJobResumeService service, ToolJobResumeLauncher launcher) {
        try {
            var field = ToolJobResumeService.class.getDeclaredField("resumeLauncher");
            field.setAccessible(true);
            field.set(service, launcher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- entry guards ----

    @Test
    void shouldReturnFalseWhenAnchorNotFound() {
        when(anchorService.loadAnchor("run-1")).thenReturn(null);
        assertThat(resumeService.tryResume("run-1")).isFalse();
    }

    @Test
    void shouldCleanupRedisForAlreadyConsumed() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("CONSUMED");
        anchor.setResumeToken("consumed-token");
        anchor.setResumeLeaseVersion(10);
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.clearAnchorWithToken(eq("run-1"), eq("CONSUMED"),
                eq("consumed-token"), eq(10L))).thenReturn(true);

        assertThat(resumeService.tryResume("run-1")).isTrue();
        verify(redisCache).removeDue("run-1");
        verify(redisCache).deletePendingCache("run-1");
    }

    // ---- 260818 (grace round-2): 取消锚点不自动恢复，CONSUMED 清理不受门控影响 ----

    @Test
    void shouldNotResumeCanceledAnchorEvenWithExpiredLease() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("ACCEPTED");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setResumeToken("cancel-token");
        anchor.setResumeLeaseVersion(27);
        anchor.setResumeLauncherOwnerId("owner-a");
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().minusSeconds(60));
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);

        assertThat(resumeService.tryResume("run-1")).isFalse();
        verify(anchorService, never()).takeoverExpiredResumeLauncher(
                any(), any(), any(), any(), anyLong(), any(), any(), anyLong(), anyLong());
        verify(anchorService, never()).claimResumeLauncher(
                any(), any(), any(), any(), any(), anyLong(), any(), anyLong());
        verifyNoInteractions(resumeLauncher);
    }

    @Test
    void consumedCleanupStillRunsWithoutAutoResume() {
        // CONSUMED 的幂等清理在 autoResume 门控之前执行——取消后的收尾清理不能被挡。
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("CONSUMED");
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setResumeToken("consumed-token");
        anchor.setResumeLeaseVersion(12);
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.clearAnchorWithToken(eq("run-1"), eq("CONSUMED"),
                eq("consumed-token"), eq(12L))).thenReturn(true);

        assertThat(resumeService.tryResume("run-1")).isTrue();
        verify(anchorService).clearAnchorWithToken(eq("run-1"), eq("CONSUMED"),
                eq("consumed-token"), eq(12L));
        verify(redisCache).removeDue("run-1");
        verify(redisCache).deletePendingCache("run-1");
    }

    // ---- READY → LAUNCHING (first launch) ----

    @Test
    void shouldCasReadToLaunchingAndLaunch() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setTerminalStderrPreview("Traceback: bad date");
        anchor.setTerminalErrorCode("execution_failed");
        anchor.setTerminalExitReason("NON_ZERO_EXIT");
        anchor.setTerminalRetryable(false);
        anchor.setPythonRepairAttempt(1);
        anchor.setPythonRepairPending(true);
        anchor.setPythonRepairExhausted(true);
        anchor.setPythonFailedRequestFingerprints(List.of("sha256:failed-1"));
        anchor.setCreateRequestJson("{\"code\":\"print(1)\"}");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.claimResumeLauncher(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED),
                eq("token-v1"), eq(5L), eq("owner-a"), eq(30L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isTrue();
        assertThat(anchor.getResumeState()).isIn("LAUNCHING", "ACCEPTED");
        assertThat(anchor.getResumeLeaseVersion()).isEqualTo(6); // incremented in claim

        ArgumentCaptor<ToolJobResumeContext> ctxCaptor = ArgumentCaptor.forClass(ToolJobResumeContext.class);
        verify(resumeLauncher).launch(eq("run-1"), ctxCaptor.capture());
        ToolJobResumeContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getRunId()).isEqualTo("run-1");
        assertThat(ctx.getTodoId()).isEqualTo("todo_3");
        assertThat(ctx.getCompletedTodos()).hasSize(2);
        assertThat(ctx.getCompletedTodos().get(0).getTodoId()).isEqualTo("todo_1");
        assertThat(ctx.getCompletedTodos().get(0).getDescription()).isEqualTo("fetch data");
        assertThat(ctx.getToolCallsUsed()).isEqualTo(2);
        assertThat(ctx.isTerminalSuccess()).isTrue();
        assertThat(ctx.getResumeToken()).isEqualTo("token-v1");
        assertThat(ctx.getResumeLeaseVersion()).isEqualTo(6L);
        assertThat(ctx.getResumeLauncherOwnerId()).isEqualTo("owner-a");
        assertThat(ctx.getTerminalStderrPreview()).isEqualTo("Traceback: bad date");
        assertThat(ctx.getTerminalErrorCode()).isEqualTo("execution_failed");
        assertThat(ctx.getTerminalExitReason()).isEqualTo("NON_ZERO_EXIT");
        assertThat(ctx.getTerminalRetryable()).isFalse();
        assertThat(ctx.getPythonRepairAttempt()).isEqualTo(1);
        assertThat(ctx.isPythonRepairPending()).isTrue();
        assertThat(ctx.isPythonRepairExhausted()).isTrue();
        assertThat(ctx.getPythonFailedCodePreview()).isEqualTo("print(1)");
        assertThat(ctx.getPythonFailedRequestFingerprints()).containsExactly("sha256:failed-1");
    }

    @Test
    void shouldNotLaunchWhenCasFails() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.claimResumeLauncher(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED),
                eq("token-v1"), eq(5L), eq("owner-a"), eq(30L))).thenReturn(false);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        verify(resumeLauncher, never()).launch(any(), any());
    }

    @Test
    void shouldRollbackToReadWhenLaunchRejected() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.claimResumeLauncher(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED),
                eq("token-v1"), eq(5L), eq("owner-a"), eq(30L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(false);
        // Rollback: expected LAUNCHING + token-v1 + claimedVersion 6
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("LAUNCHING"), eq("token-v1"), eq(6L))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        assertThat(anchor.getResumeState()).isEqualTo("READY");
        assertThat(anchor.getResumeLeaseVersion()).isEqualTo(7); // monotonic: claimedVersion+1, not reverted
    }

    // ---- 260818 (grace round-3): 回滚 CAS 输掉（如取消先落库）后无后续启动副作用 ----

    @Test
    void launchRejectedWithLosingRollbackProducesNoFurtherLaunchSideEffects() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.claimResumeLauncher(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED),
                eq("token-v1"), eq(5L), eq("owner-a"), eq(30L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(false);
        // 回滚 CAS 返回 0 行——数据库里锚点已被取消线程改成 autoResume=false+CANCELED
        //（或被其他 owner 接管）。旧对象必须退场，不得再产生任何启动副作用。
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("LAUNCHING"), eq("token-v1"), eq(6L)))
                .thenReturn(false);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        // 恰好一次启动尝试；回滚输掉后没有重试启动、没有二次 claim
        verify(resumeLauncher, times(1)).launch(eq("run-1"), any(ToolJobResumeContext.class));
        verify(anchorService, times(1)).claimResumeLauncher(
                any(), any(), any(), any(), any(), anyLong(), any(), anyLong());
        verifyNoInteractions(redisCache);
    }

    @Test
    void shouldRollbackToReadWhenLaunchThrows() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.claimResumeLauncher(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED),
                eq("token-v1"), eq(5L), eq("owner-a"), eq(30L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class)))
                .thenThrow(new RuntimeException("pipeline error"));
        // Rollback: expected LAUNCHING + token-v1 + claimedVersion 6
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("LAUNCHING"), eq("token-v1"), eq(6L))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        assertThat(anchor.getResumeState()).isEqualTo("READY");
        assertThat(anchor.getResumeLeaseVersion()).isEqualTo(7); // monotonic: claimedVersion+1
    }

    // ---- LAUNCHING reentry (crash recovery, §9.11) ----

    @Test
    void shouldNotRelaunchUnexpiredDurableLease() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeClaimedAt(java.time.Instant.now());
        anchor.setResumeLauncherOwnerId("owner-a");
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().plusSeconds(30));
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        verify(resumeLauncher, never()).launch(any(), any());
        verify(anchorService, never()).takeoverExpiredResumeLauncher(
                any(), any(), any(), any(), anyLong(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void shouldHandleReenterLaunchingWhenLauncherAbsent() {
        // Remove launcher to simulate not-yet-wired state
        try {
            var field = ToolJobResumeService.class.getDeclaredField("resumeLauncher");
            field.setAccessible(true);
            field.set(resumeService, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeClaimedAt(java.time.Instant.now()); // active, not stale
        anchor.setResumeLauncherOwnerId("owner-a");
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().plusSeconds(30));
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
    }

    // ---- CONSUMED and cleanup ----

    @Test
    void shouldMarkConsumedAndFullCleanup() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setUsagePersisted(true);
        anchor.setTerminalEventEmitted(true);
        anchor.setResumeToken("test-token-123");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.RECEIVED)))
                .thenReturn(true);
        when(anchorService.clearAnchorWithToken(eq("run-1"), eq("CONSUMED"),
                eq("test-token-123"), eq(5L))).thenReturn(true);

        resumeService.markConsumed("run-1");

        assertThat(anchor.getResumeState()).isEqualTo("CONSUMED");
        assertThat(anchor.isResultConsumed()).isTrue();
        verify(redisCache).removeDue("run-1");
        verify(redisCache).deletePendingCache("run-1");
    }

    @Test
    void shouldDeferCleanupWhenUsageNotPersisted() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setUsagePersisted(false); // not yet
        anchor.setTerminalEventEmitted(false);
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.RECEIVED)))
                .thenReturn(true);

        resumeService.markConsumed("run-1");

        assertThat(anchor.getResumeState()).isEqualTo("CONSUMED");
        verify(redisCache, never()).removeDue("run-1");
        verify(redisCache, never()).deletePendingCache("run-1");
        verify(anchorService, never()).clearAnchorWithToken(any(), any(), any(), anyLong());
    }

    @Test
    void acceptedHandoffPersistsNextTodoAndKeepsOldAnchor() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeLauncherOwnerId("owner-a");
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().plusSeconds(30));
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.acceptResumeHandoff(eq("run-1"), any(ToolJobAnchor.class),
                eq("token-v1"), eq(5L), eq("owner-a"), eq(30L)))
                .thenReturn(true);
        CompletedTodoRecord completed = new CompletedTodoRecord();
        completed.setTodoId("todo_3");
        completed.setSequence(3);
        completed.setOutput("terminal-result");
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setResumeToken("token-v1");
        context.setResumeLeaseVersion(5);
        context.setResumeLauncherOwnerId("owner-a");
        context.setTodoId("todo_4");
        context.setTodoSequence(4);
        context.setCompletedTodos(List.of(completed));
        context.setToolCallsUsed(3);
        context.setPythonRepairAttempt(2);
        context.setPythonRepairPending(true);
        context.setPythonRepairExhausted(true);
        context.setPythonFailedRequestFingerprints(List.of("sha256:failed-1", "sha256:failed-2"));
        context.setResultConsumed(true);

        assertThat(resumeService.markHandoffAccepted("run-1", context)).isTrue();
        assertThat(anchor.getResumeState()).isEqualTo("ACCEPTED");
        assertThat(anchor.isResultConsumed()).isTrue();
        assertThat(anchor.getTodoId()).isEqualTo("todo_4");
        assertThat(anchor.getSequence()).isEqualTo(4);
        assertThat(anchor.getCompletedTodosJson()).contains("todo_3", "terminal-result");
        assertThat(anchor.getPythonRepairAttempt()).isEqualTo(2);
        assertThat(anchor.isPythonRepairPending()).isTrue();
        assertThat(anchor.isPythonRepairExhausted()).isTrue();
        assertThat(anchor.getPythonFailedRequestFingerprints())
                .containsExactly("sha256:failed-1", "sha256:failed-2");
        verify(anchorService, never()).clearAnchorWithToken(any(), any(), any(), anyLong());
        verify(redisCache).removeDue("run-1");
        verify(redisCache).deletePendingCache("run-1");
    }

    @Test
    void completionClearsOnlyMatchingAcceptedHandoff() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("ACCEPTED");
        anchor.setResumeLauncherOwnerId("owner-a");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.clearAcceptedResumeHandoff(
                "run-1", "token-v1", 5L, "owner-a"))
                .thenReturn(true);

        assertThat(resumeService.completeHandoff(
                "run-1", "token-v1", 5L, "owner-a")).isTrue();
        verify(redisCache).removeDue("run-1");
        verify(redisCache).deletePendingCache("run-1");
    }

    // ---- double-claim prevention (§9.11 token+version CAS) ----

    @Test
    void shouldPreventDoubleClaimRace() {
        // Two reconciler scans both load READY anchor with token-v1/version 5.
        // First process wins the CAS. Second process's CAS fails because
        // the DB now has LAUNCHING + version 6, not READY + version 5.
        ToolJobAnchor anchor1 = buildReadyAnchor();
        ToolJobAnchor anchor2 = buildReadyAnchor(); // same token+version

        when(anchorService.loadAnchor("run-1")).thenReturn(anchor1, anchor2);
        when(anchorService.claimResumeLauncher(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED),
                eq("token-v1"), eq(5L), eq("owner-a"), eq(30L)))
                .thenReturn(true)   // first process wins
                .thenReturn(false); // second process loses (token+version+state mismatch)
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        // First claim succeeds
        boolean first = resumeService.tryResume("run-1");
        assertThat(first).isTrue();
        assertThat(anchor1.getResumeState()).isEqualTo("LAUNCHING");
        assertThat(anchor1.getResumeLeaseVersion()).isEqualTo(6);

        // Second claim fails — CAS rejected because DB has version 6 + LAUNCHING
        boolean second = resumeService.tryResume("run-1");
        assertThat(second).isFalse();
        // Launch was called exactly once (only by the first winning process)
        verify(resumeLauncher).launch(eq("run-1"), any(ToolJobResumeContext.class));
    }

    @Test
    void twoIndependentServicesLaunchExpiredAcceptedClaimOnlyOnce() {
        ToolJobResumeLauncher launcherA = mock(ToolJobResumeLauncher.class);
        ToolJobResumeLauncher launcherB = mock(ToolJobResumeLauncher.class);
        ToolJobResumeService serviceA = new ToolJobResumeService(
                anchorService, redisCache, config, objectMapper, "owner-a");
        ToolJobResumeService serviceB = new ToolJobResumeService(
                anchorService, redisCache, config, objectMapper, "owner-b");
        setLauncher(serviceA, launcherA);
        setLauncher(serviceB, launcherB);

        ToolJobAnchor loadedByA = expiredAcceptedAnchor("owner-old", "accepted-token", 11L);
        ToolJobAnchor loadedByB = expiredAcceptedAnchor("owner-old", "accepted-token", 11L);
        when(anchorService.loadAnchor("run-1")).thenReturn(loadedByA, loadedByB);
        when(launcherA.isActive(eq("run-1"), eq("accepted-token"), eq(11L))).thenReturn(false);
        when(launcherB.isActive(eq("run-1"), eq("accepted-token"), eq(11L))).thenReturn(false);
        when(anchorService.takeoverExpiredResumeLauncher(
                eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.EXECUTING),
                eq("accepted-token"), eq(11L), eq("owner-old"), any(),
                eq(30L), eq(120L))).thenReturn(true, false);
        when(launcherA.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        assertThat(serviceA.tryResume("run-1")).isTrue();
        assertThat(serviceB.tryResume("run-1")).isFalse();
        verify(launcherA).launch(eq("run-1"), any(ToolJobResumeContext.class));
        verify(launcherB, never()).launch(any(), any());
    }

    @Test
    void shouldRejectStaleLeaseVersionInCas() {
        // Anchor was already claimed (version 5→6), then rolled back (6→5),
        // then re-marked READY with new token+version (token-v2/version 7).
        // A stale retry with token-v1/version 5 must fail.
        ToolJobAnchor anchor = buildReadyAnchor();
        // Simulate: DB has token-v2/version 7, but we loaded stale token-v1/version 5
        anchor.setResumeToken("token-v2");
        anchor.setResumeLeaseVersion(7);

        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        // CAS with token-v2/version 7 (matching what we loaded) succeeds
        when(anchorService.claimResumeLauncher(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED),
                eq("token-v2"), eq(7L), eq("owner-a"), eq(30L)))
                .thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isTrue();
        assertThat(anchor.getResumeLeaseVersion()).isEqualTo(8);
    }

    @Test
    void shouldFailWhenTokenMismatchInCas() {
        // Loaded anchor has token-v1 but DB was already updated to token-v2 by
        // a re-mark READY cycle. CAS must fail because token doesn't match.
        ToolJobAnchor anchor = buildReadyAnchor(); // token-v1, version 5

        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        // CAS with token-v1/version 5 fails because DB has different token
        when(anchorService.claimResumeLauncher(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED),
                eq("token-v1"), eq(5L), eq("owner-a"), eq(30L)))
                .thenReturn(false);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        verify(resumeLauncher, never()).launch(any(), any());
    }

    // ---- durable launcher owner/lease and takeover ----

    @Test
    void expiredLeaseIsTakenOverByCasAndLaunchedWithNewIdentity() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeClaimedAt(java.time.Instant.now().minusSeconds(60));
        anchor.setResumeLauncherOwnerId("owner-old");
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().minusSeconds(1));
        anchor.setResumeToken("stale-token");
        anchor.setResumeLeaseVersion(8);

        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        // isActive guard: same-process worker not active → allow takeover
        when(resumeLauncher.isActive(eq("run-1"), eq("stale-token"), eq(8L))).thenReturn(false);
        when(anchorService.takeoverExpiredResumeLauncher(
                eq("run-1"), same(anchor), eq(AgentRunStatus.RECEIVED),
                eq("stale-token"), eq(8L), eq("owner-old"), eq("owner-a"),
                eq(30L), eq(120L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        assertThat(resumeService.tryResume("run-1")).isTrue();
        assertThat(anchor.getResumeState()).isIn("LAUNCHING", "ACCEPTED");
        assertThat(anchor.getResumeLeaseVersion()).isEqualTo(9L);
        assertThat(anchor.getResumeToken()).isNotEqualTo("stale-token");
        assertThat(anchor.getResumeLauncherOwnerId()).isEqualTo("owner-a");
        verify(resumeLauncher).isActive(eq("run-1"), eq("stale-token"), eq(8L));
    }

    @Test
    void expiredLeaseButIsActiveTrueShouldBlockTakeover() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeClaimedAt(java.time.Instant.now().minusSeconds(60));
        anchor.setResumeLauncherOwnerId("owner-old");
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().minusSeconds(1));
        anchor.setResumeToken("active-token");
        anchor.setResumeLeaseVersion(8);

        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        // isActive guard: same-process worker still active → block takeover
        when(resumeLauncher.isActive(eq("run-1"), eq("active-token"), eq(8L))).thenReturn(true);

        assertThat(resumeService.tryResume("run-1")).isFalse();
        verify(resumeLauncher).isActive(eq("run-1"), eq("active-token"), eq(8L));
        // Must NOT attempt takeover CAS when isActive blocks
        verify(anchorService, never()).takeoverExpiredResumeLauncher(
                any(), any(), any(), any(), anyLong(), any(), any(), anyLong(), anyLong());
        verify(resumeLauncher, never()).launch(any(), any());
    }

    @Test
    void isActiveGuardChecksExactAnchorIdentity() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeClaimedAt(java.time.Instant.now().minusSeconds(60));
        anchor.setResumeLauncherOwnerId("owner-old");
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().minusSeconds(1));
        anchor.setResumeToken("token-a");
        anchor.setResumeLeaseVersion(5);

        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        // Different token → isActive returns false (identity mismatch)
        when(resumeLauncher.isActive(eq("run-1"), eq("token-a"), eq(5L))).thenReturn(false);
        when(anchorService.takeoverExpiredResumeLauncher(
                eq("run-1"), same(anchor), eq(AgentRunStatus.RECEIVED),
                eq("token-a"), eq(5L), eq("owner-old"), eq("owner-a"),
                eq(30L), eq(120L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        assertThat(resumeService.tryResume("run-1")).isTrue();
        // isActive was checked with the exact token+version from the anchor
        verify(resumeLauncher).isActive(eq("run-1"), eq("token-a"), eq(5L));
    }

    @Test
    void expiredAcceptedHandoffIsTakenOverWithoutReturningToReady() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("ACCEPTED");
        anchor.setResumeClaimedAt(java.time.Instant.now().minusSeconds(60));
        anchor.setResumeLauncherOwnerId("owner-old");
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().minusSeconds(1));
        anchor.setResumeToken("accepted-token");
        anchor.setResumeLeaseVersion(11);

        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(resumeLauncher.isActive(eq("run-1"), eq("accepted-token"), eq(11L))).thenReturn(false);
        when(anchorService.takeoverExpiredResumeLauncher(
                eq("run-1"), same(anchor), eq(AgentRunStatus.EXECUTING),
                eq("accepted-token"), eq(11L), eq("owner-old"), eq("owner-a"),
                eq(30L), eq(120L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        assertThat(resumeService.tryResume("run-1")).isTrue();
        ArgumentCaptor<ToolJobResumeContext> contextCaptor =
                ArgumentCaptor.forClass(ToolJobResumeContext.class);
        verify(resumeLauncher).launch(eq("run-1"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().isResultConsumed()).isTrue();
        assertThat(contextCaptor.getValue().getResumeLauncherOwnerId()).isEqualTo("owner-a");
        assertThat(anchor.getResumeState()).isIn("LAUNCHING", "ACCEPTED");
    }

    @Test
    void legacyLaunchingWithoutLeaseWaitsForClaimedAtStaleWindow() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeClaimedAt(java.time.Instant.now());
        anchor.setResumeLauncherLeaseUntil(null);
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);

        assertThat(resumeService.tryResume("run-1")).isFalse();
        verify(anchorService, never()).takeoverExpiredResumeLauncher(
                any(), any(), any(), any(), anyLong(), any(), any(), anyLong(), anyLong());
        verify(resumeLauncher, never()).launch(any(), any());
    }

    @Test
    void heartbeatUsesExactLocalOwnerTokenAndVersion() {
        when(anchorService.heartbeatResumeLauncher(
                "run-1", "token-v1", 5L, "owner-a", 30L)).thenReturn(true);

        assertThat(resumeService.heartbeat("run-1", "token-v1", 5L, "owner-a")).isTrue();
        assertThat(resumeService.heartbeat("run-1", "token-v1", 5L, "owner-other")).isFalse();
        verify(anchorService).heartbeatResumeLauncher(
                "run-1", "token-v1", 5L, "owner-a", 30L);
    }

    // ---- helpers ----

    private ToolJobAnchor buildReadyAnchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setTaskId("task-123");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTodoId("todo_3");
        anchor.setCompletedTodosJson(
                "[{\"todoId\":\"todo_1\",\"description\":\"fetch data\",\"summary\":\"done\",\"modelOutput\":\"ok\",\"sequence\":1,\"toolCalls\":1}," +
                 "{\"todoId\":\"todo_2\",\"description\":\"analyze\",\"summary\":\"done\",\"output\":\"result\",\"sequence\":2,\"toolCalls\":0}]");
        anchor.setDatasetSnapshotJson("{\"digest\":\"abc123\"}");
        anchor.setToolCallsUsed(2);
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalResultPreview("{\"result\":\"ok\"}");
        anchor.setResumeState("READY");
        anchor.setResumeToken("token-v1");
        anchor.setResumeLeaseVersion(5);
        return anchor;
    }

    private ToolJobAnchor expiredAcceptedAnchor(String ownerId, String token, long version) {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("ACCEPTED");
        anchor.setResumeClaimedAt(java.time.Instant.now().minusSeconds(60));
        anchor.setResumeLauncherOwnerId(ownerId);
        anchor.setResumeLauncherLeaseUntil(java.time.Instant.now().minusSeconds(1));
        anchor.setResumeToken(token);
        anchor.setResumeLeaseVersion(version);
        return anchor;
    }
}
