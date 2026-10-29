package world.willfrog.agentlangchain.execution;

import java.util.Map;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

/** 额度数字来自本次快照，如何安排后续调用的建议来自所选指令版本。 */
public final class ToolBudgetPromptRenderer {
    private ToolBudgetPromptRenderer() { }

    public static PromptText render(AgentPromptTextReader reader, RootTreeCallBudget.ToolCallBudgetStatus status) {
        if (status == null) return PromptText.runtimeData("");
        String fragment = status.unlimited() ? "toolBudgetUnlimited" : "toolBudgetLimited";
        var body = reader.read(reader.current(), Map.of(fragment, fragment)).get(fragment);
        Map<String, String> data = status.unlimited() ? Map.of("scopeNote", status.scopeNote())
                : Map.of("scopeNote", status.scopeNote(), "used", String.valueOf(status.used()),
                        "limit", String.valueOf(status.limit()), "remaining", String.valueOf(status.remaining()));
        return reader.render(body, data, Map.of());
    }

    public static PromptText rejection(AgentPromptTextReader reader, String dimension, long used, long limit,
                                       com.fasterxml.jackson.databind.ObjectMapper json) {
        String label = switch (dimension == null ? "" : dimension) {
            case "tool_calls" -> "工具调用次数";
            case "wall_clock_ms" -> "运行时间";
            case "tokens" -> "令牌";
            default -> "运行";
        };
        String fragment = "toolBudgetRejection";
        var body = reader.read(reader.current(), Map.of(fragment, fragment)).get(fragment);
        PromptText message = reader.render(body, Map.of("dimensionLabel", label), Map.of());
        return WaitMemberResultPayload.budgetRejectionPrompt(dimension, used, limit, message, json);
    }
}
