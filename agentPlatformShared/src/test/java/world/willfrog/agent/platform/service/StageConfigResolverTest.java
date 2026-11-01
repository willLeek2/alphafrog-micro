package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StageLlmConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StageConfigResolverTest {




    private record RunStageConfigAccessor(world.willfrog.agent.platform.config.RunStageConfig config) {
        StageLlmConfig planning() {
            return config.getPlanning();
        }
    }
}
