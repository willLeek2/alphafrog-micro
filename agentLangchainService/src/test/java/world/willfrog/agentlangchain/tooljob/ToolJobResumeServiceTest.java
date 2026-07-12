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
                eq(AgentRunStatus.RECEIVED), eq("READY"))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isTrue();
        assertThat(anchor.getResumeState()).isEqualTo("LAUNCHING");

        ArgumentCaptor<ToolJobResumeContext> ctxCaptor = ArgumentCaptor.forClass(ToolJobResumeContext.class);
        verify(resumeLauncher).launch(eq("run-1"), ctxCaptor.capture());
        ToolJobResumeContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getRunId()).isEqualTo("run-1");
        assertThat(ctx.getTodoId()).isEqualTo("todo_3");
        assertThat(ctx.getCompletedTodos()).hasSize(2);
        assertThat(ctx.getCompletedTodos().get(0).getTodoId()).isEqualTo("todo_1");
        assertThat(ctx.getCompletedTodos().get(0).getSummary()).isEqualTo("done");
        assertThat(ctx.getToolCallsUsed()).isEqualTo(2);
        assertThat(ctx.isTerminalSuccess()).isTrue();
    }

    @Test
    void shouldNotLaunchWhenCasFails() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"))).thenReturn(false);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        verify(resumeLauncher, never()).launch(any(), any());
    }

    @Test
    void shouldRollbackToReadWhenLaunchRejected() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class))).thenReturn(false);
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("LAUNCHING"))).thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        assertThat(anchor.getResumeState()).isEqualTo("READY");
    }

    @Test
    void shouldRollbackToReadWhenLaunchThrows() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("READY"))).thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class)))
                .thenThrow(new RuntimeException("pipeline error"));
        when(anchorService.casResumeState(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq("LAUNCHING"))).thenReturn(true);

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
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.RECEIVED)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.RECEIVED))).thenReturn(true);

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
        // Redis NOT cleaned up — deferred
        verify(redisCache, never()).removeDue("run-1");
        verify(redisCache, never()).deletePendingCache("run-1");
        // DB anchor NOT cleared — still has tool_job_anchor_json
        verify(anchorService, never()).updateAnchorAndStatus(any(), any(), any(), any());
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
                "[{\"todoId\":\"todo_1\",\"summary\":\"done\",\"sequence\":1,\"toolCalls\":1}," +
                 "{\"todoId\":\"todo_2\",\"summary\":\"done\",\"sequence\":2,\"toolCalls\":0}]");
        anchor.setDatasetSnapshotJson("{\"digest\":\"abc123\"}");
        anchor.setToolCallsUsed(2);
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalResultPreview("{\"result\":\"ok\"}");
        anchor.setResumeState("READY");
        return anchor;
    }
}
