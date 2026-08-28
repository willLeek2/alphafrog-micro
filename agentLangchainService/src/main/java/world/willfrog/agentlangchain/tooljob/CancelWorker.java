package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.dag.*;
import world.willfrog.agent.platform.mapper.AgentRunDagNodeMapper;

import java.util.List;
import java.util.Map;

/**
 * DAG Cancel Worker — 执行单个 run 的 cancel 流程。
 * 由 CancelReconciler 周期性调度，每次处理一批到期 child。
 *
 * <p>三阶段协议：
 * <ul>
 *   <li>Phase A (CTE tx): 原子写 CANCELLING frontier + 标记所有活跃 child</li>
 *   <li>Phase B（事务外 RPC）: 调用 Sandbox cancelTask（该 RPC 尚未就绪，当前先跳过）</li>
 *   <li>Phase C (per-child tx): 根据 RPC 结果写 child 终态或重试计数</li>
 * </ul>
 *
 * <p>有界批次：每轮 SELECT 一批 child → 逐个处理 → 如有进度则继续下一轮。
 * 当一轮内没有任何 child 被成功处理时终止。PREPARING/FIRST/RETRY 在 RPC 就绪前
 * 只记录日志、不改变 child 状态，因此会触发 processedAny=false 退出，由 Reconciler
 * 下次周期再尝试。
 */
@Component
public class CancelWorker {

