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

  @Test
  void structuredStrict_shouldDefaultToFalseWhenUnset() {
    assertThat(settings.structuredStrict()).isFalse();
  }

  @Test
  void requireProviderParameters_shouldBeFalseForOpenRouterPlanningEndpoint() {
    assertThat(settings.requireProviderParameters("openrouter")).isFalse();
    assertThat(settings.requireProviderParameters("OpenRouter")).isFalse();
  }

  @Test
  void requireProviderParameters_shouldFollowConfigForNonOpenRouterEndpoint() {
    assertThat(settings.requireProviderParameters("fireworks")).isTrue();
    assertThat(settings.requireProviderParameters(null)).isTrue();
  }

  @Test
  void planningMaxAttempts_shouldUseDefaultWhenUnset() {
    assertThat(settings.planningMaxAttempts(2)).isEqualTo(2);
  }

  @Test
  void planningMaxAttempts_shouldUseStaticConfig() {
    AgentLlmProperties properties = propertiesWithMaxAttempts(4);
    AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
    when(loader.current()).thenReturn(Optional.empty());

    assertThat(new LangchainPlanningStructuredOutputSettings(properties, loader)
        .planningMaxAttempts(2)).isEqualTo(4);
  }

  @Test
  void planningMaxAttempts_shouldPreferLocalConfig() {
    AgentLlmProperties properties = propertiesWithMaxAttempts(4);
    AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
    when(loader.current()).thenReturn(Optional.of(propertiesWithMaxAttempts(3)));

    assertThat(new LangchainPlanningStructuredOutputSettings(properties, loader)
        .planningMaxAttempts(2)).isEqualTo(3);
  }

  @Test
  void planningMaxAttempts_shouldFailClosedToDefaultForInvalidConfig() {
    AgentLlmProperties properties = propertiesWithMaxAttempts(0);
    AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
    when(loader.current()).thenReturn(Optional.empty());

    assertThat(new LangchainPlanningStructuredOutputSettings(properties, loader)
        .planningMaxAttempts(2)).isEqualTo(2);
  }

  @Test
  void planningMaxAttempts_shouldFailClosedToDefaultWhenAboveSafetyBound() {
    AgentLlmProperties properties = propertiesWithMaxAttempts(100);
    AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
    when(loader.current()).thenReturn(Optional.empty());

    assertThat(new LangchainPlanningStructuredOutputSettings(properties, loader)
        .planningMaxAttempts(2)).isEqualTo(2);
  }

  private static AgentLlmProperties propertiesWithMaxAttempts(int maxAttempts) {
    AgentLlmProperties properties = LangchainTestFixtures.llmProperties();
    properties.getRuntime().getPlanning().getStructuredOutput().setMaxAttempts(maxAttempts);
    return properties;
  }
}
