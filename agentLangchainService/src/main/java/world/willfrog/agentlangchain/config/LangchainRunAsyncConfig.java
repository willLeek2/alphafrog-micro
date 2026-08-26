package world.willfrog.agentlangchain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Run worker 的物理线程池配置。
 *
 * <p>物理线程池只执行已经被业务调度器批准的任务，因此 queueCapacity 固定为 0；
 * FIFO、动态 queue 容量、core/max 准入和公平提升全部由
 * {@code LangchainRunConcurrencyScheduler} 维护，避免出现“业务队列看不到的第二层积压”。</p>
 */
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
        // 业务调度器维护唯一队列，物理线程池的 queueCapacity 设为 0，
        // 避免再出现一层无法热缩放、无法观测的积压。
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
