package world.willfrog.agent.platform.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 终态发布收敛服务（agentPlatformShared 公共入口）。
 *
 * <p>挂载点（v0）：LangchainLinearRunPipelineImpl 发布成功、部分完成与失败；
 * LangchainRunControlService / ToolJobFinalizer 发布普通取消与长工具收口后的取消；
 * LangchainRunReadService 发布读时发现的过期。各路径都必须先持久化终态，再调用本服务。
 *
 * <p>放在 agentPlatformShared 是为了提供终态发布的共享入口。workspace 监听器已迁移到
 * agentLangchainService 侧，同 JVM 消费 langchain 主链路发布的终态事件；
 * 详见 {@link AgentRunFinalizedEvent} 的事件边界说明。
 *
 * <h3>PARTIAL schema blocker</h3>
 * <p>PARTIAL 状态在 alphafrog_agent_run.status CHECK 约束里默认未包含（migration v1.1/002 已加）；
 * 如果运行环境未执行该 migration，则 PARTIAL 终态发布不影响下游（DB 写入会失败），
 * WorkspaceDumpScheduler 内部仍按 completeAndMark 走，Fingerprint 不变。
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunFinalizationService {

    /** v0 处理的终态集合 */
    public static final Set<String> TERMINAL_STATUSES = Set.of(
            "COMPLETED", "PARTIAL", "FAILED", "CANCELED", "EXPIRED"
    );

    private final ApplicationEventPublisher publisher;

    /**
     * 发布 run 终态事件。
     *
     * @param runId         run 主键
     * @param userId        run 所属用户（字符串或数值字符串；解析失败则跳过）
     * @param terminalStatus 终态枚举字符串
     */
    public void publishFinalizedEvent(String runId, String userId, String terminalStatus) {
        if (runId == null || runId.isBlank()) {
            log.warn("publishFinalizedEvent skipped: runId 为空, status={}", terminalStatus);
            return;
        }
        String status = terminalStatus == null ? "" : terminalStatus.trim();
        if (!TERMINAL_STATUSES.contains(status)) {
            log.warn("publishFinalizedEvent skipped: 非终态 status={} runId={}", status, runId);
            return;
        }
        long uid;
        try {
            uid = Long.parseLong(userId);
        } catch (Exception e) {
            log.warn("publishFinalizedEvent skipped: userId 解析失败 userId={} runId={}", userId, runId);
            return;
        }
        boolean conservative = "EXPIRED".equals(status);
        log.info("Publishing AgentRunFinalizedEvent: runId={} userId={} status={} conservative={}",
                runId, uid, status, conservative);
        try {
            publisher.publishEvent(new AgentRunFinalizedEvent(runId, uid, status, conservative));
        } catch (RuntimeException e) {
            // Workspace dump 还有数据库 polling 兜底。事件监听失败不能反向破坏已经持久化的
            // Run 终态，否则调用方会把“dump 触发失败”误当成“终态写入失败”并重复业务收口。
            log.error("publishFinalizedEvent failed after terminal state persisted: "
                    + "runId={} userId={} status={}", runId, uid, status, e);
        }
    }
}