    private static final Logger log = LoggerFactory.getLogger(CancelWorker.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentRunDagNodeMapper dagNodeMapper;
    private final TransactionTemplate transactionTemplate;

    public CancelWorker(AgentRunDagNodeMapper dagNodeMapper,
                        TransactionTemplate transactionTemplate) {
        this.dagNodeMapper = dagNodeMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Phase A: 原子写入 CANCELLING frontier + 标记所有活跃 child。
     *
     * <p>根据已锁住的 frontier phase 自动选择 statement：
     * <ul>
     *   <li>SUSPENDING/SUSPENDED → {@code cancelFrontierAndChildrenCTE}</li>
     *   <li>RESUMING → {@code cancelFrontierAndChildrenCTE_resume}（需要 lease triple）</li>
     * </ul>
     *
     * @param runId                       run ID
     * @param generation                  当前代际
     * @param expectedFrontierVersion     期望的 frontier 版本
     * @param cancelRequestId             取消请求幂等 ID
     * @param initialBackoffSeconds       首次重试退避秒数
     * @param resumeToken                 RESUMING 租约 token（非 RESUMING 传空）
     * @param ownerId                     RESUMING 租约 owner（非 RESUMING 传空）
     * @param expectedResumeLeaseVersion  RESUMING 期望租约版本（非 RESUMING 传 0）
     * @return CancelResult
     */
    public CancelResult startCancel(String runId, int generation, int expectedFrontierVersion,
                                     String cancelRequestId, int initialBackoffSeconds,
                                     String resumeToken, String ownerId,
                                     long expectedResumeLeaseVersion) {

        return transactionTemplate.execute(status -> {
            String frontierJson = dagNodeMapper.selectFrontierForUpdate(runId);
            if (frontierJson == null) {
                return new CancelResult(false, 0, 0);
            }

            String phase;
            try {
                JsonNode node = objectMapper.readTree(frontierJson);
                phase = node.has("phase") ? node.get("phase").asText() : null;
            } catch (Exception e) {
                log.error("CancelWorker: 解析 frontier JSON 失败 runId={}", runId, e);
                status.setRollbackOnly();
                return new CancelResult(false, 0, 0);
            }

            CancelResult result;
            if ("RESUMING".equals(phase)) {
                result = dagNodeMapper.cancelFrontierAndChildrenCTE_resume(
                        runId, generation, expectedFrontierVersion,
                        String.valueOf(System.currentTimeMillis() / 1000),
                        cancelRequestId, initialBackoffSeconds,
                        resumeToken, ownerId, expectedResumeLeaseVersion);
            } else {
                result = dagNodeMapper.cancelFrontierAndChildrenCTE(
                        runId, generation, expectedFrontierVersion,
                        String.valueOf(System.currentTimeMillis() / 1000),
                        cancelRequestId, initialBackoffSeconds);
            }

            if (result == null || result.frontierRows() != 1) {
                status.setRollbackOnly();
                return new CancelResult(false, 0, 0);
            }
            return result;
        });
    }

    /**
     * Phase B+C: 单批处理到期的待取消 child。
     * 一次调用只查询一批并处理，然后返回。后续批次由 CancelReconciler 的下次调度继续。
     * 这保证一次持租时间限制在一个 batch 内，不需续租。
     *
     * @param runId                 run ID
     * @param generation            当前代际
     * @param cancelRequestId       取消请求幂等 ID
     * @param initialBackoffSeconds 首次退避秒数
     * @param batchSize             单批处理数量
     */
    public void runCancelWorker(String runId, int generation, String cancelRequestId,
                                 int initialBackoffSeconds, int batchSize) {
        List<Map<String, Object>> children = dagNodeMapper.selectCancelDueChildren(
                runId, generation, cancelRequestId, batchSize);
        if (children == null || children.isEmpty()) {
            return;
        }

        int processed = 0;
        for (Map<String, Object> row : children) {
            try {
                if (processChild(runId, generation, cancelRequestId,
                        initialBackoffSeconds, row)) {
                    processed++;
                }
            } catch (Exception e) {
                log.error("CancelWorker: 处理 child 异常 runId={} nodeId={}",
                        runId, row.get("nodeId"), e);
            }
        }
        log.debug("CancelWorker: 单批完成 runId={} processed={} total={}",
                runId, processed, children.size());
    }

    /** @return true 如果 child 状态被成功修改 */
    private boolean processChild(String runId, int generation, String cancelRequestId,
                                  int initialBackoffSeconds, Map<String, Object> row) {
        String bucket = (String) row.get("cancelBucket");
        if (bucket == null) {
            return false;
        }

        CancelBucket cancelBucket;
        try {
            cancelBucket = CancelBucket.valueOf(bucket);
        } catch (IllegalArgumentException e) {
            log.warn("CancelWorker: 未知 cancelBucket={}", bucket);
            return false;
        }

        String nodeId = (String) row.get("nodeId");
        long nodeVersion = ((Number) row.get("nodeVersion")).longValue();
        String operationId = (String) row.get("operationId");
        String toolCallId = (String) row.get("toolCallId");
        int attempt = ((Number) row.get("attempt")).intValue();
        String requestDigest = (String) row.get("requestDigest");

        return switch (cancelBucket) {
            case PREPARING_STUCK -> {
                ExhaustedAdvance ea = dagNodeMapper.writePreparingStuck(
                        runId, generation, nodeId, cancelRequestId, nodeVersion);
                yield ea != null && ea.newNodeVersion() > 0;
            }
            case RPC_EXHAUSTED -> {
                ExhaustedAdvance ea = dagNodeMapper.writeRpcExhausted(
                        runId, generation, nodeId, cancelRequestId, nodeVersion);
                yield ea != null && ea.newNodeVersion() > 0;
            }
            case RECOVERY -> {
                // cancelTask RPC 尚未就绪。就绪后必须先按 operationId 回查任务、
                // 确认结果保留期已过，才能把任务写成丢失终态；就绪前保持 no-op。
                log.debug("CancelWorker: RECOVERY blocked nodeId={}", nodeId);
                yield false;
            }
            case PREPARING, FIRST, RETRY -> {
                // cancelTask RPC 尚未就绪；不写任何计数和退避，避免伪造 RPC 失败记录
                log.debug("CancelWorker: RPC not available nodeId={} bucket={}",
                        nodeId, bucket);
                yield false;
            }
        };
    }

    /** 指数退避：1 → 60s, 2 → 120s, 3 → 240s, ... 上限 1h */
    private int computeBackoffSeconds(int retryCount) {
        if (retryCount <= 0) return 60;
        long seconds = 60L * (1L << (retryCount - 1));
        return (int) Math.min(seconds, 3600L);
    }
}
