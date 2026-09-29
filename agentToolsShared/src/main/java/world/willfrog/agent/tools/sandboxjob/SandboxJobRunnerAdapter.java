package world.willfrog.agent.tools.sandboxjob;

import java.util.Optional;

/**
 * 运行器适配器：外部沙箱作业的全部 RPC 面。
 *
 * <p>这是唯一允许出现工具 RPC 类型的适配器。框架只调用这些方法，不关心背后是
 * 哪个沙箱服务、哪种协议。所有方法都要满足两条契约：按 operationId 幂等
 * （沙箱端据此去重创建）；取消的 cancelRequestId 由调用方确定性生成（迟到 RPC
 * 幂等靠它）。</p>
 *
 * @param <REQ> 创建请求类型
 * @param <RESP> 创建响应类型
 */
public interface SandboxJobRunnerAdapter<REQ, RESP> {

    /** 发起创建。调用前框架已落 PREPARING 锚点，覆盖 RPC 成败不确定窗口。 */
    RESP createTask(REQ request);

    /** 裁决创建响应：被证实 / 权威不存在 / 结果不确定。 */
    SandboxCreateVerdict verdictOf(RESP response, REQ request);

    /** 按 operationId 回查任务；指纹不符视为不存在。 */
    Optional<RESP> lookupByOperation(String operationId, String requestFingerprint);

    /** 轮询任务状态，返回规范化终态名（SUCCEEDED / FAILED / CANCELED / NOT_FOUND）或中间态名。 */
    String statusOf(String taskId);

    /** 取终态结果并映射成工具中立视图。 */
    SandboxTerminalResultView fetchResult(String runId, String taskId, String statusName);

    /** 按 operationId 取消（创建结果不确定时写取消墓碑走这里）。 */
    void cancelByOperation(String operationId, String requestFingerprint, String cancelRequestId, String reason);
}
