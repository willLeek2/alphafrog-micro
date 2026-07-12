package world.willfrog.agent.tools;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;

@Configuration
@ComponentScan(basePackages = "world.willfrog.agent.tools")
@EnableConfigurationProperties({DataAnalysisCapacityProperties.class})
public class AgentToolsAutoConfiguration {
}