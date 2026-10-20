package world.willfrog.agent.platform.failure;

import java.util.Map;
import java.util.Set;

/**
 * 工具失败信封 {@code error.category} 的四类通用失败类别。
 *
 * <p>工具错误码（{@code error.code}）是各工具族自有的自由字符串（market 五档、SQL/沙箱自有码等，
 * 全仓 20+ 种并存）。本枚举给这些码一个统一的粗分类，让模型与下游能把「参数问题 / 限流容量 /
 * 上游系统故障 / 不支持」区分开。类别只描述故障，不推导重试建议——重试语义继续由各出口既有的
 * retryable/details/文案承担，两者分离表达。</p>
 *
 * <p>取值规则（构造点显式覆盖优先）：</p>
 * <ul>
 *   <li>{@link #of(String)}：码→默认类别的兜底映射，映射表外的未知码默认 {@link #UPSTREAM_OR_SYSTEM}；</li>
 *   <li>知道更细事实的出口（如 HTTP 状态码、失败分支）在构造点直接传显式类别，覆盖兜底结果，
 *       已知事实不丢弃、不靠默认值猜。</li>
 * </ul>
 *
 * <p>非故障结果族（查询无记录类：NO_DATA / TIME_SERIES_EMPTY / ASSET_NOT_FOUND）不属四类工具故障，
 * {@link #of(String)} 对它们返回 null，信封不写 {@code category} 字段；字段缺席的语义是「本协议未将
 * 本次结果归入四类工具故障」，不作为「根因无故障」的证明，无记录事实由原 code 与 message 表达，
 * 「停止原样重试」的说明责任在 message 文案（现有文本已带）。把它们终态化为「正常空结果」属取数
 * 业务语义的配套需求，不在本枚举范围内。GUIDE_NOT_FOUND 不属非故障族（主题先经合法性确认，之后
 * 指南缺失/空白/读取异常才返回该码，属平台资源不可用），按未知码默认归 {@link #UPSTREAM_OR_SYSTEM}。
 * 容量准入的 DATA_ANALYSIS_SERVER_BUSY 一码多义（名额不足、服务恢复中、预留身份冲突、非法恢复
 * 共用），不进兜底映射表；构造点持有具体原因时经显式覆盖表达——名额不足与请求规模超限是明确的
 * 容量约束语义。</p>
 */
public enum ToolFailureCategory {

    /** 参数问题：调用方给的参数非法、缺失或超出约束。 */
    INVALID_ARGUMENT,

    /** 限流或容量不足：被限流、容量打满或结果超限一类的资源约束。 */
    RATE_LIMITED_OR_CAPACITY,

    /** 上游或系统故障：上游服务、平台资源或本方系统故障。 */
    UPSTREAM_OR_SYSTEM,

    /** 不支持的操作：工具存在但该能力未开启或该操作不在支持范围。 */
    UNSUPPORTED;

    /** 非故障结果族（查询无记录类）：of 返回 null，信封不写 category 字段。 */
    private static final Set<String> NON_FAILURE_CODES = Set.of(
            "NO_DATA", "TIME_SERIES_EMPTY", "ASSET_NOT_FOUND");

    /** 参数问题类：必填参数缺失、取值非法或超出约束（含各工具明确的参数校验码）。 */
    private static final Set<String> INVALID_ARGUMENT_CODES = Set.of(
            "INVALID_ARGUMENT", "BATCH_LIMIT_EXCEEDED", "INVALID_TOPIC", "INVALID_INPUT",
            "INVALID_QUERY_TYPE", "MISSING_SQL", "MISSING_IDS", "MISSING_DATASET_ID",
            "LIMIT_TOO_SMALL_WITHOUT_KEYWORD", "LIMIT_TOO_LARGE_WITHOUT_KEYWORD",
            "LIMIT_TOO_LARGE_WITH_KEYWORD", "UNKNOWN_TIER", "ILLEGAL_RUN_LEVEL_IDS",
            "INPUT_TOO_LARGE");

    /** 容量类：请求规模超出资源硬上限（明确的容量约束语义，非故障猜测）。 */
    private static final Set<String> CAPACITY_CODES = Set.of(
            "DATA_ANALYSIS_TASK_TOO_LARGE");

    /** 不支持类：工具未声明或按 run/灰度配置关闭的能力。 */
    private static final Set<String> UNSUPPORTED_CODES = Set.of(
            "UNSUPPORTED_TOOL", "CAPABILITY_DISABLED");

    /** 上游或系统故障类（显式列出便于核对；表外未知码同样兜底到这一类）。 */
    private static final Set<String> UPSTREAM_CODES = Set.of(
            "UPSTREAM_ERROR", "TOOL_ERROR", "HTTP_ERROR", "GUIDE_NOT_FOUND");

    /**
     * 码→默认类别的兜底映射。
     *
     * <p>非故障结果族返回 null（信封不写 category 字段）；映射表外的未知码默认
     * {@link #UPSTREAM_OR_SYSTEM}。</p>
     */
    public static ToolFailureCategory of(String code) {
        if (code == null || code.isBlank()) {
            return UPSTREAM_OR_SYSTEM;
        }
        String normalized = code.trim();
        if (NON_FAILURE_CODES.contains(normalized)) {
            return null;
        }
        if (INVALID_ARGUMENT_CODES.contains(normalized)) {
            return INVALID_ARGUMENT;
        }
        if (CAPACITY_CODES.contains(normalized)) {
            return RATE_LIMITED_OR_CAPACITY;
        }
        if (UNSUPPORTED_CODES.contains(normalized)) {
            return UNSUPPORTED;
        }
        if (UPSTREAM_CODES.contains(normalized)) {
            return UPSTREAM_OR_SYSTEM;
        }
        return UPSTREAM_OR_SYSTEM;
    }

    /**
     * 把解析出的类别写进失败信封的 error 段。
     *
     * <p>显式类别（构造点已知更细事实时传入）优先；传 null 时走 {@link #of(String)} 兜底。
     * 解析为 null（非故障结果族）时不写字段——字段缺席即「本协议未将本次结果归入四类工具故障」。</p>
     */
    public static void putCategoryIfResolved(Map<String, Object> error, String code,
                                              ToolFailureCategory explicit) {
        ToolFailureCategory resolved = explicit != null ? explicit : of(code);
        if (resolved != null) {
            error.put("category", resolved.name());
        }
    }
}
