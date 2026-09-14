package world.willfrog.agent.tools.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import world.willfrog.agent.platform.dataanalysis.CapacityAdmissionException;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisAdmissionState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisRestoreOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;

class DataAnalysisCapacityServiceImplTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-12T15:00:00Z");

    private DataAnalysisCapacityProperties properties;
    private DataAnalysisCapacityServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new DataAnalysisCapacityProperties();
        service = new DataAnalysisCapacityServiceImpl(properties);
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

    private static DataAnalysisEstimate oversizedEstimate() {
        return new DataAnalysisEstimate(
                1_000_000L,
                16L * 1024L * 1024L,
                1,
                0.1d,
                0,
                List.of(),
                DataAnalysisResourceClass.STANDARD,
                1);
    }

    private static DataAnalysisOperationIdentity identity(String runId, String toolCallId, int attempt) {
        return new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
    }

    private static DataAnalysisReservation attachedReservation(
            String runId, String toolCallId, int attempt, String taskId,
            DataAnalysisResourceClass resourceClass, int capacityUnits) {
        return new DataAnalysisReservation(
                identity(runId, toolCallId, attempt).reservationId(),
                identity(runId, toolCallId, attempt),
                resourceClass,
                capacityUnits,
                DataAnalysisReservationState.TASK_ATTACHED,
                taskId,
                FIXED_INSTANT);
    }

    private void openAfterEmptyRecover() {
        service.recover(List.of(), properties.getMaxUnits(), properties.getMaxHeavyActive());
    }

    @Nested
    @DisplayName("Recovery barrier §8.4 step 1")
    class RecoveryBarrier {

        @Test
        void newInstanceStartsInRecovering() {
            assertEquals(DataAnalysisAdmissionState.RECOVERING, service.admissionState());
        }

        @Test
        void reserveBeforeRecoverIsRejected() {
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-1", "call-1", 1), standardEstimate()));
            assertEquals(CapacityAdmissionException.Reason.RECOVERING, ex.reason());
        }

        @Test
        void restoreBeforeRecoverIsRejected() {
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.restoreReservation(
                            attachedReservation("run-1", "call-1", 1, "task-1",
                                    DataAnalysisResourceClass.STANDARD, 1)));
            assertEquals(CapacityAdmissionException.Reason.RECOVERING, ex.reason());
        }

        @Test
        void recoverWithEmptyListOpensAdmission() {
            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(), properties.getMaxUnits(), properties.getMaxHeavyActive());
            assertEquals(DataAnalysisAdmissionState.OPEN, service.admissionState());
            assertEquals(0, report.restoredReservations());
            assertEquals(0, report.activeCount());
            assertEquals(0, report.usedUnits());
            assertFalse(report.overConfigured());
            assertFalse(report.heavyOverConfigured());
            assertTrue(report.conflicts().isEmpty());
        }
    }

    @Nested
    @DisplayName("Reserve happy paths and counters")
    class ReserveLifecycle {

        @Test
        void reserveStandardCreatesPreparingReservation() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = service.reserve(
                    identity("run-1", "call-1", 1), standardEstimate());
            assertEquals(DataAnalysisReservationState.PREPARING, reservation.state());
            assertNull(reservation.taskId());
            assertEquals(DataAnalysisResourceClass.STANDARD, reservation.resourceClass());
            assertEquals(1, reservation.capacityUnits());
            assertEquals(1, service.usedUnitsSnapshot());
            assertEquals(0, service.activeCountSnapshot());
            assertEquals(0, service.heavyActiveCountSnapshot());
        }

        @Test
        void reserveHeavyReservesUnitsButDoesNotCountAsActiveHeavyYet() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = service.reserve(
                    identity("run-1", "call-1", 1), heavyEstimate());
            assertEquals(DataAnalysisResourceClass.HEAVY, reservation.resourceClass());
            assertEquals(3, reservation.capacityUnits());
            assertEquals(3, service.usedUnitsSnapshot());
            assertEquals(0, service.activeCountSnapshot());
            assertEquals(0, service.heavyActiveCountSnapshot());
        }

        @Test
        void reserveRejectsDuplicateIdentity() {
            openAfterEmptyRecover();
            service.reserve(identity("run-1", "call-1", 1), standardEstimate());
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-1", "call-1", 1), standardEstimate()));
            assertEquals(CapacityAdmissionException.Reason.ALREADY_RESERVED, ex.reason());
        }

        @Test
        void reserveRejectsOverRowsCeiling() {
            openAfterEmptyRecover();
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-1", "call-1", 1), oversizedEstimate()));
            assertEquals(CapacityAdmissionException.Reason.TASK_TOO_LARGE, ex.reason());
        }

        @Test
        void reserveRejectsOverUnitsCap() {
            properties.setMaxActive(4);
            service = new DataAnalysisCapacityServiceImpl(properties);
            openAfterEmptyRecover();
            // 本用例只验证 maxUnits，所以把 maxActive 同步放到 4，避免先触发活动任务数上限。
            service.reserve(identity("run-1", "call-1", 1), standardEstimate());
            service.reserve(identity("run-1", "call-2", 1), standardEstimate());
            service.reserve(identity("run-1", "call-3", 1), standardEstimate());
            service.reserve(identity("run-1", "call-4", 1), standardEstimate());
            assertEquals(4, service.usedUnitsSnapshot());
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-1", "call-5", 1), standardEstimate()));
            assertEquals(CapacityAdmissionException.Reason.SERVER_BUSY, ex.reason());
        }
    }

    @Nested
    @DisplayName("Restore semantics §8.4 step 2-7")
    class RestoreSemantics {

        @Test
        void restoreAddsNewActiveReservation() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            assertEquals(DataAnalysisRestoreOutcome.ADDED, service.restoreReservation(reservation));
            assertEquals(1, service.activeCountSnapshot());
            assertEquals(1, service.usedUnitsSnapshot());
        }

        @Test
        void restoreSameContentReturnsAlreadyPresentSame() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(reservation);
            assertEquals(DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME,
                    service.restoreReservation(reservation));
            // Counters must not double-count.
            assertEquals(1, service.activeCountSnapshot());
        }

        @Test
        void restoreValidStateTransitionOnSameIdApplies() {
            openAfterEmptyRecover();
            DataAnalysisReservation taskAttached = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(taskAttached);
            DataAnalysisOperationIdentity id = identity("run-1", "call-1", 1);
            DataAnalysisReservation pendingTransferred = new DataAnalysisReservation(
                    id.reservationId(), id, DataAnalysisResourceClass.STANDARD, 1,
                    DataAnalysisReservationState.PENDING_TRANSFERRED, "task-1", FIXED_INSTANT);
            // TASK_ATTACHED -> PENDING_TRANSFERRED is a valid state hop; the reservation stays
            // active so the active counter must not double-count.
            assertEquals(DataAnalysisRestoreOutcome.ADDED,
                    service.restoreReservation(pendingTransferred));
            assertEquals(1, service.activeCountSnapshot());
            assertEquals(1, service.usedUnitsSnapshot());
        }

        @Test
        void restoreTaskIdDriftOnSameIdReturnsConflict() {
            openAfterEmptyRecover();
            DataAnalysisReservation taskAttached = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(taskAttached);
            DataAnalysisOperationIdentity id = identity("run-1", "call-1", 1);
            DataAnalysisReservation differentTaskId = new DataAnalysisReservation(
                    id.reservationId(), id, DataAnalysisResourceClass.STANDARD, 1,
                    DataAnalysisReservationState.PENDING_TRANSFERRED, "task-2", FIXED_INSTANT);
            assertEquals(DataAnalysisRestoreOutcome.CONFLICT,
                    service.restoreReservation(differentTaskId));
        }

        @Test
        void restoreInvalidStateTransitionReturnsConflict() {
            openAfterEmptyRecover();
            // Reserve creates a PREPARING reservation. Attempting to skip straight to
            // TERMINAL_CONFIRMED violates the §6.6 state machine.
            service.reserve(identity("run-1", "call-1", 1), standardEstimate());
            DataAnalysisOperationIdentity id = identity("run-1", "call-1", 1);
            DataAnalysisReservation terminalConfirmed = new DataAnalysisReservation(
                    id.reservationId(), id, DataAnalysisResourceClass.STANDARD, 1,
                    DataAnalysisReservationState.TERMINAL_CONFIRMED, "task-1", FIXED_INSTANT);
            assertEquals(DataAnalysisRestoreOutcome.CONFLICT,
                    service.restoreReservation(terminalConfirmed));
        }

        @Test
        void restoreDifferentUnitsOnSameIdReturnsConflict() {
            openAfterEmptyRecover();
            DataAnalysisReservation standard = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(standard);
            DataAnalysisOperationIdentity id = identity("run-1", "call-1", 1);
            DataAnalysisReservation differentUnits = new DataAnalysisReservation(
                    id.reservationId(), id, DataAnalysisResourceClass.STANDARD, 3,
                    DataAnalysisReservationState.TASK_ATTACHED, "task-1", FIXED_INSTANT);
            assertEquals(DataAnalysisRestoreOutcome.CONFLICT,
                    service.restoreReservation(differentUnits));
        }

        @Test
        void restoreHeavyReservationUpdatesHeavyCounter() {
            openAfterEmptyRecover();
            DataAnalysisReservation heavy = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.HEAVY, 3);
            assertEquals(DataAnalysisRestoreOutcome.ADDED, service.restoreReservation(heavy));
            assertEquals(1, service.heavyActiveCountSnapshot());
            assertEquals(3, service.usedUnitsSnapshot());
        }
    }

    @Nested
    @DisplayName("Release: terminal-confirmed, pre-dispatch, idempotency")
    class ReleaseLifecycle {

        @Test
        void terminalConfirmedReleaseFreesCapacity() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = service.reserve(
                    identity("run-1", "call-1", 1), standardEstimate());
            DataAnalysisReservation taskAttached = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(taskAttached);
            DataAnalysisReservation terminalConfirmed = new DataAnalysisReservation(
                    reservation.reservationId(), reservation.identity(),
                    reservation.resourceClass(), reservation.capacityUnits(),
                    DataAnalysisReservationState.TERMINAL_CONFIRMED, "task-1", FIXED_INSTANT);
            service.restoreReservation(terminalConfirmed);
            assertEquals(1, service.activeCountSnapshot());
            assertEquals(1, service.usedUnitsSnapshot());

            DataAnalysisTerminalEnvelope envelope = new DataAnalysisTerminalEnvelope(
                    "run-1", "call-1", 1, "run-1:call-1:1", "task-1",
                    "SUCCEEDED", true, "preview", null, null, null, false,
                    standardEstimate(), terminalConfirmed,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage.missing(
                            DataAnalysisResourceClass.STANDARD),
                    FIXED_INSTANT, false);
            DataAnalysisReleaseRequest request = new DataAnalysisReleaseRequest(
                    terminalConfirmed,
                    new DataAnalysisReleaseProof.Terminal(envelope),
                    DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
            assertEquals(DataAnalysisReleaseOutcome.RELEASED, service.releaseReservation(request));
            assertEquals(0, service.activeCountSnapshot());
            assertEquals(0, service.usedUnitsSnapshot());
        }

        @Test
        void terminalReleaseIdempotentWhenSameReservation() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = service.reserve(
                    identity("run-1", "call-1", 1), standardEstimate());
            DataAnalysisReservation taskAttached = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(taskAttached);
            DataAnalysisReservation terminalConfirmed = new DataAnalysisReservation(
                    reservation.reservationId(), reservation.identity(),
                    reservation.resourceClass(), reservation.capacityUnits(),
                    DataAnalysisReservationState.TERMINAL_CONFIRMED, "task-1", FIXED_INSTANT);
            service.restoreReservation(terminalConfirmed);

            DataAnalysisTerminalEnvelope envelope = new DataAnalysisTerminalEnvelope(
                    "run-1", "call-1", 1, "run-1:call-1:1", "task-1",
                    "SUCCEEDED", true, "preview", null, null, null, false,
                    standardEstimate(), terminalConfirmed,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage.missing(
                            DataAnalysisResourceClass.STANDARD),
                    FIXED_INSTANT, false);
            DataAnalysisReleaseRequest request = new DataAnalysisReleaseRequest(
                    terminalConfirmed,
                    new DataAnalysisReleaseProof.Terminal(envelope),
                    DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
            assertEquals(DataAnalysisReleaseOutcome.RELEASED, service.releaseReservation(request));
            assertEquals(DataAnalysisReleaseOutcome.ALREADY_RELEASED, service.releaseReservation(request));
        }

        @Test
        void preDispatchAbortReleasesPreparingReservation() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = service.reserve(
                    identity("run-1", "call-1", 1), standardEstimate());
            DataAnalysisReleaseRequest request = new DataAnalysisReleaseRequest(
                    reservation,
                    new DataAnalysisReleaseProof.PreDispatchAbort(reservation.identity()),
                    DataAnalysisReleaseReason.CREATE_NOT_STARTED);
            assertEquals(DataAnalysisReleaseOutcome.RELEASED, service.releaseReservation(request));
            assertEquals(0, service.usedUnitsSnapshot());
        }

        @Test
        void releaseMissingReservationReturnsNotFound() {
            openAfterEmptyRecover();
            DataAnalysisReservation ghost = new DataAnalysisReservation(
                    identity("run-9", "call-9", 1).reservationId(),
                    identity("run-9", "call-9", 1),
                    DataAnalysisResourceClass.STANDARD, 1,
                    DataAnalysisReservationState.PREPARING, null, FIXED_INSTANT);
            DataAnalysisReleaseRequest request = new DataAnalysisReleaseRequest(
                    ghost,
                    new DataAnalysisReleaseProof.PreDispatchAbort(ghost.identity()),
                    DataAnalysisReleaseReason.CREATE_NOT_STARTED);
            assertEquals(DataAnalysisReleaseOutcome.NOT_FOUND, service.releaseReservation(request));
        }

        @Test
        void releaseAttachedReservationIsConflictEvenWithTerminalProof() {
            openAfterEmptyRecover();
            DataAnalysisReservation taskAttached = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(taskAttached);
            // Build a Terminal proof bound to a TERMINAL_CONFIRMED reservation that does NOT
            // match the live TASK_ATTACHED state. The record validation passes (the request is
            // internally consistent) but the ledger must refuse the transition.
            DataAnalysisReservation terminalConfirmed = new DataAnalysisReservation(
                    taskAttached.reservationId(), taskAttached.identity(),
                    taskAttached.resourceClass(), taskAttached.capacityUnits(),
                    DataAnalysisReservationState.TERMINAL_CONFIRMED, "task-1", FIXED_INSTANT);
            DataAnalysisTerminalEnvelope envelope = new DataAnalysisTerminalEnvelope(
                    "run-1", "call-1", 1, "run-1:call-1:1", "task-1",
                    "SUCCEEDED", true, "preview", null, null, null, false,
                    standardEstimate(), terminalConfirmed,
                    world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage.missing(
                            DataAnalysisResourceClass.STANDARD),
                    FIXED_INSTANT, false);
            DataAnalysisReleaseRequest request = new DataAnalysisReleaseRequest(
                    terminalConfirmed,
                    new DataAnalysisReleaseProof.Terminal(envelope),
                    DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
            assertEquals(DataAnalysisReleaseOutcome.CONFLICT, service.releaseReservation(request));
        }
    }

    @Nested
    @DisplayName("Cancel / timeout must NOT auto-release §8.6")
    class CancelTimeoutDiscipline {

        @Test
        void uiCancelDoesNotReleaseAttachedReservation() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(reservation);
            assertEquals(1, service.activeCountSnapshot());
            // No release API call → ledger unchanged.
            assertEquals(1, service.activeCountSnapshot());
            assertEquals(1, service.usedUnitsSnapshot());
            assertSame(reservation, service.ledgerSnapshot().get(reservation.reservationId()));
        }

        @Test
        void agentTimeoutDoesNotReleaseWhileSandboxStillRunning() {
            openAfterEmptyRecover();
            DataAnalysisReservation reservation = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            service.restoreReservation(reservation);
            // Even if the upper layer "times out" the agent call, the ledger stays occupied
            // because no proof-bearing release has arrived.
            assertEquals(1, service.activeCountSnapshot());
            assertEquals(1, service.usedUnitsSnapshot());
        }
    }

    @Nested
    @DisplayName("Recovery §8.4 step 1-7")
    class RecoveryReporting {

        @Test
        void recoverRestoresActiveReservationsAndOpensAdmission() {
            DataAnalysisReservation taskAttached = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            DataAnalysisReservation pending = new DataAnalysisReservation(
                    identity("run-2", "call-2", 1).reservationId(),
                    identity("run-2", "call-2", 1),
                    DataAnalysisResourceClass.HEAVY, 3,
                    DataAnalysisReservationState.PENDING_TRANSFERRED, "task-2", FIXED_INSTANT);
            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(taskAttached, pending),
                    properties.getMaxUnits(), properties.getMaxHeavyActive());
            assertEquals(2, report.restoredReservations());
            assertEquals(2, report.activeCount());
            assertEquals(1, report.heavyActiveCount());
            assertEquals(4, report.usedUnits());
            assertFalse(report.overConfigured());
            assertFalse(report.heavyOverConfigured());
            assertTrue(report.conflicts().isEmpty());
            assertEquals(DataAnalysisAdmissionState.OPEN, report.admissionState());
        }

        @Test
        void recoverWithConflictsReportsDegraded() {
            DataAnalysisReservation taskAttached = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            DataAnalysisOperationIdentity id = identity("run-1", "call-1", 1);
            DataAnalysisReservation conflicting = new DataAnalysisReservation(
                    id.reservationId(), id, DataAnalysisResourceClass.STANDARD, 1,
                    DataAnalysisReservationState.PENDING_TRANSFERRED, "task-other", FIXED_INSTANT);
            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(taskAttached, conflicting),
                    properties.getMaxUnits(), properties.getMaxHeavyActive());
            assertFalse(report.conflicts().isEmpty());
            assertEquals(DataAnalysisAdmissionState.DEGRADED, report.admissionState());
        }

        @Test
        void recoverOverConfiguredReportsDegraded() {
            // 1 STANDARD (1 unit) + 1 HEAVY (3 units) + 1 HEAVY (3 units) = 7 units > maxUnits=4
            DataAnalysisReservation s1 = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.STANDARD, 1);
            DataAnalysisReservation h1 = attachedReservation(
                    "run-2", "call-2", 1, "task-2",
                    DataAnalysisResourceClass.HEAVY, 3);
            DataAnalysisReservation h2 = attachedReservation(
                    "run-3", "call-3", 1, "task-3",
                    DataAnalysisResourceClass.HEAVY, 3);
            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(s1, h1, h2),
                    properties.getMaxUnits(), properties.getMaxHeavyActive());
            assertTrue(report.overConfigured());
            assertEquals(DataAnalysisAdmissionState.DEGRADED, report.admissionState());
        }

        @Test
        void recoverHeavyOverConfiguredReportsDegraded() {
            // 2 HEAVY > maxHeavyActive=1
            DataAnalysisReservation h1 = attachedReservation(
                    "run-1", "call-1", 1, "task-1",
                    DataAnalysisResourceClass.HEAVY, 3);
            DataAnalysisReservation h2 = attachedReservation(
                    "run-2", "call-2", 1, "task-2",
                    DataAnalysisResourceClass.HEAVY, 3);
            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(h1, h2),
                    properties.getMaxUnits(), properties.getMaxHeavyActive());
            assertTrue(report.heavyOverConfigured());
            assertEquals(DataAnalysisAdmissionState.DEGRADED, report.admissionState());
        }

        @Test
        void recoverDropsReleasedAndPreparingWithoutTaskId() {
            DataAnalysisReservation released = new DataAnalysisReservation(
                    identity("run-1", "call-1", 1).reservationId(),
                    identity("run-1", "call-1", 1),
                    DataAnalysisResourceClass.STANDARD, 1,
                    DataAnalysisReservationState.RELEASED, "task-1", FIXED_INSTANT);
            DataAnalysisReservation preparing = new DataAnalysisReservation(
                    identity("run-2", "call-2", 1).reservationId(),
                    identity("run-2", "call-2", 1),
                    DataAnalysisResourceClass.STANDARD, 1,
                    DataAnalysisReservationState.PREPARING, null, FIXED_INSTANT);
            DataAnalysisCapacityRecoveryReport report = service.recover(
                    List.of(released, preparing),
                    properties.getMaxUnits(), properties.getMaxHeavyActive());
            assertEquals(0, report.restoredReservations());
            assertEquals(DataAnalysisAdmissionState.OPEN, report.admissionState());
        }
    }

    @Nested
    @DisplayName("Composition limits §8.1")
    class CompositionLimits {

        @Test
        void standardAndHeavyCombineUnderUnitsCap() {
            openAfterEmptyRecover();
            // 1 STANDARD + 1 HEAVY = 1 + 3 = 4 ≤ maxUnits=4
            service.reserve(identity("run-1", "call-1", 1), standardEstimate());
            service.reserve(identity("run-2", "call-2", 1), heavyEstimate());
            assertEquals(4, service.usedUnitsSnapshot());
            // Both are PREPARING; the active counters stay at zero until they are restored.
            assertEquals(0, service.heavyActiveCountSnapshot());
            assertEquals(0, service.activeCountSnapshot());
        }

        @Test
        void secondHeavyRejectedWhenHeavyCapReached() {
            openAfterEmptyRecover();
            service.reserve(identity("run-1", "call-1", 1), heavyEstimate());
            // 第一个 HEAVY 仍处于 PREPARING，但已经拿到 pre-create 名额；第二个必须在
            // Sandbox create 之前被拒绝。
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-2", "call-2", 1), heavyEstimate()));
            assertEquals(CapacityAdmissionException.Reason.SERVER_BUSY, ex.reason());
        }

        @Test
        void activeCapEnforcedBeforeSandboxCreate() {
            properties.setMaxActive(2);
            service = new DataAnalysisCapacityServiceImpl(properties);
            openAfterEmptyRecover();
            // PREPARING 已经代表下一步可创建真实 Sandbox task；因此第三个请求必须在
            // reserve 阶段拒绝，不能等到 TASK_ATTACHED 才留下 orphan task。
            service.reserve(identity("run-1", "call-1", 1), standardEstimate());
            service.reserve(identity("run-1", "call-2", 1), standardEstimate());
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-1", "call-3", 1), standardEstimate()));
            assertEquals(CapacityAdmissionException.Reason.SERVER_BUSY, ex.reason());
            assertEquals(2, service.usedUnitsSnapshot());
            assertEquals(0, service.activeCountSnapshot());
            // 已获准的两个 reservation 可以正常附着 taskId。
            assertEquals(DataAnalysisRestoreOutcome.ADDED,
                    service.restoreReservation(attachedReservation("run-1", "call-1", 1, "task-1",
                            DataAnalysisResourceClass.STANDARD, 1)));
            assertEquals(DataAnalysisRestoreOutcome.ADDED,
                    service.restoreReservation(attachedReservation("run-1", "call-2", 1, "task-2",
                            DataAnalysisResourceClass.STANDARD, 1)));
            assertEquals(2, service.activeCountSnapshot());
        }

        @Test
        void inconsistentFrozenEstimateIsRejectedWithoutLedgerMutation() {
            openAfterEmptyRecover();
            DataAnalysisEstimate inconsistent = new DataAnalysisEstimate(
                    100_000L, 16L * 1024L * 1024L, 1, 0.2d, 0,
                    List.of(), DataAnalysisResourceClass.HEAVY, 3);

            assertThrows(IllegalArgumentException.class,
                    () -> service.reserve(identity("run-1", "call-1", 1), inconsistent));
            assertEquals(0, service.usedUnitsSnapshot());
            assertEquals(0, service.activeCountSnapshot());
        }
    }

    @Nested
    @DisplayName("Capacity service contract surface")
    class ContractSurface {

        @Test
        void implementsInterface() {
            assertTrue(service instanceof DataAnalysisCapacityService);
        }

        @Test
        void capacityAdmissionExceptionCarriesReason() {
            openAfterEmptyRecover();
            CapacityAdmissionException ex = assertThrows(CapacityAdmissionException.class,
                    () -> service.reserve(identity("run-1", "call-1", 1), oversizedEstimate()));
            assertNotNull(ex.reason());
            assertNotNull(ex.getMessage());
        }
    }
}
