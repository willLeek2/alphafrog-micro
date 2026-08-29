package world.willfrog.agentlangchain.control;

public class LangchainRunRejectedException extends RuntimeException {

    private final String reason;

    public LangchainRunRejectedException(String message) {
        this(message, "unknown");
    }

    /**
     * @param reason 拒绝原因分类（queue_full / capacity_full / executor_hard_rejected ...），
     *               供指标与事件标签使用
     */
    public LangchainRunRejectedException(String message, String reason) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
