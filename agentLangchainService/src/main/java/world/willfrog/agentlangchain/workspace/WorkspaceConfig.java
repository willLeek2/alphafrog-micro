package world.willfrog.agentlangchain.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * workspace dump 线程池配置。
 *
 * <p>用于异步执行 run 终态后的 workspace 文件 dump 任务，CallerRunsPolicy 让 caller 阻塞以避免 OOM。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>agent.workspace.dump.executor.core-pool-size — 核心线程数，默认 4</li>
 *   <li>agent.workspace.dump.executor.max-pool-size — 最大线程数，默认 8</li>
 *   <li>agent.workspace.dump.executor.queue-capacity — 任务队列容量，默认 200</li>
 *   <li>agent.workspace.dump.executor.thread-name-prefix — 线程名前缀，默认 workspace-dump-</li>
 * </ul>
 *
 * @author wang
 */
@Configuration
// 260814 scheduler-03: workspace export 总开关默认关闭；关闭时 dump executor
// bean 不创建。
@ConditionalOnExpression("${agent.workspace.export-enabled:false}"
        + " && !${agent.deployment.retirement-only:false}")
public class WorkspaceConfig {

    @Bean(name = "workspaceDumpExecutor")
    public ThreadPoolTaskExecutor workspaceDumpExecutor(
            @Value("${agent.workspace.dump.executor.core-pool-size:4}") int corePoolSize,
            @Value("${agent.workspace.dump.executor.max-pool-size:8}") int maxPoolSize,
            @Value("${agent.workspace.dump.executor.queue-capacity:200}") int queueCapacity,
            @Value("${agent.workspace.dump.executor.thread-name-prefix:workspace-dump-}") String threadNamePrefix) {
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
}
