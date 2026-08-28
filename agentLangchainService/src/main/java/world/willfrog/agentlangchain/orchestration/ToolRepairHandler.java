package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.Map;

/**
 * 某个工具的失败修复策略。编排主流程只问目录「有没有 handler 认领」，
 * 具体能不能修、怎么拼指令、轮次上限都由实现类自己知道。
 */
public interface ToolRepairHandler {

    /** 本策略认领的工具名，例如 {@code executePython}。 */
    String toolName();

    /** 这个工具的这类失败能不能修。 */
    boolean supports(ToolJobResumeContext failure);

    /**
     * 已经进入修复轮次、需要再跑一遍当前待办。
     * 与 {@link #supports} 不同：这里看的是已经记在交接上下文里的修复状态。
     */
    boolean isRepairRound(ToolJobResumeContext context);

    /** 交接已被接受、且修复仍在进行中（尚未耗尽）。 */
    boolean isActiveRepair(ToolJobResumeContext context);

    /** 拼进当前待办 user message 的修复指令（含阶段说明与诊断上下文）。 */
    String buildRepairInstruction(ToolJobResumeContext context);

    /** 本工具允许的修复轮次上限（含首次执行）。 */
    int maxAttempts();

    int currentAttempt(ToolJobResumeContext context);

    /** 启动下一轮修复：记下轮次并把 pending 打开。 */
    void markPending(ToolJobResumeContext context, int repairAttempt);

    /** 次数用尽，记下耗尽，避免崩溃重入再开一轮。 */
    void markExhausted(ToolJobResumeContext context);

    /** 成功消费或进入下一待办时，关掉 pending。 */
    void clearPending(ToolJobResumeContext context);

    /** 把本轮修复所需的运行时保护写进 ThreadLocal。 */
    void activateRuntime(ToolJobResumeContext context);

    /** 通用待办语义重试若再次落到本工具，也要带上轮次标记。 */
    void prepareSemanticRetryRuntime();

    /** 修复轮次是否真正完成了一次被接受的工具执行。 */
    boolean acceptsExecution(dev.langchain4j.service.tool.ToolExecution execution);

    String exhaustedFailureCode();

    String executeRequiredFailureCode();

    Map<String, Object> exhaustedMetadata(ToolJobResumeContext context, int maxAttempts);

    Map<String, Object> repairingMetadata(ToolJobResumeContext context);

    Map<String, Object> executeRequiredMetadata(ToolJobResumeContext context);
}
