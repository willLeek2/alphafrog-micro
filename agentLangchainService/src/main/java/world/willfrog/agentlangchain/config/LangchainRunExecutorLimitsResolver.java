package world.willfrog.agentlangchain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

@Component
@Slf4j
public class LangchainRunExecutorLimitsResolver {

    private final AgentLlmLocalConfigLoader configLoader;
    private final LangchainRunExecutorLimits hardLimits;

    public LangchainRunExecutorLimitsResolver(
            @Value("${agent.langchain.run.executor.core-pool-size:4}") int defaultCorePoolSize,
            @Value("${agent.langchain.run.executor.max-pool-size:8}") int defaultMaxPoolSize,
            @Value("${agent.langchain.run.executor.queue-capacity:50}") int defaultQueueCapacity,
            @Value("${agent.langchain.run.executor.thread-name-prefix:agent-langchain-run-}") String defaultThreadNamePrefix,
            AgentLlmLocalConfigLoader configLoader) {
        this.configLoader = configLoader;
        AgentLlmProperties.ExecutorConfig executorConfig = configLoader.current()
                .map(AgentLlmProperties::getExecutor)
                .orElse(null);
        this.hardLimits = resolveHardLimits(
                executorConfig,
                defaultCorePoolSize,
                defaultMaxPoolSize,
                defaultQueueCapacity,
                defaultThreadNamePrefix);
        log.info("Langchain run hard executor limits initialized: {}", hardLimits.summary());
    }

    public LangchainRunExecutorLimits hardLimits() {
        return hardLimits;
    }

    public LangchainRunExecutorLimits currentLimits() {
        AgentLlmProperties.ExecutorConfig executorConfig = configLoader.current()
                .map(AgentLlmProperties::getExecutor)
                .orElse(null);
        AgentLlmProperties.ExecutorConfig currentConfig = null;
        if (executorConfig != null && executorConfig.getParallel() != null) {
            currentConfig = executorConfig.getParallel().getCurrent();
            if (currentConfig == null) {
                return hardLimits;
            }
        }
        if (currentConfig == null) {
            currentConfig = executorConfig;
        }
        return clampCurrent(currentConfig);
    }

    private LangchainRunExecutorLimits resolveHardLimits(AgentLlmProperties.ExecutorConfig executorConfig,
                                                         int defaultCorePoolSize,
                                                         int defaultMaxPoolSize,
                                                         int defaultQueueCapacity,
                                                         String defaultThreadNamePrefix) {
        AgentLlmProperties.ExecutorConfig hardConfig = null;
        if (executorConfig != null && executorConfig.getParallel() != null) {
            hardConfig = executorConfig.getParallel().getHard();
        }
        if (hardConfig == null) {
            hardConfig = executorConfig;
        }
        int core = valueOrDefault(hardConfig == null ? null : hardConfig.getCorePoolSize(), defaultCorePoolSize);
        int max = valueOrDefault(hardConfig == null ? null : hardConfig.getMaxPoolSize(), defaultMaxPoolSize);
        int queue = valueOrDefault(hardConfig == null ? null : hardConfig.getQueueCapacity(), defaultQueueCapacity);
        String prefix = firstNonBlank(
                hardConfig == null ? null : hardConfig.getThreadNamePrefix(),
                executorConfig == null ? null : executorConfig.getThreadNamePrefix(),
                defaultThreadNamePrefix);
        return new LangchainRunExecutorLimits(core, max, queue, prefix);
    }

    private LangchainRunExecutorLimits clampCurrent(AgentLlmProperties.ExecutorConfig currentConfig) {
        int requestedCore = valueOrDefault(currentConfig == null ? null : currentConfig.getCorePoolSize(),
                hardLimits.getCorePoolSize());
        int requestedMax = valueOrDefault(currentConfig == null ? null : currentConfig.getMaxPoolSize(),
                hardLimits.getMaxPoolSize());
        int requestedQueue = valueOrDefault(currentConfig == null ? null : currentConfig.getQueueCapacity(),
                hardLimits.getQueueCapacity());
        String requestedPrefix = firstNonBlank(currentConfig == null ? null : currentConfig.getThreadNamePrefix(),
                hardLimits.getThreadNamePrefix());

        int max = Math.min(Math.max(1, requestedMax), hardLimits.getMaxPoolSize());
        int core = Math.min(Math.max(1, requestedCore), Math.min(max, hardLimits.getCorePoolSize()));
        int queue = Math.min(Math.max(0, requestedQueue), hardLimits.getQueueCapacity());
        if (core != requestedCore || max != requestedMax || queue != requestedQueue) {
            log.warn("Langchain run current executor limits exceed hard gate, clamped from core={}, max={}, queue={} to {}",
                    requestedCore, requestedMax, requestedQueue,
                    new LangchainRunExecutorLimits(core, max, queue, requestedPrefix).summary());
        }
        return new LangchainRunExecutorLimits(core, max, queue, requestedPrefix);
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
