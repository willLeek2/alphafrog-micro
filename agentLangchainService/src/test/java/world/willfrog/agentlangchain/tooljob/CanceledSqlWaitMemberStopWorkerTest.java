package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.asynctask.AsyncToolTask;
import world.willfrog.agent.platform.asynctask.AsyncToolTaskStore;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.*;
import world.willfrog.agent.tools.dataanalysis.SqlQueryJobResultAdapter;
import world.willfrog.agent.tools.python.PythonSandboxJobRunnerAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobObservability;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.tools.python.DataAnalysisCapacityServiceImpl;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SQL取消使用真实成员结算器和容量账本；外部RPC及数据库仅提供受控结果。 */
class CanceledSqlWaitMemberStopWorkerTest {
    private static final String RUN = "run-sql-stop";
    private static final String TASK = "sql-task";
    private static final String FINISHED = "2026-10-09T10:00:00Z";
    /** 新任务表主键替代旧等待成员身份：工人要求任务行编号与成员编号一致（41）。 */
    private static final long JOB_ID = 41L;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DataAnalysisOperationIdentity operation = new DataAnalysisOperationIdentity(RUN, "query-node-s1", 1);
    private final DataAnalysisEstimate estimate = new DataAnalysisEstimate(1, 1024, 1, 1.0, 0,
            List.of(), DataAnalysisResourceClass.STANDARD, 1);
    private AsyncToolTaskStore tasks;
    private WaitGroupStore groups;
    private PythonSandboxService sandbox;
    private DataAnalysisCapacityServiceImpl capacity;
    private DataAnalysisTerminalRecorder recorder;
    private PythonSandboxDispatchStore sessions;
    private CanceledWaitMemberStopWorker worker;
    private AsyncToolTask task;
    private WaitMember member;
    private String fingerprint;

