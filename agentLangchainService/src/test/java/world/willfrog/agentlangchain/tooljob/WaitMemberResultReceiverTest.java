package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.tools.sandboxjob.*;
import world.willfrog.agent.tools.python.PythonSandboxJobRequestAdapter;
import world.willfrog.agent.tools.python.PythonSandboxJobResultAdapter;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
import world.willfrog.agent.platform.finance.*;
import world.willfrog.agent.platform.mapper.FinanceRecordBatchMapper;
import world.willfrog.agent.platform.mapper.FinanceMetricRecordMapper;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.tools.finance.FinanceResultModelProjector;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import world.willfrog.agent.platform.entity.AgentRun;
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
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private RunOwnershipGateway ownership;
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
        SandboxJobAdapterRegistry.clearForTests();
        waitGroupStore = Mockito.mock(WaitGroupStore.class);
        ownership = Mockito.mock(RunOwnershipGateway.class);
        Mockito.lenient().when(ownership.requireIdentity()).thenReturn(
                new DeploymentIdentity("stage5a-test", "gen-" + "a".repeat(64)));
        nodeWorkItemStore = Mockito.mock(NodeWorkItemStore.class);
        sandboxService = Mockito.mock(PythonSandboxService.class);
        pythonSandboxTools = Mockito.mock(PythonSandboxTools.class);
        settlement = Mockito.mock(WaitMemberSettlement.class);
        recoveryDispatcher = Mockito.mock(DualPoolRecoveryDispatcher.class);
        acceptancePolicies = Mockito.mock(AcceptanceRunPolicyRegistry.class);
        releasePoints = Mockito.mock(AcceptanceReleasePointStore.class);
        ruleHits = Mockito.mock(FixtureRuleHitStore.class);
        receiver = new WaitMemberResultReceiver(waitGroupStore, ownership, nodeWorkItemStore,
                sandboxService, pythonSandboxTools, settlement, recoveryDispatcher, objectMapper,
                TestSchedulerSettings.propertyOnly(
                        "agent.langchain.wait-member.receiver.batch-size", "8",
                        "agent.langchain.wait-member.receiver.backoff-base-ms", "1000",
                        "agent.langchain.wait-member.receiver.backoff-max-ms", "15000",
                        "agent.langchain.wait-member.receiver.max-backoff-step", "6"),
                4096, 1000L, new FrozenEffectiveSettings(), acceptancePolicies, releasePoints, ruleHits);
        Mockito.lenient().when(settlement.settlePrompt(any(), any(), any(), any(), any(), any()))
                .thenReturn(new WaitMemberSettlement.Outcome(true, null));

        Mockito.lenient().when(waitGroupStore.rescheduleMember(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(true);
        Mockito.lenient().when(pythonSandboxTools.formatTerminalResult(
                anyString(), any(), any(), any(), any(), any()))
                .thenReturn("{\"ok\":true,\"stdout\":\"done\"}");
        // 既有接收器编排用例只替换正文；业务分类仍用真实 Python 适配器。
        var classifier = new PythonSandboxJobResultAdapter(new FinanceToolResultFormatter(objectMapper), null);
        SandboxJobResultAdapter textStub = new SandboxJobResultAdapter() {
            public boolean isSuccess(SandboxTerminalResultView terminal) { return classifier.isSuccess(terminal); }
            public String errorCodeOf(SandboxTerminalResultView terminal) { return classifier.errorCodeOf(terminal); }
            public world.willfrog.alphafrogmicro.common.racing.prompt.PromptText formatTerminalResultText(
                    SandboxTerminalResultView terminal, Object ignored) {
                // 本接收器测试替身明确产生普通 stdout 事实，真实建议分支另用真实适配器。
                return world.willfrog.alphafrogmicro.common.racing.prompt.PromptText.runtimeData(
                        formatTerminalResult(terminal, ignored));
            }
            public String formatTerminalResult(SandboxTerminalResultView terminal, Object ignored) {
                return pythonSandboxTools.formatTerminalResult(terminal.statusName(),
                        (TaskResultResponse) terminal.nativePayload(), RUN_ID, null, "node-1", "tc-1");
            }
        };
        SandboxJobAdapterRegistry.register(new SandboxJobAdapters(
                new PythonSandboxJobRequestAdapter(), null, textStub, null));
    }

    @AfterEach
    void clearAdapters() {
        SandboxJobAdapterRegistry.clearForTests();
    }

    private PythonSandboxTools installRealAdapters() {
        SandboxJobAdapterRegistry.clearForTests();
        PythonSandboxTools python = ToolJobPromptTestFixture.configureTool(new PythonSandboxTools(objectMapper));
        ReflectionTestUtils.setField(python, "pythonSandboxService", sandboxService);
        ReflectionTestUtils.invokeMethod(python, "registerSandboxJobAdapters");
        SqlQueryTools query = ToolJobPromptTestFixture.configureTool(new SqlQueryTools(objectMapper));
        ReflectionTestUtils.setField(query, "pythonSandboxService", sandboxService);
        ReflectionTestUtils.invokeMethod(query, "registerSandboxJobAdapters");
        return python;
    }







































    // ===== 验收夹具的结果放行策略 =====












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
        Mockito.lenient().when(waitGroupStore.scanDueMembers(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(members));
    }

    private void givenRunAndGroup() {
        Mockito.lenient().when(ownership.findOwnedRun(RUN_ID))
                .thenReturn(run(AgentRunStatus.EXECUTING));
        Mockito.lenient().when(waitGroupStore.findGroup(GROUP_ID)).thenReturn(Optional.of(group()));
        Mockito.lenient().when(nodeWorkItemStore.findByIdentity(any())).thenReturn(Optional.of(segment()));
    }

    private void status(String statusName) {
        // 沙箱按请求的任务号回答：测试里问哪个任务，回的就是那个任务的号。
        Mockito.lenient().when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenAnswer(invocation -> TaskStatusResponse.newBuilder()
                        .setTaskId(invocation.getArgument(0, GetTaskStatusRequest.class).getTaskId())
                        .setStatus(statusName)
                        .setFinishedAt("2026-09-25T02:03:04.123456")
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
        return "{\"rules\":[{\"for\":{\"planGeneration\":2,\"nodeId\":\"node-1\",\"nodeAttempt\":1,\"segmentSequence\":3,\"modelTurn\":0,\"memberSeq\":0},"
                + "\"holdUntilPoint\":\"point-a\"}]}";
    }

    private static String holdRuleWithHoldTimeout() {
        return "{\"maxHoldSeconds\":1,\"rules\":[{\"for\":{\"planGeneration\":2,\"nodeId\":\"node-1\",\"nodeAttempt\":1,\"segmentSequence\":3,\"modelTurn\":0,\"memberSeq\":0},"
                + "\"holdUntilPoint\":\"point-a\"}]}";
    }

    /** 点名等另一条成员先落终态：等谁也用选择器写，光写编号在别的组里会点错。 */
    private static String peerRule(String peerToolCallId) {
        return "{\"rules\":[{\"for\":{\"planGeneration\":2,\"nodeId\":\"node-1\",\"nodeAttempt\":1,\"segmentSequence\":3,\"modelTurn\":0,\"memberSeq\":0},"
                + "\"releaseAfter\":[{\"toolCallId\":\"" + peerToolCallId + "\"}]}]}";
    }

    private static String peerRuleWithHoldTimeout() {
        return "{\"maxHoldSeconds\":1,\"rules\":[{\"for\":{\"planGeneration\":2,\"nodeId\":\"node-1\",\"nodeAttempt\":1,\"segmentSequence\":3,\"modelTurn\":0,\"memberSeq\":0},"
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
        run.setUserId("receiver-fixture-user");
        run.setStatus(status);
        run.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        run.setPlanGeneration(2);
        run.setRunControlVersion(RUN_CONTROL_VERSION);
        return run;
    }
}
