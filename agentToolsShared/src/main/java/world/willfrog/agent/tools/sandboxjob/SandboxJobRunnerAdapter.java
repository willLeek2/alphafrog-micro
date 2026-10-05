package world.willfrog.agent.tools.sandboxjob;

/**
 * 运行器适配器：外部沙箱作业的全部 RPC 面。
 *
 * <p>这是唯一允许出现工具 RPC 类型的适配器。框架只调用这些方法，不关心背后是
 * 哪个沙箱服务、哪种协议。所有方法都要满足两条契约：按 operationId 幂等
 * （沙箱端据此去重创建）；取消的 cancelRequestId 由调用方确定性生成（迟到 RPC
 * 幂等靠它）。</p>
 *
 * <p>建任务结果的灾后判定有两档严度，是既有代码里两条路径真实存在的差异，
 * 提取时不做合并：Run 级路径宽松（found 且指纹一致即证实），等待成员路径严格
 * （额外要求错误详情干净、任务号一致、未找到时答复完全空白）。</p>
 *
 * @param <REQ> 创建请求类型
 * @param <RESP> 创建响应类型
 * @param <L> 按 operationId 回查的响应类型（与创建响应未必同型）
 */
public interface SandboxJobRunnerAdapter<REQ, RESP, L> {

    /** 发起创建。调用前框架已落 PREPARING 锚点，覆盖 RPC 成败不确定窗口。 */
    RESP createTask(REQ request);

    /** 按 operationId 原始回查，答复原样返回（判定在 verdictFromLookup*）；查询本身失败抛异常。 */
    L lookupRaw(String operationId);

    /** 灾后回查判定（Run 级 durable 路径）：found 且指纹精确一致即证实；未找到且无错误即不存在；其余不确定。 */
    SandboxCreateVerdict verdictFromLookupForRunPath(L lookup, String requestFingerprint);

    /** 灾后回查判定（等待成员路径）：在 Run 级之上额外要求错误详情干净、任务号与响应一致、未找到时答复完全空白。 */
    SandboxCreateVerdict verdictFromLookupForMemberPath(L lookup, String requestFingerprint, String responseTaskId);

    /** 裁决创建响应（等待成员路径第一连）：响应自身身份齐全且指纹一致即证实，否则转回查。 */
    SandboxCreateVerdict verdictOf(RESP response, REQ request);

    /** 建任务抛异常时的裁决（等待成员路径第二连）：一律转回查。 */
    SandboxCreateVerdict verdictOfFailure(Exception failure, REQ request);

    /** 合成一份仅含任务号与指纹的确认响应（灾后附着成功时使用，丢弃其余字段）。 */
    RESP confirmedResponse(String taskId, String requestFingerprint);

    /** 取消墓碑身份核验：取消确认的任务号、回查响应与指纹三方一致且答复无错误。 */
    boolean verifyTombstoneIdentity(L lookup, String canceledTaskId, String requestFingerprint, String responseTaskId);

    /** 从响应里取任务号；没有任务号时返回空串。 */
    String taskIdOf(RESP response);

    /** 从响应里取错误文本；没有错误时返回空串。 */
    String errorOf(RESP response);

    /** 从响应里取请求指纹；没有指纹时返回空串。 */
    String requestFingerprintOf(RESP response);

    /** 轮询任务状态，返回规范化视图（状态名 + 附带错误文本）。 */
    SandboxJobStatusView statusOf(String taskId);

    /** 取终态结果并映射成工具中立视图；取不到时按工具的既有语义返回空视图或抛异常。 */
    SandboxTerminalResultView fetchResult(String runId, String taskId, String statusName);

    /**
     * 按 operationId 取消（创建结果不确定时写取消墓碑走这里）。
     * 取消侧无法确认任务身份时抛异常。
     */
    SandboxCancelOutcomeView cancelByOperation(String operationId, String requestFingerprint,
                                               String cancelRequestId, String reason);
}
