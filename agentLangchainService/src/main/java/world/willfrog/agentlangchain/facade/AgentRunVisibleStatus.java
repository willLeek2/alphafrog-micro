package world.willfrog.agentlangchain.facade;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;

/**
 * 用户读取的只读状态投影：取消结果不随外部作业是否停下而改变。
 *
 * <p>数据库运行还没到终态、锚点里已经持久化取消处置、且收尾尚未进入终态清理步骤时，
 * 读取返回已取消。收尾已到终态清理步骤的锚点视为历史残留（可能来自获准恢复前的旧取消），
 * 不再驱动展示；刚受理恢复的 RECEIVED 窗口也以数据库状态为准。
 * 只影响读取结果，不修改数据库状态、服务所有权、容量和收尾路径。</p>
 */
final class AgentRunVisibleStatus {

    private AgentRunVisibleStatus() {
    }

    static AgentRunStatus resolve(AgentRun run, AgentRunStatus baseStatus) {
        if (run == null || baseStatus == null || isTerminal(baseStatus)) {
            return baseStatus;
        }
        // 获准恢复刚落下时状态回到 RECEIVED；历史取消处置不能覆盖这次恢复。
        if (baseStatus == AgentRunStatus.RECEIVED) {
            return baseStatus;
        }
        String anchorJson = run.getToolJobAnchorJson();
        if (anchorJson == null || anchorJson.isBlank()) {
            return baseStatus;
        }
        try {
            ToolJobAnchor anchor = ToolJobAnchor.fromJson(anchorJson);
            if (anchor == null || !"CANCELED".equals(anchor.getRunDisposition())) {
                return baseStatus;
            }
            // 收尾步骤已走到终态清理的锚点是历史残留（可能来自获准恢复前的旧取消）；
            // 资源责任仍由恢复/残留清理流程处理，读取不能把它当成正在进行的取消。
            if (isFinishedCancelStep(anchor.getFinalizerStep())) {
                return baseStatus;
            }
            return AgentRunStatus.CANCELED;
        } catch (RuntimeException broken) {
            // 锚点损坏时按数据库状态读取；损坏本身由写路径处理。
        }
        return baseStatus;
    }

    /** 与终态Run残留取消锚点的清理条件保持同一组收尾步骤。 */
    private static boolean isFinishedCancelStep(String finalizerStep) {
        return "EVENT".equals(finalizerStep) || "CAS_STATUS".equals(finalizerStep)
                || "RESUME_READY".equals(finalizerStep) || "CANCELED".equals(finalizerStep);
    }

    private static boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }
}
