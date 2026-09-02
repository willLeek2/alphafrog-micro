package world.willfrog.alphafrogmicro.common.lane;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务代码发起服务间调用时的唯一绑定入口。
 *
 * <p>每次 {@link #bindNewCall(String, String)} 都从同一个原子执行点读一次当前指针。
 * 读到的绑定对象不会在指针切换后被改写；下一次调用再读一次。指针缺失、默认实例为空，
 * 或文件无法读取时抛出 {@link LaneRouteUnavailableException}，不得回退到未过滤的地址列表。</p>
 */
public final class LaneCallRouter {

    private final LaneRoutePointer pointer;
    private final AtomicInteger readCount = new AtomicInteger();

    public LaneCallRouter(LaneRoutePointer pointer) {
        this.pointer = Objects.requireNonNull(pointer, "pointer");
    }

    public static LaneCallRouter disabled() {
        return new LaneCallRouter(LaneRouteTable::empty);
    }

    public LaneCallBinding bindNewCall(String trafficScopeId, String serviceName) {
        readCount.incrementAndGet();
        LaneRouteTable table;
        try {
            table = pointer.current();
        } catch (LaneRouteFactsUncertainException uncertain) {
            throw uncertain;
        } catch (RuntimeException failed) {
            throw new LaneRouteUnavailableException(
                    LaneRouteUnavailableException.CODE, failed);
        }
        if (table == null) {
            throw new LaneRouteUnavailableException();
        }
        LaneServiceRoute route = table.find(trafficScopeId, serviceName);
        if (route == null) {
            throw new LaneRouteUnavailableException();
        }
        return route.toBinding();
    }

    public LaneCallBinding bindNewCallByRegistration(String trafficScopeId, String registrationServiceName) {
        readCount.incrementAndGet();
        LaneRouteTable table;
        try {
            table = pointer.current();
        } catch (LaneRouteFactsUncertainException uncertain) {
            throw uncertain;
        } catch (RuntimeException failed) {
            throw new LaneRouteUnavailableException(
                    LaneRouteUnavailableException.CODE, failed);
        }
        if (table == null) {
            throw new LaneRouteUnavailableException();
        }
        LaneServiceRoute route = table.findByRegistration(trafficScopeId, registrationServiceName);
        if (route == null) {
            route = table.find(trafficScopeId, registrationServiceName);
        }
        if (route == null) {
            throw new LaneRouteUnavailableException();
        }
        return route.toBinding();
    }

    int readCount() {
        return readCount.get();
    }
}
