package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.MemberCompletionRequest;
import world.willfrog.agent.platform.wait.MemberCompletionResult;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupState;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePointStore;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePolicy;
import world.willfrog.agentlangchain.acceptance.FixtureRuleHitStore;
import world.willfrog.agentlangchain.acceptance.AcceptanceRunPolicyRegistry;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRecoveryDispatcher;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;
import world.willfrog.agentlangchain.execution.WaitMemberResultPayload;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import world.willfrog.agentlangchain.control.dualpool.TestSchedulerSettings;
import org.springframework.mock.env.MockEnvironment;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;

/**
 * 等待成员的结果接收：问一次外部作业，按结论写成员终态或推后。
 *
 * <p>这里量四件事：还没到终态时按退避推后（不每一轮都去问同一个长任务）；确认终态时按同一条写入
 * 语句落成员终态，并只在整组齐备时叫一次恢复分发器；归属核对不齐时不写任何东西、只推后；一轮里
 * 某一个成员出错不带走这一批。</p>
 */
class WaitMemberResultReceiverTest {

    private static final long GROUP_ID = 7L;
    private static final String MEMBER_IDENTITY = "node-1:s1:tc-1";
    private static final String RUN_ID = "run-receiver";
    private static final long RUN_CONTROL_VERSION = 5L;
    private static final long CONTEXT_VERSION = 33L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WaitGroupStore waitGroupStore;
    private AgentRunMapper runMapper;
    private NodeWorkItemStore nodeWorkItemStore;
    private PythonSandboxService sandboxService;
    private PythonSandboxTools pythonSandboxTools;
    private WaitMemberSettlement settlement;
    private DualPoolRecoveryDispatcher recoveryDispatcher;
    private AcceptanceRunPolicyRegistry acceptancePolicies;
    private AcceptanceReleasePointStore releasePoints;
    private FixtureRuleHitStore ruleHits;
    private WaitMemberResultReceiver receiver;

