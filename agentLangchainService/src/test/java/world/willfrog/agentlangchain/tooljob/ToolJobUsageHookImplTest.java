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
        anchor.setTerminalRetryable(false);
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
