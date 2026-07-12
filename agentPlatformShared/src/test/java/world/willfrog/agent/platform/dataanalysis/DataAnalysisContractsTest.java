package world.willfrog.agent.platform.dataanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
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
    }

    @Test
    void pendingDispatchRequiresMatchingReservationTask() {
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                "run-1:call-1:1",
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.ACTIVE,
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
    }

    @Test
    void recoveryReportCannotOpenWithConflicts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisCapacityRecoveryReport(
                        1,
                        1,
                        4,
                        false,
                        List.of("reservation conflict"),
                        DataAnalysisAdmissionState.OPEN));
    }

    @Test
    void completeUsageCannotDeclareMissingFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataAnalysisResourceUsage(
                        DataAnalysisResourceClass.STANDARD,
                        1L,
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
                        List.of("memoryByteMillis")));
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
}
