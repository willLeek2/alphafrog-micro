package world.willfrog.alphafrogmicro.frontend.lane;

/** 部署控制器暂时无法提供可信状态。 */
final class LaneRouteFactsUnavailableException extends RuntimeException {

    LaneRouteFactsUnavailableException(String message) {
        super(message);
    }

    LaneRouteFactsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
