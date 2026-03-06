package world.willfrog.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class DagExecutorConfig {

    @Bean("dagExecutor")
    public ExecutorService dagExecutor(
            @Value("${agent.dag.executor.core-pool-size:10}") int corePoolSize,
            @Value("${agent.dag.executor.max-pool-size:20}") int maxPoolSize,
            @Value("${agent.dag.executor.queue-capacity:100}") int queueCapacity,
            @Value("${agent.dag.executor.keep-alive-seconds:60}") long keepAliveSeconds) {

        return new ThreadPoolExecutor(
                Math.max(1, corePoolSize),
                Math.max(corePoolSize, maxPoolSize),
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
                new DagThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private static class DagThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "dag-pool-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
