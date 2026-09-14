package world.willfrog.agentlangchain.failure;

import world.willfrog.agent.platform.exception.AgentRunFailureClass;

/**
 * {@link LangchainFailureCategory} 到 Run 级四分类的映射表。
 *
 * <p>控制流（挂起 / 取消 / 暂停）不走这个枚举，由 {@code AgentRunControlSignal} 识别。
 * 本表只覆盖失败细分类。四分类字段写进失败事件放到后续批次。</p>
 */
public final class LangchainFailureClassMapping {

    private LangchainFailureClassMapping() {
    }

    public static AgentRunFailureClass from(LangchainFailureCategory category) {
        if (category == null) {
            return AgentRunFailureClass.UNKNOWN_DEFECT;
        }
        return switch (category) {
            case BUDGET_EXCEEDED,
                 BUDGET_EXCEEDED_LLM_CALLS,
                 BUDGET_EXCEEDED_TOKENS,
                 BUDGET_EXCEEDED_TOOL_CALLS,
                 BUDGET_EXCEEDED_WALL_CLOCK,
                 BUDGET_EXCEEDED_HTTP_ATTEMPTS,
                 INFRA_RETRY,
                 PROVIDER_TRANSIENT,
                 PROVIDER_RATE_LIMIT,
                 PROVIDER_MODEL_UNAVAILABLE -> AgentRunFailureClass.RESOURCE_SIGNAL;
            case TOOL_ERROR,
                 REPEATED_TOOL_CALL,
                 PARAM_RETRY_WITH_HINT,
                 EMPTY_OUTPUT,
                 PROVIDER_BAD_REQUEST,
                 PROVIDER_AUTH_REJECTED -> AgentRunFailureClass.BUSINESS_REJECTION;
            case UNKNOWN, PROVIDER_UNKNOWN -> AgentRunFailureClass.UNKNOWN_DEFECT;
        };
    }
}
