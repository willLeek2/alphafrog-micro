package world.willfrog.alphafrogmicro.common.lane;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一次原子替换后的完整路由表。调用方每次查找都使用同一份不可变对象。 */
public final class LaneRouteTable {

    private final Map<String, LaneServiceRoute> byService;
    private final Map<String, LaneServiceRoute> byRegistration;

    private LaneRouteTable(
            Map<String, LaneServiceRoute> byService,
            Map<String, LaneServiceRoute> byRegistration) {
        this.byService = byService;
        this.byRegistration = byRegistration;
    }

    public static LaneRouteTable empty() {
        return new LaneRouteTable(Map.of(), Map.of());
    }

    public static LaneRouteTable of(Iterable<LaneServiceRoute> routes) {
        Map<String, LaneServiceRoute> byService = new LinkedHashMap<>();
        Map<String, LaneServiceRoute> byRegistration = new LinkedHashMap<>();
        for (LaneServiceRoute route : Objects.requireNonNull(routes, "routes")) {
            byService.put(serviceKey(route.trafficScopeId(), route.serviceName()), route);
            if (route.registrationServiceName() != null) {
                byRegistration.put(
                        serviceKey(route.trafficScopeId(), route.registrationServiceName()), route);
            }
        }
        return new LaneRouteTable(
                Collections.unmodifiableMap(byService),
                Collections.unmodifiableMap(byRegistration));
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
        LaneServiceRoute exact = byRegistration.get(serviceKey(trafficScopeId, registrationServiceName));
        if (exact != null) {
            return exact;
        }
        // Nacos 末尾的 @@providers 是登记类别，不属于 Dubbo 协议服务键。
        // 分组、接口名和版本仍全部保留；若去掉类别后出现多个候选，则拒绝猜测。
        String protocolServiceKey = stripRegistryCategorySuffix(registrationServiceName);
        LaneServiceRoute matched = null;
        for (LaneServiceRoute route : byRegistration.values()) {
            if (!trafficScopeId.equals(route.trafficScopeId())
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
