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






}
