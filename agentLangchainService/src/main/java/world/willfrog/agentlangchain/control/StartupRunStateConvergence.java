package world.willfrog.agentlangchain.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agentlangchain.control.dualpool.SchedulerVersionPolicy;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 单实例服务启动后的一次性遗留状态收敛。
 *
 * <p>它接手原 LEGACY 启动恢复扫描里与「接回旧调度器」无关、且删除 LEGACY 后仍要无条件执行的
 * 两件事：</p>
 *
 * <ol>
 *   <li>CANCELING 收尾——重启前停在 CANCELING 的 Run（任何调度器版本）在启动时完成取消。
 *   原先这一分支跟着 {@code agent.workflow-restart.enabled} 开关走，开关关掉就静默丢收尾；
 *   并入本类后无条件执行，开关随之退役。</li>
 *   <li>存量非终态 LEGACY Run 的一次性收口——LEGACY 调度实现删除后这些 Run 永远无人推进，
 *   启动时按 {@code legacy_scheduler_removed} 关成 FAILED（条件更新天然幂等）。两环境核查均
 *   无在途 LEGACY，本条是防御。</li>
 * </ol>
 *
 * <p>双池家族（V1/V2）的非 CANCELING 候选一律不动，由双池自己的启动受理与协调协议决定接不接手；
 * 版本未知的候选保持失败关闭。它不是常驻 reconciler，也没有多实例租约，与被删除的旧扫描同等
 * 假设：单实例或可容忍重复扫描的部署形态。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnExpression("${agent.langchain.provider.enabled:false}")
public class StartupRunStateConvergence {

    private final AgentRunMapper runMapper;
    private final AgentRunEventService eventService;
    private final AgentRunFinalizationService finalizationService;
    private final RunOwnershipGateway ownershipGateway;
    private final SchedulerVersionPolicy schedulerVersionPolicy;

    @Value("${agent.workflow-restart.scan-limit:100}")
    private int scanLimit;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        OffsetDateTime startedBefore = OffsetDateTime.now();
        int boundedLimit = Math.max(1, Math.min(scanLimit, 1000));
        List<AgentRun> candidates = ownershipGateway.listStartupRecoveryCandidates(
                startedBefore, boundedLimit);
        for (AgentRun candidate : candidates) {
            convergeOne(candidate);
        }
        log.info("启动态收敛扫描 {} 条候选, limit={}", candidates.size(), boundedLimit);
    }

    /**
     * 收敛一条候选。返回 true 表示确实改写了状态（完成取消或收口成 FAILED）；
     * 启动扫描本身不看返回值，留返回值只为可测与日志可读。
     */
    boolean convergeOne(AgentRun candidate) {
        if (candidate == null || candidate.getStatus() == null) {
            return false;
        }
        String runId = candidate.getId();
        String userId = candidate.getUserId();
        try {
            if (candidate.getStatus() == AgentRunStatus.CANCELING) {
                // 写入合同原样沿自旧启动恢复的 CANCELING 分支：条件完成取消＋事件＋终态广播。
                if (runMapper.completeStartupCancellation(runId, userId) == 1) {
                    eventService.append(runId, userId, "WORKFLOW_RESTART_CANCELED", Map.of(
                            "reason", "canceling_during_service_restart"));
                    finalizationService.publishFinalizedEvent(
                            runId, userId, AgentRunStatus.CANCELED.name());
                    return true;
                }
                return false;
            }
            // 双池家族的 Run 由双池那套协议接管（服务所有权 + 分段行事实），不是「不具备恢复协议」。
            boolean dualPoolFamily;
            try {
                dualPoolFamily = schedulerVersionPolicy.isDualPoolFamily(candidate);
            } catch (IllegalStateException unknownVersion) {
                // 未知版本不能被启动扫描改写状态；保留原记录供人工检查。
                log.error("Run 的调度器版本未知，启动态收敛保持失败关闭: runId={} schedulerVersion={}",
                        runId, candidate.getSchedulerVersion());
                return false;
            }
            if (dualPoolFamily) {
                log.warn("双池家族的 Run 由双池启动受理决定接不接手，这里不动它: runId={} status={} "
                                + "schedulerVersion={}",
                        runId, candidate.getStatus(), candidate.getSchedulerVersion());
                return false;
            }
            // 走到这里只可能是 LEGACY（版本未知已在上面失败关闭）：调度实现已删除，收口成 FAILED。
            return closeLegacyRun(candidate, "legacy_scheduler_removed");
        } catch (Exception e) {
            log.error("启动态收敛处理失败: runId={}", runId, e);
            return false;
        }
    }

    private boolean closeLegacyRun(AgentRun run, String reason) {
        if (runMapper.failLegacyStartupClosure(run.getId(), run.getUserId(), reason) != 1) {
            return false;
        }
        try {
            eventService.append(run.getId(), run.getUserId(), "WORKFLOW_RESTART_REJECTED", Map.of(
                    "reason", reason,
                    "restart_attempt", run.getRestartAttempt() == null ? 0 : run.getRestartAttempt()));
        } finally {
            finalizationService.publishFinalizedEvent(
                    run.getId(), run.getUserId(), AgentRunStatus.FAILED.name());
        }
        return true;
    }
}
