package world.willfrog.agentlangchain.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunStateStore;

import java.util.Optional;

/**
 * pipeline 与 todo executor 共用的协作式停机守卫。
 *
 * <p>长工具把 Run 切换到 {@link AgentRunStatus#WAITING_TOOL_JOB} 后，旧 worker 可能还会走到下一处
 * 守卫。这里必须把该状态视为“本轮执行立即停止”，否则旧 worker 会继续消费后续 todo，与稍后由
 * resume launcher 领取的新 worker 并发修改同一个 Run。守卫只阻止旧执行继续推进，不负责恢复；
 * 恢复资格完全由 durable anchor 的 token、lease version 与 CAS 决定。</p>
 */
@Component
@RequiredArgsConstructor
public class LangchainRunExecutionGuard {

    private final ObjectProvider<AgentRunStateStore> stateStoreProvider;
    private final AgentRunEventService eventService;
    private final AgentRunMapper runMapper;

    public boolean shouldStop(String runId, String userId) {
        // 统一复用带原因的判断，避免 boolean 分支与诊断分支对 WAITING_TOOL_JOB 的定义漂移。
        return stopReason(runId, userId).isPresent();
    }

    /**
     * @return 表示本轮不能再推进、也不能覆盖控制面状态的 Redis 或数据库状态
     */
    public Optional<String> stopReason(String runId, String userId) {
        if (isBlank(runId)) {
            // 缺少 Run 身份时无法做可靠的状态隔离；保持旧兼容行为，不凭空宣告停止。
            return Optional.empty();
        }
        AgentRunStateStore stateStore = stateStoreProvider.getIfAvailable();
        if (stateStore != null) {
            // Redis 是执行线程的快速信号面，先读它可尽快让已经占用 worker 的旧循环退出。
            Optional<String> redisStatus = stateStore.loadRunStatus(runId);
            if (redisStatus.isPresent() && isControlStopStatus(redisStatus.get())) {
                return redisStatus;
            }
        }
        if (!isBlank(userId) && !eventService.isRunnable(runId, userId)) {
            // Redis 可能丢失或尚未同步，因此最终仍以数据库中的持久 Run 状态为准。
            AgentRun run = runMapper.findByIdAndUser(runId, userId);
            if (run != null && run.getStatus() != null) {
                return Optional.of(run.getStatus().name());
            }
            // isRunnable 已明确拒绝但无法取得具体记录时，也必须失败即关闭，不能放旧 worker 继续跑。
            return Optional.of("NOT_RUNNABLE");
        }
        return Optional.empty();
    }

    private static boolean isControlStopStatus(String status) {
        // WAITING_TOOL_JOB 与人工暂停 WAITING 的共同点仅是“当前 worker 要退出”；二者恢复入口不同。
        return AgentRunStatus.CANCELING.name().equals(status)
                || AgentRunStatus.CANCELED.name().equals(status)
                || AgentRunStatus.WAITING.name().equals(status)
                || AgentRunStatus.WAITING_TOOL_JOB.name().equals(status);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
