package world.willfrog.alphafrogmicro.common.datasource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code alphafrog.datasource.market-read} 只读数据源与返回量上限。
 *
 * <p>url / username / password 无默认值：漏设时启动失败，不会静默连到主库或 beta 库。
 * 端口默认值只出现在各服务 yaml 的占位符里（任务 3-2），不在本类兜底。</p>
 */
@Data
@ConfigurationProperties(prefix = "alphafrog.datasource.market-read")
public class MarketReadDataSourceProperties {

    private String url;
    private String username;
    private String password;
    private Hikari hikari = new Hikari();
    private Limits limits = new Limits();

    public void validateRequired() {
        if (isBlank(url)) {
            throw new IllegalStateException(
                    "alphafrog.datasource.market-read.url is required when alphafrog.market-read.enabled=true");
        }
        if (isBlank(username)) {
            throw new IllegalStateException(
                    "alphafrog.datasource.market-read.username is required when alphafrog.market-read.enabled=true");
        }
        if (password == null) {
            throw new IllegalStateException(
                    "alphafrog.datasource.market-read.password is required when alphafrog.market-read.enabled=true");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Data
    public static class Hikari {
        /** 落地版中篇：每实例读池 5。 */
        private int maximumPoolSize = 5;
        private long connectionTimeout = 3000L;
        /**
         * Hikari 默认 1ms，启动时会尝试建连。测试可设为 -1 跳过探活；生产保持默认以便配错立刻失败。
         */
        private long initializationFailTimeout = 1L;
    }

    @Data
    public static class Limits {
        private int maxRows = 10_000;
        private long maxBytes = 1_048_576L;
        private int defaultPageSize = 100;
        private int maxPageSize = 1_000;
    }
}
