package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.agent.platform.exception.RunInterruptedException;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.wait.MemberCompletionRequest;
import world.willfrog.agent.platform.wait.MemberCompletionResult;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupIdentity;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDraft;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.wait.WaitSuspensionRequest;
import world.willfrog.agent.platform.wait.WaitSuspensionResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureModelRegistry;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePolicy;
import world.willfrog.agentlangchain.acceptance.FixtureCallIdentity;
import world.willfrog.agentlangchain.acceptance.FixtureRuleHitStore;
import world.willfrog.agentlangchain.prompt.ToolCapabilityPromptRenderer;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 新调度器版本（DUAL_POOL_V2）的节点分段执行器：LINEAR 与 DAG 共用同一份模型与工具循环。
 *
 * <p>一次领取只做一件事：发一次模型请求。回复里没有工具请求，这个节点就到此结束；有工具请求，
 * 就先把整组请求、检查点和下一条等待分段原子写进数据库，再逐个派发。派发完成后本线程立刻交还
 * 节点执行名额，不在原分段里继续跑模型——下一位 Worker 会按同一份检查点把会话接回来。</p>
 *
 * <p>它和旧版本执行路径最要紧的差别在这里：旧路径是 LangChain4j 的自动工具循环，框架拿到回复就直接
 * 执行工具；那时外部副作用已经发生，而「这次调用属于哪个节点、哪一段、第几个成员」还没有落库。
 * 新路径把顺序倒过来——先存整组，再执行工具。</p>
 *
 * <p>结果按成员原始序号接回：模型回复里的工具请求顺序就是成员序号顺序，恢复时按号读回并逐条拼成
 * 工具结果消息，所以「结果数量、身份和顺序与原模型回复一致」不依赖完成先后。</p>
 */
@Component
@Slf4j
public class DualPoolWaitGroupNodeExecutor {

    private static final String KIND_TODO = "TODO";
    /** 子代理工具在模型可见目录里的名字。 */
    private static final List<String> SUB_AGENT_TOOL_NAMES = List.of("spawnSubAgent", "waitForSubAgent");
    /** 模型没有给出工具调用身份时，工具结果消息用的占位名字；只在本组内配对使用。 */
    private static final String SYNTHETIC_CALL_ID_PREFIX = "waitcall-";
    /**
     * 夹具点名的动作没有落到成员身上（这条成员没有进入等待）：这一条按失败收场，原因就是这个码。
     *
     * <p>派发之前已经按工具名拦过一类（点名的成员不是会转后台的工具）；这个是最后一道，
     * 覆盖「工具在本次调用里当场出结果、或者被额度与控制信号中止」这些没有进入等待的情况。</p>
     */
    private static final String RULE_ACTION_NOT_APPLIED_CODE = "acceptance_fixture_rule_action_not_applied";

    private final AgentPromptService promptService;
    @Autowired
    private RootTreeCallBudget rootTreeCallBudget;
    private final LangchainRunExecutionGuard executionGuard;
    private final WaitGroupStore waitGroupStore;
    private final NodeToolDispatcher toolDispatcher;
    private final ObjectProvider<PersistentSubAgentToolBridge> subAgentBridge;
    private final PlatformTransactionManager transactionManager;
    private final ResumedSegmentPublisher resumedSegmentPublisher;
    private final ObjectMapper objectMapper;
    /** 放行策略的规则命中记录：一条规则真的打中了哪一条成员，终态核对着它点名。 */
    private final FixtureRuleHitStore ruleHitStore;
    /** 等待组成员上限按组读取：这个值允许在运行期改，改完只影响之后新建的等待组。 */
    private final DualPoolSchedulerSettings settings;
    private final int maxMemberResultChars;
    private final long memberPollDelayMs;