    @BeforeEach void setUp() {
        tasks = mock(AsyncToolTaskStore.class);
        sandbox = mock(PythonSandboxService.class);
        recorder = mock(DataAnalysisTerminalRecorder.class);
        sessions = mock(PythonSandboxDispatchStore.class);
        DataAnalysisCapacityProperties limits = new DataAnalysisCapacityProperties();
        limits.setMaxUnits(1); limits.setMaxActive(1);
        capacity = spy(new DataAnalysisCapacityServiceImpl(limits));
        WaitMemberSettlement settlement = new WaitMemberSettlement(capacity, recorder, json);
        settlement.setDispatchStore(sessions);
        groups = mock(WaitGroupStore.class);
        RunOwnershipGateway ownership = mock(RunOwnershipGateway.class);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity("beta", "gen-" + "a".repeat(64)));
        AgentRun run = new AgentRun(); run.setId(RUN); run.setStatus(AgentRunStatus.CANCELED); run.setLaneTag("sql-stop-lane");
        when(ownership.findOwnedRun(RUN)).thenReturn(run);
        worker = new CanceledWaitMemberStopWorker(tasks, groups, sandbox, settlement, json,
                ownership, mock(DualPoolRunAdmissionRegistry.class), 2, 120, 5, 30);
        member = new WaitMember(); member.setId(JOB_ID); member.setGroupId(7L); member.setRunId(RUN);
        member.setMemberIdentity("query-member"); member.setToolCallId("raw-model-call");
        member.setToolName("executeQuery"); member.setExternalOperationId(operation.operationId()); member.setState("CANCELED");
        task = new AsyncToolTask(); task.setJobId(JOB_ID); task.setGroupId(7L);
        task.setRunId(RUN); task.setMemberIdentity("query-member");
        task.setExternalOperationId(operation.operationId()); task.setClaimEpoch(1L);
        // 请求快照非空表示创建请求可能已经发出；具体内容工人不解析，只用来区分「还没落库」。
        task.setRequestJson("{\"create_request\":\"pending\"}");
        when(groups.findMemberByOperation(RUN, operation.operationId())).thenReturn(Optional.of(member));
        when(tasks.finishStop(anyLong(), anyString(), anyLong(), any())).thenReturn(true);
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED, DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME);
        claimAgain();
    }

    @AfterEach void clearLane() { LaneContext.clear(); }

    private DataAnalysisReservation install(DataAnalysisReservationState state) throws Exception {
        String taskId = state == DataAnalysisReservationState.PREPARING ? null : TASK;
        DataAnalysisReservation reservation = new DataAnalysisReservation(operation.reservationId(), operation,
                DataAnalysisResourceClass.STANDARD, 1, state, taskId, Instant.parse("2026-10-09T09:00:00Z"));
        capacity.recover(List.of(reservation), 1, 1);
        CanonicalSandboxCreateSpec spec = new CanonicalSandboxCreateSpec(CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION,
                operation.operationId(), "a".repeat(64), "b".repeat(64), DataAnalysisResourceClass.STANDARD,
                1024, 1000, "environment", "c".repeat(64), "d".repeat(64));
        fingerprint = spec.requestFingerprint();
        ExecuteRequest request = ExecuteRequest.newBuilder().setOperationId(operation.operationId())
                .setRequestFingerprint(fingerprint).setCode("print('query')").build();
        member.setDispatchProofJson(new WaitMemberDispatchProof(WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION,
                operation.operationId(), taskId, fingerprint, json.writeValueAsString(spec), json.writeValueAsString(estimate),
                json.writeValueAsString(reservation), "2026-10-09T09:00:00Z", JsonFormat.printer().print(request)).toJson(json));
        task.setTaskId(taskId); task.setRequestJson(JsonFormat.printer().print(request));
        when(sandbox.getTaskByOperationId(any())).thenReturn(taskId == null
                ? GetTaskByOperationIdResponse.getDefaultInstance()
                : GetTaskByOperationIdResponse.newBuilder().setFound(true).setTaskId(TASK)
                    .setRequestFingerprint(fingerprint).build());
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCELED).setTaskId(TASK).setStatus("CANCELED").build());
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId(TASK).setStatus("CANCELED").setFinishedAt(FINISHED).build());
        when(sandbox.getTaskResult(any())).thenReturn(result());
        return reservation;
    }

    private TaskResultResponse result() {
        return TaskResultResponse.newBuilder().setTaskId(TASK).setStatus("CANCELED")
                .setExitCode(-1).setError("canceled").setRetryable(false).build();
    }

    private void claimAgain() {
        when(tasks.scanStopDue(any(), anyLong(), anyInt())).thenReturn(List.of(task), List.of());
        when(tasks.claimStop(anyLong(), anyString(), any(), any()))
                .thenReturn(Optional.of(task), Optional.empty());
    }

    private void assertCapacityHeld() {
        assertThat((Integer) ReflectionTestUtils.invokeMethod(capacity, "usedUnitsSnapshot")).isEqualTo(1);
        assertThatThrownBy(() -> capacity.reserve(new DataAnalysisOperationIdentity("next-run", "query", 1), estimate))
                .isInstanceOf(CapacityAdmissionException.class);
    }

    private DataAnalysisReservation occupyReleasedSlot() {
        assertThat((Integer) ReflectionTestUtils.invokeMethod(capacity, "usedUnitsSnapshot")).isZero();
        return capacity.reserve(new DataAnalysisOperationIdentity("next-run", "query", 1), estimate);
    }

    @ParameterizedTest
    @EnumSource(value = DataAnalysisReservationState.class, names = {"PREPARING", "TASK_ATTACHED", "PENDING_TRANSFERRED"})
    void sqlCancellationSettlesOriginalTaskAndMakesItsCapacityAvailableExactlyOnce(DataAnalysisReservationState state)
            throws Exception {
        install(state);
        String originalProof = member.getDispatchProofJson();
        assertCapacityHeld();
        assertThat(worker.runBatch()).isEqualTo(1);
        verify(sandbox).cancelTask(argThat(request -> request.hasByOperation()
                && request.getByOperation().getOperationId().equals(operation.operationId())
                && request.getByOperation().getRequestFingerprint().equals(fingerprint)
                && request.getCancelRequestId().equals("async-task-" + JOB_ID)));
        verify(sandbox, never()).createTask(any());
        verify(recorder).upsert(argThat(envelope -> envelope.operationId().equals(operation.operationId())
                && envelope.taskId().equals(TASK) && envelope.terminalStatus().equals("CANCELED")
                && !envelope.success() && envelope.reservation().state() == DataAnalysisReservationState.TERMINAL_CONFIRMED));
        var order = inOrder(capacity, recorder, tasks);
        order.verify(capacity).releaseReservation(argThat(request -> request.proof() instanceof DataAnalysisReleaseProof.Terminal
                && request.reason() == DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED));
        order.verify(recorder).upsert(any());
        order.verify(tasks).finishStop(eq(JOB_ID), anyString(), eq(1L), any());
        assertThat(member.getState()).isEqualTo("CANCELED");
        assertThat(member.getDispatchProofJson()).isEqualTo(originalProof);
        DataAnalysisReservation next = occupyReleasedSlot();
        claimAgain();
        assertThat(worker.runBatch()).isEqualTo(1);
        assertThat((Integer) ReflectionTestUtils.invokeMethod(capacity, "usedUnitsSnapshot")).isEqualTo(1);
        verify(capacity, times(2)).releaseReservation(argThat(request -> request.reservation().identity().equals(operation)));
        verify(capacity, never()).releaseReservation(argThat(request -> request.reservation().identity().equals(next.identity())));
        verify(tasks, never()).abandonStop(anyLong(), any(), anyLong(), any());
        verify(tasks, never()).retryStop(anyLong(), any(), anyLong(), any(), any());
    }

    @Test void runningSqlKeepsReservationUntilLaterConfirmedCancellation() throws Exception {
        install(DataAnalysisReservationState.TASK_ATTACHED);
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCEL_INTENT_RECORDED).setTaskId(TASK).setStatus("RUNNING").build());
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder().setTaskId(TASK).setStatus("RUNNING").build());
        worker.runBatch();
        assertCapacityHeld();
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
        verify(tasks).retryStop(eq(JOB_ID), anyString(), eq(1L), any(), eq("sandbox_not_terminal"));
        verify(tasks, never()).finishStop(anyLong(), any(), anyLong(), any());
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId(TASK).setStatus("CANCELED").setFinishedAt(FINISHED).build());
        claimAgain(); worker.runBatch(); occupyReleasedSlot();
        verify(tasks).finishStop(eq(JOB_ID), anyString(), eq(1L), any());
    }

    @Test void unavailableSqlLookupRetriesWithoutCancelingOrReleasing() throws Exception {
        install(DataAnalysisReservationState.PREPARING);
        when(sandbox.getTaskByOperationId(any())).thenReturn(null);
        worker.runBatch(); assertCapacityHeld();
        verify(sandbox, never()).cancelTask(any());
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
        verify(tasks).retryStop(eq(JOB_ID), anyString(), eq(1L), any(), eq("operation_lookup_unavailable"));
    }

    @Test void incompleteCanceledSqlResultRetriesWithoutReleasingCapacity() throws Exception {
        install(DataAnalysisReservationState.TASK_ATTACHED);
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder().setTaskId(TASK).setStatus("CANCELED").build());
        worker.runBatch(); assertCapacityHeld();
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
        verify(tasks).retryStop(eq(JOB_ID), anyString(), eq(1L), any(), eq("sandbox_terminal_result_unavailable"));
        when(sandbox.getTaskResult(any())).thenReturn(result());
        claimAgain(); worker.runBatch(); occupyReleasedSlot();
    }

    @Test void usageFailureAfterReleaseRetriesWithoutReopeningOrDoubleReleasingCapacity() throws Exception {
        install(DataAnalysisReservationState.PREPARING);
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.CONFLICT, DataAnalysisUpsertOutcome.INSERTED);
        worker.runBatch();
        verify(tasks).retryStop(eq(JOB_ID), anyString(), eq(1L), any(), eq("sandbox_settlement_incomplete"));
        verify(tasks, never()).finishStop(anyLong(), any(), anyLong(), any());
        DataAnalysisReservation next = occupyReleasedSlot();
        claimAgain(); worker.runBatch();
        assertThat((Integer) ReflectionTestUtils.invokeMethod(capacity, "usedUnitsSnapshot")).isEqualTo(1);
        verify(capacity, never()).releaseReservation(argThat(request -> request.reservation().identity().equals(next.identity())));
        verify(tasks).finishStop(eq(JOB_ID), anyString(), eq(1L), any());
        verify(sessions).clearActive(RUN, operation.operationId());
    }

    @Test void alreadyFinishedSqlBusinessFailureReleasesCapacityWithoutRevivingCanceledMember() throws Exception {
        install(DataAnalysisReservationState.TASK_ATTACHED);
        String originalProof = member.getDispatchProofJson();
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.ALREADY_TERMINAL).setTaskId(TASK).setStatus("SUCCEEDED").build());
        when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                .setTaskId(TASK).setStatus("SUCCEEDED").setFinishedAt(FINISHED).build());
        TaskResultResponse sqlResult = TaskResultResponse.newBuilder()
                .setTaskId(TASK).setStatus("SUCCEEDED").setExitCode(0)
                .setStdout("__EXECUTE_QUERY_RESULT__{\"status\":\"FAILED\",\"error\":{\"code\":\"PLAN_REJECTED\"}}\n").build();
        var terminal = new PythonSandboxJobRunnerAdapter(sandbox, new SandboxJobObservability(null))
                .toTerminalView(sqlResult, "SUCCEEDED");
        var sqlAdapter = new SqlQueryJobResultAdapter(json);
        var business = sqlAdapter.resolveTerminal(terminal, null);
        assertThat(sqlAdapter.isSuccess(terminal)).isFalse();
        assertThat(business.success()).isFalse();
        assertThat(business.errorCode()).isEqualTo("PLAN_REJECTED");
        assertThat(json.readTree(business.output()).path("ok").asBoolean()).isFalse();
        when(sandbox.getTaskResult(any())).thenReturn(sqlResult);
        when(tasks.finishStop(eq(JOB_ID), anyString(), eq(1L), any())).thenReturn(true);
        assertThat(worker.runBatch()).isEqualTo(1);
        occupyReleasedSlot();
        verify(tasks).finishStop(eq(JOB_ID), anyString(), eq(1L), any());
        // 资源信封记录进程成功退出，业务失败正文与取消成员的事实均保留。
        verify(recorder).upsert(argThat(envelope -> envelope.terminalStatus().equals("SUCCEEDED")
                && envelope.success() && envelope.resultPreview().contains("PLAN_REJECTED")));
        assertThat(member.getState()).isEqualTo("CANCELED");
        assertThat(member.getDispatchProofJson()).isEqualTo(originalProof);
        verify(groups).findMemberByOperation(RUN, operation.operationId());
        verifyNoMoreInteractions(groups);
        verify(tasks, never()).retryStop(anyLong(), any(), anyLong(), any(), any());
    }

    private void changeProof(String field, String value) throws Exception {
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(member.getDispatchProofJson());
        changed.put(field, value); member.setDispatchProofJson(changed.toString());
    }

    enum InvalidIdentity { MEMBER_ID, GROUP_ID, TOOL, MEMBER_STATE, PROOF_OPERATION, PROOF_FINGERPRINT, PROOF_TASK }
    @ParameterizedTest @EnumSource(InvalidIdentity.class)
    void wrongSqlMemberOrProofCannotIssueCancellationOrChangeCapacity(InvalidIdentity invalid) throws Exception {
        install(DataAnalysisReservationState.TASK_ATTACHED);
        switch (invalid) {
            case MEMBER_ID -> member.setId(99L);
            case GROUP_ID -> member.setGroupId(99L);
            case TOOL -> member.setToolName("spawnSubAgent");
            case MEMBER_STATE -> member.setState("RUNNING");
            case PROOF_OPERATION -> changeProof("operationId", "another:call:1");
            case PROOF_FINGERPRINT -> changeProof("requestFingerprint", "sha256:" + "f".repeat(64));
            case PROOF_TASK -> changeProof("taskId", "another-task");
        }
        worker.runBatch(); assertCapacityHeld();
        // 指纹矛盾现在没有本地停机记录可比，要靠一次沙箱只读查询发现；无论哪种身份矛盾，
        // 都不许发取消、不许落用量、不许动容量。
        verify(sandbox, never()).cancelTask(any());
        verify(recorder, never()).upsert(any());
        verify(capacity, never()).releaseReservation(any());
        verify(tasks).abandonStop(eq(JOB_ID), anyString(), eq(1L), any());
        verify(tasks, never()).finishStop(anyLong(), any(), anyLong(), any());
    }
}
