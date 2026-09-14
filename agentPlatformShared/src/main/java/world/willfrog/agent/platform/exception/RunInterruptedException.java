package world.willfrog.agent.platform.exception;

/**
 * 用户取消或暂停导致当前 Run 必须停下来。这是控制流信号，不是工具失败。
 *
 * <p>消息仍用 {@code RUN_INTERRUPTED:<reason>}，事件和摘要里的这串前缀保持不变。</p>
 */
public final class RunInterruptedException extends IllegalStateException implements AgentRunControlSignal {

    private final String reason;

    public RunInterruptedException(String reason) {
        super(buildMessage(reason));
        this.reason = reason == null ? "" : reason;
    }

    public String getReason() {
        return reason;
    }

    private static String buildMessage(String reason) {
        return "RUN_INTERRUPTED:" + (reason == null ? "" : reason);
    }
}
