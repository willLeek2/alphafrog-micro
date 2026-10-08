package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PythonSandboxJobResultAdapterTest {

    private final PythonSandboxJobResultAdapter adapter = new PythonSandboxJobResultAdapter(
            new FinanceToolResultFormatter(new ObjectMapper()), null);

    @Test
    void queuedTaskSkippedAfterDirtyWorkspaceHasExplicitNonRetryableFailure() {
        TaskResultResponse response = TaskResultResponse.newBuilder()
                .setStatus("FAILED")
                .setError("queued task skipped")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("WORKSPACE_DIRTY").build())
                .build();
        SandboxTerminalResultView result = view(response);

        assertThat(adapter.errorCodeOf(result)).isEqualTo("WORKSPACE_DIRTY");
        assertThat(adapter.formatTerminalResult(result, null))
                .contains("\"code\":\"WORKSPACE_DIRTY\"")
                .contains("本任务未执行")
                .contains("\"retryable\":false");
    }

    @Test
    void ordinaryScriptFailureKeepsExecutionFailureCode() {
        TaskResultResponse response = TaskResultResponse.newBuilder()
                .setStatus("FAILED")
                .setStderr("RuntimeError")
                .setRetryable(true)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("PYTHON_ERROR").build())
                .build();

        assertThat(adapter.errorCodeOf(view(response))).isEqualTo("PYTHON_EXECUTION_FAILED");
        assertThat(adapter.formatTerminalResult(view(response), null))
                .contains("\"code\":\"PYTHON_EXECUTION_FAILED\"")
                .contains("\"retryable\":true");
    }

    private static SandboxTerminalResultView view(TaskResultResponse response) {
        return new SandboxTerminalResultView(response.getStatus(), response.getExitCode(),
                response.getStdout(), response.getStderr(), null, null, response.getError(),
                response.hasRetryable() ? response.getRetryable() : null, null, response);
    }
}
