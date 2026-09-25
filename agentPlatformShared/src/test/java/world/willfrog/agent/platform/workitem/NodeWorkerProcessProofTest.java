package world.willfrog.agent.platform.workitem;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NodeWorkerProcessProofTest {
    private static final String MACHINE = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_MACHINE = "fedcba9876543210fedcba9876543210";
    private static final String BOOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String NEXT_BOOT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String NAMESPACE = "1xk4qga";
    private static final Instant STARTED = Instant.parse("2026-09-25T08:00:00Z");
    private static final String OLD = "dual-pool-node:v3@" + MACHINE + "@" + BOOT
            + "@1@" + STARTED.toEpochMilli() + "@" + NAMESPACE;

    @Test
    void liveAndUnverifiableOwnersNeverReleaseCapacity() {
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, MACHINE, BOOT, NAMESPACE, true, STARTED)).isFalse();
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                "old-uuid-claimant", MACHINE, BOOT, NAMESPACE, false, null)).isFalse();
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD.replace("v3@", "v2@"), MACHINE, BOOT, NAMESPACE, false, null)).isFalse();
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, MACHINE, BOOT, NAMESPACE, true, null)).isFalse();
    }

    @Test
    void sameHostAndBootCanProveAbsentOrReusedPidExited() {
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, MACHINE, BOOT, NAMESPACE, false, null)).isTrue();
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, MACHINE, BOOT, NAMESPACE, true, STARTED.plusSeconds(30))).isTrue();
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, MACHINE, BOOT, NAMESPACE, true, STARTED)).isFalse();
    }

    @Test
    void differentPidNamespaceOnSameBootCannotProveAnOldContainerExited() {
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, MACHINE, BOOT, "another-namespace", false, null)).isFalse();
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, MACHINE, BOOT, "another-namespace", true, STARTED.plusSeconds(30))).isFalse();
    }

    @Test
    void anotherHostNeverProvesExitEvenIfBootAndPidDiffer() {
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, OTHER_MACHINE, NEXT_BOOT, NAMESPACE, false, null)).isFalse();
    }

    @Test
    void sameHostWithNewKernelBootProvesOldProcessIsGone() {
        assertThat(NodeWorkerProcessProof.exitedByObservation(
                OLD, MACHINE, NEXT_BOOT, "another-namespace", true, STARTED)).isTrue();
    }

    @Test
    void recognizingAClaimantChecksVersionAndHostWithoutGuessingProcessExit() {
        assertThat(NodeWorkerProcessProof.recognizesByObservation(OLD, MACHINE)).isTrue();
        assertThat(NodeWorkerProcessProof.recognizesByObservation(OLD, OTHER_MACHINE)).isFalse();
        assertThat(NodeWorkerProcessProof.recognizesByObservation(OLD, null)).isFalse();
        assertThat(NodeWorkerProcessProof.recognizesByObservation(
                OLD.replace("v3@", "v2@"), MACHINE)).isFalse();
        assertThat(NodeWorkerProcessProof.recognizesByObservation(
                OLD.replace("@" + NAMESPACE, "@bad:namespace"), MACHINE)).isFalse();
        // 是否为同一宿主不依赖内核启动代际；真正退出仍由其他持久证明决定。
        assertThat(NodeWorkerProcessProof.recognizesByObservation(
                OLD.replace("@" + BOOT + "@", "@" + NEXT_BOOT + "@"), MACHINE)).isTrue();
    }
}
