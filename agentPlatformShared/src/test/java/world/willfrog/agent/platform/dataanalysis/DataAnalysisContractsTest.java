package world.willfrog.agent.platform.dataanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

class DataAnalysisContractsTest {

    private static final String A_SHA = "a".repeat(64);
    private static final String B_SHA = "b".repeat(64);
    private static final String C_SHA = "c".repeat(64);

    @Test
    void canonicalCreateSpecHasStableGoldenFingerprint() {
        CanonicalSandboxCreateSpec spec = createSpec(60_000L);

        assertEquals(
                "sha256:bb9cae49af46c9abd9c78ffdebd579266b666dc7ae994bd8afdd8b4e8c7e643c",
                spec.requestFingerprint());
        assertEquals(spec.requestFingerprint(), createSpec(60_000L).requestFingerprint());
    }

    @Test
    void canonicalCreateSpecChangesFingerprintForSemanticChanges() {
        assertNotEquals(
                createSpec(60_000L).requestFingerprint(),
                createSpec(90_000L).requestFingerprint());
    }

    @Test
    void canonicalCreateSpecRejectsInvalidDigests() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CanonicalSandboxCreateSpec(
                        CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION,
                        "run-1:call-1:1",
                        "not-a-digest",
                        A_SHA,
                        DataAnalysisResourceClass.STANDARD,
                        536_870_912L,
                        60_000L,
                        "python-3.12-image-v1",
                        B_SHA,
                        C_SHA));
    }

    @Test
    void operationIdentityUsesRunToolCallAndAttempt() {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                "run-1",
                "call-1",
                2);

        assertEquals("run-1:call-1:2", identity.operationId());
        assertEquals(identity.operationId(), identity.reservationId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisOperationIdentity("run:1", "call-1", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisReservation(
                        "another-reservation",
                        identity,
                        DataAnalysisResourceClass.STANDARD,
                        1,
                        DataAnalysisReservationState.PREPARING,
                        null,
                        Instant.parse("2026-07-12T00:00:00Z")));
    }

    @Test
    void pendingDispatchRequiresMatchingReservationTask() {
        DataAnalysisOperationIdentity identity = identity();
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-1",
                Instant.parse("2026-07-12T00:00:00Z"));

        PythonSandboxDispatchOutcome.Pending pending = new PythonSandboxDispatchOutcome.Pending(
                "run-1:call-1:1",
                createSpec(60_000L).requestFingerprint(),
                "task-1",
                10_000L,
                1_000L,
                AgentRunDatasetSnapshot.empty(),
                reservation);

        assertEquals("task-1", pending.taskId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PythonSandboxDispatchOutcome.Pending(
                        "run-1:call-1:1",
                        createSpec(60_000L).requestFingerprint(),
                        "task-2",
                        10_000L,
                        1_000L,
                        AgentRunDatasetSnapshot.empty(),
                        reservation));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PythonSandboxDispatchOutcome.Pending(
                        "run-1:call-other:1",
                        createSpec(60_000L).requestFingerprint(),
                        "task-1",
                        10_000L,
                        1_000L,
                        AgentRunDatasetSnapshot.empty(),
                        reservation));
    }

    @Test
    void recoveryReportCannotOpenWithConflicts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisCapacityRecoveryReport(
                        1,
                        1,
                        0,
                        1,
                        4,
                        1,
                        false,
                        false,
                        List.of("reservation conflict"),
                        DataAnalysisAdmissionState.OPEN));
    }

    @Test
    void recoveryReportCannotOpenWhenHeavyLimitExceeded() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisCapacityRecoveryReport(
                        2,
                        2,
                        2,
                        4,
                        4,
                        1,
                        false,
                        true,
                        List.of(),
                        DataAnalysisAdmissionState.OPEN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisCapacityRecoveryReport(
                        1,
                        1,
                        2,
                        1,
                        4,
                        2,
                        false,
                        false,
                        List.of(),
                        DataAnalysisAdmissionState.RECOVERING));
    }

    @Test
    void completeUsageRejectsNullRequiredMeasuredField() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisResourceUsage(
                        DataAnalysisResourceClass.STANDARD,
                        null,
                        1L,
                        null,
                        1L,
                        null,
                        null,
                        1L,
                        1L,
                        1L,
                        1L,
                        1,
                        "SUCCESS",
                        false,
                        false,
                        true,
                        null,
                        List.of()));
    }

    @Test
    void partialUsageRejectsEmptyOrInconsistentMissingFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> usage(false, null, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> usage(false, 1L, List.of("cpuMillis")));
        assertEquals(
                DataAnalysisResourceUsage.P0_REQUIRED_MEASURED_FIELDS.stream().sorted().toList(),
                DataAnalysisResourceUsage.missing(DataAnalysisResourceClass.STANDARD).missingFields());
    }

    @Test
    void terminalEnvelopeCrossChecksIdentityReservationAndBoundedResult() {
        DataAnalysisReservation reservation = terminalReservation();
        DataAnalysisEstimate estimate = estimate();
        DataAnalysisResourceUsage usage = usage(true, 1L, List.of());

        DataAnalysisTerminalEnvelope envelope = new DataAnalysisTerminalEnvelope(
                "run-1",
                "call-1",
                1,
                identity().operationId(),
                "task-1",
                "COMPLETED",
                true,
                "{\"ok\":true}",
                null,
                null,
                null,
                false,
                estimate,
                reservation,
                usage,
                Instant.parse("2026-07-12T00:01:00Z"),
                true);

        assertEquals(identity().operationId(), envelope.operationId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisTerminalEnvelope(
                        "run-1",
                        "call-1",
                        1,
                        "run-1:wrong:1",
                        "task-1",
                        "COMPLETED",
                        true,
                        "ok",
                        null,
                        null,
                        null,
                        false,
                        estimate,
                        reservation,
                        usage,
                        Instant.parse("2026-07-12T00:01:00Z"),
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisTerminalEnvelope(
                        "run-1",
                        "call-1",
                        1,
                        identity().operationId(),
                        "task-1",
                        "COMPLETED",
                        true,
                        "x".repeat(DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES + 1),
                        null,
                        null,
                        null,
                        false,
                        estimate,
                        reservation,
                        usage,
                        Instant.parse("2026-07-12T00:01:00Z"),
                        false));
    }

    @Test
    void releaseRequiresMatchingDurableTerminalProof() {
        DataAnalysisReservation reservation = terminalReservation();
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisReleaseRequest(
                        reservation,
                        null,
                        DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED));
    }

    @Test
    void reservationStateFreezesTerminalProofLifecycle() {
        assertEquals(
                true,
                DataAnalysisReservationState.PREPARING.canTransitionTo(
                        DataAnalysisReservationState.TASK_ATTACHED));
        assertEquals(
                true,
                DataAnalysisReservationState.TASK_ATTACHED.canTransitionTo(
                        DataAnalysisReservationState.PENDING_TRANSFERRED));
        assertEquals(
                true,
                DataAnalysisReservationState.PENDING_TRANSFERRED.canTransitionTo(
                        DataAnalysisReservationState.TERMINAL_CONFIRMED));
        assertEquals(
                false,
                DataAnalysisReservationState.PENDING_TRANSFERRED.canTransitionTo(
                        DataAnalysisReservationState.RELEASED));
    }

    @Test
    void restoreContractDistinguishesAddedSameAndConflict() {
        StubCapacityService service = new StubCapacityService();
        DataAnalysisReservation first = preparingReservation(
                DataAnalysisResourceClass.STANDARD,
                1);
        DataAnalysisReservation conflicting = preparingReservation(
                DataAnalysisResourceClass.HEAVY,
                3);

        assertEquals(DataAnalysisRestoreOutcome.ADDED, service.restoreReservation(first));
        assertEquals(
                DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME,
                service.restoreReservation(first));
        assertEquals(
                DataAnalysisRestoreOutcome.CONFLICT,
                service.restoreReservation(conflicting));
    }

    @Test
    void resourceClassCapacityUnitsAreFrozen() {
        assertEquals(1, DataAnalysisResourceClass.STANDARD.defaultCapacityUnits());
        assertEquals(3, DataAnalysisResourceClass.HEAVY.defaultCapacityUnits());
    }

    private CanonicalSandboxCreateSpec createSpec(long timeoutMillis) {
        return new CanonicalSandboxCreateSpec(
                CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION,
                "run-1:call-1:1",
                A_SHA,
                A_SHA,
                DataAnalysisResourceClass.STANDARD,
                536_870_912L,
                timeoutMillis,
                "python-3.12-image-v1",
                B_SHA,
                C_SHA);
    }

    private DataAnalysisOperationIdentity identity() {
        return new DataAnalysisOperationIdentity("run-1", "call-1", 1);
    }

    private DataAnalysisEstimate estimate() {
        return new DataAnalysisEstimate(
                150_000L,
                10_000_000L,
                10,
                1.0d,
                10,
                List.of(),
                DataAnalysisResourceClass.STANDARD,
                1);
    }

    private DataAnalysisReservation terminalReservation() {
        return new DataAnalysisReservation(
                identity().reservationId(),
                identity(),
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.TERMINAL_CONFIRMED,
                "task-1",
                Instant.parse("2026-07-12T00:00:00Z"));
    }

    private DataAnalysisReservation preparingReservation(
            DataAnalysisResourceClass resourceClass,
            int capacityUnits) {
        return new DataAnalysisReservation(
                identity().reservationId(),
                identity(),
                resourceClass,
                capacityUnits,
                DataAnalysisReservationState.PREPARING,
                null,
                Instant.parse("2026-07-12T00:00:00Z"));
    }

    private DataAnalysisResourceUsage usage(
            boolean complete,
            Long cpuMillis,
            List<String> missingFields) {
        return new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                cpuMillis,
                1L,
                null,
                1L,
                null,
                null,
                1L,
                1L,
                1L,
                1L,
                1,
                "SUCCESS",
                false,
                false,
                complete,
                null,
                missingFields);
    }

    private static final class StubCapacityService implements DataAnalysisCapacityService {

        private final Map<String, DataAnalysisReservation> reservations = new HashMap<>();

        @Override
        public DataAnalysisReservation reserve(
                DataAnalysisOperationIdentity identity,
                DataAnalysisEstimate estimate) {
            throw new UnsupportedOperationException("not needed by restore contract test");
        }

        @Override
        public DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation reservation) {
            DataAnalysisReservation existing = reservations.putIfAbsent(
                    reservation.reservationId(),
                    reservation);
            if (existing == null) {
                return DataAnalysisRestoreOutcome.ADDED;
            }
            return existing.equals(reservation)
                    ? DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME
                    : DataAnalysisRestoreOutcome.CONFLICT;
        }

        @Override
        public DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest request) {
            throw new UnsupportedOperationException("not needed by restore contract test");
        }

        @Override
        public DataAnalysisCapacityRecoveryReport recover(
                List<DataAnalysisReservation> durableReservations,
                int configuredMaxUnits,
                int configuredMaxHeavyActive) {
            throw new UnsupportedOperationException("not needed by restore contract test");
        }

        @Override
        public DataAnalysisAdmissionState admissionState() {
            return DataAnalysisAdmissionState.OPEN;
        }
    }
}
