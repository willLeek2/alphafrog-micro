package world.willfrog.agent.tools.python;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxTerminalResultValidatorTest {
    @Test
    void acceptsCanceledBeforeCreateWithStructuredReasonAndExplicitNonRetryable() {
        TaskResultResponse tombstone = TaskResultResponse.newBuilder()
                .setTaskId("task-1").setStatus("CANCELED").setExitCode(-1)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setResourceClass("UNKNOWN").setExitReason("CANCELED")
                        .setAttributionComplete(false).build())
                .setRetryable(false).build();
        assertThat(SandboxTerminalResultValidator.validate(
                "task-1", "run-1", tombstone, "CANCELED")).isSameAs(tombstone);
    }

    @Test
    void rejectsEmptyCanceledResultWithoutStructuredReasonOrRetryability() {
        TaskResultResponse empty = TaskResultResponse.newBuilder()
                .setTaskId("task-1").setStatus("CANCELED").build();
        assertThat(SandboxTerminalResultValidator.validate(
                "task-1", "run-1", empty, "CANCELED")).isNull();
    }
}
