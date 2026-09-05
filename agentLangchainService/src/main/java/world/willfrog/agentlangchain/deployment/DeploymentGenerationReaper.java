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
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 为已经没有存活实例的代际补写明确失败终态。
 *
 * <p>第一次观察到注册为空时只开始计时；连续缺席达到统一处理期限，并在写库前再次核对
 * 注册仍为空，才允许使用独立 SQL 收口。注册查询失败时停止当前及剩余候选，绝不把
 * “不知道”当作消亡；失败前已经提交的其它候选写入不回滚。</p>
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
    private final Map<DeploymentIdentity, Long> lastSeenScanCycle = new HashMap<>();
    private DeploymentIdentity scanCursor;
    private long scanCycle;

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
            missingSinceNanos.clear();
            lastSeenScanCycle.clear();
            log.warn("无法核对 Nacos 注册现场，停止当前及剩余候选的清扫并重新计算连续缺席期限；"
                    + "此前已提交的写入不回滚", registryUncertain);
        } catch (RuntimeException databaseFailure) {
            log.error("代际清扫器访问数据库失败，本轮停止且不改变注册缺席计时", databaseFailure);
        }
    }

    private void sweepWithVerifiedRegistry() {
        DeploymentIdentity local = identityProvider.current();
        List<DeploymentGenerationRecord> candidates = listNextCandidates(local);
        long now = nanoTime.getAsLong();
        int remaining = batchSize;
        for (DeploymentGenerationRecord candidate : candidates) {
            if (remaining == 0) {
                break;
            }
            DeploymentIdentity identity;
            try {
                identity = new DeploymentIdentity(
                        candidate.getDeploymentId(), candidate.getDeploymentGenerationId());
            } catch (IllegalArgumentException invalidIdentity) {
                log.error("拒绝清扫格式不合法的部署代际记录: deploymentId={} generationId={}",
                        candidate.getDeploymentId(), candidate.getDeploymentGenerationId());
                continue;
            }
            if ("stable".equals(identity.deploymentId())) {
                missingSinceNanos.remove(identity);
                lastSeenScanCycle.remove(identity);
                continue;
            }
            lastSeenScanCycle.put(identity, scanCycle);
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
                lastSeenScanCycle.remove(identity);
            } else if (runMapper.countNonTerminalRunsForDeploymentGeneration(
                    identity.deploymentId(), identity.generationId()) == 0) {
                missingSinceNanos.remove(identity);
                lastSeenScanCycle.remove(identity);
            }
            if (failed > 0) {
                log.warn("部署代际已连续超过处理期限没有存活实例，已把遗留 Run 写为失败: "
                                + "deploymentId={} generationId={} count={}",
                        identity.deploymentId(), identity.generationId(), failed);
            }
        }
        if (!candidates.isEmpty()) {
            DeploymentGenerationRecord last = candidates.get(candidates.size() - 1);
            try {
                scanCursor = new DeploymentIdentity(
                        last.getDeploymentId(), last.getDeploymentGenerationId());
            } catch (IllegalArgumentException invalidCursor) {
                scanCursor = null;
            }
        }
    }

    private List<DeploymentGenerationRecord> listNextCandidates(DeploymentIdentity local) {
        List<DeploymentGenerationRecord> candidates = runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(),
                scanCursor == null ? null : scanCursor.deploymentId(),
                scanCursor == null ? null : scanCursor.generationId(), batchSize);
        if (candidates.isEmpty() && scanCursor != null) {
            evictEntriesNotSeenInCycle(scanCycle);
            scanCycle++;
            scanCursor = null;
            candidates = runMapper.listNonTerminalDeploymentGenerations(
                    local.deploymentId(), local.generationId(), null, null, batchSize);
            if (candidates.isEmpty()) {
                missingSinceNanos.clear();
                lastSeenScanCycle.clear();
            }
        }
        return candidates;
    }

    private boolean hasLiveInstance(DeploymentIdentity identity) {
        try {
            return livenessProbe.hasLiveInstance(identity);
        } catch (RuntimeException registryUncertain) {
            throw new RegistryUncertainException(registryUncertain);
        }
    }

    private void evictEntriesNotSeenInCycle(long completedCycle) {
        lastSeenScanCycle.entrySet().removeIf(entry -> {
            if (entry.getValue() < completedCycle) {
                missingSinceNanos.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    private static final class RegistryUncertainException extends RuntimeException {
        private RegistryUncertainException(RuntimeException cause) {
            super(cause);
        }
    }
}
