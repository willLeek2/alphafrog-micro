package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.agent.platform.exception.RunInterruptedException;
import world.willfrog.agent.platform.dataanalysis.PythonRiskReplayEvidenceMissingException;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.wait.MemberCompletionRequest;
import world.willfrog.agent.platform.wait.WaitGroupIdentity;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.acceptance.FixtureCallIdentity;
import world.willfrog.agentlangchain.acceptance.FixtureCallStore;
import world.willfrog.agentlangchain.acceptance.FrozenModelScript;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePolicy;
import world.willfrog.agentlangchain.acceptance.FixtureRuleHitStore;
import world.willfrog.agentlangchain.acceptance.ScriptedChatModel;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.PythonRiskReviewService;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.agent.tools.compaction.ToolOutputCompactionService;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.router.ToolResultCacheService;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.tools.ToolRouterToolExecutor;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.sandbox.idl.AcquireWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceResult;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doAnswer;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;
import world.willfrog.agentlangchain.control.dualpool.TestSchedulerSettings;

/**
 * 共用节点执行器的行为自检：正常领取只发一次模型请求，未执行的准入错误允许有限修正；
 * 有合法工具请求就先落整组再派发；同步结果当场写终态；
 * 恢复时按成员原始序号把结果接回；连续多个等待组的分段序号依次加一。
 *
 * <p>这里用的是内存版存储与脚本化模型，证明的是执行器自己的顺序与配对规则。真库上的并发条件更新、
 * 唯一约束与锁顺序不在本用例的范围内。</p>
 */
class DualPoolWaitGroupNodeExecutorTest {

    private static final String RUN_ID = "run-stage3";
    private static final String NODE_ID = "todo_1";
    private static final int GENERATION = 3;
    private static final String CLAIMANT = "worker-1";

