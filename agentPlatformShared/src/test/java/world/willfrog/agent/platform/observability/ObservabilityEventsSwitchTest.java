package world.willfrog.agent.platform.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 开关三层取值：热配置 → 环境属性 → 代码默认（关）。每一层都要能压过下一层，缺一层就回落。
 */
class ObservabilityEventsSwitchTest {

    @AfterEach
    void cleanUp() {
        ObservabilityEvents.installSwitch(null);
    }

}
