package world.willfrog.alphafrogmicro.common.lane;

/** 当前没有默认路由，或路由执行点无法读取。 */
public final class LaneRouteUnavailableException extends LaneRoutingException {

    public static final String CODE = "BETA_DEFAULT_ROUTE_UNAVAILABLE";

    public LaneRouteUnavailableException() {
        super(CODE);
    }

    public LaneRouteUnavailableException(String message) {
        super(message);
    }

    public LaneRouteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
