package world.willfrog.agent.platform.dataanalysis;

/**
 * 容量层准入失败异常。{@link Reason} 与 §6.8 错误码词汇表镜像，
 * 工具层据此翻译为 {@code DATA_ANALYSIS_SERVER_BUSY} 或
 * {@code DATA_ANALYSIS_TASK_TOO_LARGE}，不泄漏 in-memory 实现细节。
 *
 * <p>位于接口包内，consumer 依赖接缝而非 {@code DataAnalysisCapacityServiceImpl}。</p>
 */
public final class CapacityAdmissionException extends RuntimeException {

    public enum Reason { RECOVERING, SERVER_BUSY, TASK_TOO_LARGE, ALREADY_RESERVED, ILLEGAL_RESTORE }

    private final Reason reason;

    public CapacityAdmissionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
