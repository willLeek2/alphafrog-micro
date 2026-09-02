/**
 * 灰度规则的加载、稳定分桶和统一业务判断入口。
 *
 * <p>本包不得 import {@code datasource} 或 {@code lane}。业务模块不得在本包之外重新实现百分比、
 * 名单、过期或稳定分桶逻辑。</p>
 */
package world.willfrog.alphafrogmicro.common.gray;
