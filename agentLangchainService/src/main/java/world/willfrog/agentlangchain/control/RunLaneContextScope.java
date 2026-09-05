package world.willfrog.agentlangchain.control;

import com.alibaba.ttl.TtlRunnable;
import org.slf4j.MDC;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

/**
 * 从 Run 的持久化标签重建执行线程上下文。
 *
 * <p>调度器可能先把任务放入业务队列，随后由另一个线程提交到物理线程池，因此不能依赖
 * 提交线程当时碰巧携带的标签。包装任务会在实际执行前写入数据库中的标签，并在结束后恢复
 * 原值；TransmittableThreadLocal 的任务包装同时处理线程池复用时的上下文捕获和还原。</p>
 */
public final class RunLaneContextScope {

    private RunLaneContextScope() {
    }

    public static Runnable wrap(AgentRun run, Runnable task) {
        String persistedLaneTag = run == null ? null : run.getLaneTag();
        Runnable scoped = () -> {
            String previous = LaneContext.trafficScopeId();
            String previousMdc = MDC.get(LaneContext.MDC_LANE_TAG);
            try {
                LaneContext.setTrafficScopeId(persistedLaneTag);
                setMdcLaneTag(persistedLaneTag);
                task.run();
            } finally {
                LaneContext.restore(previous);
                setMdcLaneTag(previousMdc);
            }
        };
        return TtlRunnable.get(scoped, false, true);
    }

    private static void setMdcLaneTag(String laneTag) {
        if (laneTag == null || laneTag.isBlank()) {
            MDC.remove(LaneContext.MDC_LANE_TAG);
        } else {
            MDC.put(LaneContext.MDC_LANE_TAG, laneTag);
        }
    }
}
