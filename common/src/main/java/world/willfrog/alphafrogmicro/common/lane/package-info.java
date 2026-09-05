/**
 * 服务间调用只透传官方流量标签，选路交给 Dubbo 标签路由和同区优先。
 *
 * <p>本包不得 import {@code gray} 或 {@code datasource}。消费方集群过滤器在官方标签路由
 * 选实例之前，把当前线程的泳道标签写入官方附件 {@code dubbo.tag}；提供方入站后从同一附件
 * 恢复线程上下文。主 Beta 流量范围 {@code main-beta} 不写标签，因此会落到无标实例。
 * 没有泳道标签的稳定流量也不写附件。本包不再按实例坐标选择地址。</p>
 */
package world.willfrog.alphafrogmicro.common.lane;
