package world.willfrog.agentlangchain.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class LaneScopeGatewayTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @org.junit.jupiter.api.Test
    void synchronousCallRestoresPreviousLaneEvenWhenSupplierFails() {
        AgentRun run = new AgentRun();
        run.setLaneTag("lane-persisted");
        LaneContext.setTrafficScopeId("previous-lane");

        assertThat(LaneScopeGateway.call(run, LaneContext::trafficScopeId))
                .isEqualTo("lane-persisted");
        assertThatThrownBy(() -> LaneScopeGateway.call(run, () -> {
            assertThat(LaneContext.trafficScopeId()).isEqualTo("lane-persisted");
            throw new IllegalStateException("failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(LaneContext.trafficScopeId()).isEqualTo("previous-lane");
    }

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


}
