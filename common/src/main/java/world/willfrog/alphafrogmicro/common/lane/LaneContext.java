package world.willfrog.alphafrogmicro.common.lane;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 当前请求所属的隔离流量范围。
 *
 * <p>入口过滤器写入范围标识；服务间调用在提供方入站时从官方 {@code dubbo.tag} 恢复，
 * 在消费方出站时继续写入同一附件。线程复用前必须恢复旧值。主 Beta 范围不对应官方标签。
 * 存储使用可传递线程本地（TransmittableThreadLocal），线程池任务提交时捕获、执行时重放。
 * 本对象不保存实例地址，也不读取控制器状态文件；Dubbo 根据当前标签和注册中心事实选择提供者。</p>
 */
public final class LaneContext {

    public static final String ATTACHMENT_TRAFFIC_SCOPE_ID = "alphafrog.traffic-scope-id";
    public static final String DUBBO_TAG_KEY = "dubbo.tag";
    public static final String MAIN_BETA_TRAFFIC_SCOPE_ID = "main-beta";
    public static final String MDC_LANE_TAG = "lane_tag";

    private static final TransmittableThreadLocal<String> TRAFFIC_SCOPE_ID =
            new TransmittableThreadLocal<>();

    private LaneContext() {
    }

    public static String trafficScopeId() {
        return TRAFFIC_SCOPE_ID.get();
    }

    /**
     * 当前范围对应的官方 Dubbo 标签；主 Beta 或空范围返回 {@code null}。
     */
    public static String officialDubboTag() {
        return toOfficialDubboTag(trafficScopeId());
    }

    public static String toOfficialDubboTag(String trafficScopeId) {
        if (trafficScopeId == null || trafficScopeId.isBlank()) {
            return null;
        }
        if (MAIN_BETA_TRAFFIC_SCOPE_ID.equals(trafficScopeId)) {
            return null;
        }
        return trafficScopeId;
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