    @BeforeEach
    void setUp() {
        waitGroupStore = Mockito.mock(WaitGroupStore.class);
        runMapper = Mockito.mock(AgentRunMapper.class);
        nodeWorkItemStore = Mockito.mock(NodeWorkItemStore.class);
        sandboxService = Mockito.mock(PythonSandboxService.class);
        pythonSandboxTools = Mockito.mock(PythonSandboxTools.class);
        settlement = Mockito.mock(WaitMemberSettlement.class);
        recoveryDispatcher = Mockito.mock(DualPoolRecoveryDispatcher.class);
        acceptancePolicies = Mockito.mock(AcceptanceRunPolicyRegistry.class);
        releasePoints = Mockito.mock(AcceptanceReleasePointStore.class);
        ruleHits = Mockito.mock(FixtureRuleHitStore.class);
        receiver = new WaitMemberResultReceiver(waitGroupStore, runMapper, nodeWorkItemStore,
                sandboxService, pythonSandboxTools, settlement, recoveryDispatcher, objectMapper,
                TestSchedulerSettings.propertyOnly(
                        "agent.langchain.wait-member.receiver.batch-size", "8",
                        "agent.langchain.wait-member.receiver.backoff-base-ms", "1000",
                        "agent.langchain.wait-member.receiver.backoff-max-ms", "15000",
                        "agent.langchain.wait-member.receiver.max-backoff-step", "6"),
                4096, 1000L, new FrozenEffectiveSettings(), acceptancePolicies, releasePoints, ruleHits);
        Mockito.lenient().when(settlement.settle(any(), any(), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(true, null));

        Mockito.lenient().when(waitGroupStore.rescheduleMember(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(true);
        Mockito.lenient().when(pythonSandboxTools.formatTerminalResult(anyString(), any()))
                .thenReturn("{\"ok\":true,\"stdout\":\"done\"}");
    }

    /** 按回合读的参数改完下一轮就生效：批次从 8 改成 3，下一次扫描就按 3 要。 */
    @Test
    void aChangedReceiverBatchSizeTakesEffectOnTheNextRound() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.langchain.wait-member.receiver.batch-size", "8");
        Mockito.lenient().when(waitGroupStore.scanDueMembers(any(), anyInt())).thenReturn(List.of());
        WaitMemberResultReceiver live = new WaitMemberResultReceiver(waitGroupStore, runMapper,
                nodeWorkItemStore, sandboxService, pythonSandboxTools, settlement, recoveryDispatcher,
                objectMapper, new DualPoolSchedulerSettings(null, environment), 4096, 1000L,
                new FrozenEffectiveSettings(), acceptancePolicies, releasePoints, ruleHits);

        live.round();
        verify(waitGroupStore).scanDueMembers(any(), eq(8));

        environment.setProperty("agent.langchain.wait-member.receiver.batch-size", "3");
        live.round();
        verify(waitGroupStore).scanDueMembers(any(), eq(3));
    }

    /** 还在跑的作业：推后下次查询时间，不写任何终态。 */
    @Test
    void aRunningTaskIsPushedToTheNextQuery() {
        givenDueMember();
        status("RUNNING");

        assertThat(receiver.round()).isEqualTo(1);

        ArgumentCaptor<OffsetDateTime> nextPoll = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(waitGroupStore).rescheduleMember(eq(GROUP_ID), eq(MEMBER_IDENTITY), nextPoll.capture(), eq(6));
        assertThat(nextPoll.getValue())
                .as("推后一个正数间隔：不是原地不动，也不是推到一个过去的时间")
                .isAfter(OffsetDateTime.now());
        verify(waitGroupStore, never()).completeMember(any());
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverDeferredTotal", 1L)
                .containsEntry("waitMemberReceiverCompletedTotal", 0L);
    }

    /** 确认成功的作业：按同一份载荷写成员终态，并只在这条让整组齐备时叫一次分发器。 */
    @Test
    void aSucceededTaskIsWrittenBackAndTheGroupIsWokenUp() {
        givenDueMember();
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, 99L);

        assertThat(receiver.round()).isEqualTo(1);

        MemberCompletionRequest request = capturedRequest();
        assertThat(request.memberState()).isEqualTo(WaitMemberState.SUCCEEDED);
        assertThat(request.planGeneration()).isEqualTo(2);
        assertThat(request.contextVersion())
                .as("上下文版本取自那一段工作项，不是猜的")
                .isEqualTo(CONTEXT_VERSION);
        assertThat(request.runControlVersion()).isEqualTo(RUN_CONTROL_VERSION);
        assertThat(request.externalOperationId()).isEqualTo("op-1");
        assertThat(request.resultRefJson())
                .as("载荷与同步执行那条路用同一个写入方：同样的入参得到同样的 JSON")
                .isEqualTo(WaitMemberResultPayload.encode(objectMapper, "executePython", "tc-1",
                        true, "{\"ok\":true,\"stdout\":\"done\"}",
                        java.util.Map.of("taskId", "task-1"), 4096));

        verify(recoveryDispatcher).wake(99L);
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverCompletedTotal", 1L)
                .containsEntry("waitMemberReceiverWakeupsTotal", 1L);
    }

    /** 失败的作业：成员落失败，载荷里带明确的错误码。 */
    @Test
    void aFailedTaskIsWrittenBackAsFailure() {
        givenDueMember();
        status("FAILED");
        result("FAILED", 1, "boom");
        completion(true, WaitMemberState.FAILED, null);

        receiver.round();

        MemberCompletionRequest request = capturedRequest();
        assertThat(request.memberState()).isEqualTo(WaitMemberState.FAILED);
        assertThat(request.resultRefJson())
                .contains("PYTHON_EXECUTION_FAILED")
                .contains("\"status\":\"FAILED\"");
        verify(recoveryDispatcher, never()).wake(anyLong());
    }

    /** 结果体超过上限：成员也跟着落失败，不能成员说成功、模型看到失败。 */
    @Test
    void aTooLargeResultFailsTheMemberAsWell() {
        givenDueMember();
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        Mockito.lenient().when(pythonSandboxTools.formatTerminalResult(anyString(), any()))
                .thenReturn("x".repeat(4097));
        Mockito.lenient().when(waitGroupStore.completeMember(any())).thenReturn(
                new MemberCompletionResult(true, WaitMemberState.FAILED, WaitGroupState.READY, 1, 1, null));

        receiver.round();

        MemberCompletionRequest request = capturedRequest();
        assertThat(request.memberState()).isEqualTo(WaitMemberState.FAILED);
        assertThat(request.resultRefJson()).contains(WaitMemberResultPayload.TOO_LARGE);
    }

    /** 证明里没有任务号时按外部作业身份回查：查到就用它继续问状态与结果。 */
    @Test
    void anUnconfirmedTaskIsResolvedByItsOperationId() {
        givenDueMemberWithProof(proof(null));
        when(sandboxService.getTaskByOperationId(any(GetTaskByOperationIdRequest.class)))
                .thenReturn(GetTaskByOperationIdResponse.newBuilder().setTaskId("task-9").build());
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, null);

        receiver.round();

        verify(sandboxService).getTaskStatus(GetTaskStatusRequest.newBuilder().setTaskId("task-9").build());
        assertThat(capturedRequest().resultRefJson()).contains("task-9");
    }

