package world.willfrog.agentlangchain.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.MarketQueryTexts;

import java.util.ArrayList;
import java.util.List;

/**
 * 非管理员运行从模型工具清单里去掉 executeQuery。
 * searchStock 保留，说明改成关键词搜索，不再指向 executeQuery。
 * 管理员运行保持权威说明。
 */
final class MarketQueryVisibility {

    private MarketQueryVisibility() {
    }

    static List<ToolSpecification> apply(List<ToolSpecification> specifications) {
        if (!AgentContext.isMarketQueryHidden() || specifications == null || specifications.isEmpty()) {
            return specifications;
        }
        List<ToolSpecification> result = new ArrayList<>(specifications.size());
        for (ToolSpecification specification : specifications) {
            if (specification == null) {
                continue;
            }
            if (MarketQueryTexts.omitted(specification.name())) {
                continue;
            }
            if (MarketQueryTexts.rewrites(specification.name())) {
                result.add(specification.toBuilder()
                        .description(MarketQueryTexts.hiddenDescription(specification.name()))
                        .build());
            } else {
                result.add(specification);
            }
        }
        return List.copyOf(result);
    }
}
