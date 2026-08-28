package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.search.SearchTools;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static world.willfrog.agent.platform.service.AgentRunObservabilityService.PHASE_SUB_AGENT;

/**
 * LangChain4j（LC4j）{@link ToolProvider} 适配层：把内部 {@link ToolRouter} 工具目录暴露给
 * {@link dev.langchain4j.service.AiServices}，使模型在 tool loop 里看到的工具列表与 legacy 路径一致。
 *
 * <p>职责分工（与 {@link ToolRouterToolExecutor}、{@link ToolRouter} 的关系）：</p>
 * <ul>
 *   <li><b>本类</b>：决定「这次 AiService 调用里 LLM 能看见哪些工具」——生成
 *       {@link ToolSpecification} 列表，并为每个 spec 绑定同一个 {@link ToolRouterToolExecutor}；</li>
 *   <li>{@link ToolRouterToolExecutor}：模型选中某个 tool 后，把 JSON 参数解析并委托
 *       {@link ToolRouter#invokeWithMeta(String, Map)} 执行；</li>
 *   <li>{@link ToolRouter}：预算、trace、缓存、参数别名、业务工具分发（详见
 *       {@code ToolRouter.java} 的注释）。</li>
 * </ul>
 *
 * <p>工具路由的讲解要点已迁出，见
 * {@code agent-working-docs/code-review/phase2/agent-run-overall/tool-routing-interview-points.md}。</p>
 *
 * <p>调用路径：</p>
 * <ol>
 *   <li>{@link world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor} 构建 AiServices 时
 *       注入 {@code ObjectProvider<ToolProvider>}（Spring Bean 为 {@link world.willfrog.agentlangchain.config.LangchainToolsConfiguration#langchainToolProvider}）；</li>
 *   <li>每次 LC4j 发起 chat 前，若 {@link #isDynamic()} 为 true，会调用 {@link #provideTools(ToolProviderRequest)}；</li>
 *   <li>{@link LangchainRunContextBridge} 把 {@link InvocationParameters} 里的 runId/userId 写回
 *       {@link AgentContext}，供后续 {@link ToolRouter} 记 observability；</li>
 *   <li>{@link ToolCatalogBuilder} 从 {@link MarketDataTools} 等 Bean 反射生成 spec，并按
 *       webSearch / codeInterpreter 开关过滤，再 {@code mergeCanonical} 合入
 *       {@code checkParallelLimits}（避免 0 参数 {@code @Tool} 反射漏注册）；</li>
 *   <li>返回的 {@link ToolProviderResult} 中，所有 spec 共享一个 {@link ToolRouterToolExecutor} 实例。</li>
 * </ol>
 *
 * @see ToolRouterToolExecutor 工具执行委托
 * @see ToolCatalogBuilder run 级工具目录拼装
 * @see world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor AiServices 注入点
 * @see world.willfrog.agent.tools.router.ToolRouter 统一执行语义
 */
@RequiredArgsConstructor
public class ToolRouterToolProvider implements ToolProvider {

    private final ToolRouter toolRouter;
    private final MarketDataTools marketDataTools;
    private final RagTools ragTools;
    private final SearchTools searchTools;
    private final PythonSandboxTools pythonSandboxTools;
    private final ListMyDataTool listMyDataTool;
    private final LoadToolGuideTool loadToolGuideTool;
    private final RereadToolHandler rereadToolHandler;
    private final ObjectMapper objectMapper;
    /**
     * 事件服务，传递给 {@link ToolRouterToolExecutor} 用于发射 TOOL_CALL_STARTED / TOOL_CALL_FINISHED
     * SSE 事件（经 Redis pub-sub 推送）。
     */
    private final AgentRunEventService agentEventService;
    private final LangchainToolConcurrencyThrottle toolThrottle;
    private final PythonSandboxDispatchStore pythonSandboxDispatchStore;

    /**
     * 为当前 LC4j 调用构建「工具名 → ToolExecutor」映射。
     *
     * <p>注意：所有 {@link ToolSpecification} 共用同一个 {@link ToolRouterToolExecutor}，
     * 真正按工具名分支的逻辑在 {@link ToolRouter} 内部，不在 Provider 层。</p>
     */
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        // 把 Pipeline / TodoNodeExecutor 放进 InvocationParameters 的 run 上下文同步到 AgentContext。
        LangchainRunContextBridge.apply(request);

        boolean webSearchEnabled = resolveBoolean(
                request.invocationParameters(),
                LangchainToolInvocationKeys.WEB_SEARCH_ENABLED,
                AgentContext.isWebSearchEnabled(),
                false
        );
        boolean codeInterpreterEnabled = resolveBoolean(
                request.invocationParameters(),
                LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED,
                true,
                true
        );

        List<ToolSpecification> specifications = ToolCatalogBuilder.buildSpecifications(
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                listMyDataTool,
                loadToolGuideTool,
                rereadToolHandler,
                webSearchEnabled,
                codeInterpreterEnabled
        );
        if (PHASE_SUB_AGENT.equals(AgentContext.getPhase())) {
            // 子代理阶段禁止再生成子代理：模型可见目录和 Router 运行时检查同时关掉这两个工具。
            specifications = specifications.stream()
                    .filter(spec -> !"spawnSubAgent".equals(spec.name())
                            && !"waitForSubAgent".equals(spec.name()))
                    .collect(Collectors.toCollection(java.util.ArrayList::new));
        }

        ToolExecutor executor = new ToolRouterToolExecutor(
                toolRouter, objectMapper, agentEventService, toolThrottle, pythonSandboxDispatchStore);
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (ToolSpecification specification : specifications) {
            tools.put(specification, executor);
        }
        return ToolProviderResult.builder()
                .addAll(tools)
                .build();
    }

    /**
     * 声明为动态工具目录。
     *
     * <p>每次 AiService 调用前都会重新进入 {@link #provideTools}，
     * 这样 run 级开关（webSearch/codeInterpreter）和当前 AgentContext（runId/userId）
     * 才能实时生效，而不是进程启动时固定一份工具列表。</p>
     */
    @Override
    public boolean isDynamic() {
        return true;
    }

    /**
     * 解析布尔开关，三层优先级：InvocationParameters > AgentContext > 默认值。
     *
     * <p>设计意图：单元测试或无上下文调用时直接使用 invocation parameters；
     * 生产环境有 runId 时回退到 AgentContext 的开关值，参数未透传时也能跟随运行
     * 上下文获取当前值。</p>
     */
    private static boolean resolveBoolean(InvocationParameters parameters,
                                            String key,
                                            boolean contextFallback,
                                            boolean defaultValue) {
        if (parameters != null && parameters.containsKey(key)) {
            Boolean value = parameters.get(key);
            return value != null ? value : defaultValue;
        }
        if (AgentContext.getRunId() != null) {
            return contextFallback;
        }
        return defaultValue;
    }
}
