package world.willfrog.sandbox;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关没有数据库业务，主类显式排除了 DataSource 自动配置。这个测试在没有任何
 * datasource url 的环境下加载完整应用上下文：以后 agentPlatformShared 的依赖变化
 * 再把 spring-jdbc 带进来触发自动配置时，这里会先失败。
 */
@SpringBootTest(properties = {
        "dubbo.registry.address=N/A",
        "dubbo.protocol.port=-1",
        "logging.config=classpath:logback-test.xml",
})
class PythonSandboxGatewayApplicationContextTest {

    @Autowired(required = false)
    private DataSource dataSource;

    @Test
    void contextStartsWithoutADataSourceUrl() {
        assertThat(dataSource).isNull();
    }
}
