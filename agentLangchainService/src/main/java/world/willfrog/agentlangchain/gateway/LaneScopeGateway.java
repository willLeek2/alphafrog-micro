package world.willfrog.agentlangchain.gateway;

import com.alibaba.ttl.TtlRunnable;
import org.slf4j.MDC;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 入站泳道作用域：把 Run 上持久化过的泳道标签带进实际执行线程，供出站 Dubbo 过滤器打标。
 *
 * <p>调度器可能先把任务放入业务队列，随后由另一个线程提交到物理线程池，因此不能依赖
 * 提交线程当时碰巧携带的标签。包装任务会在实际执行前写入数据库中的标签，并在结束后恢复
 * 原值；TransmittableThreadLocal 的任务包装同时处理线程池复用时的上下文捕获和还原。</p>
 *
 * <p>DAG 节点还会在进入工作线程时显式快照/还原同一份上下文（{@link Snapshot}），
 * 保证节点内部临时改写标签不会残留给后续节点。业务包只经由本类传递泳道，
 * 不直接读写 {@link LaneContext}。</p>
 */
public final class LaneScopeGateway {

    private LaneScopeGateway() {
    }

    /** 受理入口读取当前入站泳道标签（可能为空，表示主 Beta 默认口径）。 */
    public static String currentLaneTag() {
        return LaneContext.trafficScopeId();
    }

    /** 用 Run 的持久化标签包装任务：执行期间生效，结束后恢复线程原值。 */
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

    /** 用 Run 的持久化泳道执行一次同步计算，并在返回或抛错后恢复线程作用域。 */
    public static <T> T call(AgentRun run, Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        wrap(run, () -> result.set(task.get())).run();
        return result.get();
    }

    /** 快照当前线程的泳道作用域（含日志 MDC），供跨线程搬运后还原。 */
    public static Snapshot capture() {
        return new Snapshot(LaneContext.trafficScopeId(), MDC.get(LaneContext.MDC_LANE_TAG));
    }

    public record Snapshot(String laneTag, String mdcLaneTag) {

        /** 把快照写回当前线程；null/空标签表示清除。 */
        public void restore() {
            LaneContext.restore(laneTag);
            setMdcLaneTag(mdcLaneTag);
        }
    }

    private static void setMdcLaneTag(String laneTag) {
        if (laneTag == null || laneTag.isBlank()) {
            MDC.remove(LaneContext.MDC_LANE_TAG);
        } else {
            MDC.put(LaneContext.MDC_LANE_TAG, laneTag);
        }
    }
}
