package world.willfrog.agentlangchain.deployment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 三个部署组件必须能被 Spring 用生产构造器正常装配。
 *
 * <p>这三个类都保留了包级测试构造器，属于多构造器类：生产构造器不标 {@code @Autowired}
 * 时 Spring 会回退找无参构造器并直接失败（No default constructor found），应用启动即崩。
 * 直接 {@code new} 包级构造器的单测走不到 Spring 构造器解析，拦不住这类问题；
 * 本测试走真实容器装配路径。</p>
 */
class DeploymentBeansConstructorWiringTest {

    @Test
    void reaperProbeAndShutdownStateResolveThroughSpringConstructorInjection() {
        DeploymentIdentity identity = new DeploymentIdentity("stable", "gen-" + "a".repeat(64));
        DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
        when(identityProvider.current()).thenReturn(identity);
        ThreadPoolTaskExecutor runExecutor = new ThreadPoolTaskExecutor();
        runExecutor.initialize();
        new ApplicationContextRunner()
                .withPropertyValues(
                        "agent.langchain.generation-reaper.enabled=true",
                        "agent.langchain.generation-reaper.nacos.server-address=127.0.0.1:1",
                        "agent.langchain.run.executor.shutdown-await-seconds=0",
                        "agent.langchain.run.executor.shutdown-finalization-margin-seconds=0")
                .withBean(AgentRunMapper.class, () -> mock(AgentRunMapper.class))
                .withBean(DeploymentIdentityProvider.class, () -> identityProvider)
                .withBean(LangchainRunConcurrencyScheduler.class,
                        () -> mock(LangchainRunConcurrencyScheduler.class))
                .withBean("agentLangchainRunTaskExecutor", ThreadPoolTaskExecutor.class, () -> runExecutor)
                .withUserConfiguration(
                        NacosDeploymentGenerationLivenessProbe.class,
                        DeploymentGenerationReaper.class,
                        AgentServiceShutdownState.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(NacosDeploymentGenerationLivenessProbe.class);
                    assertThat(context).hasSingleBean(DeploymentGenerationReaper.class);
                    assertThat(context).hasSingleBean(AgentServiceShutdownState.class);
                });
    }
}
