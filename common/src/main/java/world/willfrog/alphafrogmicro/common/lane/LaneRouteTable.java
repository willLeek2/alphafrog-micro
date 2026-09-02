package world.willfrog.alphafrogmicro.common.lane;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一次原子替换后的完整路由表。调用方每次查找都使用同一份不可变对象。 */
public final class LaneRouteTable {

    private final Map<String, LaneServiceRoute> byService;
    private final List<LaneServiceRoute> routes;

    private LaneRouteTable(
            Map<String, LaneServiceRoute> byService,
            List<LaneServiceRoute> routes) {
        this.byService = byService;
        this.routes = routes;
    }

    public static LaneRouteTable empty() {
        return new LaneRouteTable(Map.of(), List.of());
    }

    public static LaneRouteTable of(Iterable<LaneServiceRoute> routes) {
        Map<String, LaneServiceRoute> byService = new LinkedHashMap<>();
        List<LaneServiceRoute> allRoutes = new java.util.ArrayList<>();
        for (LaneServiceRoute route : Objects.requireNonNull(routes, "routes")) {
            byService.put(serviceKey(route.trafficScopeId(), route.serviceName()), route);
            allRoutes.add(route);
        }
        return new LaneRouteTable(
                Collections.unmodifiableMap(byService),
                List.copyOf(allRoutes));
    }

    public LaneServiceRoute find(String trafficScopeId, String serviceName) {
        if (trafficScopeId == null || serviceName == null) {
            return null;
        }
        return byService.get(serviceKey(trafficScopeId, serviceName));
    }

    public LaneServiceRoute findByRegistration(String trafficScopeId, String registrationServiceName) {
        if (trafficScopeId == null || registrationServiceName == null) {
            return null;
        }
        LaneServiceRoute exact = null;
        for (LaneServiceRoute route : routes) {
            if (trafficScopeId.equals(route.trafficScopeId())
                    && registrationServiceName.equals(route.registrationServiceName())) {
                if (exact != null && exact != route) {
                    return null;
                }
                exact = route;
            }
        }
        if (exact != null) {
            return exact;
        }
        // Nacos 末尾的 @@providers 是登记类别，不属于 Dubbo 协议服务键。
        // 分组、接口名和版本仍全部保留；若去掉类别后出现多个候选，则拒绝猜测。
        String protocolServiceKey = stripRegistryCategorySuffix(registrationServiceName);
        LaneServiceRoute matched = null;
        for (LaneServiceRoute route : routes) {
            if (!trafficScopeId.equals(route.trafficScopeId())
                    || route.registrationServiceName() == null
                    || !protocolServiceKey.equals(stripRegistryCategorySuffix(route.registrationServiceName()))) {
                continue;
            }
            if (matched != null && matched != route) {
                return null;
            }
            matched = route;
        }
        return matched;
    }

    public LaneServiceRoute findByDubboServiceKey(
            String trafficScopeId,
            LaneDubboServiceKey dubboServiceKey) {
        if (trafficScopeId == null || dubboServiceKey == null) {
            return null;
        }
        LaneServiceRoute matched = null;
        for (LaneServiceRoute route : routes) {
            if (!trafficScopeId.equals(route.trafficScopeId())
                    || !dubboServiceKey.matchesNacosRegistration(route.registrationServiceName())) {
                continue;
            }
            if (matched != null && matched != route) {
                return null;
            }
            matched = route;
        }
        return matched;
    }

    public boolean isEmpty() {
        return byService.isEmpty();
    }

    static String serviceKey(String trafficScopeId, String serviceName) {
        return trafficScopeId + "\0" + serviceName;
    }

    private static String stripRegistryCategorySuffix(String registrationServiceName) {
        int groupSeparator = registrationServiceName.indexOf("@@");
        if (groupSeparator < 0) {
            return registrationServiceName;
        }
        return registrationServiceName.substring(0, groupSeparator);
    }
}
