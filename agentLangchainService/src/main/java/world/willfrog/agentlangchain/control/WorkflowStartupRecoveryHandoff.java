package world.willfrog.agentlangchain.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

/**
 * {@link LegacyRunHandoff} 的实现：读回 Run，交给启动恢复那一套协议去领。
 *
 * <p>与启动扫描的区别在于「谁来判定该不该跑」：启动扫描按「这条 Run 在本次进程启动之前就开始了」
 * 挑候选，这条路按服务所有权挑——调用方已经拿到这条 Run 的租约，说明原来那一代进程已经不在了。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowStartupRecoveryHandoff implements LegacyRunHandoff {

    private final AgentRunMapper runMapper;
    private final ObjectProvider<WorkflowStartupRecovery> startupRecovery;

    @Override
    public boolean handOff(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        WorkflowStartupRecovery recovery = startupRecovery.getIfAvailable();
        if (recovery == null) {
            // 旧路径没启用（或者被明确关掉）：这条 Run 没有人接手，保持原样等人工处理。
            log.warn("旧版本 Run 需要接手，但启动恢复没有启用，保持原样: runId={}", runId);
            return false;
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            return false;
        }
        AgentRunStatus status = run.getStatus();
        if (status == null || status == AgentRunStatus.CANCELING || terminal(status)) {
            // 已经结束或正在取消：交给取消与收尾那两条路，不由恢复入口重新跑起来。
            return false;
        }
        return recovery.recoverOne(run);
    }

    private static boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELED || status == AgentRunStatus.EXPIRED
                || status == AgentRunStatus.PARTIAL;
    }
}
