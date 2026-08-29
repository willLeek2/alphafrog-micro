package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolSpecification;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 当前 Todo 实际执行环境的线程内快照。
 *
 * <p>控制工具只在模型的真实 tool loop 中可用，因此它不能从全局配置重新猜模型、工具目录
 * 或 run 身份。本上下文在 {@link LangchainTodoNodeExecutor} 进入/退出时成对安装和恢复，
 * spawn 时捕获不可变快照交给异步子线程。</p>
 */
public final class LangchainSubAgentExecutionContext {

    private static final ThreadLocal<Environment> CURRENT = new ThreadLocal<>();

    private LangchainSubAgentExecutionContext() {
    }

    public static Scope install(LangchainLinearWorkflowRequest request,
                                Map<String, String> datasetRefs,
                                AtomicInteger runToolCalls) {
        Environment previous = CURRENT.get();
        Environment environment = new Environment(
                copyRequest(request),
                AgentContext.captureRunContext(),
                datasetRefs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(datasetRefs)),
                runToolCalls);
        CURRENT.set(environment);
        return new Scope(previous);
    }

    public static Optional<Environment> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    private static LangchainLinearWorkflowRequest copyRequest(LangchainLinearWorkflowRequest source) {
        if (source == null) {
            return null;
        }
        List<ToolSpecification> specifications = source.getToolSpecifications() == null
                ? List.of()
                : List.copyOf(source.getToolSpecifications());
        return LangchainLinearWorkflowRequest.builder()
                .runId(source.getRunId())
                .userId(source.getUserId())
                .userGoal(source.getUserGoal())
                .dialogueContext(source.getDialogueContext())
                .model(source.getModel())
                .planningModel(source.getPlanningModel())
                .executionModel(source.getExecutionModel())
                .finalAnswerModel(source.getFinalAnswerModel())
                .toolSpecifications(specifications)
                .maxTodos(source.getMaxTodos())
                .maxToolRoundTrips(source.getMaxToolRoundTrips())
                .webSearchEnabled(source.getWebSearchEnabled())
                .codeInterpreterEnabled(source.getCodeInterpreterEnabled())
                .planningEndpointName(source.getPlanningEndpointName())
                .planningModelName(source.getPlanningModelName())
                .planningProviderOrder(source.getPlanningProviderOrder() == null
                        ? null : List.copyOf(source.getPlanningProviderOrder()))
                .build();
    }

    public record Environment(
            LangchainLinearWorkflowRequest parentRequest,
            AgentContext.ContextSnapshot runContext,
            Map<String, String> datasetRefs,
            AtomicInteger runToolCalls) {
    }

    public static final class Scope implements AutoCloseable {
        private final Environment previous;
        private boolean closed;

        private Scope(Environment previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
