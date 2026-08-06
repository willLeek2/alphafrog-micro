package world.willfrog.alphafrogmicro.frontend;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import world.willfrog.alphafrogmicro.frontend.service.debug.AuthObservabilityProperties;

@SpringBootApplication(scanBasePackages = {
        "world.willfrog.alphafrogmicro.frontend",
        "world.willfrog.alphafrogmicro.common"
})
@EnableDubbo
@MapperScan("world.willfrog.alphafrogmicro.common.dao")
@EnableConfigurationProperties(AuthObservabilityProperties.class)
public class FrontendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FrontendApplication.class, args);
    }

}
