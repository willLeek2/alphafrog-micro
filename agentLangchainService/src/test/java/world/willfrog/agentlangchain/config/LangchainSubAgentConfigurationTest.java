package world.willfrog.agentlangchain.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

class LangchainSubAgentConfigurationTest {

    @AfterEach
    void clearLane() {
        LaneContext.clear();
    }

    @Test
    void subAgentPoolCapturesAndRestoresLaneForEachSubmission() throws Exception {
        ExecutorService executor = new LangchainSubAgentConfiguration()
                .langchainSubAgentExecutor(1, 1, 2);
        try {
            assertThat(executor.submit(LaneContext::trafficScopeId).get(2, TimeUnit.SECONDS)).isNull();

            LaneContext.setTrafficScopeId("lane-a");
            assertThat(executor.submit(LaneContext::trafficScopeId).get(2, TimeUnit.SECONDS))
                    .isEqualTo("lane-a");

            LaneContext.clear();
            assertThat(executor.submit(LaneContext::trafficScopeId).get(2, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
