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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolJobResumeServiceTest {

    @Mock
    private ToolJobAnchorService anchorService;

    @Mock
    private ToolJobRedisCache redisCache;

    @Mock
    private ToolJobResumeLauncher resumeLauncher;

    private ToolJobResumeService resumeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        resumeService = new ToolJobResumeService(anchorService, redisCache, objectMapper);
        try {
            var field = ToolJobResumeService.class.getDeclaredField("resumeLauncher");
            field.setAccessible(true);
            field.set(resumeService, resumeLauncher);
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
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);

        assertThat(resumeService.tryResume("run-1")).isTrue();
        verify(redisCache).removeDue("run-1");
        verify(redisCache).deletePendingCache("run-1");
    }

    // ---- READY → LAUNCHING (first launch) ----

    @Test
    void shouldCasReadToLaunchingAndLaunch() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-v1"), eq(5L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isTrue();
        assertThat(anchor.getResumeState()).isEqualTo("LAUNCHING");
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
    }

    @Test
    void shouldNotLaunchWhenCasFails() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-v1"), eq(5L))).thenReturn(false);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        verify(resumeLauncher, never()).launch(any(), any());
    }

    @Test
    void shouldRollbackToReadWhenLaunchRejected() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-v1"), eq(5L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(false);
        // Rollback: expected LAUNCHING + token-v1 + claimedVersion 6
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("LAUNCHING"), eq("token-v1"), eq(6L))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        assertThat(anchor.getResumeState()).isEqualTo("READY");
        assertThat(anchor.getResumeLeaseVersion()).isEqualTo(5); // rolled back to original
    }

    @Test
    void shouldRollbackToReadWhenLaunchThrows() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-v1"), eq(5L))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class)))
                .thenThrow(new RuntimeException("pipeline error"));
        // Rollback: expected LAUNCHING + token-v1 + claimedVersion 6
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("LAUNCHING"), eq("token-v1"), eq(6L))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        assertThat(anchor.getResumeState()).isEqualTo("READY");
    }

    // ---- LAUNCHING reentry (crash recovery, §9.11) ----

    @Test
    void shouldReenterLaunchingAndRelunch() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isTrue();
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
        when(anchorService.clearAnchorWithToken("run-1", "test-token-123")).thenReturn(true);

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
        verify(anchorService, never()).clearAnchor(any());
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
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-v1"), eq(5L)))
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
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-v2"), eq(7L)))
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
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"), eq("token-v1"), eq(5L)))
                .thenReturn(false);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        verify(resumeLauncher, never()).launch(any(), any());
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
}
