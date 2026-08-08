package world.willfrog.agent.platform.finance;

/** Fail-closed processor error: callers must not write ENVELOPE or return ok=true. */
public class FinanceRecordProcessingException extends RuntimeException {
    private final String code;

    public FinanceRecordProcessingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public FinanceRecordProcessingException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
