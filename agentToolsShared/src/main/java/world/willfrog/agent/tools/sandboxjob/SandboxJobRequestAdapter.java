package world.willfrog.agent.tools.sandboxjob;

import world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;

/**
 * 请求适配器：把工具入参变成可幂等、可重放、可指纹的创建请求。
 *
 * <p>生命周期框架只通过本接口接触创建请求：规范化规格进锚点（恢复时核对任务身份）、
 * 请求指纹绑定入参（防同一 operationId 被不同请求复用）、PREPARING 恢复时按
 * createRequestJson 重放。一个工具一套实现。</p>
 *
 * @param <REQ> 工具的沙箱创建请求类型（对框架不透明）
 */
public interface SandboxJobRequestAdapter<REQ> {

    /** 本适配器服务的工具名，与工具注册表里的名字一致。 */
    String toolName();

    /** 从创建请求导出规范化规格：容量估算、名额预留与恢复核对都用这同一份。 */
    CanonicalSandboxCreateSpec buildCanonicalSpec(REQ request);

    /** 把容量准入结果与 canonical 身份写进真正发送给沙箱的请求（名额预留之后调用）。 */
    REQ enrichWithCapacity(REQ request, DataAnalysisReservation reservation,
                           DataAnalysisEstimate estimate, CanonicalSandboxCreateSpec spec);

    /** 请求指纹：绑定本次入参，阻止同一 operationId 被不同请求复用。 */
    String requestFingerprint(REQ request);

    /** 把锚点里冻存的 createRequestJson 解析回创建请求，供 PREPARING 恢复重放。 */
    REQ parseStoredCreateRequest(String createRequestJson);

    /** 恢复注入结果时给模型看的载荷预览（截断后的代码或语句文本）。 */
    String payloadPreview(String createRequestJson);
}
