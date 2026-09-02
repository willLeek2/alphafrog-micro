/**
 * 服务间调用按原子路由指针绑定精确实例。
 *
 * <p>本包不得 import {@code gray} 或 {@code datasource}。Beta 流量必须按控制器已经原子替换的
 * 路由快照选择 {@code instanceId} 和访问地址，不能把可变指针缓存到下一次调用。入口完成身份判断
 * 后可以把同一次读取固定到对应的目标调用，保证请求身份和网络目标属于同一代。读不到可信绑定时
 * 不能回退到未过滤的 Nacos 实例列表。稳定流量没有泳道范围时不经过这套绑定。</p>
 */
package world.willfrog.alphafrogmicro.common.lane;
