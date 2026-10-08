package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ToolJobFinalizerWorkspaceResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
    private final ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
    private ToolJobFinalizer finalizer;

    @BeforeEach
    void setUp() {
        when(anchorService.updateAnchor(eq("run-a"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-a"), any(),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(usageHook.upsertUsage(eq("run-a"), any())).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-a"), any())).thenReturn(true);
        finalizer = new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class),
                new FinanceToolResultFormatter(objectMapper), mock(FinanceResultModelAdapter.class));
        ReflectionTestUtils.setField(finalizer, "usageHook", usageHook);
        ReflectionTestUtils.setField(finalizer, "eventHook", eventHook);
    }

    @Test
    void dirtyQueuedTaskKeepsExplicitResultAcrossPersistedFinalizerRetry() throws Exception {
        when(usageHook.upsertUsage(eq("run-a"), any())).thenReturn(false, true);
        TaskResultResponse response = TaskResultResponse.newBuilder()
                .setTaskId("task-b").setStatus("FAILED").setExitCode(-1)
                .setError("workspace marked dirty by task task-a; not re-admitted")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("WORKSPACE_DIRTY").setAttributionComplete(false).build())
                .build();
        ToolJobAnchor anchor = anchor();

        ToolJobFinalizer.FinalizerOutcome interrupted =
                finalizer.handleTerminal("run-a", anchor, "FAILED", response, true);

        assertThat(interrupted.done()).isFalse();
        assertThat(interrupted.step()).isEqualTo(ToolJobFinalizer.STEP_USAGE);
        JsonNode preview = objectMapper.readTree(anchor.getTerminalResultPreview());
        assertThat(preview.path("ok").asBoolean()).isFalse();
        assertThat(preview.path("data").isEmpty()).isTrue();
        assertThat(preview.path("error").path("code").asText()).isEqualTo("WORKSPACE_DIRTY");
        assertThat(preview.path("error").path("message").asText())
                .contains("前一个任务失败", "本任务未执行");
        assertThat(preview.path("error").path("retryable").asBoolean()).isFalse();
        assertThat(preview.path("error").path("action").asText()).contains("新的 Run");
        assertThat(anchor.getTerminalErrorCode()).isEqualTo("WORKSPACE_DIRTY");
        assertThat(anchor.getTerminalRetryable()).isFalse();

        // 模拟收尾中断后只从持久记录继续，不再次取得沙箱结果。
        ToolJobAnchor restored = objectMapper.readValue(
                objectMapper.writeValueAsString(anchor), ToolJobAnchor.class);
        ToolJobFinalizer.FinalizerOutcome completed =
                finalizer.handleTerminal("run-a", restored, "FAILED", null, true);

        assertThat(completed.done()).isTrue();
        assertThat(restored.getTerminalResultPreview()).isEqualTo(anchor.getTerminalResultPreview());
        assertThat(restored.getTerminalErrorCode()).isEqualTo("WORKSPACE_DIRTY");
        assertThat(restored.getTerminalRetryable()).isFalse();
        assertThat(restored.getToolCallId()).isEqualTo("call-b");
        verify(anchorService).updateAnchorAndStatus(eq("run-a"), any(),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(eventHook).emitTerminalEvent(eq("run-a"), any());
    }

    @Test
    void ordinaryScriptFailureKeepsOutputRetryabilityAndExistingGuidance() throws Exception {
        TaskResultResponse response = TaskResultResponse.newBuilder()
                .setStatus("FAILED").setExitCode(1).setStdout("partial")
                .setStderr("RuntimeError").setRetryable(true)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("PYTHON_ERROR").build())
                .build();
        ToolJobAnchor anchor = anchor();

        assertThat(finalizer.handleTerminal("run-a", anchor, "FAILED", response, false).done()).isTrue();

        JsonNode preview = objectMapper.readTree(anchor.getTerminalResultPreview());
        assertThat(preview.path("data").path("stdout").asText()).isEqualTo("partial");
        assertThat(preview.path("data").path("stderr").asText()).isEqualTo("RuntimeError");
        assertThat(preview.path("error").path("code").asText()).isEqualTo("PYTHON_EXECUTION_FAILED");
        assertThat(preview.path("error").path("message").asText()).isEqualTo("Sandbox FAILED");
        assertThat(preview.path("error").path("retryable").asBoolean()).isTrue();
        assertThat(preview.path("error").path("action").asText()).isEqualTo("检查代码后重试");
    }

    @Test
    void canceledTaskKeepsExistingFailureCodeAndNonRetryableGuidance() throws Exception {
        TaskResultResponse response = TaskResultResponse.newBuilder()
                .setStatus("CANCELED").setExitCode(-1).setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder().setExitReason("CANCELED").build())
                .build();
        ToolJobAnchor anchor = anchor();

        assertThat(finalizer.handleTerminal("run-a", anchor, "CANCELED", response, false).done()).isTrue();

        JsonNode preview = objectMapper.readTree(anchor.getTerminalResultPreview());
        assertThat(preview.path("error").path("code").asText()).isEqualTo("PYTHON_EXECUTION_CANCELED");
        assertThat(preview.path("error").path("message").asText()).isEqualTo("Sandbox CANCELED");
        assertThat(preview.path("error").path("retryable").asBoolean()).isFalse();
        assertThat(preview.path("error").path("action").asText()).isEqualTo("检查代码或联系管理员");
    }

    private static ToolJobAnchor anchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-a:call-b:1");
        anchor.setToolCallId("call-b");
        anchor.setAttempt(1);
        anchor.setTaskId("task-b");
        anchor.setAutoResume(true);
        return anchor;
    }
}
