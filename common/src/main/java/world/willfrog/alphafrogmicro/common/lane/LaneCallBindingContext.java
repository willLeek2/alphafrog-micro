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

    public static LaneCallBinding find(String trafficScopeId, LaneDubboServiceKey dubboServiceKey) {
        PinnedBinding pinned = CURRENT.get();
        if (pinned == null
                || trafficScopeId == null
                || !trafficScopeId.equals(pinned.binding().trafficScopeId())
                || !pinned.dubboServiceKey().equals(dubboServiceKey)) {
            return null;
        }
        return pinned.binding();
    }

    public static void set(LaneDubboServiceKey dubboServiceKey, LaneCallBinding binding) {
        CURRENT.set(new PinnedBinding(dubboServiceKey, binding));
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

    public record PinnedBinding(LaneDubboServiceKey dubboServiceKey, LaneCallBinding binding) {

        public PinnedBinding {
            Objects.requireNonNull(dubboServiceKey, "dubboServiceKey");
            Objects.requireNonNull(binding, "binding");
        }
    }
}
