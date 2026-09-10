package world.willfrog.sandbox;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

// 网关只做请求转发，没有数据库业务；agentPlatformShared 传递进来的 spring-jdbc
// 会触发 DataSource 自动配置，没有 datasource url 时启动直接失败，这里显式排除。
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableDubbo
public class PythonSandboxGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(PythonSandboxGatewayApplication.class, args);
    }
}
