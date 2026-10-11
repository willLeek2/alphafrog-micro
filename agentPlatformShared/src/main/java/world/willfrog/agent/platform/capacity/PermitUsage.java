package world.willfrog.agent.platform.capacity;

/**
 * 一层名额的读数：在用多少、还能用多少、上限是多少。
 *
 * @param layer     哪一层
 * @param inUse     在用数量
 * @param available 可用数量（等于上限减在用，不小于 0）
 * @param limit     上限；{@link #UNLIMITED} 表示不限
 */
public record PermitUsage(SchedulerPermitLayer layer, int inUse, int available, int limit) {

    /** 不限上限的取值。 */
    public static final int UNLIMITED = -1;

    public static PermitUsage of(SchedulerPermitLayer layer, int inUse, int limit) {
        int available = limit == UNLIMITED ? UNLIMITED : Math.max(0, limit - inUse);
        return new PermitUsage(layer, inUse, available, limit);
    }

    public boolean unlimited() {
        return limit == UNLIMITED;
    }

    public String describe() {
        return layer.label() + " 在用 " + inUse + "，可用 " + (unlimited() ? "不限" : available)
                + "，上限 " + (unlimited() ? "不限" : limit);
    }
}