    @Autowired
    public DualPoolWaitGroupNodeExecutor(AgentPromptService promptService,
                                         LangchainRunExecutionGuard executionGuard,
                                         WaitGroupStore waitGroupStore,
                                         NodeToolDispatcher toolDispatcher,
                                         ObjectProvider<PersistentSubAgentToolBridge> subAgentBridge,
                                         PlatformTransactionManager transactionManager,
                                         ResumedSegmentPublisher resumedSegmentPublisher,
                                         ObjectMapper objectMapper,
                                         DualPoolSchedulerSettings settings,
                                         @Value("${agent.langchain.dual-pool.wait-group.max-member-result-chars:1048576}")
                                         int maxMemberResultChars,
                                         @Value("${agent.langchain.dual-pool.wait-group.member-poll-delay-ms:2000}")
                                         long memberPollDelayMs,
                                         FrozenEffectiveSettings frozenEffectiveSettings,
                                         FixtureRuleHitStore ruleHitStore) {
        this.promptService = promptService;
        this.executionGuard = executionGuard;
        this.waitGroupStore = waitGroupStore;
        this.toolDispatcher = toolDispatcher;
        this.subAgentBridge = subAgentBridge;
        this.transactionManager = transactionManager;
        this.resumedSegmentPublisher = resumedSegmentPublisher;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.ruleHitStore = ruleHitStore;
        this.maxMemberResultChars = Math.max(1, maxMemberResultChars);
        this.memberPollDelayMs = Math.max(1L, memberPollDelayMs);
        // 登记归一化之后真正在用的值，读数才不会报出一个这里根本没有采用的数。
        String component = "DualPoolWaitGroupNodeExecutor";
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_WAIT_GROUP_MAX_MEMBER_RESULT_CHARS,
                component, this.maxMemberResultChars);
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_WAIT_GROUP_MEMBER_POLL_DELAY_MS,
                component, this.memberPollDelayMs);
    }

    /** 旧调用点的构造方式只用于不含持久子代理的节点测试。 */
    DualPoolWaitGroupNodeExecutor(AgentPromptService promptService,
                                  LangchainRunExecutionGuard executionGuard,
                                  WaitGroupStore waitGroupStore,
                                  NodeToolDispatcher toolDispatcher,
                                  ResumedSegmentPublisher resumedSegmentPublisher,
                                  ObjectMapper objectMapper,
                                  DualPoolSchedulerSettings settings,
                                  int maxMemberResultChars,
                                  long memberPollDelayMs,
                                  FrozenEffectiveSettings frozenEffectiveSettings,
                                  FixtureRuleHitStore ruleHitStore) {
        this(promptService, executionGuard, waitGroupStore, toolDispatcher, null, null,
                resumedSegmentPublisher, objectMapper, settings, maxMemberResultChars, memberPollDelayMs,
                frozenEffectiveSettings, ruleHitStore);
    }

    /**
     * 成员刚转后台时的第一次查询时间。
     *
     * <p>库里的待查索引只收录「执行中且写了下次查询时间」的成员，所以派发成功时必须写一个时间，
     * 否则这个成员永远不会被结果接收方扫到。</p>
     */
    private OffsetDateTime firstPollAt() {
        return OffsetDateTime.now().plusNanos(memberPollDelayMs * 1_000_000L);
    }

    /**
     * 一次分段执行的输入。
     *
     * @param identity       当前执行分段的五字段身份
     * @param versions       当前分段上的四类版本
     * @param claimant       当前分段的领取者
     * @param request        这条 Run 的模型、工具目录与用户目标
     * @param todo           这一段所属的逻辑节点
     * @param completedTodos 走到这个节点之前已经完成的节点
     * @param datasetRefs    跨节点数据引用
     * @param payload        当前分段载荷（首段由协调回合写入，后续段由上一次挂起写入）
     */
    public record SegmentExecution(NodeWorkItemIdentity identity,
                                   NodeWorkItemVersions versions,
                                   String claimant,
                                   LangchainWorkflowRequest request,
                                   TodoItem todo,
                                   List<LangchainCompletedTodo> completedTodos,
                                   Map<String, String> datasetRefs,
                                   JsonNode payload) {

        public SegmentExecution {
            if (identity == null || versions == null || request == null || todo == null) {
                throw new IllegalArgumentException("分段执行必须带身份、版本、请求与节点");
            }
            completedTodos = completedTodos == null ? List.of() : List.copyOf(completedTodos);
            datasetRefs = datasetRefs == null ? Map.of() : Map.copyOf(datasetRefs);
            payload = payload == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    : payload;
        }
    }

    /** 一次分段执行的结果。 */
    public sealed interface Outcome {

        /** 这个节点完成了：调用方把这份分段结果按四类版本条件提交。 */
        record Completed(Map<String, Object> resultPatch) implements Outcome {
        }

        /** 这一段把整组工具交了出去：分段已经结束，节点还没完成。 */
        record Suspended(long groupId, int modelTurn, int nextSegmentSequence, int memberCount)
                implements Outcome {
        }

        /** 这一段已经不在调用方手里：一行都没写，调用方什么都不要做。 */
        record NotOwned(String detail) implements Outcome {
        }
    }

    /** 跑完一个分段。抛出的是控制信号与基础设施异常，业务失败都走 {@link Outcome}。 */
    public Outcome executeSegment(SegmentExecution input) {
        NodeSegmentCheckpoint stored = NodeSegmentCheckpoint.read(input.payload());
        List<ChatMessage> messages = new ArrayList<>();
        NodeSegmentCheckpoint checkpoint;
        if (stored == null) {
            messages.addAll(initialMessages(input));
            checkpoint = new NodeSegmentCheckpoint(0, null, messages, 0);
        } else {
            messages.addAll(stored.messages());
            if (stored.resumeGroupTurn() != null) {
                appendMemberResults(messages, input, stored.resumeGroupTurn());
            }
            checkpoint = stored;
        }
        ensureRunnable(input.request());
        AiMessage reply;
        try {
            reply = chatOnce(input, messages, checkpoint.modelTurn());
        } catch (RunBudgetException budget) {
            // 额度耗尽发生在挂起之前：这一段还没有交出去，直接按失败结果提交，
            // 由 Run 协调侧按既有语义收尾（与旧执行路径把额度失败写成节点失败结果一致）。
            return new Outcome.Completed(failurePatch(input, checkpoint,
                    "run_budget_exceeded", LangchainTodoNodeExecutor.extractBudgetFailureMetadata(budget)));
        }
        List<ToolExecutionRequest> calls = toolRequests(reply);
        if (calls.isEmpty()) {
            return new Outcome.Completed(turnResultPatch(input, checkpoint, reply.text()));
        }
        return suspendForToolCalls(input, checkpoint, messages, reply, calls);
    }

    // ==================== 一次模型回合 ====================

    private List<ChatMessage> initialMessages(SegmentExecution input) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(promptService.reactSystemPrompt()));
        List<ToolSpecification> visible = visibleToolSpecifications(input.identity().runId(),
                input.request().getToolSpecifications());
        messages.add(UserMessage.from(LangchainTodoUserMessageBuilder.buildTodoUserMessage(
                promptService,
                input.request().getUserGoal(),
                input.completedTodos(),
                input.datasetRefs(),
                input.todo().getDescription(),
                visible,
                ToolCapabilityPromptRenderer.render(promptService, visible))));
        return messages;
    }

    private AiMessage chatOnce(SegmentExecution input, List<ChatMessage> messages, int modelTurn) {
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(visibleToolSpecifications(input.identity().runId(),
                        input.request().getToolSpecifications()))
                .build();
        // 这次调用的身份：哪条 Run、哪个计划代际、哪个节点、第几次尝试、哪一段、段内第几次模型回合。
        // 夹具按这个身份发脚本回合，所以并行跑的几个节点各拿各的回复，重启后重做同一段也拿回同一份。
        // 不带夹具编号的 Run 走的是真实模型，这一步原样返回，不影响它。
        NodeWorkItemIdentity identity = input.identity();
        if (rootTreeCallBudget != null) {
            rootTreeCallBudget.beforeModelCall(identity, Integer.toString(modelTurn));
        }
        ChatModel model = AcceptanceFixtureModelRegistry.forCall(
                input.request().executionModelOrDefault(),
                () -> FixtureCallIdentity.nodeSegment(identity.runId(), identity.planGeneration(),
                        identity.nodeId(), identity.nodeAttempt(), identity.segmentSequence(), modelTurn));
        ChatResponse response = model.chat(LangchainTodoNodeExecutor.maybeInjectLastMileHint(chatRequest));
        AiMessage reply = response == null ? null : response.aiMessage();
        if (reply == null) {
            throw new IllegalStateException("模型没有返回回复，无法继续这个节点：" + input.identity().describe());
        }
        return reply;
    }

    /**
     * 交给模型的工具目录。
     *
     * <p>父 Run 只有在持久子代理桥接确认开关和身份均允许时才看见入口；子 Run 不得再生子 Run。
     * 派发层独立复核，防止伪造的模型请求绕过目录。</p>
     */
    private List<ToolSpecification> visibleToolSpecifications(String runId,
                                                              List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return List.of();
        }
        PersistentSubAgentToolBridge bridge = subAgentBridge == null ? null : subAgentBridge.getIfAvailable();
        if (bridge != null && bridge.availableForRun(runId)) {
            return List.copyOf(specifications);
        }
        return specifications.stream()
                .filter(specification -> !SUB_AGENT_TOOL_NAMES.contains(specification.name()))
                .toList();
    }

    private static List<ToolExecutionRequest> toolRequests(AiMessage reply) {
        List<ToolExecutionRequest> requests = reply.toolExecutionRequests();
        return requests == null ? List.of() : requests;
    }

    // ==================== 接回上一次的成员结果 ====================

    private void appendMemberResults(List<ChatMessage> messages, SegmentExecution input, int groupTurn) {
        WaitGroupIdentity groupIdentity = new WaitGroupIdentity(
                previousSegmentIdentity(input.identity()), groupTurn);
        WaitGroup group = waitGroupStore.findGroup(groupIdentity)
                .orElseThrow(() -> new IllegalStateException(
                        "恢复分段找不到等待组：" + input.identity().describe() + " turn=" + groupTurn));
        List<WaitMember> members = waitGroupStore.listMembers(group.getId());
        if (members.isEmpty()) {
            throw new IllegalStateException("等待组里没有成员：" + group.getId());
        }
        for (WaitMember member : members) {
            if (!member.terminal()) {
                throw new IllegalStateException("等待组还没齐备，这一段不该被领取：group=" + group.getId()
                        + " member=" + member.getMemberIdentity() + " state=" + member.getState());
            }
            messages.add(ToolExecutionResultMessage.from(
                    messageToolCallId(member),
                    member.getToolName(),
                    WaitMemberResultPayload.modelText(member.getResultRefJson(), objectMapper)));
        }
    }

    private static NodeWorkItemIdentity previousSegmentIdentity(NodeWorkItemIdentity identity) {
        if (identity.segmentSequence() <= 0) {
            throw new IllegalStateException("第零段没有上一段，不该要求接回等待组：" + identity.describe());
        }
        return new NodeWorkItemIdentity(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence() - 1);
    }

    /** 工具结果消息与助手消息配对用的名字：模型给了身份就用它，没给就按原始序号补一个。 */
    static String messageToolCallId(WaitMember member) {
        String toolCallId = member.getToolCallId();
        if (toolCallId != null && !toolCallId.isBlank()) {
            return toolCallId;
        }
        return SYNTHETIC_CALL_ID_PREFIX + member.getMemberSeq();
    }

    // ==================== 整组落库与派发 ====================

    private Outcome suspendForToolCalls(SegmentExecution input,
                                        NodeSegmentCheckpoint checkpoint,
                                        List<ChatMessage> messages,
                                        AiMessage reply,
                                        List<ToolExecutionRequest> calls) {
        int maxMembers = settings.waitGroupMaxMembers().intValue();
        if (calls.size() > maxMembers) {
            return new Outcome.Completed(failurePatch(input, checkpoint,
                    "wait_group_member_limit_exceeded:" + calls.size() + "/" + maxMembers, null));
        }
        List<WaitMemberDraft> drafts = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            ToolExecutionRequest call = calls.get(index);
            String rawToolCallId = call.id() == null || call.id().isBlank() ? null : call.id();
            if (rawToolCallId == null && toolDispatcher.requiresStableOperationId(call.name())) {
                // 转后台的工具没有稳定身份就没法在进程退出后找回同一个外部作业，
                // 与其存一组没人认得出来的成员，不如让这个节点按失败收场。
                return new Outcome.Completed(failurePatch(input, checkpoint,
                        "wait_group_async_tool_without_call_id:" + call.name(), null));
            }
            String operationId = rawToolCallId == null ? null
                    : toolDispatcher.stableOperationId(call.name(), rawToolCallId, input.identity()).orElse(null);
            drafts.add(new WaitMemberDraft(index, rawToolCallId, call.name(), operationId));
        }
        int modelTurn = checkpoint.modelTurn();
        AcceptanceReleasePolicy policy = input.request().getAcceptanceReleasePolicy();
        List<AcceptanceReleasePolicy.MemberFacts> facts = memberFacts(input, modelTurn, drafts, calls);
        AcceptanceReleasePolicy.RuleMatches policyMatches = matchPolicyRules(policy, facts);
        String unwaitable = unwaitableTargetReason(policy, policyMatches, drafts);
        if (unwaitable != null) {
            return new Outcome.Completed(failurePatch(input, checkpoint, unwaitable, null));
        }
        int nextSegmentSequence = input.identity().segmentSequence() + 1;
        int nodeToolCalls = checkpoint.toolCallsUsed() + drafts.size();
        List<ChatMessage> history = new ArrayList<>(messages);
        history.add(withSyntheticCallIds(reply, calls));

        NodeSegmentCheckpoint nextCheckpoint = new NodeSegmentCheckpoint(
                checkpoint.modelTurn() + 1, checkpoint.modelTurn(), history, nodeToolCalls);
        String suspensionPayloadJson = json(Map.of(WaitGroupSuspensionMarker.FIELD,
                new WaitGroupSuspensionMarker(checkpoint.modelTurn(), nextSegmentSequence, drafts.size())
                        .toJson(objectMapper)));
        String nextSegmentPayloadJson = json(nextSegmentPayload(input, nextCheckpoint));

        WaitSuspensionRequest suspension = new WaitSuspensionRequest(
                input.identity(),
                input.versions(),
                input.claimant(),
                checkpoint.modelTurn(),
                SchedulerVersion.DUAL_POOL_V2,
                drafts,
                suspensionPayloadJson,
                nextSegmentPayloadJson);
        SuspensionReservation reservation = suspendAndReserveSubAgents(input, calls, suspension);
        if (!reservation.suspended().suspended()) {
            return new Outcome.NotOwned("segment_not_matched:" + input.identity().describe());
        }
        long groupId = reservation.suspended().groupId();
        policyMatches = recordPolicyMatches(input.identity().runId(), policy, policyMatches, groupId);
        Long notificationId = keepNotification(reservation.notificationId(),
                dispatchMembers(groupId, input, modelTurn, policy, policyMatches, calls));
        if (notificationId != null) {
            boolean published = resumedSegmentPublisher.publish(notificationId,
                    input.versions().runControlVersion(),
                    new NodeWorkItemIdentity(input.identity().runId(), input.identity().planGeneration(),
                            input.identity().nodeId(), input.identity().nodeAttempt(), nextSegmentSequence));
            if (!published) {
                log.warn("整组已经齐备但没有立刻放行下一段，留给周期补扫处理：groupId={} segment={}",
                        groupId, input.identity().describe());
            }
        }
        return new Outcome.Suspended(groupId, checkpoint.modelTurn(), nextSegmentSequence, drafts.size());
    }

    private record SuspensionReservation(WaitSuspensionResult suspended, Long notificationId) {
    }

    /** 创建意图、等待请求与父节点挂起在同一事务中落库，进程退出后不会只留下其中一半。 */
    private SuspensionReservation suspendAndReserveSubAgents(SegmentExecution input,
                                                              List<ToolExecutionRequest> calls,
                                                              WaitSuspensionRequest suspension) {
        boolean hasSubAgentCall = calls.stream().anyMatch(call -> SUB_AGENT_TOOL_NAMES.contains(call.name()));
        PersistentSubAgentToolBridge bridge = subAgentBridge == null ? null : subAgentBridge.getIfAvailable();
        if (!hasSubAgentCall || bridge == null || !bridge.availableForRun(input.identity().runId())) {
            return new SuspensionReservation(waitGroupStore.suspendSegment(suspension), null);
        }
        if (transactionManager == null) {
            throw new IllegalStateException("子代理创建需要数据库事务，不能只挂起父等待组");
        }
        SuspensionReservation result = new TransactionTemplate(transactionManager).execute(status -> {
            WaitSuspensionResult suspended = waitGroupStore.suspendSegment(suspension);
            if (!suspended.suspended()) {
                return new SuspensionReservation(suspended, null);
            }
            Long notificationId = null;
            for (WaitMember member : waitGroupStore.listMembers(suspended.groupId())) {
                if (member.terminal() || !SUB_AGENT_TOOL_NAMES.contains(member.getToolName())) {
                    continue;
                }
                ToolExecutionRequest call = locateCall(member, calls);
                if (call == null) {
                    throw new IllegalStateException("已挂起的子代理成员找不到原始工具请求："
                            + member.getMemberIdentity());
                }
                if (member.getExternalOperationId() == null || member.getExternalOperationId().isBlank()) {
                    throw new IllegalStateException("子代理成员缺少持久操作身份：" + member.getMemberIdentity());
                }
                try {
                    if (rootTreeCallBudget != null) {
                        // 创建意图会在提交后由 outbox 真正建子 Run；额度必须先于意图在同一事务确认。
                        rootTreeCallBudget.beforeToolCall(input.identity().runId(),
                                suspended.groupId(), member.getMemberIdentity());
                    }
                } catch (RunBudgetException budget) {
                    String resultJson = WaitMemberResultPayload.encode(objectMapper, member.getToolName(),
                            member.getToolCallId(), false, "",
                            Map.of("errorCode", "run_budget_exceeded",
                                    "errorDetail", "当前运行预算已用尽，无法继续调用工具。请根据已有结果回答；信息不足时说明无法完成。"),
                            maxMemberResultChars);
                    MemberCompletionResult completed = waitGroupStore.completeMember(new MemberCompletionRequest(
                            suspended.groupId(), member.getMemberIdentity(), WaitMemberState.FAILED,
                            resultJson, member.getExternalOperationId(), input.identity().planGeneration(),
                            input.versions().contextVersion(), input.versions().runControlVersion()));
                    notificationId = keepNotification(notificationId, completed.notificationId());
                    continue;
                }
                PersistentSubAgentToolBridge.ReservationRequest request =
                        new PersistentSubAgentToolBridge.ReservationRequest(
                                input.identity().runId(), input.identity(), input.versions(),
                                suspended.groupId(), member.getMemberSeq(), member.getMemberIdentity(),
                                member.getToolCallId(), member.getExternalOperationId(), call.arguments());
                PersistentSubAgentToolBridge.ReservationOutcome outcome =
                        "spawnSubAgent".equals(member.getToolName())
                                ? bridge.reserveSpawn(request) : bridge.reserveWait(request);
                if (outcome == null) {
                    throw new IllegalStateException("子代理预留没有返回结果：" + member.getMemberIdentity());
                }
                if (outcome != PersistentSubAgentToolBridge.ReservationOutcome.RESERVED) {
                    String errorCode = switch (outcome) {
                        case LIMIT_EXCEEDED -> "sub_agent_limit_exceeded";
                        case INVALID_REQUEST -> "sub_agent_invalid_request";
                        case NOT_FOUND -> "sub_agent_not_found";
                        case RESERVED -> throw new IllegalStateException("已预留不能写失败结果");
                    };
                    String resultJson = WaitMemberResultPayload.encode(objectMapper, member.getToolName(),
                            member.getToolCallId(), false, "", Map.of("errorCode", errorCode),
                            maxMemberResultChars);
                    MemberCompletionResult completed = waitGroupStore.completeMember(new MemberCompletionRequest(
                            suspended.groupId(), member.getMemberIdentity(), WaitMemberState.FAILED,
                            resultJson, member.getExternalOperationId(), input.identity().planGeneration(),
                            input.versions().contextVersion(), input.versions().runControlVersion()));
                    notificationId = keepNotification(notificationId, completed.notificationId());
                }
            }
            return new SuspensionReservation(suspended, notificationId);
        });
        if (result == null) {
            throw new IllegalStateException("子代理挂起事务没有返回结果");
        }
        return result;
    }

    /**
     * 这一批草稿在策略眼里的样子。
     *
     * <p>等待组一级的身份来自分段身份与这一段的模型回合，成员一级的身份来自草稿的组内序号、模型给的
     * 工具调用编号，以及派发时的外部作业身份与工具名。参数正文给 {@code uniqueExternalTask} 的
     * {@code codeContains} 用，结果接收方没有这份正文，按派发时已经记下的命中找回。</p>
     */
    private static List<AcceptanceReleasePolicy.MemberFacts> memberFacts(SegmentExecution input,
                                                                        int modelTurn,
                                                                        List<WaitMemberDraft> drafts,
                                                                        List<ToolExecutionRequest> calls) {
        NodeWorkItemIdentity identity = input.identity();
        List<AcceptanceReleasePolicy.MemberFacts> facts = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            WaitMemberDraft draft = drafts.get(index);
            String argumentText = index < calls.size() ? calls.get(index).arguments() : null;
            facts.add(new AcceptanceReleasePolicy.MemberFacts(identity.planGeneration(), identity.nodeId(),
                    identity.nodeAttempt(), identity.segmentSequence(), modelTurn,
                    draft.getMemberSeq(), draft.getToolCallId(),
                    draft.getExternalOperationId(), draft.getToolName(), argumentText));
        }
        return List.copyOf(facts);
    }

    /** 一条已落库的成员在策略眼里的样子：派发与收尾两处都用它，免得两边凑的字段不一样。 */
    private static AcceptanceReleasePolicy.MemberFacts memberFacts(SegmentExecution input,
                                                                  int modelTurn,
                                                                  WaitMember member,
                                                                  String argumentText) {
        NodeWorkItemIdentity identity = input.identity();
        return new AcceptanceReleasePolicy.MemberFacts(identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), modelTurn,
                member.getMemberSeq(), member.getToolCallId(),
                member.getExternalOperationId(), member.getToolName(), argumentText);
    }

    /**
     * 派发之前把策略核一遍：这一批里每条规则打中了谁，点名的兄弟成员在不在这一批里。
     *
     * <p>一个等待组的成员就是这一次模型回合里那几个工具调用，组建出来之后就定死了；规则点名了一个
     * 不在这一批里的成员，这条等待永远等不到头（被压住的成员除了兜底时限没人会来放行）。所以核对放在
     * 建组与派发之前：一个外部作业都还没建出来，夹具写错了当场停住，原因就是夹具自己的稳定错误码。</p>
     *
     * <p>选择器同时打中两条成员、两条规则点名同一条成员，这两类由策略匹配当场拒绝：点名对象
     * 不确定，压住/放行/判失败的是谁就说不清。{@code allExternalTasks} 是例外，一条规则可以打中
     * 这一批里多条外部作业。等自己、组内绕成圈这两类要看这一批到底有哪几条成员，所以在这里判：
     * 绕成圈的那几条成员会一直压着，除了兜底时限没有别的出路。</p>
     */
    private AcceptanceReleasePolicy.RuleMatches matchPolicyRules(
            AcceptanceReleasePolicy policy,
            List<AcceptanceReleasePolicy.MemberFacts> facts) {
        if (policy == null) {
            return null;
        }
        AcceptanceReleasePolicy.RuleMatches matches = policy.match(facts);
        policy.rejectCyclesInGroup(facts, matches);
        return matches;
    }

    /**
     * 这一批里命中的规则各记一笔「打中了谁」。
     *
     * <p>规则只写「点名某一次调用」时，字段名或序号写错不会报错，只会一条成员都打不中，那次验收看
     * 起来像跑完了。所以命中要落库，跑到终态时按它核对。派发前与结果接收方都会记，重复记只留一行。
     * 这里记的是「选择器打中了谁」；被点名的动作有没有真的落到这条成员身上，是动作真的发生时才记的
     * （压住、等兄弟成员、按失败收尾各自在自己的那一刻写）。</p>
     *
     * <p>{@code uniqueExternalTask} 打中一个已经绑过别人的目标时，这一条从返回的命中里拿掉：
     * 后面 {@link #completeMember} 不会再按这条规则改写它的业务结果。越界记在已经绑定的那一行上。
     * {@code allExternalTasks} 的其余成员仍留在命中里，动作按内存生效。建组派发前能拦住的写错
     * （点名组外成员、压住当场出结果的工具）仍然直接抛，那时还没有沙箱结果可销毁。</p>
     */
    private AcceptanceReleasePolicy.RuleMatches recordPolicyMatches(String runId,
                                                                   AcceptanceReleasePolicy policy,
                                                                   AcceptanceReleasePolicy.RuleMatches matches,
                                                                   long groupId) {
        if (policy == null || matches == null) {
            return matches;
        }
        Map<Integer, List<AcceptanceReleasePolicy.MemberFacts>> kept = new LinkedHashMap<>();
        for (AcceptanceReleasePolicy.Rule rule : policy.rules()) {
            List<AcceptanceReleasePolicy.MemberFacts> keptTargets = new ArrayList<>();
            for (AcceptanceReleasePolicy.MemberFacts target : matches.targetsOf(rule.index())) {
                FixtureRuleHitStore.MatchBinding binding =
                        ruleHitStore.recordMatch(runId, rule, groupId, target);
                if (binding == FixtureRuleHitStore.MatchBinding.ALREADY_BOUND_OTHER) {
                    if (AcceptanceReleasePolicy.MATCH_ALL_EXTERNAL_TASKS.equals(rule.match())) {
                        // 命中表按规则序号只留第一行；其余成员的压住仍按内存里的命中生效。
                        keptTargets.add(target);
                        continue;
                    }
                    AcceptanceReleasePolicy.MemberFacts bound = ruleHitStore.hitOf(runId, rule.index())
                            .map(FixtureRuleHitStore.RuleHit::target)
                            .orElse(null);
                    ruleHitStore.recordOverHit(runId, rule, target, bound);
                    continue;
                }
                keptTargets.add(target);
            }
            if (!keptTargets.isEmpty()) {
                kept.put(rule.index(), keptTargets);
            }
        }
        return new AcceptanceReleasePolicy.RuleMatches(kept);
    }

    /**
     * 点名的动作在这一批里做不做得到：做不到就在派发之前停下。
     *
     * <p>「压住等放行点」与「等兄弟成员先落终态」这两种动作，前提是这条成员的结果以后才回来
     * ——它会转后台、留在执行中。点名的成员如果是一个当场就出结果的工具，这两种动作没有可等的东西，
     * 系统只能把它照原样收成终态；那时命中的那一行已经在库里，终态核对会以为「压住过」「等过」，
     * 一次本来没跑出目标控制流的验收会显示证据完整。所以这一类配置在派发之前就拒绝：一个外部作业
     * 都还没建出来，夹具写错了当场停住，原因里写清是哪条规则、哪一条成员、什么工具。</p>
     *
     * @return 空表示这一批做得到；有值时是拒绝的原因
     */
    private String unwaitableTargetReason(AcceptanceReleasePolicy policy,
                                          AcceptanceReleasePolicy.RuleMatches matches,
                                          List<WaitMemberDraft> drafts) {
        if (policy == null || matches == null) {
            return null;
        }
        for (AcceptanceReleasePolicy.Rule rule : policy.rules()) {
            if (!rule.holds() && !rule.waitsForPeers()) {
                continue;
            }
            for (AcceptanceReleasePolicy.MemberFacts target : matches.targetsOf(rule.index())) {
                String toolName = toolNameOf(drafts, target.memberSeq());
                if (toolName == null || toolDispatcher.requiresStableOperationId(toolName)) {
                    // 会转后台的工具就是那个需要稳定外部作业身份的工具（见 NodeToolDispatcher）：
                    // 它的结果以后才回来，压住与等兄弟成员有可等的东西。
                    continue;
                }
                return "acceptance_fixture_rule_needs_waiting_member:" + rule.index() + ":" + toolName;
            }
        }
        return null;
    }

    private static String toolNameOf(List<WaitMemberDraft> drafts, int memberSeq) {
        for (WaitMemberDraft draft : drafts) {
            if (draft.getMemberSeq() == memberSeq) {
                return draft.getToolName();
            }
        }
        return null;
    }

    /**
     * 逐个派发还没有结果的成员。
     *
     * <p>只动还没派发的成员：同一次模型回合被重复执行时，已经落终态或已经在执行中的成员原样保留，
     * 不会第二次调用工具。返回这一次刚好让整组齐备的那条恢复通知编号。</p>
     */
    private Long dispatchMembers(long groupId,
                                 SegmentExecution input,
                                 int modelTurn,
                                 AcceptanceReleasePolicy policy,
                                 AcceptanceReleasePolicy.RuleMatches policyMatches,
                                 List<ToolExecutionRequest> calls) {
        List<WaitMember> members = waitGroupStore.listMembers(groupId);
        Long notificationId = null;
        try {
            for (WaitMember member : members) {
                if (member.terminal() || member.stateEnum() == WaitMemberState.RUNNING) {
                    continue;
                }
                ToolExecutionRequest call = locateCall(member, calls);
                if (call == null) {
                    notificationId = keepNotification(notificationId, completeMember(input, modelTurn, policy,
                            policyMatches, member, false, "",
                            Map.of("errorCode", "wait_group_member_request_missing"), null));
                    continue;
                }
                NodeToolDispatcher.DispatchOutcome outcome = toolDispatcher.dispatch(
                        new NodeToolDispatcher.DispatchRequest(
                                input.identity().runId(), input.identity(), groupId, member.getMemberSeq(),
                                member.getMemberIdentity(), member.getToolCallId(), member.getToolName(),
                                call.arguments()));
                if (outcome instanceof NodeToolDispatcher.DispatchOutcome.Completed completed) {
                    notificationId = keepNotification(notificationId, completeMember(input, modelTurn, policy,
                            policyMatches, member, true, completed.output(), Map.of(), call.arguments()));
                } else if (outcome instanceof NodeToolDispatcher.DispatchOutcome.Failed failed) {
                    notificationId = keepNotification(notificationId, completeMember(input, modelTurn, policy,
                            policyMatches, member, false, failed.reason(), Map.of(), call.arguments()));
                } else if (outcome instanceof NodeToolDispatcher.DispatchOutcome.Pending pending) {
                    // 后台作业已经建出来了。成员从「待派发」进入「执行中」，并把派发证明与下次查询
                    // 时间写上：证明留给结果接收方收尾，查询时间让接收方能找到这个成员。
                    boolean marked = waitGroupStore.markMemberDispatched(groupId, member.getMemberIdentity(),
                            pending.operationId(), pending.dispatchProofJson(), firstPollAt(),
                            input.versions().runControlVersion());
                    if (!marked) {
                        log.warn("成员已经在别处派发过，这一次不重复标记：group={} member={} task={}",
                                groupId, member.getMemberIdentity(), pending.taskId());
                    } else {
                        log.info("等待成员已转后台：group={} member={} seq={} {}",
                                groupId, member.getMemberIdentity(), member.getMemberSeq(), pending.taskId());
                    }
                }
            }
        } catch (RunBudgetException budget) {
            // 额度耗尽：还没拿到结果的成员记成失败，让整组仍然能齐备；本段按挂起收场，
            // 下一段恢复后模型调用会再次撞上额度检查，由那一次给出节点失败结果。
            notificationId = keepNotification(notificationId,
                    abortRemainingMembers(groupId, input, modelTurn, policy, policyMatches, members,
                            "run_budget_exceeded"));
            log.warn("派发期间额度耗尽，未完成的成员按失败记：groupId={} segment={}",
                    groupId, input.identity().describe());
        } catch (RuntimeException e) {
            if (!LangchainTerminalToolErrorHandler.isTerminalSignal(e)) {
                throw e;
            }
            // 取消、暂停这一类控制信号要求当前 Worker 松开调用栈。先把没拿到结果的成员记成失败，
            // 让等待链停在「组已齐备」而不是永远等不到人；随后原样抛出，交给上层收尾。
            abortRemainingMembers(groupId, input, modelTurn, policy, policyMatches, members,
                    "member_dispatch_aborted");
            throw e;
        }
        return notificationId;
    }

    private Long abortRemainingMembers(long groupId,
                                       SegmentExecution input,
                                       int modelTurn,
                                       AcceptanceReleasePolicy policy,
                                       AcceptanceReleasePolicy.RuleMatches policyMatches,
                                       List<WaitMember> members,
                                       String errorCode) {
        Long notificationId = null;
        for (WaitMember member : members) {
            if (member.terminal() || member.stateEnum() == WaitMemberState.RUNNING) {
                continue;
            }
            Map<String, Object> failure = "run_budget_exceeded".equals(errorCode)
                    ? Map.of("errorCode", errorCode,
                            "errorDetail", "当前运行预算已用尽，无法继续调用工具。请根据已有结果回答；信息不足时说明无法完成。")
                    : Map.of("errorCode", errorCode);
            notificationId = keepNotification(notificationId, completeMember(input, modelTurn, policy,
                    policyMatches, member, false, "", failure, null));
        }
        return notificationId;
    }

    private Long keepNotification(Long current, Long candidate) {
        return candidate != null ? candidate : current;
    }

    /** 上报一个成员的终态；返回这一次刚好让整组齐备的那条恢复通知编号，没有就是空。 */
    private Long completeMember(SegmentExecution input,
                                int modelTurn,
                                AcceptanceReleasePolicy policy,
                                AcceptanceReleasePolicy.RuleMatches policyMatches,
                                WaitMember member,
                                boolean success,
                                String output,
                                Map<String, Object> extra,
                                String argumentText) {
        Optional<AcceptanceReleasePolicy.Rule> rule = policy == null || policyMatches == null
                ? Optional.empty()
                : policy.ruleAt(policyMatches, memberFacts(input, modelTurn, member, argumentText));
        Optional<String> designated = rule.filter(AcceptanceReleasePolicy.Rule::fails)
                .map(AcceptanceReleasePolicy.Rule::failureDetail);
        AcceptanceReleasePolicy.Rule namedBy = rule.orElse(null);
        AcceptanceReleasePolicy.Rule actionNotApplied = null;
        if (designated.isPresent()) {
            // 验收夹具点名这条成员按失败收尾：工具当场真的成功了也记成失败，这正是这个场景要造出来的
            // 「有一条成员失败、其余的照常」。工具的真实输出留在成员行里，失败原因单独写清楚。
            // 这条成员本来就是失败的（额度耗尽、控制信号中止、工具自己报错）时不覆盖真实原因：
            // 覆盖掉会让排查的人以为是夹具把它弄失败的，夹具那句另记一处。
            extra = new LinkedHashMap<>(extra);
            if (success) {
                success = false;
                extra.put("errorCode", AcceptanceReleasePolicy.DESIGNATED_FAILURE_CODE);
                extra.put("errorDetail", designated.get());
            } else {
                extra.put("designatedFailure", designated.get());
            }
        } else if (namedBy != null) {
            // 压住结果与等兄弟成员先落终态这两种规则，针对的是「结果以后才回来」的成员。这条成员在
            // 本次调用里没有进入等待（当场出结果，或者被额度、控制信号按失败中止），那两条动作没有
            // 可作用的对象。派发之前已经按工具名拦过一次，这里是最后一道：动作没有落到它身上，就不
            // 能在终态核对里算成「压住过」或者「等过」，所以这一条按夹具自己的错误码收场，并把
            // 「动作没有生效」写进证据。
            actionNotApplied = namedBy;
            extra = new LinkedHashMap<>(extra);
            String detail = "放行策略 " + namedBy.describe() + " 点名的这条成员没有进入等待"
                    + "（当场出结果，或者已经按失败中止），压住与等兄弟成员没有可等的东西";
            if (success) {
                success = false;
                extra.put("errorCode", RULE_ACTION_NOT_APPLIED_CODE);
                extra.put("errorDetail", detail);
            } else {
                extra.put("ruleActionNotApplied", detail);
            }
        }
        String resultJson = WaitMemberResultPayload.encode(objectMapper, member.getToolName(),
                member.getToolCallId(), success, output, extra, maxMemberResultChars);
        MemberCompletionResult result = persistMemberCompletion(new MemberCompletionRequest(
                member.getGroupId(),
                member.getMemberIdentity(),
                success ? WaitMemberState.SUCCEEDED : WaitMemberState.FAILED,
                resultJson,
                member.getExternalOperationId(),
                input.identity().planGeneration(),
                input.versions().contextVersion(),
                input.versions().runControlVersion()), member);
        if (!result.applied()) {
            log.info("成员结果没有写进去（重复上报或已落终态）：group={} member={}",
                    member.getGroupId(), member.getMemberIdentity());
            return result.notificationId();
        }
        recordMemberAction(input, member, designated.isPresent() ? namedBy : null, actionNotApplied);
        return result.notificationId();
    }

    /**
     * 先按工具原文写入；写成 jsonb 失败时改用短失败载荷再写一次，让等待组仍能齐备。
     */
    private MemberCompletionResult persistMemberCompletion(MemberCompletionRequest request, WaitMember member) {
        try {
            return waitGroupStore.completeMember(request);
        } catch (RuntimeException e) {
            log.error("成员结果没能写入等待组，改用短失败载荷再写一次：group={} member={}",
                    member.getGroupId(), member.getMemberIdentity(), e);
            String compact = WaitMemberResultPayload.compactPersistFailure(
                    objectMapper, member.getToolName(), member.getToolCallId(), e.getMessage());
            MemberCompletionRequest fallback = new MemberCompletionRequest(
                    request.groupId(),
                    request.memberIdentity(),
                    WaitMemberState.FAILED,
                    compact,
                    request.externalOperationId(),
                    request.planGeneration(),
                    request.contextVersion(),
                    request.runControlVersion());
            try {
                return waitGroupStore.completeMember(fallback);
            } catch (RuntimeException retry) {
                e.addSuppressed(retry);
                throw e;
            }
        }
    }

    /**
     * 这条成员的终态落定之后，把「被点名的动作有没有落到它身上」写进证据。
     *
     * <p>两种：指定失败的动作（终态按夹具说的落成失败）算生效；压住与等兄弟成员这两种在这条路上
     * 没有可作用的对象，算没有生效。压住与等兄弟成员真正生效的那一刻在结果接收方（成员真的被留在
     * 执行中、等放行点或者等兄弟成员），那里写的是另外两种结果。</p>
     */
    private void recordMemberAction(SegmentExecution input,
                                    WaitMember member,
                                    AcceptanceReleasePolicy.Rule designatedRule,
                                    AcceptanceReleasePolicy.Rule notAppliedRule) {
        String runId = input.identity().runId();
        if (designatedRule != null) {
            ruleHitStore.recordAction(runId, designatedRule.index(),
                    FixtureRuleHitStore.APPLIED_FAILURE, "成员按夹具点名的原因收成失败");
        }
        if (notAppliedRule != null) {
            ruleHitStore.recordAction(runId, notAppliedRule.index(), FixtureRuleHitStore.NOT_APPLIED,
                    "这条成员（" + member.getMemberIdentity() + "，组内第 " + member.getMemberSeq()
                            + " 条）没有进入等待，压住与等兄弟成员没有可等的东西");
        }
    }

    /** 在这一次模型回合的请求里找回某个成员对应的那条工具请求。 */
    private ToolExecutionRequest locateCall(WaitMember member, List<ToolExecutionRequest> calls) {
        String toolCallId = member.getToolCallId();
        if (toolCallId != null && !toolCallId.isBlank()) {
            for (ToolExecutionRequest call : calls) {
                if (toolCallId.equals(call.id())) {
                    return call;
                }
            }
            return null;
        }
        Integer memberSeq = member.getMemberSeq();
        return memberSeq != null && memberSeq >= 0 && memberSeq < calls.size() ? calls.get(memberSeq) : null;
    }

    /**
     * 模型没有给出工具调用身份时，给助手消息里的这条请求补一个只在本组内使用的名字。
     *
     * <p>补名字只影响消息配对：等待成员的稳定身份仍然按「组 + 原始序号」派生（见
     * {@code WaitMemberIdentity}），不会因为这个占位名字变成一个「有身份的调用」。</p>
     */
    private AiMessage withSyntheticCallIds(AiMessage reply, List<ToolExecutionRequest> calls) {
        boolean synthetic = false;
        for (int index = 0; index < calls.size(); index++) {
            String id = calls.get(index).id();
            if (id == null || id.isBlank()) {
                synthetic = true;
                break;
            }
        }
        if (!synthetic) {
            return reply;
        }
        List<ToolExecutionRequest> rewritten = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            ToolExecutionRequest call = calls.get(index);
            String id = call.id();
            if (id != null && !id.isBlank()) {
                rewritten.add(call);
            } else {
                rewritten.add(ToolExecutionRequest.builder()
                        .id(SYNTHETIC_CALL_ID_PREFIX + index)
                        .name(call.name())
                        .arguments(call.arguments())
                        .build());
            }
        }
        return AiMessage.builder()
                .text(reply.text())
                .toolExecutionRequests(rewritten)
                .build();
    }

    // ==================== 分段结果与载荷 ====================

    private Map<String, Object> nextSegmentPayload(SegmentExecution input, NodeSegmentCheckpoint checkpoint) {
        Map<String, Object> next = objectMapper.convertValue(input.payload(),
                new TypeReference<Map<String, Object>>() { });
        next.put("kind", KIND_TODO);
        // 挂起标记只属于刚结束的那一段，下一段不许带上它，否则协调侧会一直把这一段当成挂起。
        next.remove(WaitGroupSuspensionMarker.FIELD);
        next.put(NodeSegmentCheckpoint.FIELD, checkpoint.toJson(objectMapper));
        return next;
    }

    private Map<String, Object> turnResultPatch(SegmentExecution input,
                                                NodeSegmentCheckpoint checkpoint,
                                                String text) {
        String output = text == null ? "" : text.trim();
        if (output.isEmpty()) {
            return failurePatch(input, checkpoint, "empty_todo_output:" + input.todo().getId(), null);
        }
        return basePatch(true, output, output, "", Map.of(), checkpoint, input);
    }

    private Map<String, Object> failurePatch(SegmentExecution input,
                                             NodeSegmentCheckpoint checkpoint,
                                             String reason,
                                             Map<String, Object> failureMetadata) {
        return basePatch(false, "", reason, reason,
                failureMetadata == null ? Map.of() : failureMetadata, checkpoint, input);
    }

    private Map<String, Object> basePatch(boolean success,
                                          String output,
                                          String summary,
                                          String failureReason,
                                          Map<String, Object> failureMetadata,
                                          NodeSegmentCheckpoint checkpoint,
                                          SegmentExecution input) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("kind", KIND_TODO);
        patch.put("success", success);
        patch.put("output", output);
        patch.put("summary", summary);
        patch.put("failureReason", failureReason);
        patch.put("failureMetadata", failureMetadata);
        // toolCallsUsed 记本节点累计（前面各段加这一段），cumulativeToolCallsUsed 记 Run 级累计。
        // 挂起的那几段只有挂起标记、没有结果，所以按行累加不会重复计数。
        patch.put("toolCallsUsed", checkpoint.toolCallsUsed());
        patch.put("cumulativeToolCallsUsed",
                Math.max(0, input.payload().path("toolCallsUsed").asInt(0)) + checkpoint.toolCallsUsed());
        return patch;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("分段载荷无法编码", e);
        }
    }

    private void ensureRunnable(LangchainWorkflowRequest request) {
        if (request == null) {
            return;
        }
        Optional<String> stop = executionGuard.stopReason(request.getRunId(), request.getUserId());
        if (stop.isPresent()) {
            throw new RunInterruptedException(stop.get());
        }
    }
}
