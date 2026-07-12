package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ToolJobAnchorTest {

    @Test
    void shouldSerializeAndDeserializeRoundTrip() {
        ToolJobAnchor original = new ToolJobAnchor();
        original.setOperationId("run-1:tc-1:1");
        original.setTaskId("sandbox-task-123");
        original.setToolCallId("tc-1");
        original.setAttempt(1);
        original.setTodoId(3);
        original.setSequence(5);
        original.setNextPollAt(Instant.parse("2026-07-12T10:00:00Z"));
        original.setTimeoutAt(Instant.parse("2026-07-12T10:05:00Z"));
        original.setResumeState("READY");

        String json = original.toJson();
        ToolJobAnchor restored = ToolJobAnchor.fromJson(json);

        assertThat(restored.getOperationId()).isEqualTo("run-1:tc-1:1");
        assertThat(restored.getTaskId()).isEqualTo("sandbox-task-123");
        assertThat(restored.getToolCallId()).isEqualTo("tc-1");
        assertThat(restored.getAttempt()).isEqualTo(1);
        assertThat(restored.getTodoId()).isEqualTo(3);
        assertThat(restored.getSequence()).isEqualTo(5);
        assertThat(restored.getResumeState()).isEqualTo("READY");
        assertThat(restored.getNextPollAt()).isEqualTo("2026-07-12T10:00:00Z");
        assertThat(restored.getTimeoutAt()).isEqualTo("2026-07-12T10:05:00Z");
    }

    @Test
    void shouldDefaultAutoResumeToTrue() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        assertThat(anchor.isAutoResume()).isTrue();
    }

    @Test
    void shouldTrackFinalizerProgress() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setFinalizerStep("ENVELOPE");
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalAt(Instant.now());

        assertThat(anchor.getFinalizerStep()).isEqualTo("ENVELOPE");
        assertThat(anchor.getTerminalStatus()).isEqualTo("SUCCEEDED");
        assertThat(anchor.getTerminalAt()).isNotNull();
    }

    @Test
    void shouldTrackResultFetchState() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setResultFetchState("PENDING");
        anchor.setResultFetchAttempts(3);
        anchor.setTerminalConfirmedAt(Instant.now());

        assertThat(anchor.getResultFetchState()).isEqualTo("PENDING");
        assertThat(anchor.getResultFetchAttempts()).isEqualTo(3);
        assertThat(anchor.getTerminalConfirmedAt()).isNotNull();
    }

    @Test
    void emptyAnchorShouldDefaultSchemaVersion() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        assertThat(anchor.getSchemaVersion()).isEqualTo(1);
        String json = anchor.toJson();
        assertThat(json).contains("\"schemaVersion\":1");
    }

    @Test
    void shouldHandleReservationJson() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setReservationJson("{\"reservationId\":\"res-1\"}");
        assertThat(anchor.getReservationJson()).isEqualTo("{\"reservationId\":\"res-1\"}");
    }
}
