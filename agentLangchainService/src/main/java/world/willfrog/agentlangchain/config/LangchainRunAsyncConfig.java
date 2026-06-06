package world.willfrog.agentlangchain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class LangchainRunAsyncConfig {

    @Bean(name = "agentLangchainRunTaskExecutor")
    public ThreadPoolTaskExecutor agentLangchainRunTaskExecutor(
            @Value("${agent.langchain.run.executor.keep-alive-seconds:60}") int keepAliveSeconds,
            LangchainRunExecutorLimitsResolver limitsResolver) {
        LangchainRunExecutorLimits hard = limitsResolver.hardLimits();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(hard.getCorePoolSize());
        executor.setMaxPoolSize(hard.getMaxPoolSize());
        // Queueing semantics live in LangchainRunConcurrencyScheduler so current.queueCapacity
        // can shrink/grow at runtime. Keep the physical executor queue at 0 to avoid hidden backlog.
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(hard.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
