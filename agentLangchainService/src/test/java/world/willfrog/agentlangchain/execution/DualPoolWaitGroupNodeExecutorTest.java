package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.wait.MemberCompletionRequest;
import world.willfrog.agent.platform.wait.WaitGroupIdentity;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;
import world.willfrog.agentlangchain.control.dualpool.TestSchedulerSettings;

/**
 * 共用节点执行器的行为自检：一次领取只发一次模型请求；有工具请求就先落整组再派发；同步结果当场写终态；
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

    private final AgentPromptService promptService = mock(AgentPromptService.class);
    private final LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private InMemoryWaitGroupStore store;
    private ScriptedModel model;
    private ScriptedDispatcher dispatcher;
    private RecordingPublisher publisher;
    private DualPoolWaitGroupNodeExecutor executor;

    @BeforeEach
    void setUp() {
        store = new InMemoryWaitGroupStore();
        model = new ScriptedModel();
        dispatcher = new ScriptedDispatcher();
        publisher = new RecordingPublisher(store);
        executor = new DualPoolWaitGroupNodeExecutor(promptService, guard, store, dispatcher, publisher,
                objectMapper, TestSchedulerSettings.propertyOnly(
                        "agent.langchain.dual-pool.wait-group.max-members", "16"),
                1024 * 1024, 2000L, new FrozenEffectiveSettings());
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        when(promptService.reactSystemPrompt()).thenReturn("系统提示");
        when(promptService.dagReactStageInstruction(any())).thenReturn("阶段说明");
        when(promptService.dynamicContextPrefix()).thenReturn("上下文");
        when(promptService.renderToolCapabilities(any())).thenReturn("工具能力");
    }

    // ==================== 完成与失败 ====================

    @Test
    void aReplyWithoutToolRequestsCompletesTheNode() {
        model.enqueue(AiMessage.from("  结论如下  "));
        DualPoolWaitGroupNodeExecutor.Outcome outcome = executor.executeSegment(firstSegment(List.of()));

        assertThat(outcome).isInstanceOfSatisfying(DualPoolWaitGroupNodeExecutor.Outcome.Completed.class,
                completed -> {
                    assertThat(completed.resultPatch()).containsEntry("success", true);
                    assertThat(completed.resultPatch()).containsEntry("output", "结论如下");
                    assertThat(completed.resultPatch()).containsEntry("summary", "结论如下");
                    // 本段没有执行工具；载荷里协调回合给的 Run 级累计是 2。
                    assertThat(completed.resultPatch()).containsEntry("toolCallsUsed", 0);
                    assertThat(completed.resultPatch()).containsEntry("cumulativeToolCallsUsed", 2);
                });
        assertThat(store.events()).as("没有工具请求就不该动等待组").isEmpty();
    }

    @Test
    void aBlankReplyFailsTheNodeWithEmptyOutput() {
        model.enqueue(AiMessage.from("   "));
        DualPoolWaitGroupNodeExecutor.Outcome outcome = executor.executeSegment(firstSegment(List.of()));

        assertThat(outcome).isInstanceOfSatisfying(DualPoolWaitGroupNodeExecutor.Outcome.Completed.class,
                completed -> {
                    assertThat(completed.resultPatch()).containsEntry("success", false);
                    assertThat(completed.resultPatch()).containsEntry("failureReason",
                            "empty_todo_output:" + NODE_ID);
                });
    }

    // ==================== 先落库、再派发 ====================

    @Test
    void theWholeGroupIsPersistedBeforeTheFirstToolRuns() {
        model.enqueue(AiMessage.from(List.of(
                toolCall("call-a", "getStockDaily", "{\"asset\":\"000001.SZ\"}"),
                toolCall("call-b", "searchWeb", "{\"q\":\"测试\"}"))));
        dispatcher.outputs.put("getStockDaily", "日线数据");
        dispatcher.outputs.put("searchWeb", "搜索结果");
        List<List<String>> eventsWhenDispatchStarts = new ArrayList<>();
        dispatcher.beforeDispatch = () -> eventsWhenDispatchStarts.add(store.events());

        DualPoolWaitGroupNodeExecutor.Outcome outcome = executor.executeSegment(firstSegment(List.of()));

        assertThat(outcome).isInstanceOfSatisfying(DualPoolWaitGroupNodeExecutor.Outcome.Suspended.class,
                suspended -> {
                    assertThat(suspended.memberCount()).isEqualTo(2);
                    assertThat(suspended.nextSegmentSequence()).isEqualTo(1);
                });
        List<String> firstDispatch = eventsWhenDispatchStarts.get(0);
        assertThat(firstDispatch).as("派发之前整组已经落库")
                .anyMatch(event -> event.startsWith("segment_closed:"));
        assertThat(firstDispatch).as("派发之前没有任何成员结果写进去")
                .noneMatch(event -> event.startsWith("member_done:"));
        assertThat(dispatcher.dispatched)
                .as("按成员原始序号逐个派发，工具名与身份一一对应")
                .extracting(NodeToolDispatcher.DispatchRequest::memberSeq,
                        NodeToolDispatcher.DispatchRequest::toolCallId,
                        NodeToolDispatcher.DispatchRequest::toolName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "call-a", "getStockDaily"),
                        org.assertj.core.groups.Tuple.tuple(1, "call-b", "searchWeb"));
    }

    // ==================== 同步成员当场结束 ====================

    @Test
    void synchronousMembersFinishInPlaceAndTheNextSegmentIsPublished() {
        model.enqueue(AiMessage.from(List.of(
                toolCall("call-a", "getStockDaily", "{}"),
                toolCall("call-b", "searchWeb", "{}"),
                toolCall("call-c", "getIndexDaily", "{}"))));

        DualPoolWaitGroupNodeExecutor.Outcome outcome = executor.executeSegment(firstSegment(List.of()));

        long groupId = ((DualPoolWaitGroupNodeExecutor.Outcome.Suspended) outcome).groupId();
        assertThat(store.memberRows(groupId)).as("三个成员都落了终态")
                .extracting(row -> row.state)
                .containsExactly(WaitMemberState.SUCCEEDED.name(),
                        WaitMemberState.SUCCEEDED.name(), WaitMemberState.SUCCEEDED.name());
        assertThat(store.events()).as("计数加满写出恢复通知").anyMatch(event -> event.startsWith("group_ready:"));
        assertThat(publisher.published).as("组齐备之后立刻放行下一段").hasSize(1);
        assertThat(publisher.published.get(0).segment().segmentSequence()).isEqualTo(1);
        assertThat(store.promoted(new WaitGroupIdentity(segmentIdentity(1), 0)))
                .as("等待组被消费成已交接，下一段放成可恢复").isTrue();
    }

    // ==================== 结果按原始序号接回 ====================

    @Test
    void resumedSegmentReadsMemberResultsInOriginalOrder() {
        // 第一段：三个工具，结果乱序到达（先 2、再 0、最后 1）。
        model.enqueue(AiMessage.from(List.of(
                toolCall("call-a", "getStockDaily", "{}"),
                toolCall("call-b", "searchWeb", "{}"),
                toolCall("call-c", "getIndexDaily", "{}"))));
        dispatcher.pending.put("getStockDaily", new NodeToolDispatcher.DispatchOutcome.Pending(
                RUN_ID + ":call-a:1", "task-a",
                dispatchProof(RUN_ID + ":call-a:1", "task-a")));
        dispatcher.pending.put("searchWeb", new NodeToolDispatcher.DispatchOutcome.Pending(
                RUN_ID + ":call-b:1", "task-b",
                dispatchProof(RUN_ID + ":call-b:1", "task-b")));
        dispatcher.pending.put("getIndexDaily", new NodeToolDispatcher.DispatchOutcome.Pending(
                RUN_ID + ":call-c:1", "task-c",
                dispatchProof(RUN_ID + ":call-c:1", "task-c")));
        DualPoolWaitGroupNodeExecutor.Outcome first = executor.executeSegment(firstSegment(List.of()));
        long groupId = ((DualPoolWaitGroupNodeExecutor.Outcome.Suspended) first).groupId();
        assertThat(store.memberRows(groupId)).extracting(row -> row.state)
                .as("交给后台的成员还没结束").containsExactly(WaitMemberState.RUNNING.name(),
                        WaitMemberState.RUNNING.name(), WaitMemberState.RUNNING.name());

        // 后台结果乱序回来：第 3 个、第 1 个、第 2 个。
        completeMember(groupId, "call-c", "第三个结果");
        completeMember(groupId, "call-a", "第一个结果");
        Long notificationId = completeMember(groupId, "call-b", "第二个结果");
        assertThat(notificationId).as("最后一个成员让整组齐备").isNotNull();
        store.consumeRecovery(notificationId, "test-dispatcher", 0L, "test-owner", 1L);

        JsonNode nextPayload = nextPayload(0, 0);
        model.enqueue(AiMessage.from("汇总完成"));
        DualPoolWaitGroupNodeExecutor.Outcome second = executor.executeSegment(
                segment(segmentIdentity(1), nextPayload));

        assertThat(second).isInstanceOfSatisfying(DualPoolWaitGroupNodeExecutor.Outcome.Completed.class,
                completed -> {
                    assertThat(completed.resultPatch()).containsEntry("output", "汇总完成");
                    assertThat(completed.resultPatch()).containsEntry("toolCallsUsed", 3);
                    assertThat(completed.resultPatch()).containsEntry("cumulativeToolCallsUsed", 5);
                });
        List<ChatMessage> messages = model.lastRequest();
        assertThat(messages).as("系统 + 用户 + 助手 + 三条工具结果").hasSize(6);
        assertThat(messages.subList(3, 6)).allSatisfy(message ->
                assertThat(message).isInstanceOf(ToolExecutionResultMessage.class));
        assertThat(messages.subList(3, 6)).extracting(message ->
                        ((ToolExecutionResultMessage) message).id())
                .as("按成员原始序号接回，与完成先后无关")
                .containsExactly("call-a", "call-b", "call-c");
        assertThat(messages.subList(3, 6)).extracting(message ->
                        ((ToolExecutionResultMessage) message).text())
                .containsExactly("第一个结果", "第二个结果", "第三个结果");
    }

    @Test
    void consecutiveWaitGroupsAdvanceTheSegmentSequence() {
        model.enqueue(AiMessage.from(List.of(toolCall("call-a", "getStockDaily", "{}"))));
        executor.executeSegment(firstSegment(List.of()));
        JsonNode nextPayload = nextPayload(0, 0);
        // 第二段又把另一个工具请求交给后台，于是产生第二个等待组。
        model.enqueue(AiMessage.from(List.of(toolCall("call-b", "searchWeb", "{}"))));
        dispatcher.pending.put("searchWeb", new NodeToolDispatcher.DispatchOutcome.Pending(
                RUN_ID + ":call-b:1", "task-b",
                dispatchProof(RUN_ID + ":call-b:1", "task-b")));

        DualPoolWaitGroupNodeExecutor.Outcome second = executor.executeSegment(
                segment(segmentIdentity(1), nextPayload));

        assertThat(second).isInstanceOfSatisfying(DualPoolWaitGroupNodeExecutor.Outcome.Suspended.class,
                suspended -> {
                    assertThat(suspended.modelTurn()).as("第二个等待组来自第二次模型回复").isEqualTo(1);
                    assertThat(suspended.nextSegmentSequence()).isEqualTo(2);
                });
        assertThat(store.memberRows(((DualPoolWaitGroupNodeExecutor.Outcome.Suspended) second).groupId()))
                .extracting(row -> row.toolCallId).containsExactly("call-b");
        JsonNode thirdPayload = nextPayload(1, 1);
        assertThat(thirdPayload.path(NodeSegmentCheckpoint.FIELD).path("toolCallsUsed").asInt())
                .as("两段加起来一共派发过两次工具").isEqualTo(2);
    }

    // ==================== 派发之前就把不该写的挡下来 ====================

    @Test
    void tooManyMembersFailTheNodeBeforeAnythingIsWritten() {
        List<ToolExecutionRequest> calls = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            calls.add(toolCall("call-" + index, "getStockDaily", "{}"));
        }
        model.enqueue(AiMessage.from(calls));

        DualPoolWaitGroupNodeExecutor.Outcome outcome = executor.executeSegment(firstSegment(List.of()));

        assertThat(outcome).isInstanceOfSatisfying(DualPoolWaitGroupNodeExecutor.Outcome.Completed.class,
                completed -> assertThat(completed.resultPatch().get("failureReason").toString())
                        .startsWith("wait_group_member_limit_exceeded"));
        assertThat(store.events()).isEmpty();
        assertThat(dispatcher.dispatched).isEmpty();
    }

    @Test
    void anAsyncToolWithoutCallIdFailsClosedInsteadOfPersistingAStranger() {
        dispatcher.requiresOperationId = true;
        model.enqueue(AiMessage.from(List.of(ToolExecutionRequest.builder()
                .name("executePython").arguments("{}").build())));

        DualPoolWaitGroupNodeExecutor.Outcome outcome = executor.executeSegment(firstSegment(List.of()));

        assertThat(outcome).isInstanceOfSatisfying(DualPoolWaitGroupNodeExecutor.Outcome.Completed.class,
                completed -> assertThat(completed.resultPatch().get("failureReason").toString())
                        .startsWith("wait_group_async_tool_without_call_id"));
        assertThat(store.events()).isEmpty();
    }

    @Test
    void aSegmentThatIsNoLongerOursWritesNothing() {
        store.expectedClaimEpoch = 99;
        model.enqueue(AiMessage.from(List.of(toolCall("call-a", "getStockDaily", "{}"))));

        DualPoolWaitGroupNodeExecutor.Outcome outcome = executor.executeSegment(firstSegment(List.of()));

        assertThat(outcome).isInstanceOfSatisfying(DualPoolWaitGroupNodeExecutor.Outcome.NotOwned.class,
                notOwned -> assertThat(notOwned.detail()).startsWith("segment_not_matched"));
        assertThat(dispatcher.dispatched).as("分段不归自己所有时一个工具都不许派发").isEmpty();
        assertThat(publisher.published).isEmpty();
    }

    // ==================== 后台成员与工具目录 ====================

    @Test
    void anAsyncMemberIsMarkedDispatchedAndTheGroupKeepsWaiting() {
        model.enqueue(AiMessage.from(List.of(
                toolCall("call-a", "getStockDaily", "{}"),
                toolCall("call-b", "executePython", "{}"))));
        dispatcher.outputs.put("getStockDaily", "日线数据");
        dispatcher.pending.put("executePython", new NodeToolDispatcher.DispatchOutcome.Pending(
                RUN_ID + ":call-b:1", "task-b",
                dispatchProof(RUN_ID + ":call-b:1", "task-b")));

        DualPoolWaitGroupNodeExecutor.Outcome outcome = executor.executeSegment(firstSegment(List.of()));

        long groupId = ((DualPoolWaitGroupNodeExecutor.Outcome.Suspended) outcome).groupId();
        assertThat(store.memberRows(groupId)).extracting(row -> row.state)
                .containsExactly(WaitMemberState.SUCCEEDED.name(), WaitMemberState.RUNNING.name());
        assertThat(store.memberRows(groupId).get(1).externalOperationId).isEqualTo(RUN_ID + ":call-b:1");
        assertThat(store.memberRows(groupId).get(1).dispatchProofJson)
                .as("派发证明原样写进成员行，结果接收方靠它收尾")
                .isEqualTo(dispatchProof(RUN_ID + ":call-b:1", "task-b"));
        assertThat(store.memberRows(groupId).get(1).nextPollAt)
                .as("转后台的成员必须写下一次查询时间，否则接收方扫不到它")
                .isNotNull();
        assertThat(publisher.published).as("还有一个成员没结束，不能放行下一段").isEmpty();
    }

    @Test
    void aMemberThatReportsAnotherOperationIdentityKeepsThePersistedOne() {
        model.enqueue(AiMessage.from(List.of(toolCall("call-b", "executePython", "{}"))));
        dispatcher.requiresOperationId = true;
        // 派发层报回一个别的身份：成员行上的身份不能被换掉，写入因此不生效。
        dispatcher.pending.put("executePython", new NodeToolDispatcher.DispatchOutcome.Pending(
                RUN_ID + ":someone-else:1", "task-b",
                dispatchProof(RUN_ID + ":someone-else:1", "task-b")));

        executor.executeSegment(firstSegment(List.of()));

        long groupId = store.groupRows().get(0).id;
        assertThat(store.memberRows(groupId).get(0).externalOperationId)
                .as("成员行上的外部作业身份保持整组落库时写下的值")
                .isEqualTo(RUN_ID + ":call-b:1");
        assertThat(store.memberRows(groupId).get(0).dispatchProofJson)
                .as("身份对不上时不写派发证明").isNull();
        assertThat(store.memberRows(groupId).get(0).state)
                .as("没写进去就还是待派发状态，由上层按未派发处理").isEqualTo(WaitMemberState.PENDING.name());
    }

    @Test
    void theSubAgentToolsAreNotOfferedToTheModel() {
        model.enqueue(AiMessage.from("直接回答"));
        List<ToolSpecification> specifications = List.of(
                ToolSpecification.builder().name("getStockDaily").description("日线").build(),
                ToolSpecification.builder().name("spawnSubAgent").description("子代理").build(),
                ToolSpecification.builder().name("waitForSubAgent").description("等子代理").build());

        executor.executeSegment(firstSegment(specifications));

        assertThat(model.lastSpecifications()).extracting(ToolSpecification::name)
                .containsExactly("getStockDaily");
    }

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
                output, Map.of(), 1024 * 1024);
        return store.completeMember(new MemberCompletionRequest(groupId, toolCallId,
                WaitMemberState.SUCCEEDED, resultJson, null, GENERATION, 0L, 0L)).notificationId();
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

    private DualPoolWaitGroupNodeExecutor.SegmentExecution segment(NodeWorkItemIdentity identity, JsonNode payload) {
        return segment(identity, payload, List.of());
    }

    private DualPoolWaitGroupNodeExecutor.SegmentExecution segment(NodeWorkItemIdentity identity,
                                                                  JsonNode payload,
                                                                  List<ToolSpecification> specifications) {
        return new DualPoolWaitGroupNodeExecutor.SegmentExecution(
                identity,
                new NodeWorkItemVersions(0L, 0L, 1),
                CLAIMANT,
                LangchainWorkflowRequest.builder()
                        .runId(RUN_ID)
                        .userId("user-1")
                        .userGoal("把这件事做完")
                        .executionModel(model)
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
            return ChatResponse.builder().aiMessage(reply).build();
        }
    }

    /** 脚本化派发器：按工具名给结果，并记录每一次派发。 */
    static final class ScriptedDispatcher implements NodeToolDispatcher {

        final Map<String, String> outputs = new LinkedHashMap<>();
        final Map<String, DispatchOutcome> pending = new LinkedHashMap<>();
        final List<DispatchRequest> dispatched = new ArrayList<>();
        boolean requiresOperationId;
        Runnable beforeDispatch = () -> { };

        @Override
        public DispatchOutcome dispatch(DispatchRequest request) {
            beforeDispatch.run();
            dispatched.add(request);
            DispatchOutcome outcome = pending.get(request.toolName());
            if (outcome != null) {
                return outcome;
            }
            return new DispatchOutcome.Completed(
                    outputs.getOrDefault(request.toolName(), "结果:" + request.toolName()));
        }

        @Override
        public Optional<String> stableOperationId(String toolName, String rawToolCallId,
                                                  NodeWorkItemIdentity segment) {
            if (!requiresOperationId || !"executePython".equals(toolName)) {
                return Optional.empty();
            }
            return Optional.of(segment.runId() + ":" + rawToolCallId + ":1");
        }

        @Override
        public boolean requiresStableOperationId(String toolName) {
            return requiresOperationId && "executePython".equals(toolName);
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
