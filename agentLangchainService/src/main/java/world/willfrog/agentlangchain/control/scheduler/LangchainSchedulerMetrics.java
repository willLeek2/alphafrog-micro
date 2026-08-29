package world.willfrog.agentlangchain.control.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.IntSupplier;

/**
 * 调度器的低基数基础指标（Micrometer）。
 *
 * <p>只保留执行数、排队数、容量占用、拒绝、取消、等待时间和长工具续接计数。
 * instanceId、跨实例快照和周期全量诊断默认关闭，由
 * {@code agent.langchain.run.scheduler.advanced-diagnostics-enabled} 单独控制。</p>
 *
 * <p>指标名统一前缀 {@code alphafrog.scheduler}：</p>
 * <ul>
 *   <li>gauge：running / queued / capacity.used</li>
 *   <li>counter：rejected.total{reason=queue_full|capacity_full}、
 *       cancelled.total{stage=queued|running}、
 *       worker.released.total{reason=tool_pending}、
 *       continuation.requeued.total、
 *       completed.total{result=...}</li>
 *   <li>timer：queue.wait</li>
 * </ul>
 */
@Component
public class LangchainSchedulerMetrics {

    static final String PREFIX = "alphafrog.scheduler";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public LangchainSchedulerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 绑定三个数量 gauge；scheduler 在构造后调用一次。 */
    public void bindGauges(IntSupplier running, IntSupplier queued, IntSupplier capacityUsed) {
        registry.gauge(PREFIX + ".running", running, IntSupplier::getAsInt);
        registry.gauge(PREFIX + ".queued", queued, IntSupplier::getAsInt);
        registry.gauge(PREFIX + ".capacity.used", capacityUsed, IntSupplier::getAsInt);
    }

    public void recordRejected(String reason) {
        counter("rejected.total", "reason", reason).increment();
    }

    /** stage ∈ {queued, running}。 */
    public void recordCancelled(String stage) {
        counter("cancelled.total", "stage", stage).increment();
    }

    /** 长工具挂起释放 worker。 */
    public void recordWorkerReleased() {
        counter("worker.released.total", "reason", "tool_pending").increment();
    }

    /** 长工具终态后 Run 重新进入调度队列。 */
    public void recordContinuationRequeued() {
        counter("continuation.requeued.total").increment();
    }

    /**
     * Run 终态完成计数，按结果分类。
     *
     * <p>这是给 workflow pipeline 的稳定入口：在已成功持久化的 COMPLETED / PARTIAL / FAILED / CANCELED 出口调用。
     * WAITING_TOOL_JOB（长工具挂起）不算终态，只记 worker 释放。</p>
     */
    public void recordCompletion(AgentRunStatus status) {
        if (status == null) {
            return;
        }
        counter("completed.total", "result", status.name().toLowerCase()).increment();
    }

    public void recordQueueWait(Duration duration) {
        registry.timer(PREFIX + ".queue.wait").record(duration);
    }

    private Counter counter(String name, String... tagKeyValue) {
        String key = name + "|" + String.join(",", tagKeyValue);
        return counters.computeIfAbsent(key, k -> {
            io.micrometer.core.instrument.Counter.Builder builder =
                    Counter.builder(PREFIX + "." + name);
            for (int i = 0; i < tagKeyValue.length; i += 2) {
                builder = builder.tag(tagKeyValue[i], tagKeyValue[i + 1]);
            }
            return builder.register(registry);
        });
    }
}
