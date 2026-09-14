package world.willfrog.agentlangchain.execution.dag;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DAG 调度的低基数指标。
 *
 * <p>不把 runId / todoId 打成标签，避免基数膨胀。gauge 是当前进程内正在真正执行的节点数合计
 * （并发多张 DAG 时加在一起）；summary / timer 按每次调度各自记录。</p>
 */
@Component
public class LangchainDagMetrics {

    static final String PREFIX = "alphafrog.dag";

    private final MeterRegistry registry;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger queueDepth = new AtomicInteger();

    public LangchainDagMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge(PREFIX + ".parallelism", inFlight, AtomicInteger::get);
        registry.gauge(PREFIX + ".queue.depth", queueDepth, AtomicInteger::get);
    }

    public void recordGraphShape(int nodeCount, int maxDependencyDepth) {
        DistributionSummary.builder(PREFIX + ".node.count").register(registry).record(nodeCount);
        DistributionSummary.builder(PREFIX + ".dependency.depth.max")
                .register(registry)
                .record(maxDependencyDepth);
    }

    public void recordScheduleDuration(Duration duration) {
        Timer.builder(PREFIX + ".schedule.duration").register(registry).record(duration);
    }

    public void recordParallelismMax(int maxInFlight) {
        DistributionSummary.builder(PREFIX + ".parallelism.max").register(registry).record(maxInFlight);
    }

    public void recordQueueDepthMax(int maxQueueDepth) {
        DistributionSummary.builder(PREFIX + ".queue.depth.max").register(registry).record(maxQueueDepth);
    }

    public void setQueueDepth(int depth) {
        queueDepth.set(Math.max(0, depth));
    }

    public int enterExecution() {
        int current = inFlight.incrementAndGet();
        return current;
    }

    public void leaveExecution() {
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
    }
}
