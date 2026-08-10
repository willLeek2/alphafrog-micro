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

    @Test
    void releaseSuccessThenStepWriteFailsReentryHandlesAlreadyReleased() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        StatefulCapacityFake capacityFake = new StatefulCapacityFake();
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);

        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true)   // ENVELOPE succeeds
                .thenReturn(false)  // RELEASE DB write FAILS ← injection point
                .thenReturn(true);
        when(anchorService.updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        // task #115: shared atomic promote wins; loadAnchor returns persisted READY
        when(anchorService.promoteCasStatusToResumeReady(
                eq("run-1"), eq("run-1:tc-1:1"), eq("tc-1"), eq(1), eq("task-1"),
                eq(0L), anyString())).thenReturn(1);
        when(anchorService.loadAnchor("run-1"))
                .thenReturn(buildPersistedReadyAnchor("run-1", "tc-1", 1, "task-1"));

        when(usageHook.upsertUsage(eq("run-1"), any(ToolJobAnchor.class))).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-1"), any(ToolJobAnchor.class))).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService,
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        String reservationJson = buildReservationJson("run-1", "tc-1", 1, "task-1",
                DataAnalysisReservationState.PENDING_TRANSFERRED);

        // === First call: RELEASE succeeds at capacity ledger, DB step write fails ===
        ToolJobAnchor anchor1 = buildTerminalAnchor("run-1", "tc-1", 1, "task-1", reservationJson);
        finalizer.handleTerminal("run-1", anchor1, "SUCCEEDED", null, true);

        // Ledger: one release call, one transition (reserved → released)
        assertThat(capacityFake.releaseCallCount).isEqualTo(1);
        assertThat(capacityFake.transitionCount).isEqualTo(1);
        assertThat(capacityFake.distinctReservationIds()).isEqualTo(1);
        verify(anchorService, times(2)).updateAnchor(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(resumeService, never()).tryResume(any());

        // === Second call: re-entry, ALREADY_RELEASED recognized ===
        ToolJobAnchor anchor2 = buildTerminalAnchor("run-1", "tc-1", 1, "task-1", reservationJson);
        finalizer.handleTerminal("run-1", anchor2, "SUCCEEDED", null, true);

        // Ledger: two release calls total, but only one transition
        assertThat(capacityFake.releaseCallCount).isEqualTo(2);
        assertThat(capacityFake.transitionCount).isEqualTo(1);
        // Same reservation identity throughout
        assertThat(capacityFake.distinctReservationIds()).isEqualTo(1);
        verify(resumeService, times(1)).tryResume("run-1");
    }

    @Test
    void releaseSuccessThenStepWriteFailsRestoreConflictThenAlreadyReleased() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        // Pre-seed the ledger as already RELEASED (simulates prior crash/unrelated release)
        StatefulCapacityFake capacityFake = new StatefulCapacityFake();
        capacityFake.preSeedReleased("run-2:tc-2:1");
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);

        when(anchorService.updateAnchor(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true)
                .thenReturn(false)
                .thenReturn(true);
        when(anchorService.updateAnchor(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        // task #115: shared atomic promote wins; loadAnchor returns persisted READY
        when(anchorService.promoteCasStatusToResumeReady(
                eq("run-2"), eq("run-2:tc-2:1"), eq("tc-2"), eq(1), eq("task-2"),
                eq(0L), anyString())).thenReturn(1);
        when(anchorService.loadAnchor("run-2"))
                .thenReturn(buildPersistedReadyAnchor("run-2", "tc-2", 1, "task-2"));

        when(usageHook.upsertUsage(eq("run-2"), any(ToolJobAnchor.class))).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-2"), any(ToolJobAnchor.class))).thenReturn(true);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService,
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        String reservationJson = buildReservationJson("run-2", "tc-2", 1, "task-2",
                DataAnalysisReservationState.PENDING_TRANSFERRED);

        ToolJobAnchor anchor1 = buildTerminalAnchor("run-2", "tc-2", 1, "task-2", reservationJson);
        finalizer.handleTerminal("run-2", anchor1, "SUCCEEDED", null, true);

        // Already released in ledger → ALREADY_RELEASED, no new transition
        assertThat(capacityFake.releaseCallCount).isEqualTo(1);
        assertThat(capacityFake.transitionCount).isEqualTo(0);
        assertThat(capacityFake.distinctReservationIds()).isEqualTo(1);
        verify(anchorService, times(2)).updateAnchor(eq("run-2"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB));

        ToolJobAnchor anchor2 = buildTerminalAnchor("run-2", "tc-2", 1, "task-2", reservationJson);
        finalizer.handleTerminal("run-2", anchor2, "SUCCEEDED", null, true);

        // Two total calls, still zero transitions (already released)
        assertThat(capacityFake.releaseCallCount).isEqualTo(2);
        assertThat(capacityFake.transitionCount).isEqualTo(0);
        assertThat(capacityFake.distinctReservationIds()).isEqualTo(1);
        verify(resumeService, times(1)).tryResume("run-2");
    }

    // ===== Fixture 2 =====

    @Test
    void usageHookSuccessThenStepWriteFailsReentryProducesSingleDurableRecord() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);

        when(anchorService.updateAnchor(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true)   // ENVELOPE
                .thenReturn(true)   // RELEASE
                .thenReturn(false)  // USAGE DB write FAILS ← injection point
                .thenReturn(true);
        when(anchorService.updateAnchor(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-3"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        // task #115: shared atomic promote wins; loadAnchor returns persisted READY
        when(anchorService.promoteCasStatusToResumeReady(
                eq("run-3"), eq("run-3:tc-3:1"), eq("tc-3"), eq(1), eq("task-3"),
                eq(0L), anyString())).thenReturn(1);
        when(anchorService.loadAnchor("run-3"))
                .thenReturn(buildPersistedReadyAnchor("run-3", "tc-3", 1, "task-3"));

        String releasedJson = buildReservationJson("run-3", "tc-3", 1, "task-3",
                DataAnalysisReservationState.RELEASED);
        when(capacityService.restoreReservation(any(DataAnalysisReservation.class)))
                .thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any(DataAnalysisReleaseRequest.class)))
                .thenReturn(DataAnalysisReleaseOutcome.RELEASED);

        DurableUsageFake usageFake = new DurableUsageFake();
        DedupedEventFake eventFake = new DedupedEventFake();

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityService, resumeService,
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", (ToolJobUsageHook) usageFake);
        inject(finalizer, "eventHook", (ToolJobEventHook) eventFake);

        // === First call: USAGE hook succeeds, DB step write fails ===
        ToolJobAnchor anchor1 = buildTerminalAnchor("run-3", "tc-3", 1, "task-3", releasedJson);
        finalizer.handleTerminal("run-3", anchor1, "SUCCEEDED", null, true);

        assertThat(usageFake.callCount).isEqualTo(1);
        assertThat(usageFake.distinctOperationIds()).isEqualTo(1);
        String firstOpId = usageFake.calls.get(0).operationId;

        // === Second call: re-entry, hook called again with same operationId ===
        ToolJobAnchor anchor2 = buildTerminalAnchor("run-3", "tc-3", 1, "task-3", releasedJson);
        anchor2.setFinalizerStep("RELEASE");
        finalizer.handleTerminal("run-3", anchor2, "SUCCEEDED", null, true);

        assertThat(usageFake.callCount).isEqualTo(2);
        assertThat(usageFake.calls.get(0).operationId).isEqualTo(firstOpId);
        assertThat(usageFake.calls.get(1).operationId).isEqualTo(firstOpId);
        assertThat(usageFake.distinctOperationIds()).isEqualTo(1);
        verify(resumeService, times(1)).tryResume("run-3");
        assertThat(eventFake.callCount).isEqualTo(1);
        assertThat(eventFake.distinctDedupeKeys()).isEqualTo(1);
    }

    @Test
    void eventHookSuccessThenStepWriteFailsReentryProducesSingleDurableEvent() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        DataAnalysisCapacityService capacityService = mock(DataAnalysisCapacityService.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);

        when(anchorService.updateAnchor(eq("run-4"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(false)  // EVENT DB write FAILS ← injection point
                .thenReturn(true);
        when(anchorService.updateAnchor(eq("run-4"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED))).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-4"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        // task #115: shared atomic promote wins; loadAnchor returns persisted READY
        when(anchorService.promoteCasStatusToResumeReady(
                eq("run-4"), eq("run-4:tc-4:1"), eq("tc-4"), eq(1), eq("task-4"),
                eq(0L), anyString())).thenReturn(1);
        when(anchorService.loadAnchor("run-4"))
                .thenReturn(buildPersistedReadyAnchor("run-4", "tc-4", 1, "task-4"));

        String releasedJson = buildReservationJson("run-4", "tc-4", 1, "task-4",
                DataAnalysisReservationState.RELEASED);
        when(capacityService.restoreReservation(any(DataAnalysisReservation.class)))
                .thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any(DataAnalysisReleaseRequest.class)))
                .thenReturn(DataAnalysisReleaseOutcome.RELEASED);

        DurableUsageFake usageFake = new DurableUsageFake();
        DedupedEventFake eventFake = new DedupedEventFake();

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityService, resumeService,
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", (ToolJobUsageHook) usageFake);
        inject(finalizer, "eventHook", (ToolJobEventHook) eventFake);

        // === First call: EVENT hook succeeds, DB step write fails ===
        ToolJobAnchor anchor1 = buildTerminalAnchor("run-4", "tc-4", 1, "task-4", releasedJson);
        anchor1.setFinalizerStep("USAGE");
        anchor1.setUsagePersisted(true);
        finalizer.handleTerminal("run-4", anchor1, "SUCCEEDED", null, true);

        assertThat(eventFake.callCount).isEqualTo(1);
        assertThat(eventFake.distinctDedupeKeys()).isEqualTo(1);
        String firstDedupeKey = eventFake.calls.get(0).dedupeKey;

        // === Second call: re-entry, event hook uses same dedupe key ===
        ToolJobAnchor anchor2 = buildTerminalAnchor("run-4", "tc-4", 1, "task-4", releasedJson);
        anchor2.setFinalizerStep("USAGE");
        anchor2.setUsagePersisted(true);
        finalizer.handleTerminal("run-4", anchor2, "SUCCEEDED", null, true);

        assertThat(eventFake.callCount).isEqualTo(2);
        assertThat(eventFake.calls.get(0).dedupeKey).isEqualTo(firstDedupeKey);
        assertThat(eventFake.calls.get(1).dedupeKey).isEqualTo(firstDedupeKey);
        assertThat(eventFake.distinctDedupeKeys()).isEqualTo(1);
        verify(resumeService, times(1)).tryResume("run-4");
    }

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
