package world.willfrog.agent.platform.exception;

import lombok.Getter;

/**
 * Run 级预算超限的结构化异常。
 *
 * <p>继承 {@link IllegalStateException} 以保持对现有 {@code catch (IllegalStateException)}
 * 代码的兼容。消息格式保持 {@code RUN_BUDGET_EXCEEDED:<dimension>:<actual>/<limit>}，
 * 使 {@link world.willfrog.agentlangchain.orchestration.LangchainTerminalToolErrorHandler}
 * 等依赖字符串前缀的链路继续生效。</p>
 */
@Getter
public class RunBudgetException extends IllegalStateException {

    private final String dimension;
    private final long actual;
    private final long limit;
    private final double ratio;
    private final boolean partial;

    public RunBudgetException(String dimension, long actual, long limit, boolean partial) {
        super(buildMessage(dimension, actual, limit));
        this.dimension = dimension == null ? "" : dimension;
        this.actual = actual;
        this.limit = limit;
        this.ratio = limit > 0 ? ((double) actual) / limit : 0.0;
        this.partial = partial;
    }

    private static String buildMessage(String dimension, long actual, long limit) {
        return "RUN_BUDGET_EXCEEDED:" + (dimension == null ? "" : dimension) + ":" + actual + "/" + limit;
    }
}
