package world.willfrog.agentlangchain.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

class RunLaneContextScopeTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() throws InterruptedException {
        LaneContext.clear();
        MDC.remove(LaneContext.MDC_LANE_TAG);
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    static Stream<Arguments> activationBoundaries() {
        return Stream.of(
                Arguments.of("fresh asynchronous execution", "lane-fresh"),
                Arguments.of("suspended tool continuation", "lane-tool"),
                Arguments.of("crash restart recovery", "lane-restart"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("activationBoundaries")
    void restoresPersistedRunLaneForEveryBusinessTaskActivation(String boundary, String persistedLane) throws Exception {
        AgentRun run = new AgentRun();
        run.setId("run-" + boundary.hashCode());
        run.setLaneTag(persistedLane);
        AtomicReference<String> observed = new AtomicReference<>();
        AtomicReference<String> observedMdc = new AtomicReference<>();

        // 先创建工作线程，避免它通过 InheritableThreadLocal 继承下面故意设置的调用方标签。
        assertThat(executor.submit(LaneContext::trafficScopeId).get(2, TimeUnit.SECONDS)).isNull();
        LaneContext.setTrafficScopeId("unrelated-caller-lane");
        MDC.put(LaneContext.MDC_LANE_TAG, "unrelated-caller-lane");
        executor.submit(RunLaneContextScope.wrap(run, () -> {
            observed.set(LaneContext.trafficScopeId());
            observedMdc.set(MDC.get(LaneContext.MDC_LANE_TAG));
        })).get(2, TimeUnit.SECONDS);

        assertThat(observed).hasValue(persistedLane);
        assertThat(observedMdc).hasValue(persistedLane);
        assertThat(LaneContext.trafficScopeId()).isEqualTo("unrelated-caller-lane");
        assertThat(MDC.get(LaneContext.MDC_LANE_TAG)).isEqualTo("unrelated-caller-lane");
        assertThat(executor.submit(LaneContext::trafficScopeId).get(2, TimeUnit.SECONDS)).isNull();
        assertThat(executor.submit(() -> MDC.get(LaneContext.MDC_LANE_TAG)).get(2, TimeUnit.SECONDS)).isNull();
    }
}
