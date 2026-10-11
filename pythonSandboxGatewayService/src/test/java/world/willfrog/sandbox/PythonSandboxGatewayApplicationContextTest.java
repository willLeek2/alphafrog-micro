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
 *
 * 网关的消费方引用（Run 持久资源资格回查）在注册中心为 N/A 时无法聚合服务地址，
 * Dubbo 部署器启动会直接抛错；这里给该引用配一个永不可达的直连 URL（check=false
 * 只在首次调用时才连接），让上下文离线加载，同时保持 Dubbo 提供方面照常启动。
 */
@SpringBootTest(properties = {
        "dubbo.registry.address=N/A",
        "dubbo.protocol.port=-1",
        "dubbo.consumer.check=false",
        "dubbo.reference.world.willfrog.alphafrogmicro.agent.idl." +
                "AgentRunPersistentResourceEligibilityService.url=tri://127.0.0.1:1",
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
