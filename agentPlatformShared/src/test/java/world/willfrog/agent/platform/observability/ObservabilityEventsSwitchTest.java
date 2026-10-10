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

    @Test
    @SuppressWarnings("unchecked")
    void resolvesHotConfigThenEnvironmentThenCodeDefault() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        Environment environment = mock(Environment.class);
        ObjectProvider<AgentLlmLocalConfigLoader> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(loader);
        when(loader.current()).thenReturn(Optional.of(properties));

        // 热配置写了 true：压过环境属性里的 false
        properties.getRuntime().getObservabilityEvents().setEnabled(true);
        when(environment.getProperty(ObservabilityEventsSwitch.ENABLED_PROPERTY, Boolean.class)).thenReturn(false);
        assertTrue(new ObservabilityEventsSwitch(provider, environment).enabledNow());

        // 热配置没写：回落到环境属性
        properties.getRuntime().getObservabilityEvents().setEnabled(null);
        when(environment.getProperty(ObservabilityEventsSwitch.ENABLED_PROPERTY, Boolean.class)).thenReturn(true);
        assertTrue(new ObservabilityEventsSwitch(provider, environment).enabledNow());

        // 两层都没写：代码默认关
        when(environment.getProperty(ObservabilityEventsSwitch.ENABLED_PROPERTY, Boolean.class)).thenReturn(null);
        assertFalse(new ObservabilityEventsSwitch(provider, environment).enabledNow());

        // 热配置写了 false：同样是最优先的一层，压过环境属性里的 true
        properties.getRuntime().getObservabilityEvents().setEnabled(false);
        when(environment.getProperty(ObservabilityEventsSwitch.ENABLED_PROPERTY, Boolean.class)).thenReturn(true);
        assertFalse(new ObservabilityEventsSwitch(provider, environment).enabledNow());
    }
}
