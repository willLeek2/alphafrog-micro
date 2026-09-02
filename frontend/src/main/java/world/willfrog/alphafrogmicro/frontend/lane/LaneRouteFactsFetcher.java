package world.willfrog.alphafrogmicro.frontend.lane;

import java.util.Optional;

/** 部署控制器状态读取边界，供缓存与测试替身共同使用。 */
@FunctionalInterface
interface LaneRouteFactsFetcher {

    Optional<LaneRouteFacts> fetch(String trafficScopeId, String serviceName);
}
