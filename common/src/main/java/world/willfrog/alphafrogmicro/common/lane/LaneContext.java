package world.willfrog.alphafrogmicro.common.lane;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 当前请求所属的隔离流量范围。
 *
 * <p>入口请求路由组件写入范围标识；服务间调用在提供方入站时恢复，在消费方出站时继续携带。
 * 线程复用前必须恢复旧值。这里不保存路由地址，Dubbo 根据当前标签和注册中心事实选择提供者。</p>
 */
public final class LaneContext {

    public static final String ATTACHMENT_TRAFFIC_SCOPE_ID = "alphafrog.traffic-scope-id";
    public static final String MDC_LANE_TAG = "lane_tag";

    private static final TransmittableThreadLocal<String> TRAFFIC_SCOPE_ID =
            new TransmittableThreadLocal<>();

    private LaneContext() {
    }

    public static String trafficScopeId() {
        return TRAFFIC_SCOPE_ID.get();
    }

    public static void setTrafficScopeId(String trafficScopeId) {
        if (trafficScopeId == null || trafficScopeId.isBlank()) {
            TRAFFIC_SCOPE_ID.remove();
            return;
        }
        TRAFFIC_SCOPE_ID.set(trafficScopeId);
    }

    public static void clear() {
        TRAFFIC_SCOPE_ID.remove();
    }

    public static void restore(String previous) {
        setTrafficScopeId(previous);
    }
}