    /** 权威地说这个后台作业不存在：成员按失败落终态，不让等待链一直等一个不会有结果的任务。 */
    @Test
    void anAuthoritativelyMissingTaskBecomesAFailureNotAWait() {
        givenDueMemberWithProof(proof(null));
        when(sandboxService.getTaskByOperationId(any(GetTaskByOperationIdRequest.class)))
                .thenReturn(GetTaskByOperationIdResponse.getDefaultInstance());
        completion(true, WaitMemberState.FAILED, null);

        receiver.round();

        MemberCompletionRequest request = capturedRequest();
        assertThat(request.memberState()).isEqualTo(WaitMemberState.FAILED);
        assertThat(request.resultRefJson())
                .contains("wait_member_task_not_found")
                .contains("task_not_found");
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverDeferredTotal", 0L);
    }

    /** 回查暂时问不到：只推后，不写终态——远端说不清不能当结论。 */
    @Test
    void anUnavailableLookupOnlyPushesTheMemberLater() {
        givenDueMemberWithProof(proof(null));
        when(sandboxService.getTaskByOperationId(any(GetTaskByOperationIdRequest.class)))
                .thenThrow(new IllegalStateException("网关暂时不可用"));

        receiver.round();

        verify(waitGroupStore, never()).completeMember(any());
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverDeferredTotal", 1L);
    }

    /** 派发证明读不出来：不问、不写、不许释放名额，只留一条能查的记录。 */
    @Test
    void anUnreadableProofIsIsolated() {
        WaitMember member = member();
        member.setDispatchProofJson("{不是 JSON");
        givenDue(member);
        givenRunAndGroup();

        receiver.round();

        verify(sandboxService, never()).getTaskStatus(any(GetTaskStatusRequest.class));
        verify(waitGroupStore, never()).completeMember(any());
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverIsolatedTotal", 1L)
                .containsEntry("waitMemberReceiverDeferredTotal", 1L);
    }

    /** Run 已经不在执行中：取消、暂停、终态各有自己的路径，这一路只隔离不动它。 */
    @Test
    void aMemberWhoseRunIsNotExecutingIsIsolated() {
        givenDueMember();
        AgentRun run = run(AgentRunStatus.CANCELED);
        when(runMapper.findById(RUN_ID)).thenReturn(run);

        receiver.round();

        verify(sandboxService, never()).getTaskStatus(any(GetTaskStatusRequest.class));
        verify(recoveryDispatcher, never()).wake(anyLong());
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverIsolatedTotal", 1L);
    }

    /** 找不到那一段工作项：归属核对不齐，不写成员行。 */
    @Test
    void aMemberWithoutItsSegmentIsIsolated() {
        givenDueMember();
        AgentRun run = run(AgentRunStatus.EXECUTING);
        when(runMapper.findById(RUN_ID)).thenReturn(run);
        when(waitGroupStore.findGroup(GROUP_ID)).thenReturn(Optional.of(group()));
        when(nodeWorkItemStore.findByIdentity(any())).thenReturn(Optional.empty());

        receiver.round();

        verify(waitGroupStore, never()).completeMember(any());
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverIsolatedTotal", 1L);
    }

    /** 别人已经写过这条成员：不重复写、也不再叫分发器。 */
    @Test
    void anAlreadyCompletedMemberIsNotWrittenTwice() {
        givenDueMember();
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(false, WaitMemberState.SUCCEEDED, null);

        receiver.round();

        verify(recoveryDispatcher, never()).wake(anyLong());
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverDuplicateTotal", 1L)
                .containsEntry("waitMemberReceiverCompletedTotal", 0L);
    }

