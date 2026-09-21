package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;

import java.util.List;
import java.util.Optional;

/**
 * 用生产环境的工具目录派发一次工具调用。
 *
 * <p>它拿的是和 LangChain4j 自动工具循环同一份目录：同一条 {@code ToolProvider}、同一个执行器，
 * 所以工具体验（事件、限流、重复调用拦截、数据引用登记、长工具身份）没有第二套实现。区别只在
 * 「谁决定什么时候执行」——这里由节点执行器在整组落库之后显式调用。</p>
 *
 * <p>子代理工具在新调度器版本里不派发：模型看不到它们（执行器那一层已经从目录里滤掉），
 * 万一有别的路径把请求送进来，这里也直接拒绝，不让它落到工具路由器上。</p>
 */
@Component
@Slf4j
public class LangchainNodeToolDispatcher implements NodeToolDispatcher {

    private static final List<String> SUB_AGENT_TOOL_NAMES = List.of("spawnSubAgent", "waitForSubAgent");
    /** 长工具后台任务的尝试轮次：与 {@code PythonSandboxTools} 建任务时用的值保持一致。 */
    private static final int DURABLE_ATTEMPT = 1;

    private final ObjectProvider<ToolProvider> toolProvider;

    public LangchainNodeToolDispatcher(ObjectProvider<ToolProvider> toolProvider) {
        this.toolProvider = toolProvider;
    }

    @Override
    public DispatchOutcome dispatch(DispatchRequest request) {
        if (SUB_AGENT_TOOL_NAMES.contains(request.toolName())) {
            return new DispatchOutcome.Failed("sub_agent_tool_not_available:" + request.toolName());
        }
        if (DurableToolCallIds.ASYNC_PYTHON_TOOL.equals(request.toolName())) {
            // 后台提交要改工具层：提交沙箱任务时不能写 Run 级锚点、也不能把 Run 切成等待态，
            // 只能把外部作业身份写到成员行上。这一条与恢复接收一起在下一次提交里接通。
            return new DispatchOutcome.Failed("wait_group_async_tool_not_connected:" + request.toolName());
        }
        ToolExecutor executor = executorFor(request.toolName());
        if (executor == null) {
            return new DispatchOutcome.Failed("unknown_tool:" + request.toolName());
        }
        ToolExecutionRequest executionRequest = ToolExecutionRequest.builder()
                .id(request.toolCallId() == null || request.toolCallId().isBlank()
                        ? "waitcall-" + request.memberSeq()
                        : request.toolCallId())
                .name(request.toolName())
                .arguments(request.argumentsJson() == null || request.argumentsJson().isBlank()
                        ? "{}" : request.argumentsJson())
                .build();
        try {
            String output = executor.execute(executionRequest, null);
            return new DispatchOutcome.Completed(output == null ? "" : output);
        } catch (RuntimeException e) {
            if (LangchainTerminalToolErrorHandler.isTerminalSignal(e)) {
                // 取消、暂停、额度不足、后台挂起都是控制信号：它们要求当前 Worker 松开调用栈，
                // 不能在这里变成一次普通的工具失败文本。
                throw e;
            }
            log.warn("新版本节点执行期间工具失败：tool={} member={} segment={}",
                    request.toolName(), request.memberSeq(), request.segment().describe(), e);
            String message = e.getMessage();
            return new DispatchOutcome.Failed(message == null || message.isBlank()
                    ? e.getClass().getSimpleName() : message);
        }
    }

    @Override
    public Optional<String> stableOperationId(String toolName, String rawToolCallId, NodeWorkItemIdentity segment) {
        if (!DurableToolCallIds.ASYNC_PYTHON_TOOL.equals(toolName)
                || rawToolCallId == null || rawToolCallId.isBlank()) {
            return Optional.empty();
        }
        String durableCallId = DurableToolCallIds.forTool(toolName, rawToolCallId, segment);
        return Optional.of(new DataAnalysisOperationIdentity(segment.runId(), durableCallId, DURABLE_ATTEMPT)
                .operationId());
    }

    @Override
    public boolean requiresStableOperationId(String toolName) {
        return DurableToolCallIds.ASYNC_PYTHON_TOOL.equals(toolName);
    }

    private ToolExecutor executorFor(String toolName) {
        ToolProvider provider = toolProvider.getIfAvailable();
        if (provider == null) {
            return null;
        }
        // 工具目录是动态的：每次派发前重新取一遍，run 级开关与当前上下文才会实时生效。
        ToolProviderResult result = provider.provideTools(ToolProviderRequest.builder().build());
        return result == null ? null : result.toolExecutorByName(toolName);
    }
}
