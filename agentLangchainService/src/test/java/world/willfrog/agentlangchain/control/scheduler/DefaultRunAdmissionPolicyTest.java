package world.willfrog.agentlangchain.control.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRunAdmissionPolicyTest {

    private final DefaultRunAdmissionPolicy policy = new DefaultRunAdmissionPolicy();

    private static RunAdmissionPolicy.AdmissionState state(
            int running, boolean queueEmpty, int reservedQueued, int queueCapacity,
            int capacityUsed, int maxCapacity, int core, int max, int weight) {
        return new RunAdmissionPolicy.AdmissionState(
                running, queueEmpty, reservedQueued, queueCapacity,
                capacityUsed, maxCapacity, core, max, weight);
    }

    @Test
    void idleSchedulerGrantsRunningWhenCapacityFits() {
        var decision = policy.evaluate(state(0, true, 0, 2, 0, 4, 2, 3, 1));
        assertThat(decision).isEqualTo(RunAdmissionPolicy.AdmissionDecision.RUNNING);
    }

    @Test
    void coreFullButQueueHasRoomGoesQueued() {
        var decision = policy.evaluate(state(2, false, 0, 2, 2, 4, 2, 3, 1));
        assertThat(decision).isEqualTo(RunAdmissionPolicy.AdmissionDecision.QUEUED);
    }

    @Test
    void queueFullButElasticRoomGoesElastic() {
        var decision = policy.evaluate(state(2, false, 2, 2, 2, 4, 2, 3, 1));
        assertThat(decision).isEqualTo(RunAdmissionPolicy.AdmissionDecision.ELASTIC);
    }

    @Test
    void everythingFullRejectsWithQueueFullReason() {
        var state = state(3, false, 2, 2, 3, 4, 2, 3, 1);
        var decision = policy.evaluate(state);
        assertThat(decision).isEqualTo(RunAdmissionPolicy.AdmissionDecision.REJECTED);
        assertThat(decision.rejectReason(state)).isEqualTo("queue_full");
    }

    @Test
    void capacityInsufficientRejectsWithCapacityFullReason() {
        // 队列与弹性都满，且容量 4 已用 3、新 Run 权重 2 放不下 → capacity_full。
        var state = state(3, false, 2, 2, 3, 4, 2, 3, 2);
        var decision = policy.evaluate(state);
        assertThat(decision).isEqualTo(RunAdmissionPolicy.AdmissionDecision.REJECTED);
        assertThat(decision.rejectReason(state)).isEqualTo("capacity_full");
    }

    @Test
    void weightAboveTotalCapacityRejectedImmediatelyEvenWithQueueRoom() {
        // 权重 5 > 容量 4：即使队列有名额也必须立即拒绝，否则永久堵死队首。
        var state = state(0, true, 0, 2, 0, 4, 2, 3, 5);
        var decision = policy.evaluate(state);
        assertThat(decision).isEqualTo(RunAdmissionPolicy.AdmissionDecision.REJECTED);
        assertThat(decision.rejectReason(state)).isEqualTo("capacity_full");
    }

    @Test
    void capacityInsufficientStillAllowsQueuing() {
        // 队列有名额时容量不足也允许排队等待，不误拒。
        var decision = policy.evaluate(state(1, false, 0, 2, 4, 4, 2, 3, 1));
        assertThat(decision).isEqualTo(RunAdmissionPolicy.AdmissionDecision.QUEUED);
    }

    @Test
    void defaultPoliciesAreConstantNormalAndStandardWeight() {
        DefaultRunPriorityPolicy priorityPolicy = new DefaultRunPriorityPolicy();
        DefaultRunWeightPolicy weightPolicy = new DefaultRunWeightPolicy();

        assertThat(priorityPolicy.priorityFor("run-x")).isEqualTo(RunPriority.NORMAL);
        assertThat(priorityPolicy.priorityFor(null)).isEqualTo(RunPriority.NORMAL);
        assertThat(weightPolicy.weightUnitsFor("run-x")).isEqualTo(RunWeightPolicy.STANDARD_WEIGHT);
        assertThat(weightPolicy.weightUnitsFor(null)).isEqualTo(RunWeightPolicy.STANDARD_WEIGHT);
        assertThat(RunPriority.NORMAL.schedulingOrder()).isZero();
    }
}
