package world.willfrog.agentlangchain.tooljob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.mapper.AgentRunDagNodeMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cancel Reconciler — 周期性扫描 CANCELLING 状态的 run，为每个 run 启动 CancelWorker。
 *
 * <p>默认关闭（matchIfMissing=false）。等 cancelTask RPC 与调用方都就绪后，
 * 再通过配置显式打开 {@code alphafrog.cancel.reconciler.enabled=true}。
 */
@Component
@ConditionalOnExpression("${alphafrog.cancel.reconciler.enabled:false}"
        + " && !${agent.deployment.retirement-only:false}")
public class CancelReconciler {

    private static final Logger log = LoggerFactory.getLogger(CancelReconciler.class);

    private final AgentRunDagNodeMapper dagNodeMapper;
    private final CancelWorker cancelWorker;

    private final String ownerId = UUID.randomUUID().toString();

    @Value("${alphafrog.cancel.reconciler.batchSize:10}")
    private int batchSize;

    @Value("${alphafrog.cancel.reconciler.initialBackoffSeconds:60}")
    private int initialBackoffSeconds;

    @Value("${alphafrog.cancel.reconciler.leaseSeconds:120}")
    private long leaseSeconds;

    public CancelReconciler(AgentRunDagNodeMapper dagNodeMapper,
                             CancelWorker cancelWorker) {
        this.dagNodeMapper = dagNodeMapper;
        this.cancelWorker = cancelWorker;
    }

    @Scheduled(fixedDelayString = "${alphafrog.cancel.reconciler.intervalMs:30000}",
               initialDelayString = "${alphafrog.cancel.reconciler.initialDelayMs:15000}")
    public void reconcile() {
        log.debug("CancelReconciler: 开始周期扫描 batchSize={}", batchSize);

        List<Map<String, Object>> candidates;
        try {
            candidates = dagNodeMapper.selectCANCELLINGRuns(batchSize);
        } catch (Exception e) {
            log.error("CancelReconciler: 扫描失败", e);
            return;
        }

        if (candidates == null || candidates.isEmpty()) {
            log.debug("CancelReconciler: 无候选 run");
            return;
        }

        log.info("CancelReconciler: 扫描到 {} 个候选 run", candidates.size());
        int claimed = 0;
        int skipped = 0;

        for (Map<String, Object> runRow : candidates) {
            String runId = (String) runRow.get("runId");
            Number generationNum = (Number) runRow.get("generation");
            String cancelRequestId = (String) runRow.get("cancelRequestId");

            if (runId == null || generationNum == null || cancelRequestId == null) {
                log.warn("CancelReconciler: 跳过无效行 runRow={}", runRow);
                skipped++;
                continue;
            }
            int generation = generationNum.intValue();

            // 短事务 claim：CAS 写入 reconcilerOwner + reconcilerLeaseUntil
            int claimResult;
            try {
                claimResult = dagNodeMapper.claimReconcilerLease(
                        runId, generation, cancelRequestId, ownerId, leaseSeconds);
            } catch (Exception e) {
                log.error("CancelReconciler: claim 异常 runId={} generation={}", runId, generation, e);
                skipped++;
                continue;
            }

            if (claimResult != 1) {
                log.debug("CancelReconciler: claim 冲突 runId={} generation={}（已被其他实例持有或身份不匹配）",
                        runId, generation);
                skipped++;
                continue;
            }

            claimed++;
            try {
                log.info("CancelReconciler: 启动 CancelWorker runId={} generation={}",
                        runId, generation);
                cancelWorker.runCancelWorker(
                        runId, generation, cancelRequestId,
                        initialBackoffSeconds, batchSize);
            } catch (Exception e) {
                log.error("CancelReconciler: CancelWorker 异常 runId={} generation={}",
                        runId, generation, e);
            } finally {
                // 释放条件带 ownerId，只有当前持有者能释放自己的 lease
                try {
                    int released = dagNodeMapper.releaseReconcilerLease(
                            runId, generation, cancelRequestId, ownerId);
                    log.debug("CancelReconciler: release lease runId={} generation={} released={}",
                            runId, generation, released);
                } catch (Exception e) {
                    log.error("CancelReconciler: 释放 lease 异常 runId={} generation={}",
                            runId, generation, e);
                }
            }
        }

        log.info("CancelReconciler: 本轮完成 claimed={} skipped={} total={}",
                claimed, skipped, candidates.size());
    }
}
