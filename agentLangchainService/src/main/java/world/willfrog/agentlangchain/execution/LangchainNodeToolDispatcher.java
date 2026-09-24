package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;
import world.willfrog.agentlangchain.tools.LangchainToolInvocationKeys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 用生产环境的工具目录派发一次工具调用。
 *
 * <p>它拿的是和 LangChain4j 自动工具循环同一份目录：同一条 {@code ToolProvider}、同一个执行器，
 * 所以工具体验（事件、限流、重复调用拦截、数据引用登记、长工具身份）没有第二套实现。区别只在
 * 「谁决定什么时候执行」——这里由节点执行器在整组落库之后显式调用。</p>
 *
 * <p>会转后台的工具（{@code executePython}）在派发前先装上成员上下文，工具层据此把这次调用建成
 * 等待成员的一次后台作业；作业交出去之后工具层抛出挂起信号，这里把它翻成
 * {@link DispatchOutcome.Pending}，由调用方把派发证明写进成员行。</p>
 *
 * <p>子代理工具只交给持久父子 Run 桥接处理；无桥接、开关关闭或当前 Run 是子 Run 时直接拒绝，
 * 不让它落到旧进程内工具路由器上。</p>
 */
@Component
@Slf4j
public class LangchainNodeToolDispatcher implements NodeToolDispatcher {

    private static final List<String> SUB_AGENT_TOOL_NAMES = List.of("spawnSubAgent", "waitForSubAgent");
    /** 长工具后台任务的尝试轮次：与 {@code PythonSandboxTools} 建任务时用的值保持一致。 */
    private static final int DURABLE_ATTEMPT = 1;

    private final ObjectProvider<ToolProvider> toolProvider;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<PersistentSubAgentToolBridge> subAgentBridge;

    @Autowired
    public LangchainNodeToolDispatcher(ObjectProvider<ToolProvider> toolProvider,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<PersistentSubAgentToolBridge> subAgentBridge) {
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.subAgentBridge = subAgentBridge;
    }

    LangchainNodeToolDispatcher(ObjectProvider<ToolProvider> toolProvider, ObjectMapper objectMapper) {
        this(toolProvider, objectMapper, null);
    }

