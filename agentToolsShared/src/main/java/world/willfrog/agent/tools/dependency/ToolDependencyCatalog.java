package world.willfrog.agent.tools.dependency;

import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.agent.tools.registry.AgentToolRegistry.Dependency;

import java.util.Set;

/** 只从唯一工具登记读取检查依据，不维护第二份工具名单。 */
public final class ToolDependencyCatalog {
    private ToolDependencyCatalog() {
    }

    public static Set<Dependency> resolve(String toolName) {
        return AgentToolRegistry.find(toolName)
                .map(AgentToolRegistry.ToolDeclaration::dependencies)
                .orElse(Set.of());
    }
}
