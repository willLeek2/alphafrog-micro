package world.willfrog.agent.tools.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import world.willfrog.agent.platform.dataanalysis.DataAnalysisAdmissionState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.tools.python.DataAnalysisCapacityServiceImpl.CapacityAdmissionException;

/**
 * P0-08: DEGRADED admission on restart with overconfigured capacity.
 *
 * <p>Verifies that when the capacity ledger recovers from durable state where
 * the total used units exceed the current configured maximum, admission opens
 * in {@link DataAnalysisAdmissionState#DEGRADED}. New reservations are refused,
 * but existing reservations can still complete and release normally. Admission
 * stays DEGRADED after release (no auto-transition) and requires an explicit
 * recover() with in-range units to return to OPEN.
 */
class DataAnalysisCapacityServiceImplP008Test {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-12T15:00:00Z");

    private DataAnalysisCapacityProperties properties;
    private DataAnalysisCapacityServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new DataAnalysisCapacityProperties();
        // Defaults: maxUnits=4, maxActive=2, maxHeavyActive=1
        service = new DataAnalysisCapacityServiceImpl(properties);
    }

    private static DataAnalysisOperationIdentity identity(String runId, String toolCallId, int attempt) {
        return new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
    }

    private static DataAnalysisReservation taskAttachedHeavy(
            String runId, String toolCallId, int attempt, String taskId) {
        return new DataAnalysisReservation(
                identity(runId, toolCallId, attempt).reservationId(),
                identity(runId, toolCallId, attempt),
                DataAnalysisResourceClass.HEAVY,
                3,
                DataAnalysisReservationState.TASK_ATTACHED,
                taskId,
                FIXED_INSTANT);
    }

    private static DataAnalysisReservation taskAttachedStandard(
            String runId, String toolCallId, int attempt, String taskId) {
        return new DataAnalysisReservation(
                identity(runId, toolCallId, attempt).reservationId(),
                identity(runId, toolCallId, attempt),
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.TASK_ATTACHED,
                taskId,
                FIXED_INSTANT);
    }

    /**
     * Advance a TASK_ATTACHED reservation to TERMINAL_CONFIRMED for terminal release.
     * The new reservation shares the same identity, resource class, capacity units,
     * taskId, and acquiredAt so the state transition is valid.
     */
    private static DataAnalysisReservation toTerminalConfirmed(DataAnalysisReservation attached) {
        return new DataAnalysisReservation(
                attached.reservationId(),
                attached.identity(),
                attached.resourceClass(),
                attached.capacityUnits(),
                DataAnalysisReservationState.TERMINAL_CONFIRMED,
                attached.taskId(),
                attached.acquiredAt());
    }

    /**
     * Build a Terminal-proof release request for a TERMINAL_CONFIRMED reservation.
     */
    private static DataAnalysisReleaseRequest terminalReleaseRequest(
            DataAnalysisReservation terminalConfirmed) {
        DataAnalysisOperationIdentity id = terminalConfirmed.identity();
        DataAnalysisResourceClass resourceClass = terminalConfirmed.resourceClass();
        DataAnalysisEstimate estimate = resourceClass == DataAnalysisResourceClass.HEAVY
                ? heavyEstimate()
                : standardEstimate();
        DataAnalysisTerminalEnvelope envelope = new DataAnalysisTerminalEnvelope(
                id.runId(),
                id.toolCallId(),
                id.attempt(),
                id.operationId(),
                terminalConfirmed.taskId(),
                "SUCCEEDED",
                true,
                "preview",
                null,
                null,
                null,
                false,
                estimate,
                terminalConfirmed,
                DataAnalysisResourceUsage.missing(resourceClass),
                FIXED_INSTANT,
                false);
        return new DataAnalysisReleaseRequest(
                terminalConfirmed,
                new DataAnalysisReleaseProof.Terminal(envelope),
                DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
    }

    private static DataAnalysisEstimate standardEstimate() {
        return new DataAnalysisEstimate(
                100_000L,
                16L * 1024L * 1024L,
                1,
                0.2d,
                0,
                List.of(),
                DataAnalysisResourceClass.STANDARD,
                1);
    }

    private static DataAnalysisEstimate heavyEstimate() {
        return new DataAnalysisEstimate(
                300_000L,
                48L * 1024L * 1024L,
                2,
                0.4d,
                0,
                List.of("groupby-aggregation"),
                DataAnalysisResourceClass.HEAVY,
                3);
    }

    @Nested
    @DisplayName("P0-08: DEGRADED admission on restart with overconfigured capacity")
    class P008DegradedOnOverconfiguredRecover {

        @Test
        @DisplayName("Oracle 1,2: recover with units > maxUnits reports DEGRADED and usedUnits=6 preserved")
        void recoverOverconfiguredReportsDegradedAndPreservesUnits() {
            // Two HEAVY reservations = 6 units > default maxUnits=4
            DataAnalysisReservation h1 = taskAttachedHeavy("run-1", "call-1", 1, "task-1");
            DataAnalysisReservation h2 = taskAttachedHeavy("run-2", "call-2", 1, "task-2");

            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(h1, h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());

            // Oracle 1
            assertEquals(DataAnalysisAdmissionState.DEGRADED, service.admissionState());
            assertEquals(DataAnalysisAdmissionState.DEGRADED, report.admissionState());

            // Oracle 2: usedUnits = 6, preserved not truncated
            assertEquals(6, report.usedUnits());
            assertEquals(2, report.restoredReservations());
            assertEquals(2, report.activeCount());
            assertEquals(2, report.heavyActiveCount());
            assertTrue(report.overConfigured());
            assertTrue(report.heavyOverConfigured());
        }

        @Test
        @DisplayName("Oracle 3: reserve() throws SERVER_BUSY when DEGRADED")
        void reserveRejectedInDegraded() {
            // First, enter DEGRADED
            DataAnalysisReservation h1 = taskAttachedHeavy("run-1", "call-1", 1, "task-1");
            DataAnalysisReservation h2 = taskAttachedHeavy("run-2", "call-2", 1, "task-2");
            service.recover(List.of(h1, h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());

            // Oracle 3: reserve throws CapacityAdmissionException with SERVER_BUSY
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-3", "call-3", 1), standardEstimate()));
            assertEquals(CapacityAdmissionException.Reason.SERVER_BUSY, ex.reason());
        }

        @Test
        @DisplayName("Oracle 4,5: release succeeds on existing reservations; admission stays DEGRADED")
        void releaseSucceedsWhileDegradedAndStateDoesNotAutoTransition() {
            // Two HEAVY = 6 units > maxUnits=4 -> DEGRADED
            DataAnalysisReservation h1 = taskAttachedHeavy("run-1", "call-1", 1, "task-1");
            DataAnalysisReservation h2 = taskAttachedHeavy("run-2", "call-2", 1, "task-2");
            service.recover(List.of(h1, h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());

            // Advance h1 to TERMINAL_CONFIRMED so it can be released
            DataAnalysisReservation h1TerminalConfirmed = toTerminalConfirmed(h1);
            service.restoreReservation(h1TerminalConfirmed);

            // Oracle 4: existing reservation can release
            DataAnalysisReleaseRequest request = terminalReleaseRequest(h1TerminalConfirmed);
            assertEquals(DataAnalysisReleaseOutcome.RELEASED,
                    service.releaseReservation(request));

            // After releasing h1 (3 units), ledger has 3 usedUnits remaining.
            // But admission must NOT auto-transition.

            // Oracle 5: admission stays DEGRADED after release
            assertEquals(DataAnalysisAdmissionState.DEGRADED, service.admissionState());
        }

        @Test
        @DisplayName("Oracle 6: explicit recover with remaining units <= maxUnits returns OPEN")
        void explicitRecoverWithInRangeUnitsReturnsOpen() {
            // Two HEAVY = 6 units > maxUnits=4 -> DEGRADED
            DataAnalysisReservation h1 = taskAttachedHeavy("run-1", "call-1", 1, "task-1");
            DataAnalysisReservation h2 = taskAttachedHeavy("run-2", "call-2", 1, "task-2");
            service.recover(List.of(h1, h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());

            // Release h1 via terminal release
            DataAnalysisReservation h1TerminalConfirmed = toTerminalConfirmed(h1);
            service.restoreReservation(h1TerminalConfirmed);
            service.releaseReservation(terminalReleaseRequest(h1TerminalConfirmed));

            // Verify still DEGRADED before re-recover
            assertEquals(DataAnalysisAdmissionState.DEGRADED, service.admissionState());

            // Oracle 6: explicit recover with h2 only (3 units <= maxUnits=4) -> OPEN
            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());

            assertEquals(DataAnalysisAdmissionState.OPEN, service.admissionState());
            assertEquals(DataAnalysisAdmissionState.OPEN, report.admissionState());
            assertEquals(3, report.usedUnits());
            assertEquals(1, report.restoredReservations());
            assertEquals(1, report.activeCount());
            assertEquals(1, report.heavyActiveCount());
            assertTrue(report.conflicts().isEmpty());
        }

        @Test
        @DisplayName("Degraded also blocks reserve and restores work; release idempotent")
        void fullDegradedLifecycle() {
            DataAnalysisReservation h1 = taskAttachedHeavy("run-1", "call-1", 1, "task-1");
            DataAnalysisReservation h2 = taskAttachedHeavy("run-2", "call-2", 1, "task-2");

            // Recover overconfigured -> DEGRADED
            DataAnalysisCapacityRecoveryReport report1 = service.recover(
                    List.of(h1, h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());
            assertEquals(DataAnalysisAdmissionState.DEGRADED, report1.admissionState());
            assertEquals(6, report1.usedUnits());

            // New reserve blocked
            assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-3", "call-3", 1), standardEstimate()));

            // Release h1
            DataAnalysisReservation h1Tc = toTerminalConfirmed(h1);
            service.restoreReservation(h1Tc);
            DataAnalysisReleaseRequest req = terminalReleaseRequest(h1Tc);
            assertEquals(DataAnalysisReleaseOutcome.RELEASED, service.releaseReservation(req));

            // Idempotent release
            assertEquals(DataAnalysisReleaseOutcome.ALREADY_RELEASED,
                    service.releaseReservation(req));

            // Still DEGRADED (no auto-transition)
            assertEquals(DataAnalysisAdmissionState.DEGRADED, service.admissionState());

            // Recover with only h2 (3 units <= 4) -> OPEN
            DataAnalysisCapacityRecoveryReport report2 = service.recover(
                    List.of(h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());
            assertEquals(DataAnalysisAdmissionState.OPEN, report2.admissionState());

            // Now new reservations accepted
            DataAnalysisReservation fresh = service.reserve(
                    identity("run-3", "call-3", 1), standardEstimate());
            assertEquals(DataAnalysisReservationState.PREPARING, fresh.state());
        }
    }

    @Nested
    @DisplayName("P0-08: mixed resource class overconfiguration")
    class MixedResourceOverconfiguration {

        @Test
        @DisplayName("Mixed STANDARD+HEAVY exceeding maxUnits reports DEGRADED")
        void mixedClassesOverUnitsCap() {
            // 2 STANDARD (2 units) + 2 HEAVY (6 units) = 8 units > maxUnits=4
            DataAnalysisReservation s1 = taskAttachedStandard("run-1", "call-1", 1, "task-1");
            DataAnalysisReservation s2 = taskAttachedStandard("run-2", "call-2", 1, "task-2");
            DataAnalysisReservation h1 = taskAttachedHeavy("run-3", "call-3", 1, "task-3");
            DataAnalysisReservation h2 = taskAttachedHeavy("run-4", "call-4", 1, "task-4");

            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(s1, s2, h1, h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());

            assertEquals(DataAnalysisAdmissionState.DEGRADED, report.admissionState());
            assertEquals(8, report.usedUnits());
            assertEquals(4, report.restoredReservations());
            assertEquals(2, report.heavyActiveCount());
            assertTrue(report.overConfigured());
            assertTrue(report.heavyOverConfigured());
        }

        @Test
        @DisplayName("Release all overconfigured then recover to OPEN")
        void releaseAllOverconfiguredThenRecoverToOpen() {
            DataAnalysisReservation h1 = taskAttachedHeavy("run-1", "call-1", 1, "task-1");
            DataAnalysisReservation h2 = taskAttachedHeavy("run-2", "call-2", 1, "task-2");

            service.recover(List.of(h1, h2),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());
            assertEquals(DataAnalysisAdmissionState.DEGRADED, service.admissionState());

            // Release both
            DataAnalysisReservation h1Tc = toTerminalConfirmed(h1);
            service.restoreReservation(h1Tc);
            service.releaseReservation(terminalReleaseRequest(h1Tc));

            DataAnalysisReservation h2Tc = toTerminalConfirmed(h2);
            service.restoreReservation(h2Tc);
            service.releaseReservation(terminalReleaseRequest(h2Tc));

            // Still DEGRADED after releases
            assertEquals(DataAnalysisAdmissionState.DEGRADED, service.admissionState());

            // Recover with empty list -> OPEN
            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(),
                    properties.getMaxUnits(),
                    properties.getMaxHeavyActive());

            assertEquals(DataAnalysisAdmissionState.OPEN, report.admissionState());
            assertEquals(0, report.usedUnits());
            assertEquals(0, report.activeCount());
        }
    }
}
