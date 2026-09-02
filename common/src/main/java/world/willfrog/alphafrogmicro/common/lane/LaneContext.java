package world.willfrog.alphafrogmicro.common.lane;

/**
 * 当前请求所属的隔离流量范围。
 *
 * <p>入口请求路由组件写入范围标识；服务间调用在提供方入站时恢复，在消费方出站时继续携带。
 * 线程复用前必须恢复旧值。这里不保存可变路由指针；普通新调用各自重新绑定，入口已经完成
 * 身份判断的目标调用则使用同一次请求读取固定的精确绑定。</p>
 */
public final class LaneContext {

    public static final String ATTACHMENT_TRAFFIC_SCOPE_ID = "alphafrog.traffic-scope-id";
    public static final String MDC_LANE_TAG = "lane_tag";

    private static final ThreadLocal<String> TRAFFIC_SCOPE_ID = new ThreadLocal<>();

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
