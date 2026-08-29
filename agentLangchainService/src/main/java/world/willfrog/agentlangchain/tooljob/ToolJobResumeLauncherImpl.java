package world.willfrog.agentlangchain.tooljob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 把数据库里的 LAUNCHING claim 提交到 bounded Agent Run scheduler 的生产实现。
 *
 * <p>进程内 activeClaims 只做同一 token/version 的快速去重，数据库 lease 才是
 * 跨进程真相。launcher 负责“两阶段交接”：工作流注入终态后先 markHandoffAccepted，
 * pipeline 结果持久化后再 completeHandoff，任何一步失败都保留 anchor 供重入。</p>
 *
 * <p>数据库 lease 的周期续租已拆到 {@link ToolJobResumeLauncherHeartbeat}，只在
 * 耐久恢复开启时创建；本类自身不持有任何定时线程。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolJobResumeLauncherImpl implements ToolJobResumeLauncher {

    private final AgentRunMapper runMapper;
    private final LangchainLinearRunPipeline pipeline;
    private final ObjectProvider<ToolJobResumeService> resumeServiceProvider;
    private final ConcurrentMap<ToolJobResumeClaimKey, Boolean> activeClaims = new ConcurrentHashMap<>();

    @Override
    public boolean launch(String runId, ToolJobResumeContext context) {
        // 先校验 run、token、lease、todo 恢复身份，缺失字段不能提交匿名任务。
        if (!valid(runId, context)) {
            return false;
        }
        // ClaimKey 与数据库 LAUNCHING 租约一一对应。
        ToolJobResumeClaimKey key = new ToolJobResumeClaimKey(runId, context.getResumeToken(),
                context.getResumeLeaseVersion(), context.getResumeLauncherOwnerId());
        // putIfAbsent 保证同一 JVM 内重复扫描只提交一次 pipeline Runnable。
        if (activeClaims.putIfAbsent(key, Boolean.TRUE) != null) {
            // 已接收同一逻辑 claim 时幂等返回 true，不重复入队。
            return true;
        }
        // 入队前重新读取 Run；排队身份仍由 id 和 context lease 约束。
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            // Run 已删除，释放本地去重键并报告 launch 失败。
            activeClaims.remove(key);
            return false;
        }
        try {
            // 普通启动和恢复启动共用同一个 LangchainRunConcurrencyScheduler。
            boolean accepted = pipeline.launchResumedAsync(
                    run,
                    context,
                    () -> {
                        // executor 接受外部终态后，先持久化已完成前缀和新的恢复点。
                        ToolJobResumeService service = resumeServiceProvider.getIfAvailable();
                        return service != null && service.markHandoffAccepted(runId, context);
                    },
                    durable -> {
                        try {
                            // 只有 pipeline 已把最终结果或下一 checkpoint 持久化，才清理旧 claim。
                            ToolJobResumeService service = resumeServiceProvider.getIfAvailable();
                            if (durable && service != null) {
                                service.completeHandoff(runId, context.getResumeToken(),
                                        context.getResumeLeaseVersion(),
                                        context.getResumeLauncherOwnerId());
                            }
                        } finally {
                            // 无论回调报告是否已写入数据库，都移除本地 active；失败时 DB anchor 会驱动下一轮重入。
                            activeClaims.remove(key);
                        }
                    });
            if (!accepted) {
                // 调度器未接收任务时立即撤销本地去重键，让下次扫描可以重试。
                activeClaims.remove(key);
            }
            return accepted;
        } catch (Exception e) {
            // 入队异常同样只清本地状态，不清数据库里的 LAUNCHING anchor。
            activeClaims.remove(key);
            log.warn("Resume launch rejected runId={} token={} version={}: {}",
                    runId, context.getResumeToken(), context.getResumeLeaseVersion(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isActive(String runId, String token, long version) {
        // stale 回收只在完整身份存在时查询。
        if (runId == null || token == null) {
            return false;
        }
        // true 表示本 JVM 已接收该 claim，ResumeService 不应因 TTL 过期抢占它。
        return activeClaims.keySet().stream().anyMatch(key -> key.runId().equals(runId)
                && key.token().equals(token) && key.version() == version);
    }

    /** heartbeat 组件可读的活跃 claim 快照（包内协作用）。 */
    Set<ToolJobResumeClaimKey> activeClaimKeys() {
        return Set.copyOf(activeClaims.keySet());
    }

    /** heartbeat 续租失败（rows=0）后由心跳组件撤销本地去重键。 */
    void removeClaim(ToolJobResumeClaimKey key) {
        activeClaims.remove(key);
    }

    private boolean valid(String runId, ToolJobResumeContext context) {
        // token/version 用于 fencing，todoId 用于确定恢复注入点；全部为必需字段。
        return runId != null && !runId.isBlank()
                && context != null
                && runId.equals(context.getRunId())
                && context.getResumeToken() != null
                && !context.getResumeToken().isBlank()
                && context.getResumeLeaseVersion() > 0
                && context.getResumeLauncherOwnerId() != null
                && !context.getResumeLauncherOwnerId().isBlank()
                && context.getTodoId() != null
                && !context.getTodoId().isBlank();
    }
}
