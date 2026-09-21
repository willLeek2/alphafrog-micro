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
import world.willfrog.agentlangchain.control.dualpool.DualPoolRecoveryDispatcher;
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
        receiver = new WaitMemberResultReceiver(waitGroupStore, runMapper, nodeWorkItemStore,
                sandboxService, pythonSandboxTools, settlement, recoveryDispatcher, objectMapper,
                8, 1000L, 15000L, 6, 4096, 1000L);
        Mockito.lenient().when(settlement.settle(any(), any(), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(true, null));

        Mockito.lenient().when(waitGroupStore.rescheduleMember(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(true);
        Mockito.lenient().when(pythonSandboxTools.formatTerminalResult(anyString(), any()))
                .thenReturn("{\"ok\":true,\"stdout\":\"done\"}");
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

    // ===== 造数据 =====

    private void givenDueMember() {
        givenDueMemberWithProof(proof("task-1"));
    }

    private void givenDueMemberWithProof(WaitMemberDispatchProof proof) {
        WaitMember member = member();
        member.setDispatchProofJson(proof.toJson(objectMapper));
        givenDue(member);
        givenRunAndGroup();
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
