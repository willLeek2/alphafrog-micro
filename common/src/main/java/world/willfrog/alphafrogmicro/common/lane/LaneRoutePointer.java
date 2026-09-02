package world.willfrog.alphafrogmicro.common.lane;

/**
 * 原子路由执行点。每次新调用都必须重新 {@link #current()}，不能把返回值留给下一次调用继续用。
 */
public interface LaneRoutePointer {

    LaneRouteTable current();
}