    private final world.willfrog.agentlangchain.support.LangchainTestFixtures.VersionedNodeInputs promptFixture =
            new world.willfrog.agentlangchain.support.LangchainTestFixtures.VersionedNodeInputs();
    private final AgentPromptService promptService = promptFixture.prompts();
    private final LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
    private final FixtureRuleHitStore ruleHits = mock(FixtureRuleHitStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private InMemoryWaitGroupStore store;
    private ScriptedModel model;
    private ScriptedDispatcher dispatcher;
    private RecordingPublisher publisher;
    private DualPoolWaitGroupNodeExecutor executor;

    @BeforeEach
    void setUp() {
        AgentContext.setRunId(RUN_ID);
        AgentContext.setUserId("user-1");
        store = new InMemoryWaitGroupStore();
        model = new ScriptedModel();
        dispatcher = new ScriptedDispatcher();
        publisher = new RecordingPublisher(store);
        executor = promptFixture.configureDualPool(new DualPoolWaitGroupNodeExecutor(promptService, guard, store, dispatcher, publisher,
                objectMapper, TestSchedulerSettings.propertyOnly(
                        "agent.langchain.dual-pool.wait-group.max-members", "16"),
                1024 * 1024, 2000L, new FrozenEffectiveSettings(), ruleHits));
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() { AgentContext.clear(); }

    // ==================== 完成与失败 ====================





    // ==================== 先落库、再派发 ====================


    // ==================== 同步成员当场结束 ====================













    // ==================== 结果按原始序号接回 ====================



    // ==================== 派发之前就把不该写的挡下来 ====================









    // ==================== 后台成员与工具目录 ====================








    // ==================== 测试脚手架 ====================

    /**
     * 造一份派发证明正文。
     *
     * <p>用真证明类型序列化出来，而不是手写一段 JSON：这样「执行器把证明原样写进成员行」这件事
     * 在类型这一层就成立，将来证明加字段也不会让这段用例悄悄失去意义。</p>
     */
    private String dispatchProof(String operationId, String taskId) {
        return new world.willfrog.agent.platform.wait.WaitMemberDispatchProof(
                world.willfrog.agent.platform.wait.WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION,
                operationId,
                taskId,
                "sha256:tests",
                "{\"schemaVersion\":\"sandbox_create_v1\"}",
                "{\"estimatedRows\":1}",
                "{\"reservationId\":\"" + operationId + "\"}",
                "2026-09-22T00:00:00Z")
                .toJson(objectMapper);
    }

    /** 读出某一次挂起写给下一段的载荷：它就是下一位 Worker 领取这一段时看到的东西。 */
    private JsonNode nextPayload(int previousSegment, int groupTurn) {
        String json = store.nextSegmentPayload(new WaitGroupIdentity(segmentIdentity(previousSegment), groupTurn));
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("下一段载荷无法解析", e);
        }
    }

    private Long completeMember(long groupId, String toolCallId, String output) {
        String resultJson = WaitMemberResultPayload.encode(objectMapper, toolCallId, toolCallId, true,
                output, world.willfrog.agent.platform.input.PromptMessageCodec.sourceAttributes(
                        world.willfrog.agent.platform.input.PromptMessage.Origin.RUNTIME_DATA, List.of()), 1024 * 1024);
        return store.completeMember(new MemberCompletionRequest(groupId, toolCallId,
                WaitMemberState.SUCCEEDED, resultJson, null, GENERATION, 0L, 0L)).notificationId();
    }



    private ScriptedChatModel scriptedModel(String scriptJson, FixtureCallStore calls) {
        FrozenModelScript script = FrozenModelScript.parse("fx-1", scriptJson, objectMapper);
        // 这个替身里的夹具内容不会变：每次调用前重读拿到的就是同一份，摘要与建对象时一致。
        return new ScriptedChatModel(RUN_ID, "fx-1", "scenario-a", script, calls,
                () -> new ScriptedChatModel.FixtureScript("fx-1", "scenario-a", script));
    }

    /** 一次调用在策略眼里的身份：与执行器凑出来的字段一致（计划代际、节点、尝试、段、模型回合、序号）。 */
    private static AcceptanceReleasePolicy.MemberFacts factsOf(int segmentSequence,
                                                              int memberSeq,
                                                              String toolCallId) {
        return new AcceptanceReleasePolicy.MemberFacts(GENERATION, NODE_ID, 0, segmentSequence, 0,
                memberSeq, toolCallId);
    }

    private static ToolExecutionRequest toolCall(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private static NodeWorkItemIdentity segmentIdentity(int segmentSequence) {
        return new NodeWorkItemIdentity(RUN_ID, GENERATION, NODE_ID, 0, segmentSequence);
    }

    private DualPoolWaitGroupNodeExecutor.SegmentExecution firstSegment(List<ToolSpecification> specifications) {
        return segment(segmentIdentity(0), startPayload(), specifications);
    }

    private DualPoolWaitGroupNodeExecutor.SegmentExecution firstSegment(
            List<ToolSpecification> specifications,
            AcceptanceReleasePolicy releasePolicy) {
        return segment(segmentIdentity(0), startPayload(), specifications, releasePolicy);
    }

    private DualPoolWaitGroupNodeExecutor.SegmentExecution segment(NodeWorkItemIdentity identity, JsonNode payload) {
        return segment(identity, payload, List.of());
    }

    private DualPoolWaitGroupNodeExecutor.SegmentExecution segment(NodeWorkItemIdentity identity,
                                                                  JsonNode payload,
                                                                  List<ToolSpecification> specifications) {
        return segment(identity, payload, specifications, null);
    }

    private DualPoolWaitGroupNodeExecutor.SegmentExecution segment(
            NodeWorkItemIdentity identity,
            JsonNode payload,
            List<ToolSpecification> specifications,
            AcceptanceReleasePolicy releasePolicy,
            ChatModel segmentModel) {
        return new DualPoolWaitGroupNodeExecutor.SegmentExecution(
                identity,
                new NodeWorkItemVersions(0L, 0L, 1),
                CLAIMANT,
                LangchainWorkflowRequest.builder()
                        .runId(RUN_ID)
                        .userId("user-1")
                        .userGoal("把这件事做完")
                        .executionModel(segmentModel)
                        .executionEndpointName(world.willfrog.agentlangchain.support.LangchainTestFixtures.NODE_ENDPOINT)
                        .acceptanceReleasePolicy(releasePolicy)
                        .toolSpecifications(new ArrayList<>(specifications))
                        .build(),
                TodoItem.builder().id(NODE_ID).sequence(1).description("第一个待办").build(),
                List.of(),
                Map.of(),
                payload);
    }

    private DualPoolWaitGroupNodeExecutor.SegmentExecution segment(
            NodeWorkItemIdentity identity,
            JsonNode payload,
            List<ToolSpecification> specifications,
            AcceptanceReleasePolicy releasePolicy) {
        return new DualPoolWaitGroupNodeExecutor.SegmentExecution(
                identity,
                new NodeWorkItemVersions(0L, 0L, 1),
                CLAIMANT,
                LangchainWorkflowRequest.builder()
                        .runId(RUN_ID)
                        .userId("user-1")
                        .userGoal("把这件事做完")
                        .executionModel(world.willfrog.agentlangchain.support.LangchainTestFixtures.archivedNodeModel(model))
                        .executionEndpointName(world.willfrog.agentlangchain.support.LangchainTestFixtures.NODE_ENDPOINT)
                        .acceptanceReleasePolicy(releasePolicy)
                        .toolSpecifications(new ArrayList<>(specifications))
                        .build(),
                TodoItem.builder().id(NODE_ID).sequence(1).description("第一个待办").build(),
                List.of(),
                Map.of(),
                payload);
    }

    /** 协调回合写进首段的载荷：工具调用累计 2 次，表示这个 Run 前面的节点已经用过两次工具。 */
    private JsonNode startPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kind", "TODO");
        payload.put("workflow", "linear");
        payload.put("toolCallsUsed", 2);
        payload.set("todo", objectMapper.valueToTree(
                TodoItem.builder().id(NODE_ID).sequence(1).description("第一个待办").build()));
        payload.putArray("completedContext");
        payload.putObject("datasetRefs");
        return payload;
    }

    /** 脚本化模型：按顺序吐出准备好的回复，并记录每次请求都带了什么。 */
    static final class ScriptedModel implements ChatModel {

        private final Deque<AiMessage> replies = new ArrayDeque<>();
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private final List<List<ToolSpecification>> specifications = new ArrayList<>();
        Runnable beforeReply = () -> { };

        void enqueue(AiMessage reply) {
            replies.add(reply);
        }

        List<ChatMessage> lastRequest() {
            return requests.get(requests.size() - 1);
        }

        List<ToolSpecification> lastSpecifications() {
            return specifications.get(specifications.size() - 1);
        }

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            requests.add(List.copyOf(chatRequest.messages()));
            specifications.add(chatRequest.toolSpecifications() == null
                    ? List.of() : List.copyOf(chatRequest.toolSpecifications()));
            AiMessage reply = replies.poll();
            if (reply == null) {
                throw new IllegalStateException("脚本里的模型回复不够用了");
            }
            beforeReply.run();
            return ChatResponse.builder().aiMessage(reply).build();
        }
    }

