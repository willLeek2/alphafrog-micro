package world.willfrog.beta.core;

public class ControllerException extends RuntimeException {
    private final String code;

    public ControllerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ControllerException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
