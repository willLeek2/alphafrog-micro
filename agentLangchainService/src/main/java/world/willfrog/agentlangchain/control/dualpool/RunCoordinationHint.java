package world.willfrog.agentlangchain.control.dualpool;

/**
 * Run 协调提示。提示可以丢失或重复，真正能否推进由数据库里的 Run 状态和版本决定。
 */
public record RunCoordinationHint(String runId, Reason reason) {

    public RunCoordinationHint {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id_required");
        }
        reason = reason == null ? Reason.SCAN_REDISCOVERED : reason;
    }

    public enum Reason {
        NEW_RUN,
        FOLLOW_UP,
        NODE_RESULT,
        MANUAL_RESUME,
        SCAN_REDISCOVERED
    }
}
