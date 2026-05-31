package world.willfrog.agentlangchain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

@Configuration
public class LangchainRunAsyncConfig {

    @Bean(name = "agentLangchainRunTaskExecutor")
    public Executor agentLangchainRunTaskExecutor(
            @Value("${agent.langchain.run.executor.core-pool-size:4}") int defaultCorePoolSize,
            @Value("${agent.langchain.run.executor.max-pool-size:8}") int defaultMaxPoolSize,
            @Value("${agent.langchain.run.executor.queue-capacity:50}") int defaultQueueCapacity,
            @Value("${agent.langchain.run.executor.thread-name-prefix:agent-langchain-run-}") String defaultThreadNamePrefix,
            AgentLlmLocalConfigLoader configLoader) {

        int corePoolSize = defaultCorePoolSize;
        int maxPoolSize = defaultMaxPoolSize;
        int queueCapacity = defaultQueueCapacity;
        String threadNamePrefix = defaultThreadNamePrefix;

        AgentLlmProperties.ExecutorConfig executorConfig = configLoader.current()
                .map(AgentLlmProperties::getExecutor)
                .orElse(null);

        if (executorConfig != null) {
            if (executorConfig.getCorePoolSize() != null) {
                corePoolSize = executorConfig.getCorePoolSize();
            }
            if (executorConfig.getMaxPoolSize() != null) {
                maxPoolSize = executorConfig.getMaxPoolSize();
            }
            if (executorConfig.getQueueCapacity() != null) {
                queueCapacity = executorConfig.getQueueCapacity();
            }
            if (executorConfig.getThreadNamePrefix() != null && !executorConfig.getThreadNamePrefix().isEmpty()) {
                threadNamePrefix = executorConfig.getThreadNamePrefix();
            }
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
