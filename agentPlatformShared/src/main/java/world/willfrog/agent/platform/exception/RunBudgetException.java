package world.willfrog.agent.platform.exception;

import lombok.Getter;

/**
 * Run 级预算超限的结构化异常。
 *
 * <p>这是资源信号，不是控制流。继承 {@link IllegalStateException} 以保持现有
 * {@code catch (IllegalStateException)} 兼容。消息仍用
 * {@code RUN_BUDGET_EXCEEDED:<dimension>:<actual>/<limit>}，供日志和旧包装识别。</p>
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
