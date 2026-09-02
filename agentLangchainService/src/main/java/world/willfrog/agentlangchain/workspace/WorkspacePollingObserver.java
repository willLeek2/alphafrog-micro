package world.willfrog.agentlangchain.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * workspace terminal run reconciliation observer。
 *
 * <p>职责：定期扫 {@code alphafrog_agent_run}，找出处于终态集合（COMPLETED / PARTIAL / FAILED /
 * CANCELED / EXPIRED）且 {@code updated_at > lastSeenAt} 的 run，调
 * {@link WorkspaceDumpScheduler#enqueueDumpAsync} 触发 workspace dump。</p>
 *
 * <h3>为什么需要</h3>
 * <p>WorkspaceFinalizedEventListener 已在 agentLangchainService 同 JVM 内消费终态事件。
 * 但 Spring event 不是 durable event；进程重启、部署切换、async executor/DLQ 未持久化、
 * 历史 terminal run 未补 dump 等场景仍可能漏掉 workspace 文件。
 * Polling observer 通过 DB terminal run reconciliation/backfill 兜底这些路径。</p>
 *
 * <h3>幂等</h3>
 * <p>单批内只提交到 {@link WorkspaceDumpScheduler}；scheduler 负责异步执行、重试和 DLQ。
 * dump service 内部走 fingerprint 幂等（fingerprint 一致 + manifest 齐全时 skip），所以
 * polling 重扫同一 run 不会重复 dump。lastSeenAt 推到本批成功提交的最大 updated_at，
 * 确保下一次只扫更新的 run。</p>
 *
 * <h3>配置</h3>
 * <ul>
 *   <li>agent.workspace.polling.enabled — 是否启用，默认 true</li>
 *   <li>agent.workspace.polling.interval-ms — 轮询间隔 ms，默认 30000</li>
 *   <li>agent.workspace.polling.initial-delay-ms — 启动后首次延迟 ms，默认 10000</li>
 *   <li>agent.workspace.polling.batch-size — 单批上限，默认 100</li>
 *   <li>agent.workspace.polling.initial-lookback-minutes — 启动时回溯分钟数，默认 60</li>
 *   <li>agent.workspace.polling.conservative-statuses — 走保守分支的终态，默认 EXPIRED</li>
 * </ul>
 *
 * @author wang
 */
@Component
// 260814 scheduler-03: workspace export 总开关默认关闭；关闭时本观察者不注册，
// 没有 @Scheduled 定时线程。
@ConditionalOnExpression("${agent.workspace.export-enabled:false}"
        + " && !${agent.deployment.retirement-only:false}")
@RequiredArgsConstructor
@Slf4j
public class WorkspacePollingObserver {

    private final AgentRunMapper runMapper;
    private final WorkspaceDumpScheduler dumpScheduler;

    @Value("${agent.workspace.polling.enabled:true}")
    private boolean enabled;

    @Value("${agent.workspace.polling.batch-size:100}")
    private int batchSize;

    @Value("${agent.workspace.polling.initial-lookback-minutes:60}")
    private int initialLookbackMinutes;

    /**
     * D21-B 5.2.3: 复合游标 (updatedAt, runId)，防止同秒超批永久漏扫。
     * 每批处理完成后推进到本批最后一条记录的 (updatedAt, runId)。
     */
    private volatile OffsetDateTime lastSeenTime;
    private volatile String lastSeenRunId;

    {
        lastSeenTime = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(60);
        lastSeenRunId = "";
    }

    @Scheduled(
            fixedDelayString = "${agent.workspace.polling.interval-ms:30000}",
            initialDelayString = "${agent.workspace.polling.initial-delay-ms:10000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            doScan();
        } catch (Exception e) {
            log.warn("WorkspacePollingObserver scan failed: {}", e.getMessage(), e);
        }
    }

    private void doScan() {
        OffsetDateTime cursorTime = lastSeenTime;
        String cursorRunId = lastSeenRunId;
        List<AgentRun> runs = runMapper.listByStatusAndUpdatedAfterComposite(
                List.of(
                        AgentRunStatus.COMPLETED,
                        AgentRunStatus.PARTIAL,
                        AgentRunStatus.FAILED,
                        AgentRunStatus.CANCELED,
                        AgentRunStatus.EXPIRED),
                cursorTime,
                cursorRunId,
                batchSize);
        if (runs == null || runs.isEmpty()) {
            return;
        }
        log.info("WorkspacePollingObserver found terminal runs: count={} cursorTime={} cursorRunId={}",
                runs.size(), cursorTime, cursorRunId);
        boolean submissionFailed = false;
        for (AgentRun run : runs) {
            if (run == null || run.getId() == null) {
                continue;
            }
            try {
                boolean conservative = run.getStatus() == AgentRunStatus.EXPIRED;
                dumpScheduler.enqueueDumpAsync(run.getId(), conservative);
            } catch (Exception e) {
                submissionFailed = true;
                log.warn("WorkspacePollingObserver enqueue dump failed: runId={} status={} err={}",
                        run.getId(), run.getStatus(), e.getMessage());
            }
        }
        if (submissionFailed) {
            log.warn("WorkspacePollingObserver cursor not advanced: at least one submission failed cursorTime={}",
                    cursorTime);
            return;
        }
        // 推进复合游标到本批最后一条有效记录的 (updatedAt, runId)
        AgentRun last = lastNonNull(runs);
        if (last == null) {
            return;
        }
        OffsetDateTime newTime = last.getUpdatedAt() != null ? last.getUpdatedAt() : cursorTime;
        String newRunId = last.getId() != null ? last.getId() : "";
        if (newTime.isAfter(cursorTime) || (newTime.isEqual(cursorTime) && newRunId.compareTo(cursorRunId) > 0)) {
            lastSeenTime = newTime;
            lastSeenRunId = newRunId;
            log.info("WorkspacePollingObserver cursor advanced: ({},{}) → ({},{})",
                    cursorTime, cursorRunId, newTime, newRunId);
        }
    }

    /** 从列表尾部向前查找第一个非 null、id 非 null 的条目，用于安全推进游标。 */
    private AgentRun lastNonNull(List<AgentRun> runs) {
        for (int i = runs.size() - 1; i >= 0; i--) {
            AgentRun r = runs.get(i);
            if (r != null && r.getId() != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * 测试 / 运维用：重置复合游标。
     */
    public void resetCursor() {
        lastSeenTime = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(initialLookbackMinutes);
        lastSeenRunId = "";
    }
}
