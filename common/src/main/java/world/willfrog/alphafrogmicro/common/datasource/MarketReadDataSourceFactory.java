package world.willfrog.alphafrogmicro.common.datasource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 按合同装配 Hikari 只读池。池大小与连接超时写死默认值，调用方可被 properties 覆盖，
 * 但 {@code maximum-pool-size} 默认 5、{@code connection-timeout} 默认 3000。
 */
public final class MarketReadDataSourceFactory {

    public static final String POOL_NAME = "alphafrog-market-read";

    private MarketReadDataSourceFactory() {
    }

    public static HikariDataSource create(MarketReadDataSourceProperties properties) {
        properties.validateRequired();
        MarketReadConnectionTarget.verify(properties.getUrl());
        MarketReadDataSourceProperties.Hikari hikari = properties.getHikari();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName(POOL_NAME);
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setMaximumPoolSize(hikari.getMaximumPoolSize());
        dataSource.setConnectionTimeout(hikari.getConnectionTimeout());
        dataSource.setInitializationFailTimeout(hikari.getInitializationFailTimeout());
        dataSource.setReadOnly(true);
        dataSource.setAutoCommit(true);
        return dataSource;
    }
}
