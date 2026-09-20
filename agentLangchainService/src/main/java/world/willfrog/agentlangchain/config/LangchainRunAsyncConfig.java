package world.willfrog.agentlangchain.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.capacity.SchedulerBackpressureProbe;
import world.willfrog.agent.platform.capacity.SchedulerCapacityMetrics;
import world.willfrog.agent.platform.capacity.SchedulerPermitLedger;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agentlangchain.control.dualpool.DualPoolDispatcher;

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
        // AgentServiceShutdownState 在自然处理窗口内保持执行器可用，窗口结束后主动
        // 中断剩余任务并写失败终态。Bean 销毁阶段不得重新等待一遍完整处理期限。
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(0);
        return executor;
    }

    /**
     * 双池版本的 Run 协调线程池。这里的任务只允许执行一次短协调回合：读取数据库、
     * 推进编排状态、创建节点工作项，然后立即退出，不能在该线程内等待节点完成。
     */
    @Bean(name = "agentLangchainRunCoordinationTaskExecutor")
    public ThreadPoolTaskExecutor agentLangchainRunCoordinationTaskExecutor(
            @Value("${agent.langchain.dual-pool.run-worker.core-pool-size:2}") int corePoolSize,
            @Value("${agent.langchain.dual-pool.run-worker.max-pool-size:2}") int maxPoolSize,
            @Value("${agent.langchain.dual-pool.run-worker.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${agent.langchain.dual-pool.run-worker.thread-name-prefix:agent-run-coordination-}")
            String threadNamePrefix) {
        return directHandoffExecutor(corePoolSize, maxPoolSize, keepAliveSeconds, threadNamePrefix);
    }

    /**
     * 双池版本的节点执行线程池。它只消费已经在数据库中成功领取的节点工作项，
     * 与 Run 协调线程完全分开，避免慢节点占住协调名额。
     */
    @Bean(name = "agentLangchainNodeTaskExecutor")
    public ThreadPoolTaskExecutor agentLangchainNodeTaskExecutor(
            @Value("${agent.langchain.dual-pool.node-worker.core-pool-size:4}") int corePoolSize,
            @Value("${agent.langchain.dual-pool.node-worker.max-pool-size:4}") int maxPoolSize,
            @Value("${agent.langchain.dual-pool.node-worker.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${agent.langchain.dual-pool.node-worker.thread-name-prefix:agent-node-}")
            String threadNamePrefix) {
        return directHandoffExecutor(corePoolSize, maxPoolSize, keepAliveSeconds, threadNamePrefix);
    }

    @Bean
    public SchedulerBackpressureProbe schedulerBackpressureProbe(
            NodeWorkItemStore store,
            DualPoolDispatcher dispatcher,
            @Value("${agent.langchain.dual-pool.per-run-unfinished-limit:256}") int perRunUnfinishedLimit) {
        return new SchedulerBackpressureProbe(store, dispatcher, perRunUnfinishedLimit);
    }

    @Bean
    public SchedulerCapacityMetrics schedulerCapacityMetrics(
            ObjectProvider<MeterRegistry> registryProvider,
            SchedulerPermitLedger permitLedger,
            SchedulerBackpressureProbe backpressureProbe) {
        SchedulerCapacityMetrics metrics = new SchedulerCapacityMetrics(
                registryProvider, permitLedger, backpressureProbe);
        metrics.register();
        return metrics;
    }

    private ThreadPoolTaskExecutor directHandoffExecutor(int corePoolSize,
                                                         int maxPoolSize,
                                                         int keepAliveSeconds,
                                                         String threadNamePrefix) {
        int normalizedMax = Math.max(1, maxPoolSize);
        int normalizedCore = Math.max(1, Math.min(corePoolSize, normalizedMax));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(normalizedCore);
        executor.setMaxPoolSize(normalizedMax);
        // 排队只存在于可丢、可重复的提示队列里。物理线程池不能再藏一层任务。
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(Math.max(0, keepAliveSeconds));
        executor.setThreadNamePrefix(threadNamePrefix == null || threadNamePrefix.isBlank()
                ? "agent-dual-pool-"
                : threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(0);
        return executor;
    }
}
