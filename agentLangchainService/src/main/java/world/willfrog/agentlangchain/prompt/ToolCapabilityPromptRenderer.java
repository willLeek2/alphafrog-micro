package world.willfrog.agentlangchain.prompt;

import dev.langchain4j.agent.tool.ToolSpecification;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.tools.registry.AgentToolRegistry;

import java.util.List;

/** D02 依赖适配器：membership 来自 D05 registry，正文来自 platform Prompt 权威。 */
public final class ToolCapabilityPromptRenderer {

    private ToolCapabilityPromptRenderer() {
    }

    public static String render(AgentPromptService promptService,
                                List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return "";
        }
        List<String> names = specifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        names.forEach(AgentToolRegistry::require);
        return promptService.renderToolCapabilities(names);
    }
}
