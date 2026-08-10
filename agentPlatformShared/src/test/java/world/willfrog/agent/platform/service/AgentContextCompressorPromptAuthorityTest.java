package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.util.PromptFileLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class AgentContextCompressorPromptAuthorityTest {

    @Test
    void summarySystemPrompt_shouldRenderClasspathAuthorityWithOnlyDynamicLimitInJava() {
        AgentContextCompressor compressor = new AgentContextCompressor(
                mock(AgentAiServiceFactory.class),
                mock(AgentLlmLocalConfigLoader.class),
                new AgentLlmProperties());
        String template = PromptFileLoader.load("prompts/agent/follow_up_summary_system.txt");

        String rendered = ReflectionTestUtils.invokeMethod(
                compressor, "buildSummarySystemPrompt", 321);

        assertEquals(template.replace("{{maxChars}}", "321"), rendered);
        assertFalse(rendered.contains("{{maxChars}}"), "动态字符上限占位符必须完全渲染");
    }
}
