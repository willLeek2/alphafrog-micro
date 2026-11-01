package world.willfrog.agentlangchain.planning;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainPlanningStructuredOutputSettingsTest {

  private final LangchainPlanningStructuredOutputSettings settings =
      LangchainTestFixtures.structuredOutputSettings();









  private static AgentLlmProperties propertiesWithMaxAttempts(int maxAttempts) {
    AgentLlmProperties properties = LangchainTestFixtures.llmProperties();
    properties.getRuntime().getPlanning().getStructuredOutput().setMaxAttempts(maxAttempts);
    return properties;
  }
}
