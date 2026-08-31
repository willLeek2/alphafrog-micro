package world.willfrog.alphafrogmicro.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MarketReadAutoConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(MarketReadAutoConfiguration.class);
    }

    @Test
    void disabledByDefault_shouldRegisterNoMarketReadBeans() {
        runner().run(ctx -> {
            assertThat(ctx).doesNotHaveBean(MarketReadAutoConfiguration.DATA_SOURCE_BEAN_NAME);
            assertThat(ctx).doesNotHaveBean(HikariDataSource.class);
            assertThat(ctx).doesNotHaveBean(MarketReadResultGuard.class);
            assertThat(ctx).doesNotHaveBean(MarketReadDataSourceProperties.class);
        });
    }

    @Test
    void enabledFalse_shouldRegisterNoMarketReadBeans() {
        runner().withPropertyValues("alphafrog.market-read.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(MarketReadAutoConfiguration.DATA_SOURCE_BEAN_NAME);
            assertThat(ctx).doesNotHaveBean(HikariDataSource.class);
            assertThat(ctx).doesNotHaveBean(MarketReadResultGuard.class);
        });
    }

    @Test
    void enabledTrue_shouldFailWhenUrlMissing() {
        runner().withPropertyValues("alphafrog.market-read.enabled=true").run(ctx ->
                assertThat(ctx).hasFailed());
    }

    @Test
    void enabledTrue_shouldWireReadOnlyPoolToProductionDatabase() {
        runner().withPropertyValues(
                "alphafrog.market-read.enabled=true",
                "alphafrog.datasource.market-read.url=jdbc:postgresql://10.0.0.101:5432/alphafrog",
                "alphafrog.datasource.market-read.username=alphafrog_market_reader",
                "alphafrog.datasource.market-read.password=secret",
                "alphafrog.datasource.market-read.hikari.initialization-fail-timeout=-1"
        ).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasBean(MarketReadAutoConfiguration.DATA_SOURCE_BEAN_NAME);
            assertThat(ctx).hasSingleBean(MarketReadResultGuard.class);
            HikariDataSource dataSource = ctx.getBean(
                    MarketReadAutoConfiguration.DATA_SOURCE_BEAN_NAME, HikariDataSource.class);
            assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://10.0.0.101:5432/alphafrog");
            assertThat(dataSource.getUsername()).isEqualTo("alphafrog_market_reader");
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
            assertThat(dataSource.getConnectionTimeout()).isEqualTo(3000L);
            assertThat(dataSource.isReadOnly()).isTrue();
            assertThat(dataSource.getPoolName()).isEqualTo(MarketReadDataSourceFactory.POOL_NAME);
            MarketReadConnectionTarget.Parsed parsed = MarketReadConnectionTarget.parse(dataSource.getJdbcUrl());
            assertThat(parsed.host()).isEqualTo("10.0.0.101");
            assertThat(parsed.database()).isEqualTo("alphafrog");
        });
    }

    @Test
    void enabledTrue_shouldRejectBetaDatabaseUrl() {
        runner().withPropertyValues(
                "alphafrog.market-read.enabled=true",
                "alphafrog.datasource.market-read.url=jdbc:postgresql://127.0.0.1:5432/alphafrog_beta",
                "alphafrog.datasource.market-read.username=alphafrog_market_reader",
                "alphafrog.datasource.market-read.password=secret",
                "alphafrog.datasource.market-read.hikari.initialization-fail-timeout=-1"
        ).run(ctx -> assertThat(ctx).hasFailed());
    }
}
