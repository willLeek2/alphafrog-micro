package world.willfrog.agent.platform.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProperties;

@Configuration
@EnableConfigurationProperties({
    AgentLlmProperties.class,
    CodeRefineProperties.class,
    AgentObservabilityProperties.class,
    AgentSnapshotProperties.class,
    FinanceRecordChannelProperties.class
})
public class AiConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.withMaxMessages(20);
    }
}
