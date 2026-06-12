package world.willfrog.agent.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步编排 credit 结算的线程配置。
 *
 * <p>两个独立线程池：</p>
 * <ul>
 *   <li>{@code creditSettlementExecutor}：承接 {@link world.willfrog.agent.platform.service.AgentRunCreditSettlementService#settleAsync}
 *       的 fire-and-forget 调用。immediate settlement 阶段在此执行；与 agent run 线程池隔离，
 *       结算阻塞不会拖垮 agent run。</li>
 *   <li>{@code creditSettlementScheduler}：单线程 scheduled executor，负责 30s delayed retry
 *       的延迟调度。单线程即可：retry 任务就是读 trace + 写库，没有 CPU bound 操作；
 *       单线程避免并发 retry 同一 runId 时的竞态。</li>
 * </ul>
 */
@Configuration
public class CreditSettlementAsyncConfig {

    @Bean(name = "creditSettlementExecutor")
    public Executor creditSettlementExecutor(
            @Value("${agent.credit.settlement.executor.core-pool-size:4}") int corePoolSize,
            @Value("${agent.credit.settlement.executor.max-pool-size:8}") int maxPoolSize,
            @Value("${agent.credit.settlement.executor.queue-capacity:200}") int queueCapacity,
            @Value("${agent.credit.settlement.executor.thread-name-prefix:credit-settle-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "creditSettlementScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService creditSettlementScheduler(
            @Value("${agent.credit.settlement.scheduler.thread-name-prefix:credit-settle-sched-}") String threadNamePrefix) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadNamePrefix + "1");
            t.setDaemon(true);
            return t;
        });
    }
}
