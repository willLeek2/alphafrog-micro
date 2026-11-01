package world.willfrog.agentlangchain.config;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainRunExecutorLimitsResolverTest {



    // ===== D03: getHardVersusEffectiveGap =====








    private static AgentLlmProperties configWithHardAndCurrent(
            int hardCore, int hardMax, int hardQueue, String hardPrefix,
            int curCore, int curMax, int curQueue, String curPrefix) {
        AgentLlmProperties cfg = new AgentLlmProperties();
        AgentLlmProperties.ExecutorConfig executor = new AgentLlmProperties.ExecutorConfig();
        AgentLlmProperties.ExecutorParallelConfig parallel = new AgentLlmProperties.ExecutorParallelConfig();

        AgentLlmProperties.ExecutorConfig hard = new AgentLlmProperties.ExecutorConfig();
        hard.setCorePoolSize(hardCore);
        hard.setMaxPoolSize(hardMax);
        hard.setQueueCapacity(hardQueue);
        hard.setThreadNamePrefix(hardPrefix);
        parallel.setHard(hard);

        AgentLlmProperties.ExecutorConfig current = new AgentLlmProperties.ExecutorConfig();
        current.setCorePoolSize(curCore);
        current.setMaxPoolSize(curMax);
        current.setQueueCapacity(curQueue);
        current.setThreadNamePrefix(curPrefix);
        parallel.setCurrent(current);

        executor.setParallel(parallel);
        cfg.setExecutor(executor);
        return cfg;
    }
}
