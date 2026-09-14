package world.willfrog.agentlangchain.planning;

import dev.langchain4j.service.UserMessage;

/**
 * 单阶段兼容规划接口。系统提示由 {@link LangchainAiPlanner} 动态提供；
 * 方法返回原始 JSON，由调用方统一走共享解析与结构校验。
 */
interface LangchainPlannerAiService {

    @UserMessage("{{it}}")
    String plan(String userMessage);
}
