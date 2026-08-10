package world.willfrog.agentlangchain.planning;

import dev.langchain4j.service.UserMessage;

/**
 * 单阶段兼容规划接口。系统提示由 {@link LangchainAiPlanner} 动态提供，
 * 返回原始 JSON 后统一走共享解析与结构校验，避免 POJO 反序列化形成弱校验旁路。
 */
interface LangchainPlannerAiService {

    @UserMessage("{{it}}")
    String plan(String userMessage);
}
