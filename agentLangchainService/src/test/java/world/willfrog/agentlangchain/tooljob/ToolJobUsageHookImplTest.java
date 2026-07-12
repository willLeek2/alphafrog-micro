package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalRecorder;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisUpsertOutcome;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolJobUsageHookImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DataAnalysisTerminalRecorder recorder = mock(DataAnalysisTerminalRecorder.class);
    private final ToolJobUsageHookImpl hook = new ToolJobUsageHookImpl(recorder, objectMapper);

    @Test
    void releasedAnchorBecomesTerminalConfirmedEnvelopeAndRecordsMeasuredUsage() throws Exception {
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);

        assertThat(hook.upsertUsage("run-1", anchor())).isTrue();

        ArgumentCaptor<DataAnalysisTerminalEnvelope> captor =
                ArgumentCaptor.forClass(DataAnalysisTerminalEnvelope.class);
        verify(recorder).upsert(captor.capture());
        DataAnalysisTerminalEnvelope envelope = captor.getValue();
        assertThat(envelope.operationId()).isEqualTo("run-1:call-1:1");
        assertThat(envelope.reservation().state())
                .isEqualTo(DataAnalysisReservationState.TERMINAL_CONFIRMED);
        assertThat(envelope.resourceUsage().cpuMillis()).isEqualTo(11L);
        assertThat(envelope.resourceUsage().datasetOpenCount()).isEqualTo(2);
        assertThat(envelope.resourceUsage().attributionComplete()).isTrue();
    }

    @Test
    void alreadyPresentIsSuccessButConflictBlocksFinalizer() throws Exception {
        when(recorder.upsert(any()))
                .thenReturn(DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME)
                .thenReturn(DataAnalysisUpsertOutcome.CONFLICT);

        assertThat(hook.upsertUsage("run-1", anchor())).isTrue();
        assertThat(hook.upsertUsage("run-1", anchor())).isFalse();
    }

    @Test
    void absentCollectorFieldsStayMissingInsteadOfBecomingZero() throws Exception {
        ToolJobAnchor anchor = anchor();
        anchor.setTerminalUsageJson("{\"cpuMillis\":11,\"oomKilled\":false}");
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);

        assertThat(hook.upsertUsage("run-1", anchor)).isTrue();

        ArgumentCaptor<DataAnalysisTerminalEnvelope> captor =
                ArgumentCaptor.forClass(DataAnalysisTerminalEnvelope.class);
        verify(recorder).upsert(captor.capture());
        assertThat(captor.getValue().resourceUsage().cpuMillis()).isEqualTo(11L);
        assertThat(captor.getValue().resourceUsage().memoryPeakBytes()).isNull();
        assertThat(captor.getValue().resourceUsage().attributionComplete()).isFalse();
        assertThat(captor.getValue().resourceUsage().missingFields())
                .contains("memoryPeakBytes", "executionWallMillis", "exitReason");
    }

    @Test
    void parsesTheExactProtobufJsonWrittenByFinalizer() throws Exception {
        ToolJobAnchor anchor = anchor();
        SandboxResourceUsage usage = SandboxResourceUsage.newBuilder()
                .setCpuMillis(31)
                .setMemoryPeakBytes(41)
                .setLogicalBytesScanned(51)
                .setQueueWaitMillis(1)
                .setPrepareMillis(2)
                .setExecutionWallMillis(3)
                .setCleanupMillis(4)
                .setDatasetOpenCount(5)
                .setExitReason("SUCCESS")
                .build();
        anchor.setTerminalUsageJson(JsonFormat.printer()
                .omittingInsignificantWhitespace()
                .print(usage));
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);

        assertThat(hook.upsertUsage("run-1", anchor)).isTrue();

        ArgumentCaptor<DataAnalysisTerminalEnvelope> captor =
                ArgumentCaptor.forClass(DataAnalysisTerminalEnvelope.class);
        verify(recorder).upsert(captor.capture());
        assertThat(captor.getValue().resourceUsage().cpuMillis()).isEqualTo(31L);
        assertThat(captor.getValue().resourceUsage().memoryPeakBytes()).isEqualTo(41L);
        assertThat(captor.getValue().resourceUsage().datasetOpenCount()).isEqualTo(5);
    }

    private ToolJobAnchor anchor() throws Exception {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run-1", "call-1", 1);
        DataAnalysisReservation released = new DataAnalysisReservation(
                identity.reservationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.RELEASED, "task-1",
                Instant.parse("2026-07-12T00:00:00Z"));
        DataAnalysisEstimate estimate = new DataAnalysisEstimate(
                100, 1_000, 1, 1.0, 1, List.of(), DataAnalysisResourceClass.STANDARD, 1);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId(identity.toolCallId());
        anchor.setAttempt(identity.attempt());
        anchor.setTaskId("task-1");
        anchor.setReservationJson(objectMapper.writeValueAsString(released));
        anchor.setEstimateJson(objectMapper.writeValueAsString(estimate));
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalResultPreview("ok");
        anchor.setTerminalAt(Instant.parse("2026-07-12T00:01:00Z"));
        anchor.setTerminalUsageJson("""
                {"cpuMillis":11,"memoryPeakBytes":22,"logicalBytesScanned":1000,
                 "queueWaitMillis":1,"prepareMillis":2,"executionWallMillis":3,
                 "cleanupMillis":4,"datasetOpenCount":2,"exitReason":"SUCCESS",
                 "oomKilled":false,"timedOut":false,"samplingIntervalMillis":100}
                """);
        return anchor;
    }
}
