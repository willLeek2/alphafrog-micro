package world.willfrog.alphafrogmicro.common.datasource;

/**
 * 行情读结果超过行数、字节数或分页大小上限。调用方必须把该错误原样交给上游，
 * 不得截断结果后当作成功返回。
 */
public final class MarketReadResultLimitExceededException extends RuntimeException {

    public static final String ERROR_CODE = "MARKET_READ_RESULT_LIMIT_EXCEEDED";

    public enum LimitKind {
        ROWS,
        BYTES,
        PAGE_SIZE
    }

    private final LimitKind kind;
    private final long actual;
    private final long limit;

    public MarketReadResultLimitExceededException(LimitKind kind, long actual, long limit) {
        super(buildMessage(kind, actual, limit));
        this.kind = kind;
        this.actual = actual;
        this.limit = limit;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public LimitKind getKind() {
        return kind;
    }

    public long getActual() {
        return actual;
    }

    public long getLimit() {
        return limit;
    }

    private static String buildMessage(LimitKind kind, long actual, long limit) {
        String dimension = switch (kind) {
            case ROWS -> "行数";
            case BYTES -> "字节数";
            case PAGE_SIZE -> "分页大小";
        };
        return "行情读结果超过最大" + dimension + "限制: actual=" + actual + ", limit=" + limit
                + ", errorCode=" + ERROR_CODE;
    }
}
