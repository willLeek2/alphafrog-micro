package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.tools.python.SandboxTerminalResultValidator;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

/**
 * reconciler 与 startup recovery 共用的终态结果校验入口。
 * 在结果进入 finalizer 前校验 taskId、runId、expectedStatus 和 payload 完整性，
 * 防止迟到或错配结果恢复到另一个 Run。
 */
final class ToolJobResultValidator {

    private ToolJobResultValidator() {}

    /**
     * @param requestedTaskId 本轮实际请求的 Sandbox taskId
     * @param runId 预期接收结果的 Agent Run
     * @param resp Sandbox 返回的结果体
     * @param expectedStatus 先前状态查询确认的终态
     * @return 完整匹配时返回原响应；否则返回 null，由调用方稍后重试
     */
    static TaskResultResponse validate(String requestedTaskId, String runId,
                                        TaskResultResponse resp, String expectedStatus) {
        return SandboxTerminalResultValidator.validate(
                requestedTaskId, runId, resp, expectedStatus);
    }
}
