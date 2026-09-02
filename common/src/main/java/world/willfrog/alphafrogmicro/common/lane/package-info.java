/**
 * 服务间调用按原子路由指针绑定精确实例。
 *
 * <p>本包不得 import {@code gray} 或 {@code datasource}。Beta 流量必须按控制器已经原子替换的
 * 路由快照选择 {@code instanceId} 和访问地址，不能把可变指针缓存到下一次调用，也不能在读不到
 * 指针时回退到未过滤的 Nacos 实例列表。稳定流量没有泳道范围时不经过这套绑定。</p>
 */
package world.willfrog.alphafrogmicro.common.lane;