    /** 这一轮扫不动（库读不出来）：不带走调度线程，下一轮照跑。 */
    @Test
    void aFailingRoundIsSwallowedAndTheNextRoundStillRuns() {
        when(waitGroupStore.scanDueMembers(any(), anyInt()))
                .thenThrow(new IllegalStateException("库读不了"))
                .thenReturn(List.of());

        assertThat(receiver.safeRound()).isZero();
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverFailuresTotal", 1L)
                .containsEntry("waitMemberReceiverRounds", 1L);

        assertThat(receiver.safeRound()).isZero();
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverRounds", 2L);
    }

    /** 一批里第一个成员出错，后面的成员照旧处理。 */
    @Test
    void oneBadMemberDoesNotStopTheBatch() {
        WaitMember bad = member();
        bad.setMemberIdentity("bad-member");
        bad.setDispatchProofJson("{}");
        WaitMember good = member();
        good.setDispatchProofJson(proof("task-1").toJson(objectMapper));
        givenDue(bad, good);
        givenRunAndGroup();
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, null);

        assertThat(receiver.round()).isEqualTo(2);

        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverIsolatedTotal", 1L)
                .containsEntry("waitMemberReceiverCompletedTotal", 1L);
    }

    /** 名额与用量还没收干净：不写成员终态，把这条成员推后，下一轮重来。 */
    @Test
    void aMemberWhoseSettlementIsNotDoneIsNotWritten() {
        givenDueMember();
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        Mockito.lenient().when(settlement.settle(any(), any(), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(false, "release:NOT_FOUND"));

        receiver.round();

        verify(waitGroupStore, never()).completeMember(any());
        verify(recoveryDispatcher, never()).wake(anyLong());
        verify(waitGroupStore).rescheduleMember(eq(GROUP_ID), eq(MEMBER_IDENTITY), any(), eq(6));
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverSettlementFailuresTotal", 1L)
                .containsEntry("waitMemberReceiverCompletedTotal", 0L);
    }

    /** 收尾拿到了这次调用的完整事实：终态名、结果体、以及要交给模型的那份正文。 */
    @Test
    void theSettlementSeesTheTerminalFacts() {
        givenDueMember();
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, null);

        receiver.round();

        ArgumentCaptor<String> statusName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> preview = ArgumentCaptor.forClass(String.class);
        verify(settlement).settle(any(), any(), statusName.capture(), any(), preview.capture());
        assertThat(statusName.getValue()).isEqualTo("SUCCEEDED");
        assertThat(preview.getValue())
                .as("交给收尾的正文与写进成员结果的是同一份")
                .isEqualTo("{\"ok\":true,\"stdout\":\"done\"}");
    }

    // ===== 验收夹具的结果放行策略 =====

    /** 夹具点名要等放行点：这一轮连沙箱都不去问，只把下次查询时间推一小步。 */
    @Test
    void aMemberHeldByAReleasePointIsNotCollectedYet() {
        givenGroupMembers(givenDueMember());
        policyOf(holdRule());
        when(releasePoints.isOpened(RUN_ID, "point-a")).thenReturn(false);
        Mockito.lenient().when(waitGroupStore.holdMember(eq(GROUP_ID), eq(MEMBER_IDENTITY), any()))
                .thenReturn(true);
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");

        assertThat(receiver.round()).isEqualTo(1);

        assertThat(capturedHeldNextPoll()).as("压住要推下次查询时间，而且推到将来").isAfter(OffsetDateTime.now());
        verify(sandboxService, never()).getTaskStatus(any(GetTaskStatusRequest.class));
        verify(waitGroupStore, never()).completeMember(any());
        verify(waitGroupStore, never()).rescheduleMember(anyLong(), anyString(), any(), anyInt());
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverHoldPushesTotal", 1L)
                .containsEntry("waitMemberReceiverHeldNow", 1)
                .containsEntry("waitMemberReceiverDeferredTotal", 0L);
    }

    /** 放行点被控制面标成已放行之后，这条成员照常收尾；已经不再压它。 */
    @Test
    void aHeldMemberIsCollectedOnceItsReleasePointOpens() {
        givenGroupMembers(givenDueMember());
        policyOf(holdRule());
        when(releasePoints.isOpened(RUN_ID, "point-a")).thenReturn(true);
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, null);

        receiver.round();

        assertThat(capturedRequest().memberState()).isEqualTo(WaitMemberState.SUCCEEDED);
        verify(waitGroupStore, never()).holdMember(anyLong(), anyString(), any());
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverCompletedTotal", 1L)
                .containsEntry("waitMemberReceiverHoldPushesTotal", 0L);
    }

    /** 等兄弟成员先落终态：名单里还有没落的就先压住，全落了就照常接结果。 */
    @Test
    void aMemberWaitingForItsPeersHoldsUntilTheyAllFinish() {
        givenDueMember();
        policyOf(peerRule("tc-2"));
        WaitMember due = givenDueMember();
        WaitMember peer = peerMember(1, "tc-2");
        peer.setState(WaitMemberState.RUNNING.name());
        givenGroupMembers(due, peer);
        Mockito.lenient().when(waitGroupStore.holdMember(eq(GROUP_ID), eq(MEMBER_IDENTITY), any()))
                .thenReturn(true);

        assertThat(receiver.round()).isEqualTo(1);
        verify(waitGroupStore, never()).completeMember(any());
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverHoldPushesTotal", 1L);

        // 兄弟成员落了终态：这一轮不再压，照常把结果接回来。
        peer.setState(WaitMemberState.SUCCEEDED.name());
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, null);

        assertThat(receiver.round()).isEqualTo(1);
        assertThat(capturedRequest().memberState()).isEqualTo(WaitMemberState.SUCCEEDED);
    }

    /** 夹具点名按失败收尾：沙箱明明成功，成员也落失败，原因写清是这个场景点名的。 */
    @Test
    void aDesignatedMemberFailsEvenWhenTheSandboxSucceeded() {
        givenGroupMembers(givenDueMember());
        policyOf("{\"rules\":[{\"for\":{\"nodeId\":\"node-1\",\"memberSeq\":0},"
                + "\"fail\":\"这个场景要造一条失败成员\"}]}");
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.FAILED, null);

        receiver.round();

        MemberCompletionRequest request = capturedRequest();
        assertThat(request.memberState()).isEqualTo(WaitMemberState.FAILED);
        assertThat(request.resultRefJson())
                .as("结论是失败，但拿回来的东西不丢：" + request.resultRefJson())
                .contains("acceptance_fixture_designated_failure")
                .contains("这个场景要造一条失败成员")
                .contains("done");
        // 名额与用量照常收尾：被点名按失败收尾不等于这次调用没发生过。
        verify(settlement).settle(any(), any(), any(), any(), any());
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverDesignatedFailuresTotal", 1L);
    }

    /**
     * 策略点了这个等待组里没有的成员：夹具写错了，成员按失败收尾，不许压住干等。
     *
     * <p>这条收尾还得走正常那条路：先把沙箱的真实终态取回来、按真实终态把名额与用量结算掉，再落失败。
     * 已经派发的成员名额挂在沙箱任务上，绕开真实终态去收尾（当成「作业从来没建出来」）会让名额永远
     * 还不回去，这条成员也就永远停在这里。</p>
     */
    @Test
    void aPolicyPointingAtAnUnknownPeerFailsTheMemberAfterRealSettlement() {
        givenGroupMembers(givenDueMember());
        policyOf(peerRule("tc-missing"));
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.FAILED, null);

        receiver.round();

        assertThat(capturedRequest().resultRefJson())
                .contains("acceptance_fixture_policy_peer_unknown")
                .contains("tc-missing");
        ArgumentCaptor<String> settledStatus = ArgumentCaptor.forClass(String.class);
        verify(settlement).settle(any(), any(), settledStatus.capture(), any(), any());
        assertThat(settledStatus.getValue())
                .as("按沙箱自己的终态结算，名额与用量照实记")
                .isEqualTo("SUCCEEDED");
        verify(sandboxService).getTaskStatus(any(GetTaskStatusRequest.class));
        verify(waitGroupStore, never()).holdMember(anyLong(), anyString(), any());
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverPolicyRefusalsTotal", 1L);
    }

    /**
     * 策略这一侧读不出来：这条成员按失败收尾，原因写清是夹具这一侧的问题，不推后重试。
     *
     * <p>照样先问沙箱、再结算：这条成员的外部作业可能真的在跑、真的占着名额，先结算再落失败，
     * 账才收得回来。</p>
     */
    @Test
    void anUnreadablePolicyFailsTheMemberInsteadOfRetryingForever() {
        givenDueMember();
        Mockito.lenient().when(acceptancePolicies.policyForRun(any()))
                .thenThrow(AcceptanceFixtureExecutionException.refuse("acceptance_fixture_identity_changed",
                        "这条 Run 的夹具身份中途换了"));
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.FAILED, null);

        receiver.round();

        assertThat(capturedRequest().resultRefJson())
                .contains("acceptance_fixture_policy_unreadable")
                .contains("acceptance_fixture_identity_changed");
        verify(settlement).settle(any(), any(), eq("SUCCEEDED"), any(), any());
        verify(waitGroupStore, never()).rescheduleMember(anyLong(), anyString(), any(), anyInt());
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverCompletedTotal", 1L)
                .containsEntry("waitMemberReceiverDeferredTotal", 0L);
    }

    /** 读数按真的落库成功记：收尾没成、下一轮还要重来一次的那次，不算发生过一次。 */
    @Test
    void aRefusalIsCountedOnlyAfterItIsWritten() {
        givenGroupMembers(givenDueMember());
        policyOf(peerRule("tc-missing"));
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        when(settlement.settle(any(), any(), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(false, "reservation_busy"));

        receiver.round();

        verify(waitGroupStore, never()).completeMember(any());
        assertThat(receiver.snapshot())
                .as("这一轮没写进库里，不算发生过一次")
                .containsEntry("waitMemberReceiverPolicyRefusalsTotal", 0L)
                .containsEntry("waitMemberReceiverDeferredTotal", 1L);

        when(settlement.settle(any(), any(), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(true, null));
        completion(true, WaitMemberState.FAILED, null);
        receiver.round();

        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverPolicyRefusalsTotal", 1L);
    }

    /**
     * 等兄弟成员这条规则也吃兜底时限：兄弟一直不落终态，压过时限就照常收尾。
     *
     * <p>没有这条兜底，夹具写出一条等不出头的等待关系（比如等一个已经取消、再也不会有人碰的成员）
     * 会把这条成员永远压在那里。</p>
     */
    @Test
    void aMemberWaitingForItsPeersIsReleasedWhenTheHoldTimesOut() throws Exception {
        givenDueMember();
        policyOf(peerRuleWithHoldTimeout());
        WaitMember due = givenDueMember();
        WaitMember peer = peerMember(1, "tc-2");
        peer.setState(WaitMemberState.RUNNING.name());
        givenGroupMembers(due, peer);
        Mockito.lenient().when(waitGroupStore.holdMember(eq(GROUP_ID), eq(MEMBER_IDENTITY), any()))
                .thenReturn(true);
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, null);

        assertThat(receiver.round()).isEqualTo(1);
        verify(waitGroupStore, never()).completeMember(any());

        Thread.sleep(1100L);
        assertThat(receiver.round()).isEqualTo(1);

        assertThat(capturedRequest().memberState()).isEqualTo(WaitMemberState.SUCCEEDED);
        assertThat(receiver.snapshot()).containsEntry("waitMemberReceiverReleasedOnHoldTimeoutTotal", 1L);
    }

    /** 兄弟成员已经取消：它不会再变，等待到这里就结束，不把它当成还在跑。 */
    @Test
    void aCanceledPeerCountsAsFinished() {
        givenDueMember();
        policyOf(peerRule("tc-2"));
        WaitMember due = givenDueMember();
        WaitMember peer = peerMember(1, "tc-2");
        peer.setState(WaitMemberState.CANCELED.name());
        givenGroupMembers(due, peer);
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, null);

        receiver.round();

        assertThat(capturedRequest().memberState()).isEqualTo(WaitMemberState.SUCCEEDED);
        verify(waitGroupStore, never()).holdMember(anyLong(), anyString(), any());
    }

    /** 压过兜底时限还没人放行：照常收尾，读数里单独记一笔，别把「没人放行」当成「被放行」。 */
    @Test
    void aMemberHeldTooLongIsReleased() throws Exception {
        givenGroupMembers(givenDueMember());
        policyOf(holdRuleWithHoldTimeout());
        when(releasePoints.isOpened(RUN_ID, "point-a")).thenReturn(false);
        Mockito.lenient().when(waitGroupStore.holdMember(eq(GROUP_ID), eq(MEMBER_IDENTITY), any()))
                .thenReturn(true);
        status("SUCCEEDED");
        result("SUCCEEDED", 0, "done");
        completion(true, WaitMemberState.SUCCEEDED, null);

        assertThat(receiver.round()).isEqualTo(1);
        verify(waitGroupStore, never()).completeMember(any());

        // 兜底时限是秒级，这里只能真的等过去：压住的起点是上一轮第一次压住的那一刻。
        Thread.sleep(1100L);
        assertThat(receiver.round()).isEqualTo(1);

        assertThat(capturedRequest().memberState()).isEqualTo(WaitMemberState.SUCCEEDED);
        assertThat(receiver.snapshot())
                .containsEntry("waitMemberReceiverReleasedOnHoldTimeoutTotal", 1L)
                .containsEntry("waitMemberReceiverCompletedTotal", 1L);
    }

    // ===== 造数据 =====

    private WaitMember givenDueMember() {
        return givenDueMemberWithProof(proof("task-1"));
    }

    /** 这个等待组里已落库的成员：结果接收方按它们算「选择器打中了哪一条」。 */
    private void givenGroupMembers(WaitMember... members) {
        Mockito.lenient().when(waitGroupStore.listMembers(GROUP_ID)).thenReturn(List.of(members));
    }

    /** 组里的另一条成员：序号与当前这条不同，工具调用编号按用例需要给。 */
    private static WaitMember peerMember(int memberSeq, String toolCallId) {
        WaitMember peer = member();
        peer.setMemberSeq(memberSeq);
        peer.setMemberIdentity("peer-member-" + memberSeq);
        peer.setToolCallId(toolCallId);
        return peer;
    }

    private WaitMember givenDueMemberWithProof(WaitMemberDispatchProof proof) {
        WaitMember member = member();
        member.setDispatchProofJson(proof.toJson(objectMapper));
        givenDue(member);
        givenRunAndGroup();
        return member;
    }

    private void givenDue(WaitMember... members) {
        Mockito.lenient().when(waitGroupStore.scanDueMembers(any(), anyInt()))
                .thenReturn(List.of(members));
    }

    private void givenRunAndGroup() {
        Mockito.lenient().when(runMapper.findById(RUN_ID)).thenReturn(run(AgentRunStatus.EXECUTING));
        Mockito.lenient().when(waitGroupStore.findGroup(GROUP_ID)).thenReturn(Optional.of(group()));
        Mockito.lenient().when(nodeWorkItemStore.findByIdentity(any())).thenReturn(Optional.of(segment()));
    }

    private void status(String statusName) {
        // 沙箱按请求的任务号回答：测试里问哪个任务，回的就是那个任务的号。
        Mockito.lenient().when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenAnswer(invocation -> TaskStatusResponse.newBuilder()
                        .setTaskId(invocation.getArgument(0, GetTaskStatusRequest.class).getTaskId())
                        .setStatus(statusName)
                        .build());
    }

    private void result(String statusName, int exitCode, String text) {
        // 终态结果体要满足校验器的完整性要求：成功要有输出，失败要有报错信息。
        boolean failed = "FAILED".equals(statusName) || "CANCELED".equals(statusName);
        Mockito.lenient().when(sandboxService.getTaskResult(any(GetTaskResultRequest.class)))
                .thenAnswer(invocation -> TaskResultResponse.newBuilder()
                        .setTaskId(invocation.getArgument(0, GetTaskResultRequest.class).getTaskId())
                        .setStatus(statusName)
                        .setExitCode(exitCode)
                        .setStdout(failed ? "" : text)
                        .setStderr(failed ? text : "")
                        .build());
    }

    private void completion(boolean applied, WaitMemberState state, Long notificationId) {
        Mockito.lenient().when(waitGroupStore.completeMember(any())).thenReturn(
                new MemberCompletionResult(applied, state, WaitGroupState.READY, 1, 1, notificationId));
    }

    /** 点名压住这条成员：选择器写清是哪个节点的哪一条成员，不靠工具调用编号。 */
    private static String holdRule() {
        return "{\"rules\":[{\"for\":{\"nodeId\":\"node-1\",\"memberSeq\":0},"
                + "\"holdUntilPoint\":\"point-a\"}]}";
    }

    private static String holdRuleWithHoldTimeout() {
        return "{\"maxHoldSeconds\":1,\"rules\":[{\"for\":{\"nodeId\":\"node-1\",\"memberSeq\":0},"
                + "\"holdUntilPoint\":\"point-a\"}]}";
    }

    /** 点名等另一条成员先落终态：等谁也用选择器写，光写编号在别的组里会点错。 */
    private static String peerRule(String peerToolCallId) {
        return "{\"rules\":[{\"for\":{\"nodeId\":\"node-1\",\"memberSeq\":0},"
                + "\"releaseAfter\":[{\"toolCallId\":\"" + peerToolCallId + "\"}]}]}";
    }

    private static String peerRuleWithHoldTimeout() {
        return "{\"maxHoldSeconds\":1,\"rules\":[{\"for\":{\"nodeId\":\"node-1\",\"memberSeq\":0},"
                + "\"releaseAfter\":[{\"memberSeq\":1}]}]}";
    }

    /** 让这条 Run 拿到一份真的放行策略：策略读法与写进库里的那份 JSON 是同一套。 */
    private void policyOf(String policyJson) {
        Mockito.lenient().when(acceptancePolicies.policyForRun(any())).thenReturn(Optional.of(
                AcceptanceReleasePolicy.parse("fx-receiver", policyJson, objectMapper).orElseThrow()));
    }

    /** 压住这条成员时推给库里的下次查询时间。 */
    private OffsetDateTime capturedHeldNextPoll() {
        ArgumentCaptor<OffsetDateTime> nextPoll = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(waitGroupStore).holdMember(eq(GROUP_ID), eq(MEMBER_IDENTITY), nextPoll.capture());
        return nextPoll.getValue();
    }

    private MemberCompletionRequest capturedRequest() {
        ArgumentCaptor<MemberCompletionRequest> request =
                ArgumentCaptor.forClass(MemberCompletionRequest.class);
        verify(waitGroupStore).completeMember(request.capture());
        return request.getValue();
    }

    private static WaitMemberDispatchProof proof(String taskId) {
        return new WaitMemberDispatchProof(WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION,
                "op-1", taskId, "sha256:fingerprint", "{\"code\":\"print(1)\"}",
                "{\"units\":1}", "{\"reservationId\":\"r-1\"}", OffsetDateTime.now().toString());
    }

    private static WaitMember member() {
        WaitMember member = new WaitMember();
        member.setId(41L);
        member.setGroupId(GROUP_ID);
        member.setRunId(RUN_ID);
        member.setMemberSeq(0);
        member.setMemberIdentity(MEMBER_IDENTITY);
        member.setToolCallId("tc-1");
        member.setToolName("executePython");
        member.setExternalOperationId("op-1");
        member.setState(WaitMemberState.RUNNING.name());
        member.setNextPollAt(OffsetDateTime.now().minusSeconds(1));
        member.setPollCount(1);
        member.setBackoffStep(1);
        member.setCreatedAt(OffsetDateTime.now().minusSeconds(30));
        return member;
    }

    private static WaitGroup group() {
        WaitGroup group = new WaitGroup();
        group.setId(GROUP_ID);
        group.setRunId(RUN_ID);
        group.setPlanGeneration(2);
        group.setNodeId("node-1");
        group.setNodeAttempt(1);
        group.setSegmentSequence(3);
        // 这一组的模型回合：库里这一列非空，放行策略按它比对「点的是哪一次调用」。
        group.setModelTurn(0);
        group.setState(WaitGroupState.WAITING.name());
        return group;
    }

    private static NodeWorkItem segment() {
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(RUN_ID);
        item.setPlanGeneration(2);
        item.setNodeId("node-1");
        item.setNodeAttempt(1);
        item.setSegmentSequence(3);
        item.setContextVersion(CONTEXT_VERSION);
        item.setRunControlVersion(RUN_CONTROL_VERSION);
        return item;
    }

    private static AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        run.setPlanGeneration(2);
        run.setRunControlVersion(RUN_CONTROL_VERSION);
        return run;
    }
}
