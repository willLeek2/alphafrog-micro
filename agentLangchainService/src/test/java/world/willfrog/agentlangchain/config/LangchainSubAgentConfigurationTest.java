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

}
