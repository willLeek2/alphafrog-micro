package world.willfrog.agentlangchain.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** D06 子代理专用的有界执行池与超时调度器。 */
@Configuration
public class LangchainSubAgentConfiguration {

    @Bean(name = "langchainSubAgentExecutor", destroyMethod = "shutdown")
    public ExecutorService langchainSubAgentExecutor(
            @Value("${agent.sub-agent.executor.core-pool-size:3}") int corePoolSize,
            @Value("${agent.sub-agent.executor.max-pool-size:6}") int maxPoolSize,
            @Value("${agent.sub-agent.executor.queue-capacity:24}") int queueCapacity) {
        int core = Math.max(1, corePoolSize);
        int max = Math.max(core, maxPoolSize);
        int queue = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                core,
                max,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                namedFactory("agent-sub-agent-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "langchainSubAgentTimeoutScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService langchainSubAgentTimeoutScheduler() {
        return Executors.newSingleThreadScheduledExecutor(namedFactory("agent-sub-agent-timeout-"));
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
