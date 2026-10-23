package world.willfrog.agentlangchain.facade;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;

/**
 * 用户读取的只读状态投影：取消结果不随外部作业是否停下而改变。
 *
 * <p>数据库运行还没到终态、但锚点里已经持久化取消处置时，读取一律返回已取消。
 * 只影响读取结果，不修改数据库状态、服务所有权、容量和收尾路径。</p>
 */
final class AgentRunVisibleStatus {

    private AgentRunVisibleStatus() {
    }

    static AgentRunStatus resolve(AgentRun run, AgentRunStatus baseStatus) {
        if (run == null || baseStatus == null || isTerminal(baseStatus)) {
            return baseStatus;
        }
        String anchorJson = run.getToolJobAnchorJson();
        if (anchorJson == null || anchorJson.isBlank()) {
            return baseStatus;
        }
        try {
            ToolJobAnchor anchor = ToolJobAnchor.fromJson(anchorJson);
            if (anchor != null && "CANCELED".equals(anchor.getRunDisposition())) {
                return AgentRunStatus.CANCELED;
            }
        } catch (RuntimeException broken) {
            // 锚点损坏时按数据库状态读取；损坏本身由写路径处理。
        }
        return baseStatus;
    }

    private static boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }
}
