package world.willfrog.agentlangchain.config;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainRunExecutorLimitsResolverTest {

    @Test
    void currentLimits_shouldDefaultToHardWhenParallelCurrentMissing() {
        AgentLlmProperties cfg = new AgentLlmProperties();
        AgentLlmProperties.ExecutorConfig executor = new AgentLlmProperties.ExecutorConfig();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);

        AgentLlmProperties.ExecutorParallelConfig parallel = new AgentLlmProperties.ExecutorParallelConfig();
        AgentLlmProperties.ExecutorConfig hard = new AgentLlmProperties.ExecutorConfig();
        hard.setCorePoolSize(100);
        hard.setMaxPoolSize(100);
        hard.setQueueCapacity(1000);
        parallel.setHard(hard);
        executor.setParallel(parallel);
        cfg.setExecutor(executor);

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        assertThat(resolver.hardLimits().getCorePoolSize()).isEqualTo(100);
        assertThat(resolver.currentLimits().getCorePoolSize()).isEqualTo(100);
        assertThat(resolver.currentLimits().getQueueCapacity()).isEqualTo(1000);
    }

    @Test
    void currentLimits_shouldClampToHardGate() {
        AgentLlmProperties cfg = new AgentLlmProperties();
        AgentLlmProperties.ExecutorConfig executor = new AgentLlmProperties.ExecutorConfig();
        AgentLlmProperties.ExecutorParallelConfig parallel = new AgentLlmProperties.ExecutorParallelConfig();

        AgentLlmProperties.ExecutorConfig hard = new AgentLlmProperties.ExecutorConfig();
        hard.setCorePoolSize(10);
        hard.setMaxPoolSize(20);
        hard.setQueueCapacity(100);
        parallel.setHard(hard);

        AgentLlmProperties.ExecutorConfig current = new AgentLlmProperties.ExecutorConfig();
        current.setCorePoolSize(30);
        current.setMaxPoolSize(40);
        current.setQueueCapacity(200);
        parallel.setCurrent(current);
        executor.setParallel(parallel);
        cfg.setExecutor(executor);

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        assertThat(resolver.currentLimits().getCorePoolSize()).isEqualTo(10);
        assertThat(resolver.currentLimits().getMaxPoolSize()).isEqualTo(20);
        assertThat(resolver.currentLimits().getQueueCapacity()).isEqualTo(100);
    }
}
