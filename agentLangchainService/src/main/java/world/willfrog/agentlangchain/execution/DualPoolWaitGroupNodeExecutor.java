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
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePolicy;
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
    /** 子代理工具在模型可见目录里的名字：新调度器版本不暴露它们。 */
    private static final List<String> SUB_AGENT_TOOL_NAMES = List.of("spawnSubAgent", "waitForSubAgent");
    /** 模型没有给出工具调用身份时，工具结果消息用的占位名字；只在本组内配对使用。 */
    private static final String SYNTHETIC_CALL_ID_PREFIX = "waitcall-";

    private final AgentPromptService promptService;
    private final LangchainRunExecutionGuard executionGuard;
    private final WaitGroupStore waitGroupStore;
    private final NodeToolDispatcher toolDispatcher;
    private final ResumedSegmentPublisher resumedSegmentPublisher;
    private final ObjectMapper objectMapper;
    /** 等待组成员上限按组读取：这个值允许在运行期改，改完只影响之后新建的等待组。 */
    private final DualPoolSchedulerSettings settings;
    private final int maxMemberResultChars;
    private final long memberPollDelayMs;

    public DualPoolWaitGroupNodeExecutor(AgentPromptService promptService,
                                         LangchainRunExecutionGuard executionGuard,
                                         WaitGroupStore waitGroupStore,
                                         NodeToolDispatcher toolDispatcher,
                                         ResumedSegmentPublisher resumedSegmentPublisher,
                                         ObjectMapper objectMapper,
                                         DualPoolSchedulerSettings settings,
                                         @Value("${agent.langchain.dual-pool.wait-group.max-member-result-chars:1048576}")
                                         int maxMemberResultChars,
                                         @Value("${agent.langchain.dual-pool.wait-group.member-poll-delay-ms:2000}")
                                         long memberPollDelayMs,
                                         FrozenEffectiveSettings frozenEffectiveSettings) {
        this.promptService = promptService;
        this.executionGuard = executionGuard;
        this.waitGroupStore = waitGroupStore;
        this.toolDispatcher = toolDispatcher;
        this.resumedSegmentPublisher = resumedSegmentPublisher;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.maxMemberResultChars = Math.max(1, maxMemberResultChars);
        this.memberPollDelayMs = Math.max(1L, memberPollDelayMs);
        // 登记归一化之后真正在用的值，读数才不会报出一个这里根本没有采用的数。
        String component = "DualPoolWaitGroupNodeExecutor";
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_WAIT_GROUP_MAX_MEMBER_RESULT_CHARS,
                component, this.maxMemberResultChars);
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_WAIT_GROUP_MEMBER_POLL_DELAY_MS,
                component, this.memberPollDelayMs);
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
            reply = chatOnce(input, messages);
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
        messages.add(UserMessage.from(LangchainTodoUserMessageBuilder.buildTodoUserMessage(
                promptService,
                input.request().getUserGoal(),
                input.completedTodos(),
                input.datasetRefs(),
                input.todo().getDescription(),
                input.request().getToolSpecifications(),
                ToolCapabilityPromptRenderer.render(promptService, input.request().getToolSpecifications()))));
        return messages;
    }

    private AiMessage chatOnce(SegmentExecution input, List<ChatMessage> messages) {
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(visibleToolSpecifications(input.request().getToolSpecifications()))
                .build();
        ChatResponse response = input.request().executionModelOrDefault()
                .chat(LangchainTodoNodeExecutor.maybeInjectLastMileHint(chatRequest));
        AiMessage reply = response == null ? null : response.aiMessage();
        if (reply == null) {
            throw new IllegalStateException("模型没有返回回复，无法继续这个节点：" + input.identity().describe());
        }
        return reply;
    }

    /**
     * 交给模型的工具目录。
     *
     * <p>新调度器版本不向模型暴露子代理工具：父子 Run 怎样共用容量与轮转顺序留给后续阶段，
     * 这一阶段先按计划把入口收起来。运行时的拒绝在派发那一层另做一次，模型绕不过去。</p>
     */
    private List<ToolSpecification> visibleToolSpecifications(List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return List.of();
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
        validatePolicyPeers(input, drafts);
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

        WaitSuspensionResult suspended = waitGroupStore.suspendSegment(new WaitSuspensionRequest(
                input.identity(),
                input.versions(),
                input.claimant(),
                checkpoint.modelTurn(),
                SchedulerVersion.DUAL_POOL_V2,
                drafts,
                suspensionPayloadJson,
                nextSegmentPayloadJson));
        if (!suspended.suspended()) {
            return new Outcome.NotOwned("segment_not_matched:" + input.identity().describe());
        }
        long groupId = suspended.groupId();
        Long notificationId = dispatchMembers(groupId, input, calls);
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

    /**
     * 派发之前先核对夹具策略点名的成员都在这一批里。
     *
     * <p>一个等待组的成员就是这一次模型回合里那几个工具调用，组建出来之后就定死了；策略点名了一个不在
     * 这一批里的成员，这条规则永远等不到头（被压住的成员除了兜底时限没人会来放行）。所以核对放在建组
     * 与派发之前：一个外部作业都还没建出来，夹具写错了当场停住，原因就是夹具自己的稳定错误码。</p>
     *
     * <p>自己等自己、几条成员绕成一圈那两类在读策略时已经拒了——它们只从策略本身就能判出来。</p>
     */
    private void validatePolicyPeers(SegmentExecution input, List<WaitMemberDraft> drafts) {
        AcceptanceReleasePolicy policy = input.request().getAcceptanceReleasePolicy();
        if (policy == null) {
            return;
        }
        Set<String> batch = new LinkedHashSet<>();
        for (WaitMemberDraft draft : drafts) {
            if (draft.getToolCallId() != null && !draft.getToolCallId().isBlank()) {
                batch.add(draft.getToolCallId());
            }
        }
        for (WaitMemberDraft draft : drafts) {
            String toolCallId = draft.getToolCallId();
            if (toolCallId == null || toolCallId.isBlank() || !policy.covers(toolCallId)) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String peer : policy.releaseAfter(toolCallId)) {
                if (!batch.contains(peer)) {
                    missing.add(peer);
                }
            }
            if (!missing.isEmpty()) {
                throw AcceptanceFixtureExecutionException.refuse("acceptance_fixture_policy_invalid",
                        "夹具策略里成员 " + toolCallId + " 要等 " + String.join("、", missing)
                                + " 先落终态，这几个名字不在这一批工具调用里（这一批："
                                + String.join("、", batch) + "）");
            }
        }
    }

    /**
     * 逐个派发还没有结果的成员。
     *
     * <p>只动还没派发的成员：同一次模型回合被重复执行时，已经落终态或已经在执行中的成员原样保留，
     * 不会第二次调用工具。返回这一次刚好让整组齐备的那条恢复通知编号。</p>
     */
    private Long dispatchMembers(long groupId, SegmentExecution input, List<ToolExecutionRequest> calls) {
        List<WaitMember> members = waitGroupStore.listMembers(groupId);
        Long notificationId = null;
        try {
            for (WaitMember member : members) {
                if (member.terminal() || member.stateEnum() == WaitMemberState.RUNNING) {
                    continue;
                }
                ToolExecutionRequest call = locateCall(member, calls);
                if (call == null) {
                    notificationId = keepNotification(notificationId, completeMember(input, member, false, "",
                            Map.of("errorCode", "wait_group_member_request_missing")));
                    continue;
                }
                NodeToolDispatcher.DispatchOutcome outcome = toolDispatcher.dispatch(
                        new NodeToolDispatcher.DispatchRequest(
                                input.identity().runId(), input.identity(), groupId, member.getMemberSeq(),
                                member.getMemberIdentity(), member.getToolCallId(), member.getToolName(),
                                call.arguments()));
                if (outcome instanceof NodeToolDispatcher.DispatchOutcome.Completed completed) {
                    notificationId = keepNotification(notificationId, completeMember(
                            input, member, true, completed.output(), Map.of()));
                } else if (outcome instanceof NodeToolDispatcher.DispatchOutcome.Failed failed) {
                    notificationId = keepNotification(notificationId, completeMember(
                            input, member, false, failed.reason(), Map.of()));
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
                    abortRemainingMembers(groupId, input, members, "run_budget_exceeded"));
            log.warn("派发期间额度耗尽，未完成的成员按失败记：groupId={} segment={}",
                    groupId, input.identity().describe());
        } catch (RuntimeException e) {
            if (!LangchainTerminalToolErrorHandler.isTerminalSignal(e)) {
                throw e;
            }
            // 取消、暂停这一类控制信号要求当前 Worker 松开调用栈。先把没拿到结果的成员记成失败，
            // 让等待链停在「组已齐备」而不是永远等不到人；随后原样抛出，交给上层收尾。
            abortRemainingMembers(groupId, input, members, "member_dispatch_aborted");
            throw e;
        }
        return notificationId;
    }

    private Long abortRemainingMembers(long groupId,
                                       SegmentExecution input,
                                       List<WaitMember> members,
                                       String errorCode) {
        Long notificationId = null;
        for (WaitMember member : members) {
            if (member.terminal() || member.stateEnum() == WaitMemberState.RUNNING) {
                continue;
            }
            notificationId = keepNotification(notificationId, completeMember(input, member, false, "",
                    Map.of("errorCode", errorCode)));
        }
        return notificationId;
    }

    private Long keepNotification(Long current, Long candidate) {
        return candidate != null ? candidate : current;
    }

    /** 上报一个成员的终态；返回这一次刚好让整组齐备的那条恢复通知编号，没有就是空。 */
    private Long completeMember(SegmentExecution input,
                                WaitMember member,
                                boolean success,
                                String output,
                                Map<String, Object> extra) {
        AcceptanceReleasePolicy policy = input.request().getAcceptanceReleasePolicy();
        Optional<String> designated = policy == null || member.getToolCallId() == null
                ? Optional.empty()
                : policy.designatedFailure(member.getToolCallId());
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
        } else if (policy != null && member.getToolCallId() != null
                && policy.covers(member.getToolCallId())) {
            // 压住结果与等兄弟成员先落终态这两种规则，针对的是「结果以后才回来」的成员。
            // 这条成员在本次调用里当场就把结果拿回来了，那两条规则在这里没有可等的东西，
            // 只留一行记录，不放行也不压住。
            log.info("放行策略点名的这条成员当场就结束了，压住与等兄弟成员在这里不适用：group={} member={} toolCall={}",
                    member.getGroupId(), member.getMemberIdentity(), member.getToolCallId());
        }
        String resultJson = WaitMemberResultPayload.encode(objectMapper, member.getToolName(),
                member.getToolCallId(), success, output, extra, maxMemberResultChars);
        MemberCompletionResult result = waitGroupStore.completeMember(new MemberCompletionRequest(
                member.getGroupId(),
                member.getMemberIdentity(),
                success ? WaitMemberState.SUCCEEDED : WaitMemberState.FAILED,
                resultJson,
                member.getExternalOperationId(),
                input.identity().planGeneration(),
                input.versions().contextVersion(),
                input.versions().runControlVersion()));
        if (!result.applied()) {
            log.info("成员结果没有写进去（重复上报或已落终态）：group={} member={}",
                    member.getGroupId(), member.getMemberIdentity());
        }
        return result.notificationId();
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
