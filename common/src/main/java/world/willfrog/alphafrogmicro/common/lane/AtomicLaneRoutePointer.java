package world.willfrog.alphafrogmicro.common.lane;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 用一次引用替换保存整份路由表，作为进程内的原子路由执行点。 */
public final class AtomicLaneRoutePointer implements LaneRoutePointer {

    private final AtomicReference<LaneRouteTable> table = new AtomicReference<>(LaneRouteTable.empty());

    @Override
    public LaneRouteTable current() {
        return table.get();
    }

    public void replaceAll(LaneRouteTable replacement) {
        table.set(Objects.requireNonNull(replacement, "replacement"));
    }
}
