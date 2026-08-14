package world.willfrog.agentlangchain.orchestration.scheduler;

/**
 * 判断一个 Run 是否可以立即执行、进入队列或被拒绝。
 *
 * <p>策略必须是无状态的纯函数：scheduler 持锁传入一致的调度状态快照，
 * 策略只返回决定，不修改任何全局计数。</p>
 */
public interface RunAdmissionPolicy {

    /**
     * @param state 持锁状态快照
     * @return 准入决定
     */
    AdmissionDecision evaluate(AdmissionState state);

    /** 调度状态快照，全部由 scheduler 持锁时构建。 */
    record AdmissionState(
            int running,
            boolean queueEmpty,
            int reservedQueued,
            int queueCapacity,
            int capacityUsedUnits,
            int maxCapacityUnits,
            int corePoolSize,
            int maxPoolSize,
            int weight) {

        boolean capacityFits() {
            return capacityUsedUnits + weight <= maxCapacityUnits;
        }
    }

    /** 准入决定。 */
    enum AdmissionDecision {
        /** 立即获得执行槽位；scheduler 负责占用容量账本。 */
        RUNNING,
        /** 进入有界等待队列。 */
        QUEUED,
        /**
         * core 已满、队列已满、但 max 之下还有弹性空间：
         * scheduler 先提升队首，再把当前请求保留为 QUEUED；队列为空时直接弹性执行。
         */
        ELASTIC,
        /** 拒绝；reason 区分拒绝原因，必须进入指标。 */
        REJECTED;

        /**
         * 拒绝原因，供指标与异常消息使用。
         */
        public String rejectReason(AdmissionState state) {
            return state.capacityFits() ? "queue_full" : "capacity_full";
        }
    }
}
