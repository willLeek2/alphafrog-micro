package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tier-2 durability fixtures: DB step-write failures after external side effects.
 *
 * <p>Fixture 1: capacity release succeeds → DB finalizer-step write fails
 * → re-entry recognizes ALREADY_RELEASED, no double-release.
 * Uses a stateful {@link DataAnalysisCapacityService} fake with a real ledger
 * to prove reserved→released transitions only once.
 *
 * <p>Fixture 2: usage/event hook succeeds → DB step write fails
 * → re-entry re-invokes the idempotent hook; stateful fakes prove single durable record.
 */
class ToolJobFinalizerDbWriteFailureTest {

    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // ===== Fixture 1 =====



    // ===== Fixture 2 =====



    // ===== Stateful fakes =====

    /**
     * Stateful capacity ledger that tracks actual reserved→released transitions.
     * Unlike a Mockito mock with pre-programmed returns, this proves that the
     * ledger state changes at most once even when releaseReservation is called
     * multiple times during crash/re-entry.
     */
    static class StatefulCapacityFake implements DataAnalysisCapacityService {
        private final Map<String, DataAnalysisReservationState> ledger = new LinkedHashMap<>();
        int releaseCallCount;
        int transitionCount;

        /** Pre-populate a reservation as already RELEASED (simulates prior crash). */
        void preSeedReleased(String reservationId) {
            ledger.put(reservationId, DataAnalysisReservationState.RELEASED);
        }

        int distinctReservationIds() {
            return ledger.size();
        }

        @Override
        public DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation reservation) {
            String id = reservation.reservationId();
            if (ledger.containsKey(id)) {
                return DataAnalysisRestoreOutcome.CONFLICT;
            }
            ledger.put(id, reservation.state());
            return DataAnalysisRestoreOutcome.ADDED;
        }

        @Override
        public DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest request) {
            releaseCallCount++;
            String id = request.reservation().reservationId();
            DataAnalysisReservationState current = ledger.getOrDefault(id, request.reservation().state());
            if (current == DataAnalysisReservationState.RELEASED) {
                return DataAnalysisReleaseOutcome.ALREADY_RELEASED;
            }
            ledger.put(id, DataAnalysisReservationState.RELEASED);
            transitionCount++;
            return DataAnalysisReleaseOutcome.RELEASED;
        }

        // ---- unused in fixture ----
        @Override public DataAnalysisReservation reserve(DataAnalysisOperationIdentity identity,
                                                          DataAnalysisEstimate estimate) {
            throw new UnsupportedOperationException();
        }
        @Override public DataAnalysisCapacityRecoveryReport recover(
                List<DataAnalysisReservation> durableReservations, int configuredMaxUnits,
                int configuredMaxHeavyActive) {
            throw new UnsupportedOperationException();
        }
        @Override public DataAnalysisAdmissionState admissionState() {
            throw new UnsupportedOperationException();
        }
    }

    /** Tracks upsert calls keyed by operationId (stable idempotency key per contract). */
    static class DurableUsageFake implements ToolJobUsageHook {
        final List<CallRecord> calls = new ArrayList<>();
        int callCount;

        @Override
        public boolean upsertUsage(String runId, ToolJobAnchor anchor) {
            callCount++;
            calls.add(new CallRecord(anchor.getOperationId(), runId));
            return true;
        }

        int distinctOperationIds() {
            return (int) calls.stream().map(c -> c.operationId).distinct().count();
        }

        record CallRecord(String operationId, String runId) {}
    }

    /**
     * Models the real {@code ToolJobEventHookImpl} dedupe contract:
     * key = {@code runId:toolCallId:logical_terminal}.
     * Tracks every invocation but only admits the first occurrence into the store.
     */
    static class DedupedEventFake implements ToolJobEventHook {
        final List<CallRecord> calls = new ArrayList<>();
        final Set<String> store = new LinkedHashSet<>();
        int callCount;

        @Override
        public boolean emitTerminalEvent(String runId, ToolJobAnchor anchor) {
            callCount++;
            String dedupeKey = runId + ":" + anchor.getToolCallId() + ":logical_terminal";
            calls.add(new CallRecord(runId, anchor.getToolCallId(), dedupeKey));
            store.add(dedupeKey);
            return true;
        }

        int distinctDedupeKeys() {
            return store.size();
        }

        record CallRecord(String runId, String toolCallId, String dedupeKey) {}
    }

    // ===== helpers =====

    private static ToolJobAnchor buildPersistedReadyAnchor(String runId, String toolCallId,
                                                            int attempt, String taskId) {
        ToolJobAnchor a = new ToolJobAnchor();
        a.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        a.setToolCallId(toolCallId);
        a.setAttempt(attempt);
        a.setTaskId(taskId);
        a.setFinalizerStep("RESUME_READY");
        a.setResumeState("READY");
        a.setResumeToken("token-" + runId);
        a.setResumeLeaseVersion(1);
        a.setResumeClaimedAt(Instant.now());
        return a;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String buildReservationJson(String runId, String toolCallId, int attempt,
                                                String taskId,
                                                DataAnalysisReservationState state) throws Exception {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                state, taskId, Instant.now());
        return objectMapper.writeValueAsString(reservation);
    }

    private static ToolJobAnchor buildTerminalAnchor(String runId, String toolCallId,
                                                       int attempt, String taskId,
                                                       String reservationJson) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        anchor.setToolCallId(toolCallId);
        anchor.setAttempt(attempt);
        anchor.setTaskId(taskId);
        anchor.setAutoResume(true);
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setEstimateJson(
                "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setReservationJson(reservationJson);
        anchor.setTerminalRetryable(false); // SUCCEEDED runs are not retryable
        return anchor;
    }
}
