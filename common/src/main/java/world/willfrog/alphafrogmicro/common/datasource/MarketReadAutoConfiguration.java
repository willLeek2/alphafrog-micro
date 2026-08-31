package world.willfrog.alphafrogmicro.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(HikariDataSource.class)
@ConditionalOnProperty(prefix = "alphafrog.market-read", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MarketReadDataSourceProperties.class)
public class MarketReadAutoConfiguration {

    public static final String DATA_SOURCE_BEAN_NAME = "marketReadDataSource";

    @Bean(name = DATA_SOURCE_BEAN_NAME, destroyMethod = "close")
    public HikariDataSource marketReadDataSource(MarketReadDataSourceProperties properties) {
        return MarketReadDataSourceFactory.create(properties);
    }

    @Bean
    public MarketReadResultGuard marketReadResultGuard(MarketReadDataSourceProperties properties) {
        return new MarketReadResultGuard(properties.getLimits());
    }
}
