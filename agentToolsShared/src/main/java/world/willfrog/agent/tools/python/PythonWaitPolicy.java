package world.willfrog.agent.tools.python;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code executePython} 在当前生效工作流下的等待策略。
 *
 * <p>LINEAR 可以把长任务转交给 durable pending 生命周期；DAG 节点必须留在当前
 * worker 内阻塞等待，避免生成无法安全恢复的 DAG frontier。</p>
 */
enum PythonWaitPolicy {

    DURABLE_SUSPEND("AUTO_RESUME", true),
    BLOCKING_POLL("DAG_BLOCKING_NO_RESUME", false);

    private final String runDisposition;
    private final boolean autoResume;

    PythonWaitPolicy(String runDisposition, boolean autoResume) {
        this.runDisposition = runDisposition;
        this.autoResume = autoResume;
    }

    String runDisposition() {
        return runDisposition;
    }

    boolean autoResume() {
        return autoResume;
    }

    boolean durableSuspend() {
        return this == DURABLE_SUSPEND;
    }

    /**
     * 只接受 executor 已写入 {@code AgentContext.workflow} 的 canonical 值。
     * 缺失或未知值返回空，由调用方在任何容量或 Sandbox 副作用前 fail-closed。
     */
    static Optional<PythonWaitPolicy> fromWorkflow(String workflow) {
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
