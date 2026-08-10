package world.willfrog.agentlangchain.prompt;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.tools.registry.AgentToolRegistry;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCapabilityPromptRendererTest {

    @Test
    void render_shouldCoverEveryDeclaredRegistryTool() {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.empty());
        AgentPromptService promptService = new AgentPromptService(new AgentLlmProperties(), loader);
        List<ToolSpecification> specifications = AgentToolRegistry.declaredToolNames().stream()
                .map(name -> ToolSpecification.builder().name(name).description("test").build())
                .toList();

        String rendered = ToolCapabilityPromptRenderer.render(promptService, specifications);

        for (String name : AgentToolRegistry.declaredToolNames()) {
            assertTrue(rendered.contains("- " + name + ":"), name + " 必须有权威能力说明");
        }
        assertFalse(rendered.contains("单次最多"), "能力正文不得硬编码批量上限");
    }

    @Test
    void render_shouldRejectToolMissingFromRegistry() {
        AgentPromptService promptService = mock(AgentPromptService.class);
        List<ToolSpecification> specifications = List.of(
                ToolSpecification.builder().name("notRegistered").description("test").build());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ToolCapabilityPromptRenderer.render(promptService, specifications));
        assertTrue(error.getMessage().contains("notRegistered"));
    }

    @Test
    void render_shouldNotDescribeCapabilitiesAbsentFromActualSpecifications() {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.empty());
        AgentPromptService promptService = new AgentPromptService(new AgentLlmProperties(), loader);
        List<ToolSpecification> specifications = List.of(
                ToolSpecification.builder().name("ragSearch").description("test").build(),
                ToolSpecification.builder().name("listMyData").description("test").build());

        String rendered = ToolCapabilityPromptRenderer.render(promptService, specifications);

        assertTrue(rendered.contains("- ragSearch:"));
        assertTrue(rendered.contains("- listMyData:"));
        assertFalse(rendered.contains("- searchWeb:"), "关闭的联网能力不得出现能力条目");
        assertFalse(rendered.contains("- executePython:"), "关闭的代码执行能力不得出现能力条目");
        assertFalse(rendered.contains("- checkParallelLimits:"), "未开放的限制查询工具不得出现能力条目");
    }
}
