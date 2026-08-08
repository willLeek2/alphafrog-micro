package world.willfrog.agent.tools.finance;

/**
 * 轻量模型解析客户端接口。实现由 agentPlatformShared 的 {@code FinanceMethodResolverModelService} 提供。
 *
 * <p>该接口 intentionally 小而窄：只接受用户问题、上下文和已渲染的目录文本，
 * 返回原始模型 JSON 或技术错误分类，不做任何目录/字段校验。</p>
 */
public interface FinanceMethodResolverClient {

    /**
     * 调用轻量模型解析器。
     *
     * @param query      用户自然语言问题（必填）
     * @param context    可选自然语言上下文
     * @param catalogText 已渲染的紧凑目录文本（含 system prompt 模板替换后内容）
     * @return 原始模型 JSON 或技术错误
     */
    ResolverResult resolve(String query, String context, String catalogText);

    /**
     * 解析结果：要么成功返回原始 JSON 字符串与真实模型路由信息，要么返回明确的技术错误分类。
     */
    sealed interface ResolverResult permits Ok, TechnicalError {
    }

    /**
     * 成功结果。
     *
     * @param rawJson 模型原始输出 JSON 字符串
     * @param route   真实 provider/endpoint/model 路由信息，由 platform 实现侧填入；
     *                仅用于可观测快照，不得把模型输出内容当作路由
     */
    record Ok(String rawJson, RouteInfo route) implements ResolverResult {
    }

    /**
     * 模型路由信息。compact constructor 会 trim 并拒绝空值。
     */
    record RouteInfo(String provider, String endpoint, String model) {
        public RouteInfo {
            if (provider == null || provider.trim().isEmpty()) {
                throw new IllegalArgumentException("provider must not be blank");
            }
            provider = provider.trim();
            if (endpoint == null || endpoint.trim().isEmpty()) {
                throw new IllegalArgumentException("endpoint must not be blank");
            }
            endpoint = endpoint.trim();
            if (model == null || model.trim().isEmpty()) {
                throw new IllegalArgumentException("model must not be blank");
            }
            model = model.trim();
        }
    }

    record TechnicalError(ErrorKind kind, String message) implements ResolverResult {
    }

    enum ErrorKind {
        NO_ROUTE,
        TIMEOUT,
        BAD_JSON,
        CATALOG_BUDGET_EXCEEDED
    }
}