    /** 脚本化派发器：按工具名给结果，并记录每一次派发。 */
    static final class ScriptedDispatcher implements NodeToolDispatcher {

        final Map<String, String> outputs = new LinkedHashMap<>();
        final Map<String, DispatchOutcome> pending = new LinkedHashMap<>();
        final List<DispatchRequest> dispatched = new ArrayList<>();
        boolean requiresOperationId;
        boolean subAgentOperationId;
        Runnable beforeDispatch = () -> { };

        @Override
        public DispatchOutcome dispatch(DispatchRequest request) {
            beforeDispatch.run();
            dispatched.add(request);
            DispatchOutcome outcome = pending.get(request.toolName());
            if (outcome != null) {
                return outcome;
            }
            String output = outputs.getOrDefault(request.toolName(), "结果:" + request.toolName());
            return new DispatchOutcome.Completed(output,
                    world.willfrog.alphafrogmicro.common.racing.prompt.PromptText.runtimeData(output));
        }

        @Override
        public Optional<String> stableOperationId(String toolName, String rawToolCallId,
                                                  NodeWorkItemIdentity segment) {
            if (!requiresOperationId || world.willfrog.agent.platform.dataanalysis.DurableSandboxTool.fromToolName(toolName).isEmpty()) {
                return subAgentOperationId && ("spawnSubAgent".equals(toolName)
                        || "waitForSubAgent".equals(toolName))
                        ? Optional.of("sub-agent-tool:test-" + rawToolCallId) : Optional.empty();
            }
            return Optional.of(segment.runId() + ":" + rawToolCallId + ":1");
        }

        @Override
        public boolean requiresStableOperationId(String toolName) {
            return (requiresOperationId && world.willfrog.agent.platform.dataanalysis.DurableSandboxTool.fromToolName(toolName).isPresent())
                    || (subAgentOperationId && ("spawnSubAgent".equals(toolName)
                    || "waitForSubAgent".equals(toolName)));
        }
    }

    /** 记录放行调用，并按真实实现的做法把通知消费掉。 */
    static final class RecordingPublisher implements ResumedSegmentPublisher {

        record Published(long notificationId, long runControlVersion, NodeWorkItemIdentity segment) {
        }

        final List<Published> published = new ArrayList<>();
        private final InMemoryWaitGroupStore store;

        RecordingPublisher(InMemoryWaitGroupStore store) {
            this.store = store;
        }

        @Override
        public boolean publish(long notificationId, long runControlVersion, NodeWorkItemIdentity nextSegment) {
            published.add(new Published(notificationId, runControlVersion, nextSegment));
            return store.consumeRecovery(notificationId, "test-publisher", runControlVersion,
                    "test-owner", 1L).succeeded();
        }
    }
}
