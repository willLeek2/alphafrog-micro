package world.willfrog.agentlangchain.deployment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** 停收新任务后等待本代 Run 自然完成，自然处理窗口结束时把剩余记录诚实地收为失败。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class AgentServiceShutdownState implements ApplicationListener<ContextClosedEvent> {

    private final AtomicBoolean handled = new AtomicBoolean();
    private final AgentRunMapper runMapper;
    private final DeploymentIdentityProvider identityProvider;
    private final LangchainRunConcurrencyScheduler scheduler;
    private final ThreadPoolTaskExecutor runExecutor;
    private final Duration drainTimeout;

    public AgentServiceShutdownState(
            AgentRunMapper runMapper,
            DeploymentIdentityProvider identityProvider,
            LangchainRunConcurrencyScheduler scheduler,
            @org.springframework.beans.factory.annotation.Qualifier("agentLangchainRunTaskExecutor")
            ThreadPoolTaskExecutor runExecutor,
            @Value("${agent.langchain.run.executor.shutdown-await-seconds:60}") int shutdownAwaitSeconds,
            @Value("${agent.langchain.run.executor.shutdown-finalization-margin-seconds:5}")
            int finalizationMarginSeconds) {
        this(runMapper, identityProvider, scheduler, runExecutor,
                Duration.ofSeconds(Math.max(0, shutdownAwaitSeconds)),
                Duration.ofSeconds(reservedSeconds(shutdownAwaitSeconds, finalizationMarginSeconds)));
    }

    AgentServiceShutdownState(
            AgentRunMapper runMapper,
            DeploymentIdentityProvider identityProvider,
            LangchainRunConcurrencyScheduler scheduler,
            ThreadPoolTaskExecutor runExecutor,
            Duration totalTimeout,
            Duration finalizationMargin) {
        this.runMapper = runMapper;
        this.identityProvider = identityProvider;
        this.scheduler = scheduler;
        this.runExecutor = runExecutor;
        Duration normalizedTotalTimeout = nonNegative(totalTimeout);
        Duration requestedMargin = nonNegative(finalizationMargin);
        this.drainTimeout = requestedMargin.compareTo(normalizedTotalTimeout) >= 0
                ? Duration.ZERO
                : normalizedTotalTimeout.minus(requestedMargin);
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!handled.compareAndSet(false, true)) {
            return;
        }
        scheduler.stopAcceptingNewRuns();
        DeploymentIdentity identity = identityProvider.current();
        boolean interrupted = false;
        try {
            long naturalDeadlineNanos = System.nanoTime() + drainTimeout.toNanos();
            while (remainingNanos(naturalDeadlineNanos) > 0
                    && runMapper.countNonTerminalRunsForDeploymentGeneration(
                    identity.deploymentId(), identity.generationId()) > 0) {
                try {
                    Thread.sleep(Math.min(250L, Math.max(1L,
                            Duration.ofNanos(Math.max(1L, remainingNanos(naturalDeadlineNanos))).toMillis())));
                } catch (InterruptedException shutdownInterrupt) {
                    interrupted = true;
                }
            }
            // 自然窗口到达以后不再让执行线程继续占用最后的持久化与退出余量。
            // ThreadPoolTaskExecutor 已配置为无额外等待，shutdown 会立即中断剩余任务。
            runExecutor.shutdown();
            runMapper.failNonTerminalRunsForDeploymentGeneration(
                    identity.deploymentId(), identity.generationId(),
                    "deployment_generation_shutdown_deadline_exceeded");
        } catch (RuntimeException databaseFailure) {
            log.error("关闭期间无法把本代遗留 Run 写成明确失败，后续由代际清扫器补写: deploymentId={} generationId={}",
                    identity.deploymentId(), identity.generationId(), databaseFailure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    private static Duration nonNegative(Duration value) {
        if (value == null || value.isNegative()) {
            return Duration.ZERO;
        }
        return value;
    }

    private static int reservedSeconds(int totalSeconds, int requestedSeconds) {
        int normalizedTotal = Math.max(0, totalSeconds);
        if (normalizedTotal <= 1) {
            return 0;
        }
        return Math.min(Math.max(1, requestedSeconds), normalizedTotal - 1);
    }
}
