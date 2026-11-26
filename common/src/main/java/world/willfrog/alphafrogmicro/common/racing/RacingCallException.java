package world.willfrog.alphafrogmicro.common.racing;

import world.willfrog.racing.idl.RacingError;

/** 保留平台错误对象，调用业务据此区分权限、范围与保存未确认。 */
public final class RacingCallException extends RuntimeException {
    private final RacingError error;

    public RacingCallException(RacingError error) {
        super(error.getCode() + ": " + error.getMessage());
        this.error = error;
    }

    public RacingError error() {
        return error;
    }

    public static RacingCallException failure(String code, String message) {
        return new RacingCallException(RacingError.newBuilder().setCode(code).setMessage(message).build());
    }
}
