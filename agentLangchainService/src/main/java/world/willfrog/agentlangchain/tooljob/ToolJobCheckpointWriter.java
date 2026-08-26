package world.willfrog.agentlangchain.tooljob;

/**
 * 慢工具挂起前，把 {@link ToolJobCheckpointRequest} 原子写入数据库里的 anchor 记录的接口。
 *
 * <p>调用顺序是硬约束：先持久化完整检查点，确认 CAS 成功，再允许 pipeline 返回并释放 Agent worker。
 * 如果实现缺失或写入失败，调用方必须按失败处理（不继续挂起流程），不能只把 Run 标成
 * WAITING_TOOL_JOB 后丢失内存中的 plan、completed todos、dataset registry 与工具预算。</p>
 *
 * <p>实现类从 {@code AgentRunDatasetRegistry.snapshot(runId)} 捕获数据集注册表，并连同摘要、
 * 已完成 todo、估算结果、数据集引用和已用工具次数写入 anchor。恢复时
 * {@link ToolJobResumeService#tryResume(String)} 读取同一版本的检查点，先校验 digest，再恢复 registry，
 * 最后才把 resume context 交给新的 worker。</p>
 */
@FunctionalInterface
public interface ToolJobCheckpointWriter {
    /**
     * 将完整恢复检查点原子写入 anchor；由 pipeline 在让出 worker 之前调用。
     *
     * @param request 检查点载荷，包含 Run/todo 身份、已完成结果、数据集快照、估算与工具预算
     * @return 仅当带身份约束的原子写入成功时返回 true
     */
    boolean captureAndSave(ToolJobCheckpointRequest request);
}
