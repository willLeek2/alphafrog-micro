package world.willfrog.agent.platform.finance;

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
     * @param query          用户自然语言问题（必填）
     * @param context        可选自然语言上下文
     * @param catalogFragment 紧凑目录片段（仅目录文本，不含 system prompt 模板）；
     *                        最终 prompt 由 platform 实现侧经 AgentPromptService 解析实际模板后组装，
     *                        tools 侧不持有模板，避免二次嵌套与 local override 失效
     * @return 原始模型 JSON 或技术错误
     */
    ResolverResult resolve(String query, String context, String catalogFragment);

    /**
     * 解析结果：要么成功返回原始 JSON 字符串与真实模型路由信息，要么返回明确的技术错误分类。
     */
    sealed interface ResolverResult permits Ok, TechnicalError {
    }

    /**
     * 成功结果。compact constructor 拒绝 null route 与空白 resolverPromptVersion。
     *
     * @param rawJson 模型原始输出 JSON 字符串
     * @param route   真实 provider/endpoint/model 路由信息，由 platform 实现侧填入；
     *                仅用于可观测快照，不得把模型输出内容当作路由
     * @param resolverPromptVersion 实际使用的 system prompt 模板摘要（含 local override 后的真实版本），
     *                由 platform 实现侧按实际模板计算；快照只信此值
     */
    record Ok(String rawJson, RouteInfo route, String resolverPromptVersion) implements ResolverResult {
        public Ok {
            if (route == null) {
                throw new IllegalArgumentException("route must not be null");
            }
            if (resolverPromptVersion == null || resolverPromptVersion.trim().isEmpty()) {
                throw new IllegalArgumentException("resolverPromptVersion must not be blank");
            }
            resolverPromptVersion = resolverPromptVersion.trim();
        }
    }

    /**
     * 模型路由信息。compact constructor 会 trim 并拒绝空值。
     *
     * <p>{@code provider} 语义钉死为 <b>HTTP 平台类型</b>（如 openrouter / dashscope / openai-compatible），
     * 由 platform 实现侧从解析后的真实端点推导；它<i>不是</i> OpenRouter retry 后的实际 winning provider，
     * 也不是 stage 配置别名或模型输出字段。{@code endpoint} 为解析后的真实 baseUrl（仅 DashScope 允许
     * 按 region 推导缺省端点）；{@code model} 为解析后的真实模型名。</p>
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
        CATALOG_BUDGET_EXCEEDED,
        /** query/context 合并请求字节超过 platform 侧配置上限（不静默截断）。 */
        REQUEST_TOO_LARGE,
        /** ChatModel 调用抛出的非超时异常（认证失败、连接拒绝、5xx 重试耗尽等）。 */
        CALL_FAILED
    }
}
