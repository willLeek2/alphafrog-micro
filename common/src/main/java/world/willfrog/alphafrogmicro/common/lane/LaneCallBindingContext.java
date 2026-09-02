package world.willfrog.alphafrogmicro.common.lane;

import java.util.Objects;

/**
 * 保存入口请求已经选定的一次精确服务调用绑定。
 *
 * <p>入口鉴权和精确实例路由必须使用同一次路由读取。这个上下文只固定匹配的目标服务；
 * 当前请求后来发起的其他服务调用仍各自读取最新指针。</p>
 */
public final class LaneCallBindingContext {

    private static final ThreadLocal<PinnedBinding> CURRENT = new ThreadLocal<>();

    private LaneCallBindingContext() {
    }

    public static PinnedBinding current() {
        return CURRENT.get();
    }

    public static LaneCallBinding find(String trafficScopeId, String registrationServiceName) {
        PinnedBinding pinned = CURRENT.get();
        if (pinned == null
                || trafficScopeId == null
                || !trafficScopeId.equals(pinned.binding().trafficScopeId())
                || !sameRegistration(pinned.registrationServiceName(), registrationServiceName)) {
            return null;
        }
        return pinned.binding();
    }

    public static void set(String registrationServiceName, LaneCallBinding binding) {
        CURRENT.set(new PinnedBinding(registrationServiceName, binding));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void restore(PinnedBinding previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static boolean sameRegistration(String expected, String actual) {
        if (actual == null || actual.isBlank()) {
            return false;
        }
        return expected.equals(actual) || stripProviderSuffix(expected).equals(stripProviderSuffix(actual));
    }

    private static String stripProviderSuffix(String value) {
        int separator = value.indexOf("@@");
        return separator < 0 ? value : value.substring(0, separator);
    }

    public record PinnedBinding(String registrationServiceName, LaneCallBinding binding) {

        public PinnedBinding {
            if (registrationServiceName == null || registrationServiceName.isBlank()) {
                throw new IllegalArgumentException("注册服务名称不能为空");
            }
            Objects.requireNonNull(binding, "binding");
        }
    }
}
