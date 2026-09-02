package world.willfrog.alphafrogmicro.common.lane;

/** 指针指向的实例不存在，或版本、代际与指针不一致。 */
public final class LaneRouteFactsUncertainException extends LaneRoutingException {

    public static final String CODE = "BETA_ROUTE_FACTS_UNCERTAIN";

    public LaneRouteFactsUncertainException() {
        super(CODE);
    }

    public LaneRouteFactsUncertainException(String message) {
        super(message);
    }
}
