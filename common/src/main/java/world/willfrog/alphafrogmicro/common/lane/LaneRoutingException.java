package world.willfrog.alphafrogmicro.common.lane;

/** 无法把 Beta 新调用绑定到精确实例。 */
public class LaneRoutingException extends RuntimeException {

    public LaneRoutingException(String message) {
        super(message);
    }

    public LaneRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
