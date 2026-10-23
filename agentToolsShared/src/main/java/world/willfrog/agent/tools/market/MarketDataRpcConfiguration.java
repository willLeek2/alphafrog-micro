package world.willfrog.agent.tools.market;

import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.reference.ReferenceBeanBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

import java.util.Arrays;
import java.util.List;

/** 市场查询从生产数据服务读取；其他工具继续使用部署本身的默认注册中心。 */
@Configuration(proxyBeanMethods = false)
public class MarketDataRpcConfiguration {

    /**
     * 014-new-02（方案 5.1 必改1）：market 引用订阅的 registry-ids 覆盖入口，下划线环境变量风格。
     * 设置后按其值订阅（逗号分隔的 registry id 列表，泳道场景如 {@code AF_MARKET_DUBBO_REGISTRY_IDS=beta}，
     * 让 agent 看见注册在 beta 组的泳道数据服务提供者）；不设置时保持下面的 production-only 现状
     * （生产部署行为一字不差）。
     */
    static final String MARKET_REGISTRY_IDS_PROPERTY = "AF_MARKET_DUBBO_REGISTRY_IDS";

    /** executeAssetSql 的客户端超时（毫秒）：服务端语句超时 30s，客户端 35s 留传播余量（方案 D3）。 */
    static final int ASSET_SQL_CLIENT_TIMEOUT_MS = 35_000;

    @Bean
    public ReferenceBean<DomesticStockService> marketDomesticStockService(Environment environment) {
        // 014-new-02 D3：只对 executeAssetSql 单方法放宽客户端超时到 35s（服务端语句超时 30s＋传播余量），
        // 并关闭该方法的超时重试（默认重试 2 次会把 30s 级长查询重复执行三遍）；
        // 其余市场方法不受影响，沿用全局默认超时与重试。
        MethodConfig executeAssetSql = new MethodConfig();
        executeAssetSql.setName("executeAssetSql");
        executeAssetSql.setTimeout(ASSET_SQL_CLIENT_TIMEOUT_MS);
        executeAssetSql.setRetries(0);
        return marketReference(DomesticStockService.class, environment, List.of(executeAssetSql));
    }

    @Bean
    public ReferenceBean<DomesticFundService> marketDomesticFundService(Environment environment) {
        return marketReference(DomesticFundService.class, environment, List.of());
    }

    @Bean
    public ReferenceBean<DomesticIndexService> marketDomesticIndexService(Environment environment) {
        return marketReference(DomesticIndexService.class, environment, List.of());
    }

    @Bean
    public ReferenceBean<DomesticListedAssetService> marketDomesticListedAssetService(Environment environment) {
        return marketReference(DomesticListedAssetService.class, environment, List.of());
    }

    private static <T> ReferenceBean<T> marketReference(Class<T> serviceType, Environment environment,
                                                        List<MethodConfig> methodConfigs) {
        ReferenceBeanBuilder builder = new ReferenceBeanBuilder().setInterface(serviceType);
        if (!methodConfigs.isEmpty()) {
            builder.setMethods(methodConfigs);
        }
        String[] overrideRegistryIds = marketRegistryIdsOverride(environment);
        if (overrideRegistryIds.length > 0) {
            // 014-new-02：显式覆盖订阅（泳道用）；覆盖后不再走下方 production-only 分支。
            builder.setRegistry(overrideRegistryIds);
        } else if (environment.containsProperty("dubbo.registries.production.address")) {
            // Beta 已注入 production 注册中心时，只订阅它，避免市场查询受 Beta 提供者的数据覆盖限制影响。
            builder.setRegistry(new String[]{"production"});
        }
        // 单注册中心生产部署沿用原来的默认配置，不要求它额外声明 production 编号。
        return builder.build();
    }

    /** 解析覆盖变量；空白/全逗号视为未设置，返回空数组以回落默认分支。 */
    private static String[] marketRegistryIdsOverride(Environment environment) {
        String raw = environment.getProperty(MARKET_REGISTRY_IDS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toArray(String[]::new);
    }
}
