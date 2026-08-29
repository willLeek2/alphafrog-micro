package world.willfrog.agentlangchain.execution.dag;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainDagMetricsTest {

    @Test
    void recorders_shouldUseFixedNamesWithoutRunOrTodoTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        LangchainDagMetrics metrics = new LangchainDagMetrics(registry);

        metrics.recordGraphShape(4, 3);
        metrics.recordScheduleDuration(Duration.ofMillis(25));
        metrics.recordParallelismMax(2);
        metrics.recordQueueDepthMax(1);
        metrics.enterExecution();
        metrics.setQueueDepth(1);

        DistributionSummary nodeCount = registry.get(LangchainDagMetrics.PREFIX + ".node.count").summary();
        DistributionSummary depth = registry.get(LangchainDagMetrics.PREFIX + ".dependency.depth.max").summary();
        Timer duration = registry.get(LangchainDagMetrics.PREFIX + ".schedule.duration").timer();
        DistributionSummary parallelismMax = registry.get(LangchainDagMetrics.PREFIX + ".parallelism.max").summary();
        DistributionSummary queueMax = registry.get(LangchainDagMetrics.PREFIX + ".queue.depth.max").summary();

        assertThat(nodeCount.count()).isEqualTo(1);
        assertThat(nodeCount.totalAmount()).isEqualTo(4);
        assertThat(nodeCount.getId().getTags()).isEmpty();
        assertThat(depth.totalAmount()).isEqualTo(3);
        assertThat(duration.count()).isEqualTo(1);
        assertThat(parallelismMax.totalAmount()).isEqualTo(2);
        assertThat(queueMax.totalAmount()).isEqualTo(1);
        assertThat(registry.get(LangchainDagMetrics.PREFIX + ".parallelism").gauge().value()).isEqualTo(1);
        assertThat(registry.get(LangchainDagMetrics.PREFIX + ".queue.depth").gauge().value()).isEqualTo(1);

        metrics.leaveExecution();
        metrics.setQueueDepth(0);
        assertThat(registry.get(LangchainDagMetrics.PREFIX + ".parallelism").gauge().value()).isZero();
        assertThat(registry.get(LangchainDagMetrics.PREFIX + ".queue.depth").gauge().value()).isZero();
    }
}
