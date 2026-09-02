package world.willfrog.alphafrogmicro.common.lane;

/**
 * Dubbo 扩展与自动装配共用的进程级入口。
 *
 * <p>Dubbo 过滤器与路由器不是 Spring Bean，因此这里保存当前路由器和开关。
 * 默认关闭时，带范围的调用也不会去绑精确实例。</p>
 */
public final class LaneRoutingSupport {

    private static volatile LaneCallRouter router = LaneCallRouter.disabled();
    private static volatile boolean enabled;

    private LaneRoutingSupport() {
    }

    public static void install(LaneCallRouter replacement, boolean routingEnabled) {
        router = replacement == null ? LaneCallRouter.disabled() : replacement;
        enabled = routingEnabled;
    }

    public static void reset() {
        router = LaneCallRouter.disabled();
        enabled = false;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static LaneCallRouter router() {
        return router;
    }
}
