package world.willfrog.agent.platform.dataanalysis;

/**
 * 长工具恢复验收的受限故障点。
 *
 * <p>生产默认没有实现 Bean，因此普通请求不会读故障记录。Beta 验收打开实现后，调用方只提交
 * 当前 Run 与固定检查点；是否命中、一次性消费和具体动作都由数据库里的受限记录决定，公开请求
 * 不能携带场景编号或故障动作。</p>
 */
public interface ToolJobFaultInjector {

    String BEFORE_SANDBOX_SUBMIT = "BEFORE_SANDBOX_SUBMIT";
    String AFTER_SANDBOX_ACCEPTED = "AFTER_SANDBOX_ACCEPTED";
    String AFTER_RESUME_COMMITTED = "AFTER_RESUME_COMMITTED";
    String AFTER_MODEL_COMPLETED = "AFTER_MODEL_COMPLETED";

    void hit(String runId, String checkpoint);
}
