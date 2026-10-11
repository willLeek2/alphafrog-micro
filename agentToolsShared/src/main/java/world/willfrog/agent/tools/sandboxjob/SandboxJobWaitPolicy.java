package world.willfrog.agent.tools.sandboxjob;

import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;

import java.util.Locale;
import java.util.Optional;

/**
 * 沙箱后台长工具在当前生效工作流下的等待策略（executePython 与 executeQuery 共用）。
 *
 * <p>LINEAR 可以把长任务转交给 durable pending 生命周期；DAG 节点必须留在当前
 * worker 内阻塞等待，避免生成无法安全恢复的 DAG frontier。</p>
 */
public enum SandboxJobWaitPolicy {

    DURABLE_SUSPEND("AUTO_RESUME", true),
    BLOCKING_POLL(ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME, false);

    private final String runDisposition;
    private final boolean autoResume;

    SandboxJobWaitPolicy(String runDisposition, boolean autoResume) {
        this.runDisposition = runDisposition;
        this.autoResume = autoResume;
    }

    public String runDisposition() {
        return runDisposition;
    }

    public boolean autoResume() {
        return autoResume;
    }

    public boolean durableSuspend() {
        return this == DURABLE_SUSPEND;
    }

    /**
     * 只接受 executor 已写入 {@code AgentContext.workflow} 的 canonical 值。
     * 缺失或未知值返回空，由调用方在任何容量或 Sandbox 副作用前 fail-closed。
     */
    public static Optional<SandboxJobWaitPolicy> fromWorkflow(String workflow) {
        if (workflow == null || workflow.isBlank()) {
            return Optional.empty();
        }
        return switch (workflow.trim().toLowerCase(Locale.ROOT)) {
            case "linear" -> Optional.of(DURABLE_SUSPEND);
            case "dag" -> Optional.of(BLOCKING_POLL);
            default -> Optional.empty();
        };
    }
}
