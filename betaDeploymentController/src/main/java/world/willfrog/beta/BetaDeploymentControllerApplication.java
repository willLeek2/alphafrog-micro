package world.willfrog.beta;

import com.fasterxml.jackson.core.JsonParser;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class BetaDeploymentControllerApplication {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer rejectDuplicateJsonKeys() {
        return builder -> builder.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public static void main(String[] args) {
        SpringApplication.run(BetaDeploymentControllerApplication.class, args);
    }
}
