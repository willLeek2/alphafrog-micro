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

import world.willfrog.agent.platform.dataanalysis.CapacityAdmissionException;
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





    }

    @Nested
    @DisplayName("P0-08: mixed resource class overconfiguration")
    class MixedResourceOverconfiguration {


    }
}
