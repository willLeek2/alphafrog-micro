package world.willfrog.alphafrogmicro.frontend.lane;

/** 当前入口请求选中的可信路由事实。 */
public final class LaneRequestContext {

    private static final ThreadLocal<LaneRouteFacts> CURRENT = new ThreadLocal<>();

    private LaneRequestContext() {
    }

    public static LaneRouteFacts current() {
        return CURRENT.get();
    }

    public static void set(LaneRouteFacts facts) {
        if (facts == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(facts);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void restore(LaneRouteFacts previous) {
        set(previous);
    }
}
