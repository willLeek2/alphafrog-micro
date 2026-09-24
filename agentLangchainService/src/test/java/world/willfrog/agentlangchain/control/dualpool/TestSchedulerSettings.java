package world.willfrog.agentlangchain.control.dualpool;

import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Optional;

/**
 * 造一份调度参数给用例用：要么只给环境属性那一层，要么再挂上一层热配置。
 *
 * <p>与生产同一套解析代码，所以用例里量到的取值顺序、来源、以及不合法的值怎么被跳过，
 * 与线上是同一件事，不会出现「用例自造一套规则」。</p>
 */
public final class TestSchedulerSettings {

    private TestSchedulerSettings() {
    }

    /** 只有环境属性（可以为空，那就是全用代码默认）：热配置那一层缺席。 */
    public static DualPoolSchedulerSettings propertyOnly(String... keyValues) {
        return new DualPoolSchedulerSettings(null, environment(keyValues));
    }

    /** 挂上一层热配置；环境属性按传入的键值对提供。 */
    public static DualPoolSchedulerSettings hot(AgentLlmProperties.Scheduler scheduler, String... keyValues) {
        AgentLlmProperties properties = new AgentLlmProperties();
        properties.getRuntime().setScheduler(scheduler);
        AgentLlmLocalConfigLoader loader = Mockito.mock(AgentLlmLocalConfigLoader.class);
        Mockito.when(loader.current()).thenReturn(Optional.of(properties));
        Mockito.when(loader.hotConfigIsAuthoritative()).thenReturn(true);
        return new DualPoolSchedulerSettings(loader, environment(keyValues));
    }

    private static MockEnvironment environment(String... keyValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            environment.setProperty(keyValues[index], keyValues[index + 1]);
        }
        return environment;
    }
}
