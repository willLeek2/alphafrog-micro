package world.willfrog.alphafrogmicro.frontend.lane;

import java.util.Optional;

/** 读取当前流量范围的可信默认路由事实。 */
@FunctionalInterface
public interface LaneRouteFactsSource {

    Optional<LaneRouteFacts> current(String trafficScopeId, String serviceName);
}
