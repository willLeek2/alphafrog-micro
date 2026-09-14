package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ToolJobResultValidatorTest {

    private static TaskResultResponse resp(String taskId, String status, String stdout, String error) {
        return TaskResultResponse.newBuilder()
                .setTaskId(taskId != null ? taskId : "")
                .setStatus(status != null ? status : "")
                .setStdout(stdout != null ? stdout : "")
                .setError(error != null ? error : "")
                .build();
    }

    @Test
    void shouldAcceptValidSucceededResult() {
        TaskResultResponse r = resp("task-123", "SUCCEEDED", "output data", null);
        assertThat(ToolJobResultValidator.validate("task-123", "run-1", r, "SUCCEEDED")).isNotNull();
    }

    @Test
    void shouldAcceptValidFailedResult() {
        TaskResultResponse r = resp("task-123", "FAILED", null, "sandbox timeout");
        assertThat(ToolJobResultValidator.validate("task-123", "run-1", r, "FAILED")).isNotNull();
    }

    @Test
    void shouldRejectNullResponse() {
        assertThat(ToolJobResultValidator.validate("task-1", "run-1", null, "SUCCEEDED")).isNull();
    }

    @Test
    void shouldRejectTaskIdMismatch() {
        TaskResultResponse r = resp("task-999", "SUCCEEDED", "data", null);
        assertThat(ToolJobResultValidator.validate("task-123", "run-1", r, "SUCCEEDED")).isNull();
    }

    @Test
    void shouldRejectEmptyTaskId() {
        TaskResultResponse r = resp("", "SUCCEEDED", "data", null);
        assertThat(ToolJobResultValidator.validate("task-123", "run-1", r, "SUCCEEDED")).isNull();
    }

    @Test
    void shouldRejectStatusMismatch() {
        TaskResultResponse r = resp("task-123", "FAILED", null, "error");
        assertThat(ToolJobResultValidator.validate("task-123", "run-1", r, "SUCCEEDED")).isNull();
    }

    @Test
    void shouldRejectSucceededWithNoPayload() {
        TaskResultResponse r = resp("task-123", "SUCCEEDED", "", null);
        assertThat(ToolJobResultValidator.validate("task-123", "run-1", r, "SUCCEEDED")).isNull();
    }

    @Test
    void shouldRejectFailedWithNoError() {
        TaskResultResponse r = resp("task-123", "FAILED", null, "");
        assertThat(ToolJobResultValidator.validate("task-123", "run-1", r, "FAILED")).isNull();
    }

    @Test
    void shouldRejectEmptyStatus() {
        TaskResultResponse r = resp("task-123", "", "data", null);
        assertThat(ToolJobResultValidator.validate("task-123", "run-1", r, "SUCCEEDED")).isNull();
    }

    @Test
    void shouldAcceptSucceededWithDatasetDir() {
        // datasetDir counts as valid payload
        var r = TaskResultResponse.newBuilder()
                .setTaskId("task-456")
                .setStatus("SUCCEEDED")
                .setDatasetDir("/data/ds1")
                .build();
        assertThat(ToolJobResultValidator.validate("task-456", "run-2", r, "SUCCEEDED")).isNotNull();
    }

    @Test
    void shouldAcceptFailedWithStderr() {
        var r = TaskResultResponse.newBuilder()
                .setTaskId("task-789")
                .setStatus("FAILED")
                .setStderr("traceback output")
                .build();
        assertThat(ToolJobResultValidator.validate("task-789", "run-3", r, "FAILED")).isNotNull();
    }
}
