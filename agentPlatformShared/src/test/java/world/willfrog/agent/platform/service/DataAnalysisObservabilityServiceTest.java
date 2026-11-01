package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityReadMode;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisUpsertOutcome;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DataAnalysisObservabilityServiceTest {

    private final AgentRunMapper mapper = mock(AgentRunMapper.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DataAnalysisObservabilityService service =
            new DataAnalysisObservabilityService(mapper, stateStore, objectMapper);











    private DataAnalysisTerminalEnvelope envelope(
            String runId, String toolCallId, int attempt, long cpuMillis) {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
        DataAnalysisEstimate estimate = new DataAnalysisEstimate(
                100, 1_000, 1, 1.0, 1, List.of(), DataAnalysisResourceClass.STANDARD, 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.TERMINAL_CONFIRMED, "task-1",
                Instant.parse("2026-07-12T00:00:00Z"));
        DataAnalysisResourceUsage usage = new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD, cpuMillis, 20L, null, 1_000L,
                null, null, 1L, 2L, 3L, 4L, 1, "SUCCESS",
                false, false, true, 100L, List.of());
        return new DataAnalysisTerminalEnvelope(
                runId, toolCallId, attempt, identity.operationId(), "task-1", "SUCCEEDED",
                true, "ok", null, null, null, false, estimate, reservation, usage,
                Instant.parse("2026-07-12T00:01:00Z"), true);
    }

    private AgentRun run(String runId, String snapshotJson) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setSnapshotJson(snapshotJson);
        return run;
    }

    private String snapshotRoot(DataAnalysisObservabilitySnapshot snapshot) throws Exception {
        return "{\"data_analysis_observability\":" + objectMapper.writeValueAsString(snapshot) + "}";
    }
}
