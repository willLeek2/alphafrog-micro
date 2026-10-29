package world.willfrog.agentlangchain.prompt;

import dev.langchain4j.agent.tool.ToolSpecification;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

import java.util.List;

/** 工具名称由唯一登记表核对，托管正文从原根选择的版本读取并保留来源。 */
public final class ToolCapabilityPromptRenderer {

    private ToolCapabilityPromptRenderer() {
    }

    public static String render(AgentPromptService promptService,
                                List<ToolSpecification> specifications) {
        List<String> names = names(specifications);
        return names.isEmpty() ? "" : promptService.renderToolCapabilities(names);
    }

    public static PromptText renderText(AgentPromptService promptService, List<ToolSpecification> specifications) {
        List<String> names = names(specifications);
        return names.isEmpty() ? PromptText.runtimeData("") : promptService.renderToolCapabilitiesText(names);
    }

    private static List<String> names(List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return List.of();
        }
        List<String> names = specifications.stream()
                .map(ToolSpecification::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        names.forEach(AgentToolRegistry::require);
        return names;
    }
}
