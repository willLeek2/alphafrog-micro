package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        // Inject launcher via field (Spring would do this)
        try {
            var field = ToolJobResumeService.class.getDeclaredField("resumeLauncher");
            field.setAccessible(true);
            field.set(resumeService, resumeLauncher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldNotResumeWhenAnchorNotFound() {
        when(anchorService.loadAnchor("run-1")).thenReturn(null);
        assertThat(resumeService.tryResume("run-1")).isFalse();
    }

    @Test
    void shouldNotResumeWhenResumeStateIsNotReady() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setResumeState("CONSUMED");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);

        assertThat(resumeService.tryResume("run-1")).isFalse();
    }

    @Test
    void shouldCasReadToLaunchingAndCallLauncher() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.RECEIVED)))
                .thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class)))
                .thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isTrue();
        assertThat(anchor.getResumeState()).isEqualTo("LAUNCHING");

        // Verify context was built correctly
        ArgumentCaptor<ToolJobResumeContext> ctxCaptor = ArgumentCaptor.forClass(ToolJobResumeContext.class);
        verify(resumeLauncher).launch(eq("run-1"), ctxCaptor.capture());
        ToolJobResumeContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getRunId()).isEqualTo("run-1");
        assertThat(ctx.getTodoId()).isEqualTo("todo_3");
        assertThat(ctx.getCompletedTodoIds()).containsExactly("todo_1", "todo_2");
        assertThat(ctx.getToolCallsUsed()).isEqualTo(2);
        assertThat(ctx.isTerminalSuccess()).isTrue();
    }

    @Test
    void shouldRollbackToReadyWhenCasFails() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.RECEIVED)))
                .thenReturn(false); // CAS fails — another process claimed it

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
    }

    @Test
    void shouldRollbackToReadyWhenLaunchRejected() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.RECEIVED)))
                .thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class)))
                .thenReturn(false);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        assertThat(anchor.getResumeState()).isEqualTo("READY"); // rolled back
    }

    @Test
    void shouldRollbackToReadyWhenLaunchThrows() {
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.RECEIVED)))
                .thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class)))
                .thenThrow(new RuntimeException("pipeline error"));

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse();
        assertThat(anchor.getResumeState()).isEqualTo("READY"); // rolled back
    }

    @Test
    void shouldMarkConsumedAndCleanup() {
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
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
    void shouldReenterOnRestartFromReady() {
        // Simulate restart: anchor has resumeState=READY (finalizer set it,
        // but synchronous launch didn't fire before crash)
        ToolJobAnchor anchor = buildReadyAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class), eq(AgentRunStatus.RECEIVED)))
                .thenReturn(true);
        when(resumeLauncher.launch(eq("run-1"), any(ToolJobResumeContext.class)))
                .thenReturn(true);

        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isTrue();
        assertThat(anchor.getResumeState()).isEqualTo("LAUNCHING");
    }

    @Test
    void shouldReenterOnRestartFromLaunching() {
        // Simulate crash after LAUNCHING but before CONSUMED:
        // anchor has resumeState=LAUNCHING. Service should NOT re-launch
        // since CAS from READY is the gate.
        ToolJobAnchor anchor = buildReadyAnchor();
        anchor.setResumeState("LAUNCHING");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);

        // tryResume only proceeds when resumeState is exactly READY
        boolean result = resumeService.tryResume("run-1");
        assertThat(result).isFalse(); // Not READY, so skipped
    }

    // ---- helpers ----

    private ToolJobAnchor buildReadyAnchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setTaskId("task-123");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTodoId("todo_3");
        anchor.setCompletedTodosJson("[\"todo_1\",\"todo_2\"]");
        anchor.setDatasetRefsJson("{\"ds1\":\"/data/ds1.csv\"}");
        anchor.setToolCallsUsed(2);
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalResultPreview("{\"result\":\"ok\"}");
        anchor.setResumeState("READY");
        return anchor;
    }
}
