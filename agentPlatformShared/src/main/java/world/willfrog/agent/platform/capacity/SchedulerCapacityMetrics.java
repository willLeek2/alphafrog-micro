package world.willfrog.agent.platform.capacity;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 把三层名额与背压四个数挂成指标，方便按时间看出来。
 *
 * <p>标签只有「哪一层」与「哪个数」两种取值，都是固定的少数几个，不放 runId、节点名这类高基数字段。
 * 其中背压读数来自 {@link SchedulerBackpressureProbe}，它自带短缓存，抓取不会把库查穿。</p>
 *
 * <p>它不自己注册成 Bean：两个池的提示队列由池的实现提供，等池接好之后在建池的配置里 new 一个并调用
 * {@link #register()}。</p>
 */
public class SchedulerCapacityMetrics {

    private final ObjectProvider<MeterRegistry> registryProvider;
    private final SchedulerPermitLedger permitLedger;
    private final SchedulerBackpressureProbe backpressureProbe;

    public SchedulerCapacityMetrics(ObjectProvider<MeterRegistry> registryProvider,
                                    SchedulerPermitLedger permitLedger,
                                    SchedulerBackpressureProbe backpressureProbe) {
        this.registryProvider = registryProvider;
        this.permitLedger = permitLedger;
        this.backpressureProbe = backpressureProbe;
    }

    /** 挂上指标；没有 MeterRegistry 时什么都不做（本机与单测里就是这种情况）。 */
    public void register() {
        MeterRegistry registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        for (SchedulerPermitLayer layer : SchedulerPermitLayer.values()) {
            Gauge.builder("alphafrog.agent.scheduler.permit.in_use", permitLedger,
                            ledger -> ledger.usage(layer).inUse())
                    .description("三层名额各自的在用数量")
                    .tag("layer", layer.name().toLowerCase())
                    .register(registry);
            Gauge.builder("alphafrog.agent.scheduler.permit.limit", permitLedger,
                            ledger -> ledger.usage(layer).limit())
                    .description("三层名额各自的上限，-1 表示不限")
                    .tag("layer", layer.name().toLowerCase())
                    .register(registry);
        }
        Gauge.builder("alphafrog.agent.scheduler.backpressure.db_unfinished",
                        backpressureProbe, probe -> probe.snapshot().unfinishedWorkItemsInDb())
                .description("数据库里未完成的工作项数量")
                .register(registry);
        Gauge.builder("alphafrog.agent.scheduler.backpressure.hint_queue",
                        backpressureProbe, probe -> probe.snapshot().hintQueueDepth())
                .description("内存提示队列里的元素个数")
                .register(registry);
        Gauge.builder("alphafrog.agent.scheduler.backpressure.reserved_not_enqueued",
                        backpressureProbe, probe -> probe.snapshot().reservedNotEnqueued())
                .description("已经预留但还没入队的数量")
                .register(registry);
        Gauge.builder("alphafrog.agent.scheduler.backpressure.per_run_max",
                        backpressureProbe, probe -> probe.snapshot().maxUnfinishedPerRun())
                .description("所有 Run 里未完成工作项最多的数量")
                .register(registry);
    }
}
