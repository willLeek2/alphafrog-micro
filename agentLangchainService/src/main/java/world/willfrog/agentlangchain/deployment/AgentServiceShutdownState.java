package world.willfrog.agentlangchain.deployment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
    private final Duration drainTimeout;

    public AgentServiceShutdownState(
            AgentRunMapper runMapper,
            DeploymentIdentityProvider identityProvider,
            LangchainRunConcurrencyScheduler scheduler,
            @Value("${agent.langchain.run.executor.shutdown-await-seconds:120}") int shutdownAwaitSeconds,
            @Value("${agent.langchain.run.executor.shutdown-finalization-margin-seconds:5}")
            int finalizationMarginSeconds) {
        this.runMapper = runMapper;
        this.identityProvider = identityProvider;
        this.scheduler = scheduler;
        int totalSeconds = Math.max(0, shutdownAwaitSeconds);
        int reservedSeconds = totalSeconds <= 1
                ? 0
                : Math.min(Math.max(1, finalizationMarginSeconds), totalSeconds - 1);
        this.drainTimeout = Duration.ofSeconds(totalSeconds - reservedSeconds);
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
            long deadlineNanos = System.nanoTime() + drainTimeout.toNanos();
            while (System.nanoTime() < deadlineNanos
                    && runMapper.countNonTerminalRunsForDeploymentGeneration(
                    identity.deploymentId(), identity.generationId()) > 0) {
                try {
                    Thread.sleep(Math.min(250L, Math.max(1L,
                            Duration.ofNanos(Math.max(1L, deadlineNanos - System.nanoTime())).toMillis())));
                } catch (InterruptedException shutdownInterrupt) {
                    interrupted = true;
                }
            }
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
}
