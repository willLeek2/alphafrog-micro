package world.willfrog.agent.service.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * workspace v0 跨 JVM 兜底 observer。
 *
 * <p>职责：定期扫 {@code alphafrog_agent_run}，找出处于终态集合（COMPLETED / PARTIAL / FAILED /
 * CANCELED / EXPIRED）且 {@code updated_at > lastSeenAt} 的 run，调
 * {@link WorkspaceDumpService#dumpRun} 触发 workspace dump。</p>
 *
 * <h3>为什么需要</h3>
 * <p>agentService 和 agentLangchainService 是两个独立 Spring Boot JVM。Spring
 * {@code ApplicationEventPublisher} 是 JVM 内广播，agentLangchainService publish 的
 * {@code AgentRunFinalizedEvent} 不会触发 agentService 侧的 listener。Polling observer
 * 兜底跨 JVM 路径，让 v0 仍能稳定形成 workspace dump。v1 计划替换为 Dubbo 回调或事件表 + 通知。</p>
 *
 * <h3>幂等</h3>
 * <p>单批内调 {@link WorkspaceDumpService#dumpRun}；DumpService 内部走 fingerprint 幂等
 * （fingerprint 一致 + manifest 齐全时 skip），所以 polling 重扫同一 run 不会重复 dump。
 * lastSeenAt 推到本批最大 updated_at，确保下一次只扫更新的 run。</p>
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
@RequiredArgsConstructor
@Slf4j
public class WorkspacePollingObserver {

    private final AgentRunMapper runMapper;
    private final WorkspaceDumpService dumpService;

    @Value("${agent.workspace.polling.enabled:true}")
    private boolean enabled;

    @Value("${agent.workspace.polling.batch-size:100}")
    private int batchSize;

    @Value("${agent.workspace.polling.initial-lookback-minutes:60}")
    private int initialLookbackMinutes;

    /**
     * lastSeenAt：只扫 updated_at > lastSeenAt 的 run。
     * 初始值 = 启动时刻 - initialLookbackMinutes（重启后从该时刻起扫）。
     * 每批处理完成后推进到本批最大 updated_at。
     */
    private final AtomicReference<OffsetDateTime> lastSeenAt = new AtomicReference<>(
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(60));

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
        OffsetDateTime fromTime = lastSeenAt.get();
        List<AgentRun> runs = runMapper.listByStatusAndUpdatedAfter(
                List.of(
                        AgentRunStatus.COMPLETED,
                        AgentRunStatus.PARTIAL,
                        AgentRunStatus.FAILED,
                        AgentRunStatus.CANCELED,
                        AgentRunStatus.EXPIRED),
                fromTime,
                batchSize);
        if (runs == null || runs.isEmpty()) {
            return;
        }
        log.info("WorkspacePollingObserver found terminal runs: count={} fromTime={}",
                runs.size(), fromTime);
        OffsetDateTime maxUpdatedAt = fromTime;
        for (AgentRun run : runs) {
            if (run == null || run.getId() == null) {
                continue;
            }
            if (run.getUpdatedAt() != null && run.getUpdatedAt().isAfter(maxUpdatedAt)) {
                maxUpdatedAt = run.getUpdatedAt();
            }
            try {
                boolean conservative = run.getStatus() == AgentRunStatus.EXPIRED;
                dumpService.dumpRun(run.getId(), conservative);
            } catch (Exception e) {
                // DumpService 内部已做 3 次重试 + DLQ；这里再 fail 也不再加重试，
                // 仅记 warn，等下一轮 polling 扫到时再试
                log.warn("WorkspacePollingObserver dumpRun failed: runId={} status={} err={}",
                        run.getId(), run.getStatus(), e.getMessage());
            }
        }
        // 推进 lastSeenAt：只有当本批至少处理过一条 + 推进时间晚于当前值时
        if (maxUpdatedAt.isAfter(fromTime)) {
            lastSeenAt.set(maxUpdatedAt);
            log.info("WorkspacePollingObserver lastSeenAt advanced: from={} to={}",
                    fromTime, maxUpdatedAt);
        }
    }

    /**
     * 测试 / 运维用：重置 lastSeenAt。
     */
    public void resetLastSeenAt() {
        lastSeenAt.set(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(initialLookbackMinutes));
    }
}