    @Override
    public DispatchOutcome dispatch(DispatchRequest request) {
        if (SUB_AGENT_TOOL_NAMES.contains(request.toolName())) {
            PersistentSubAgentToolBridge bridge = subAgentBridge == null ? null : subAgentBridge.getIfAvailable();
            if (bridge == null || !bridge.availableForRun(request.runId())) {
                return new DispatchOutcome.Failed("sub_agent_tool_not_available:" + request.toolName());
            }
            return bridge.dispatch(request);
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
        Optional<WaitGroupMemberExecutionContext.Snapshot> waitGroup = waitGroupContext(request);
        if (isAsyncTool(request.toolName()) && waitGroup.isEmpty()) {
            // 会转后台的工具必须有预先算好的外部作业身份，否则工具层建出来的作业没人认得回来。
            return new DispatchOutcome.Failed("wait_group_member_without_operation_id:" + request.toolName());
        }
        try (WaitGroupMemberExecutionContext.Scope ignored = waitGroup
                .map(WaitGroupMemberExecutionContext::install).orElse(null)) {
            String output = executor.execute(executionRequest, null);
            return new DispatchOutcome.Completed(output == null ? "" : output);
        } catch (WaitGroupMemberPendingException pending) {
            return toPending(request, pending);
        } catch (RuntimeException e) {
            if (LangchainTerminalToolErrorHandler.isTerminalSignal(e)) {
                // 取消、暂停、额度不足都是控制信号：它们要求当前 Worker 松开调用栈，
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

    /**
     * 工具层把后台作业交出去之后，把它的派发证明翻成派发结果。
     *
     * <p>证明里的外部作业身份必须与成员行上的值一致：结果接收方按这个身份找回成员，
     * 身份对不上就不写进成员行，让这个成员按失败处理。</p>
     */
    private DispatchOutcome toPending(DispatchRequest request, WaitGroupMemberPendingException pending) {
        WaitMemberDispatchProof proof = pending.getProof();
        Optional<String> expected = stableOperationId(request.toolName(), request.toolCallId(), request.segment());
        if (expected.isEmpty() || !expected.get().equals(proof.operationId())) {
            log.error("等待成员的外部作业身份与成员行上的值不一致，这条后台作业按失败处理："
                            + "member={} 期望={} 实际={}",
                    request.memberIdentity(), expected.orElse("<没有>"), proof.operationId());
            return new DispatchOutcome.Failed("wait_group_member_operation_identity_mismatch");
        }
        return new DispatchOutcome.Pending(proof.operationId(), proof.taskId(), proof.toJson(objectMapper));
    }

    /** 会转后台、需要在派发前装上成员上下文的工具。 */
    private boolean isAsyncTool(String toolName) {
        return DurableToolCallIds.ASYNC_PYTHON_TOOL.equals(toolName);
    }

    /** 这次派发对应的成员上下文；不是转后台的工具或算不出身份时返回空。 */
    private Optional<WaitGroupMemberExecutionContext.Snapshot> waitGroupContext(DispatchRequest request) {
        if (!isAsyncTool(request.toolName())) {
            return Optional.empty();
        }
        return stableOperationId(request.toolName(), request.toolCallId(), request.segment())
                .map(operationId -> new WaitGroupMemberExecutionContext.Snapshot(
                        request.runId(), request.groupId(), request.memberIdentity(),
                        request.memberSeq(),
                        DurableToolCallIds.forTool(request.toolName(), request.toolCallId(), request.segment()),
                        operationId, request.segment().describe()));
    }

    @Override
    public Optional<String> stableOperationId(String toolName, String rawToolCallId, NodeWorkItemIdentity segment) {
        if (SUB_AGENT_TOOL_NAMES.contains(toolName)) {
            if (rawToolCallId == null || rawToolCallId.isBlank() || segment == null) {
                return Optional.empty();
            }
            String scope = segment.describe() + "|" + toolName + "|" + rawToolCallId;
            return Optional.of("sub-agent-tool:" + UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)));
        }
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
        return DurableToolCallIds.ASYNC_PYTHON_TOOL.equals(toolName) || SUB_AGENT_TOOL_NAMES.contains(toolName);
    }

    private ToolExecutor executorFor(String toolName) {
        ToolProvider provider = toolProvider.getIfAvailable();
        if (provider == null) {
            return null;
        }
        // 工具目录是动态的：每次派发前重新取一遍，run 级开关与当前上下文才会实时生效。
        ToolProviderResult result = provider.provideTools(toolProviderRequest());
        return result == null ? null : result.toolExecutorByName(toolName);
    }

    /**
     * 取工具目录时交给目录提供者的请求。
     *
     * <p>LangChain4j 要求请求里必须带调用上下文和一条用户消息，缺一个就直接报错。这里把当前 Run 与
     * 用户放进调用参数，目录提供者据此把上下文同步回 {@code AgentContext}（与规划阶段构建目录时
     * 用的是同一套参数名）；其余开关按目录提供者的回落规则从 {@code AgentContext} 取。</p>
     */
    private ToolProviderRequest toolProviderRequest() {
        Map<String, Object> params = new LinkedHashMap<>();
        String runId = AgentContext.getRunId();
        String userId = AgentContext.getUserId();
        if (runId != null && !runId.isBlank()) {
            params.put(LangchainToolInvocationKeys.RUN_ID, runId);
        }
        if (userId != null && !userId.isBlank()) {
            params.put(LangchainToolInvocationKeys.USER_ID, userId);
        }
        // 这条用户消息只用于满足目录请求的形式要求，不参与任何提示词；目录本身与它无关。
        UserMessage userMessage = UserMessage.from("dual_pool_node_tool_lookup");
        return ToolProviderRequest.builder()
                .userMessage(userMessage)
                .invocationContext(InvocationContext.builder()
                        .userMessage(userMessage)
                        .invocationParameters(InvocationParameters.from(params))
                        .timestampNow()
                        .build())
                .build();
    }
}
