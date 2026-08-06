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

    @Test
    void insertPersistsSnapshotWithNullExpectedAndCachesBothViews() throws Exception {
        AgentRun run = run("run-1", "{\"answer\":\"ok\"}");
        when(mapper.findById("run-1")).thenReturn(run);
        when(mapper.casUpdateDataAnalysisObservability(eq("run-1"), isNull(), anyString()))
                .thenReturn(1);

        assertThat(service.upsert(envelope("run-1", "call-1", 1, 10L)))
                .isEqualTo(DataAnalysisUpsertOutcome.INSERTED);

        ArgumentCaptor<String> nextJson = ArgumentCaptor.forClass(String.class);
        verify(mapper).casUpdateDataAnalysisObservability(eq("run-1"), isNull(), nextJson.capture());
        DataAnalysisObservabilitySnapshot snapshot = objectMapper.readValue(
                nextJson.getValue(), DataAnalysisObservabilitySnapshot.class);
        assertThat(snapshot.calls()).hasSize(1);
        assertThat(snapshot.summary().cpuMillis()).isEqualTo(10L);
        verify(stateStore).saveDataAnalysisObservability(
                eq("run-1"), eq(nextJson.getValue()), contains("\"toolCallCount\":1"));
    }

    @Test
    void identicalAttemptReturnsAlreadyPresentWithoutDbWrite() throws Exception {
        DataAnalysisTerminalEnvelope envelope = envelope("run-1", "call-1", 1, 10L);
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope)));
        when(mapper.findById("run-1")).thenReturn(run("run-1", snapshotRoot(snapshot)));

        assertThat(service.upsert(envelope))
                .isEqualTo(DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME);
        verify(mapper, never()).casUpdateDataAnalysisObservability(anyString(), any(), anyString());
    }

    @Test
    void sameIdentityWithDifferentUsageReturnsConflict() throws Exception {
        DataAnalysisTerminalEnvelope original = envelope("run-1", "call-1", 1, 10L);
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(original)));
        when(mapper.findById("run-1")).thenReturn(run("run-1", snapshotRoot(snapshot)));

        assertThat(service.upsert(envelope("run-1", "call-1", 1, 11L)))
                .isEqualTo(DataAnalysisUpsertOutcome.CONFLICT);
        verify(mapper, never()).casUpdateDataAnalysisObservability(anyString(), any(), anyString());
    }

    @Test
    void summaryQueryUsesSummaryOnlyCacheAndNeverLoadsFullSnapshot() throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope("run-1", "call-1", 1, 10L))));
        String summaryJson = objectMapper.writeValueAsString(snapshot.summary());
        when(stateStore.loadDataAnalysisObservabilitySummary("run-1"))
                .thenReturn(Optional.of(summaryJson));

        assertThat(service.findSummaryByRunId(
                "run-1", DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST))
                .contains(snapshot.summary());
        verify(stateStore, never()).loadDataAnalysisObservability(anyString());
        verify(mapper, never()).findDataAnalysisObservabilityJsonById(anyString());
        verify(mapper, never()).findById(anyString());
    }

    @Test
    void fullQueryFallsBackToDbAndWarmsRedis() throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope("run-1", "call-1", 1, 10L))));
        String json = objectMapper.writeValueAsString(snapshot);
        when(stateStore.loadDataAnalysisObservability("run-1")).thenReturn(Optional.empty());
        when(mapper.findDataAnalysisObservabilityJsonById("run-1")).thenReturn(json);

        assertThat(service.findByRunId(
                "run-1", DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST))
                .contains(snapshot);
        verify(stateStore).saveDataAnalysisObservability(
                eq("run-1"), eq(json), contains("\"attemptCount\":1"));
    }

    @Test
    void summaryDbFallbackWarmsOnlySummaryCache() throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope("run-1", "call-1", 1, 10L))));
        String summaryJson = objectMapper.writeValueAsString(snapshot.summary());
        when(stateStore.loadDataAnalysisObservabilitySummary("run-1")).thenReturn(Optional.empty());
        when(mapper.findDataAnalysisObservabilitySummaryJsonById("run-1")).thenReturn(summaryJson);

        assertThat(service.findSummaryByRunId(
                "run-1", DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST))
                .contains(snapshot.summary());
        verify(stateStore).saveDataAnalysisObservabilitySummary("run-1", summaryJson);
        verify(stateStore, never()).saveDataAnalysisObservability(eq("run-1"), anyString(), anyString());
    }

    @Test
    void summaryDbFallbackStillReturnsWhenRedisWarmFails() throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope("run-1", "call-1", 1, 10L))));
        String summaryJson = objectMapper.writeValueAsString(snapshot.summary());
        when(stateStore.loadDataAnalysisObservabilitySummary("run-1")).thenReturn(Optional.empty());
        when(mapper.findDataAnalysisObservabilitySummaryJsonById("run-1")).thenReturn(summaryJson);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(stateStore).saveDataAnalysisObservabilitySummary("run-1", summaryJson);

        assertThat(service.findSummaryByRunId(
                "run-1", DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST))
                .contains(snapshot.summary());
    }

    @Test
    void terminalSummarySkipsStaleRedisAndReturnsDbTruthWithoutWarm() throws Exception {
        DataAnalysisObservabilitySnapshot stale = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope("run-1", "call-1", 1, 10L))));
        DataAnalysisObservabilitySnapshot current = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope("run-1", "call-1", 1, 99L))));
        when(stateStore.loadDataAnalysisObservabilitySummary("run-1"))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(stale.summary())));
        when(mapper.findDataAnalysisObservabilitySummaryJsonById("run-1"))
                .thenReturn(objectMapper.writeValueAsString(current.summary()));

        assertThat(service.findSummaryByRunId(
                "run-1", DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY))
                .contains(current.summary());
        verify(stateStore, never()).loadDataAnalysisObservabilitySummary(anyString());
        verify(stateStore, never()).saveDataAnalysisObservabilitySummary(anyString(), anyString());
        verify(stateStore, never()).loadDataAnalysisObservability(anyString());
    }

    @Test
    void terminalFullSkipsStaleRedisAndReturnsDbTruthWithoutWarm() throws Exception {
        DataAnalysisObservabilitySnapshot stale = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope("run-1", "call-1", 1, 10L))));
        DataAnalysisObservabilitySnapshot current = DataAnalysisObservabilitySnapshot.of(
                "run-1", List.of(world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall
                        .fromEnvelope(envelope("run-1", "call-1", 1, 99L))));
        when(stateStore.loadDataAnalysisObservability("run-1"))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(stale)));
        when(mapper.findDataAnalysisObservabilityJsonById("run-1"))
                .thenReturn(objectMapper.writeValueAsString(current));

        assertThat(service.findByRunId(
                "run-1", DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY))
                .contains(current);
        verify(stateStore, never()).loadDataAnalysisObservability(anyString());
        verify(stateStore, never()).saveDataAnalysisObservability(anyString(), anyString(), anyString());
        verify(stateStore, never()).loadDataAnalysisObservabilitySummary(anyString());
    }

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
