package world.willfrog.agentlangchain.deployment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.DeploymentGenerationRecord;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 为已经没有存活实例的代际补写明确失败终态。
 *
 * <p>第一次观察到注册为空时只开始计时；确认期限到达后，在写库前再次核对注册仍为空，
 * 才允许使用独立 SQL 分批补写失败终态。注册查询失败时停止本轮清扫。</p>
 */
@Component
@ConditionalOnProperty(prefix = "agent.langchain.generation-reaper", name = "enabled", havingValue = "true")
@Slf4j
public class DeploymentGenerationReaper {

    private static final String FAILURE = "deployment_generation_shutdown_deadline_exceeded";

    private final AgentRunMapper runMapper;
    private final DeploymentIdentityProvider identityProvider;
    private final DeploymentGenerationLivenessProbe livenessProbe;
    private final Duration absenceConfirmation;
    private final int batchSize;
    private final LongSupplier nanoTime;
    private final Map<DeploymentIdentity, Long> missingSinceNanos = new HashMap<>();

    public DeploymentGenerationReaper(
            AgentRunMapper runMapper,
            DeploymentIdentityProvider identityProvider,
            DeploymentGenerationLivenessProbe livenessProbe,
            @Value("${agent.langchain.generation-reaper.absence-confirmation-seconds:120}")
            long absenceConfirmationSeconds,
            @Value("${agent.langchain.generation-reaper.batch-size:32}") int batchSize) {
        this(runMapper, identityProvider, livenessProbe,
                Duration.ofSeconds(Math.max(1, absenceConfirmationSeconds)), batchSize,
                System::nanoTime);
    }

    DeploymentGenerationReaper(AgentRunMapper runMapper,
                               DeploymentIdentityProvider identityProvider,
                               DeploymentGenerationLivenessProbe livenessProbe,
                               Duration absenceConfirmation,
                               int batchSize,
                               LongSupplier nanoTime) {
        this.runMapper = runMapper;
        this.identityProvider = identityProvider;
        this.livenessProbe = livenessProbe;
        this.absenceConfirmation = absenceConfirmation;
        this.batchSize = Math.max(1, Math.min(batchSize, 256));
        this.nanoTime = nanoTime;
    }

    @Scheduled(fixedDelayString = "${agent.langchain.generation-reaper.scan-interval-ms:5000}")
    public synchronized void sweep() {
        try {
            sweepWithVerifiedRegistry();
        } catch (RegistryUncertainException registryUncertain) {
            log.warn("无法核对 Nacos 注册现场，本轮不再清扫其它部署代际", registryUncertain);
        } catch (RuntimeException databaseFailure) {
            log.error("代际清扫器访问数据库失败，本轮停止且不改变注册缺席计时", databaseFailure);
        }
    }

    private void sweepWithVerifiedRegistry() {
        DeploymentIdentity local = identityProvider.current();
        List<DeploymentGenerationRecord> candidates = runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId());
        Set<DeploymentIdentity> currentCandidates = new LinkedHashSet<>();
        for (DeploymentGenerationRecord candidate : candidates) {
            try {
                DeploymentIdentity identity = new DeploymentIdentity(
                        candidate.getDeploymentId(), candidate.getDeploymentGenerationId());
                if (!"stable".equals(identity.deploymentId())) {
                    currentCandidates.add(identity);
                }
            } catch (IllegalArgumentException invalidIdentity) {
                log.error("拒绝清扫格式不合法的部署代际记录: deploymentId={} generationId={}",
                        candidate.getDeploymentId(), candidate.getDeploymentGenerationId());
            }
        }
        missingSinceNanos.keySet().retainAll(currentCandidates);
        long now = nanoTime.getAsLong();
        int remaining = batchSize;
        for (DeploymentIdentity identity : currentCandidates) {
            if (remaining == 0) {
                break;
            }
            if (hasLiveInstance(identity)) {
                missingSinceNanos.remove(identity);
                continue;
            }
            long missingSince = missingSinceNanos.computeIfAbsent(identity, ignored -> now);
            if (now - missingSince < absenceConfirmation.toNanos()) {
                continue;
            }
            if (hasLiveInstance(identity)) {
                missingSinceNanos.remove(identity);
                continue;
            }
            int requested = remaining;
            int failed = runMapper.failOrphanedNonTerminalRunsForDeploymentGeneration(
                    identity.deploymentId(), identity.generationId(), FAILURE, requested);
            remaining -= Math.min(remaining, Math.max(0, failed));
            if (failed < requested) {
                missingSinceNanos.remove(identity);
            }
            if (failed > 0) {
                log.warn("部署代际经过确认期限仍没有存活实例，已把遗留 Run 写为失败: "
                                + "deploymentId={} generationId={} count={}",
                        identity.deploymentId(), identity.generationId(), failed);
            }
        }
    }

    private boolean hasLiveInstance(DeploymentIdentity identity) {
        try {
            return livenessProbe.hasLiveInstance(identity);
        } catch (RuntimeException registryUncertain) {
            throw new RegistryUncertainException(registryUncertain);
        }
    }

    private static final class RegistryUncertainException extends RuntimeException {
        private RegistryUncertainException(RuntimeException cause) {
            super(cause);
        }
    }
}
