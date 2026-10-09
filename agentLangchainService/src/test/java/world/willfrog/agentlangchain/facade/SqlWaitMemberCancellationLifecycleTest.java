package world.willfrog.agentlangchain.facade;

import world.willfrog.agentlangchain.tooljob.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.finance.*;
import world.willfrog.agent.platform.mapper.*;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.*;
import world.willfrog.agent.platform.wait.*;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.tools.dataanalysis.SqlQueryJobResultAdapter;
import world.willfrog.agent.tools.python.PythonSandboxJobRunnerAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobObservability;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.tools.python.DataAnalysisCapacityServiceImpl;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.control.dualpool.SchedulerVersionPolicy;
import world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.facade.LangchainRunControlService;
import world.willfrog.agentlangchain.facade.LangchainRunReadService;
import world.willfrog.agentlangchain.gateway.GatewayTestFixtures;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 取消接口到等待成员资源收尾的业务链回归。
 * 数据库存储替身按当前 Mapper 的状态、操作身份和租期条件更新，而不是无条件返回成功。
 * 不启动 PostgreSQL、Redis 或 Docker；不能替代这些基础设施上的实际并发验收。
 */
class SqlWaitMemberCancellationLifecycleTest {
    private static final String RUN = "run-sql-cancel";
    private static final String USER = "4";
    private static final String TASK = "task-sql-cancel";
    private static final NodeWorkItemIdentity SEGMENT = new NodeWorkItemIdentity(RUN, 0, "todo-1", 0, 0);
    private static final String DURABLE_CALL = DurableToolCallIds.forTool("executeQuery", "call-1", SEGMENT);
    private static final String OPERATION = RUN + ":" + DURABLE_CALL + ":1";
    private static final CanonicalSandboxCreateSpec SPEC = new CanonicalSandboxCreateSpec(
            CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION, OPERATION, "a".repeat(64), "b".repeat(64),
            DataAnalysisResourceClass.STANDARD, 1024, 1000, "environment", "c".repeat(64), "d".repeat(64));
    private static final String FINGERPRINT = SPEC.requestFingerprint();
    private static final String GENERATION = "gen-" + "a".repeat(64);

    @Test
    void actualV2CancelQueuesSqlStopAndClosesOnlyAfterPersistentSettlement() throws Exception {
        Fixture f = new Fixture();
        assertThat(f.cancel()).isEqualTo("CANCELED");
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.group.getState()).isEqualTo("CANCELED");
        assertThat(f.member.getState()).isEqualTo("CANCELED");
        assertThat(f.stop.getState()).isEqualTo("PENDING");
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.anchor()).isNotNull();
        verifyNoInteractions(f.sandbox);
        assertThat(f.worker.runBatch()).isEqualTo(1);
        assertThat(f.stop.getState()).isEqualTo("CONFIRMED");
        assertThat(f.capacity.releaseReservation(f.releaseRequest()))
                .isEqualTo(DataAnalysisReleaseOutcome.ALREADY_RELEASED);
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
        assertThat(f.calls).hasSize(1);
        assertThat(f.finalizerWriteRejected).isZero();
        f.reconciler.reconcileFromDue();
        verify(f.finalization, times(1)).publishFinalizedEvent(RUN, USER, "CANCELED");
    }

    @Test
    void startupDoesNotRestoreReleasedCapacityAndClosesTheOriginalSession() throws Exception {
        Fixture f = new Fixture();
        f.cancel(); f.worker.runBatch();
        f.startup().onReady();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
        assertThat(((java.util.concurrent.atomic.AtomicInteger)
                ReflectionTestUtils.getField(f.capacity, "usedUnits")).get()).isZero();
        f.startup().onReady();
        assertThat(f.calls).hasSize(1);
        verify(f.finalization, times(1)).publishFinalizedEvent(RUN, USER, "CANCELED");
    }

    @Test
    void preparingOperationOnlyCancellationUsesOriginalTombstoneAndSameClosure() throws Exception {
        Fixture f = new Fixture(); f.makePreparing();
        f.cancel();
        assertThat(f.stop.getTaskId()).isNull();
        f.worker.runBatch();
        assertThat(f.stop.getState()).isEqualTo("CONFIRMED");
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
        verify(f.sandbox).cancelTask(argThat(request -> request.hasByOperation()
                && OPERATION.equals(request.getByOperation().getOperationId())
                && FINGERPRINT.equals(request.getByOperation().getRequestFingerprint())));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SUCCEEDED", "FAILED"})
    void completionRacingCancellationPreservesSandboxTerminalAndSingleUsage(String terminal) throws Exception {
        Fixture f = new Fixture();
        when(f.sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.ALREADY_TERMINAL).setTaskId(TASK).setStatus(terminal).build());
        when(f.sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder().setTaskId(TASK)
                .setStatus(terminal).setFinishedAt("2026-10-09T00:01:00Z").build());
        String stdout = "__EXECUTE_QUERY_RESULT__{\"status\":\"FAILED\",\"error\":{\"code\":\"PLAN_REJECTED\"}}\n";
        TaskResultResponse result = TaskResultResponse.newBuilder().setTaskId(TASK)
                .setStatus(terminal).setExitCode(terminal.equals("SUCCEEDED") ? 0 : 1)
                .setStdout(stdout).setError(terminal.equals("FAILED") ? "script failed" : "").build();
        var view = new PythonSandboxJobRunnerAdapter(f.sandbox, new SandboxJobObservability(null))
                .toTerminalView(result, terminal);
        var sql = new SqlQueryJobResultAdapter(f.json);
        var business = sql.resolveTerminal(view, null);
        assertThat(sql.isSuccess(view)).isFalse();
        assertThat(business.success()).isFalse();
        assertThat(business.errorCode()).isEqualTo(terminal.equals("SUCCEEDED") ? "PLAN_REJECTED" : "QUERY_SANDBOX_FAILED");
        assertThat(f.json.readTree(business.output()).path("ok").asBoolean()).isFalse();
        when(f.sandbox.getTaskResult(any())).thenReturn(result);
        f.cancel(); f.worker.runBatch(); f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.stop.getTerminalStatus()).isEqualTo(terminal);
        assertThat(f.calls.get(OPERATION).terminalStatus()).isEqualTo(terminal);
        assertThat(f.calls.get(OPERATION).success()).isEqualTo(terminal.equals("SUCCEEDED"));
        assertThat(f.member.getState()).isEqualTo("CANCELED");
        verifyNoInteractions(f.resume);
        assertThat(f.calls).hasSize(1);
        assertThat(f.anchor()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"stop", "group", "member", "work"})
    void otherUnfinishedResponsibilityPreventsClosureWithoutReaccounting(String kind) throws Exception {
        Fixture f = new Fixture(); f.cancel(); f.worker.runBatch();
        f.otherUnfinished = kind;
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.anchor()).isNotNull();
        f.startup().onReady();
        assertThat(((java.util.concurrent.atomic.AtomicInteger)
                ReflectionTestUtils.getField(f.capacity, "usedUnits")).get()).isZero();
        assertThat(f.anchor()).isNotNull();
        verifyNoInteractions(f.finalization);
        f.otherUnfinished = null;
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
    }

    @Test
    void changedOperationAndContradictoryProofCannotClearOrUseOrdinaryFinalizer() throws Exception {
        Fixture f = new Fixture(); f.cancel(); f.worker.runBatch();
        ToolJobAnchor a = f.anchor(); a.setRequestFingerprint("sha256:" + "f".repeat(64));
        f.run.setToolJobAnchorJson(a.toJson());
        f.reconciler.reconcileFromDue();
        assertThat(f.anchor()).isNotNull(); assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        verify(f.runMapper, never()).completeCanceledSqlWaitMember(any(), any(), any(), anyLong(), any(), any(), any());
        assertThat(f.finalizerWriteRejected).isZero();
        f.startup().onReady();
        assertThat(f.anchor()).isNotNull(); verifyNoInteractions(f.finalization);
        a.setOperationId(RUN + ":other-call:1"); f.run.setToolJobAnchorJson(a.toJson());
        f.reconciler.reconcileFromDue();
        assertThat(f.anchor().getOperationId()).isEqualTo(RUN + ":other-call:1");
    }

    @Test
    void existingBusinessTerminalIsKeptWhenTheCanceledSessionCloses() throws Exception {
        Fixture f = new Fixture(); f.cancel(); f.worker.runBatch();
        f.run.setStatus(AgentRunStatus.FAILED);
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(f.anchor()).isNull();
        verify(f.finalization).publishFinalizedEvent(RUN, USER, "FAILED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LEGACY", "DUAL_POOL_V1"})
    void olderSchedulerVersionsRemainOutsideMemberOwnedSqlRecovery(String scheduler) throws Exception {
        Fixture f = new Fixture(); f.run.setSchedulerVersion(scheduler);
        ToolJobAnchor a = f.anchor(); a.setRunDisposition("CANCELED"); a.setAutoResume(false);
        f.run.setToolJobAnchorJson(a.toJson());
        assertThat(f.recovery.ownership(RUN, a))
                .isEqualTo(CanceledSqlWaitMemberRecovery.Ownership.NOT_APPLICABLE);
    }

    @Test
    void transientCancelChainFailureIsRepairedByTheExistingAnchorScan() throws Exception {
        Fixture f = new Fixture(); f.cancelChainFailures = 1;
        assertThat(f.cancel()).isEqualTo("CANCELED");
        assertThat(f.group.getState()).isEqualTo("WAITING");
        assertThat(f.member.getState()).isEqualTo("RUNNING");
        assertThat(f.stop).isNull();
        f.reconciler.reconcileFromDue();
        assertThat(f.member.getState()).isEqualTo("CANCELED");
        assertThat(f.stop.getState()).isEqualTo("PENDING");
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.capacity.restoreReservation(f.reservation))
                .isEqualTo(DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME);
        f.worker.runBatch(); f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
        assertThat(f.calls).hasSize(1);
    }

    @Test
    void aConfirmedStopWithMissingUsageCannotCloseOrRecoverTheOldReservation() throws Exception {
        Fixture f = new Fixture(); f.cancel(); f.worker.runBatch();
        f.run.setSnapshotJson("{}");
        f.reconciler.reconcileFromDue(); f.startup().onReady();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.anchor()).isNotNull();
        assertThat(f.finalizerWriteRejected).isZero();
        assertThat(((java.util.concurrent.atomic.AtomicInteger)
                ReflectionTestUtils.getField(f.capacity, "usedUnits")).get()).isZero();
        verifyNoInteractions(f.finalization);
    }

    @Test
    void startupRereadsAStopCompletedAfterTheOldRunListWasTaken() throws Exception {
        Fixture f = new Fixture(); f.cancel();
        AgentRun stale = new AgentRun(); stale.setId(RUN); stale.setToolJobAnchorJson(f.run.getToolJobAnchorJson());
        when(f.runMapper.listActiveToolJobAnchorsForDeployment(eq("stable"), eq(GENERATION), anyInt()))
                .thenAnswer(i -> { f.worker.runBatch(); return List.of(stale); });
        f.startup().onReady();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
        assertThat(((java.util.concurrent.atomic.AtomicInteger)
                ReflectionTestUtils.getField(f.capacity, "usedUnits")).get()).isZero();
        assertThat(f.calls).hasSize(1);
    }

    @Test
    void anOrdinarySqlSessionIsNotTakenByCancellationRecovery() throws Exception {
        Fixture f = new Fixture();
        assertThat(f.recovery.ownership(RUN, f.anchor()))
                .isEqualTo(CanceledSqlWaitMemberRecovery.Ownership.NOT_APPLICABLE);
        assertThat(f.recovery.complete(RUN, f.anchor())).isFalse();
        assertThat(f.member.getState()).isEqualTo("RUNNING");
        assertThat(f.anchor()).isNotNull();
        assertThat(f.stop).isNull();
    }

    @Test
    void usageReservationChangedAfterInspectionRejectsTheAtomicClosure() throws Exception {
        Fixture f = new Fixture(); f.cancel(); f.worker.runBatch();
        f.corruptUsageOnClose = true;
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.anchor()).isNotNull();
        assertThat(f.stop.getState()).isEqualTo("CONFIRMED");
        verifyNoInteractions(f.finalization);
        f.reconciler.reconcileFromDue();
        assertThat(f.finalizerWriteRejected).isZero();
        assertThat(f.anchor()).isNotNull();
    }

    @Test
    void operationReplacedAfterInspectionCannotBeClearedByTheOldStop() throws Exception {
        Fixture f = new Fixture(); f.cancel(); f.worker.runBatch(); f.replaceOperationOnClose = true;
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.anchor().getOperationId()).isEqualTo(RUN + ":other-call:1");
        verifyNoInteractions(f.finalization);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void unconfirmedStopAtStartupRestoresTheMemberReservationExactlyOnce(boolean preparing) throws Exception {
        Fixture f = new Fixture(); if (preparing) f.makePreparing(); f.cancel();
        f.startup().onReady();
        assertThat(((java.util.concurrent.atomic.AtomicInteger)
                ReflectionTestUtils.getField(f.capacity, "usedUnits")).get()).isEqualTo(1);
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.stop.getState()).isEqualTo("PENDING");
        assertThat(f.anchor()).isNotNull();
        f.worker.runBatch(); f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.calls).hasSize(1);
    }

    @Test
    void aLateMemberWithConfirmedOriginalStopCanCloseItsCanceledSession() throws Exception {
        Fixture f = new Fixture(); f.cancel(); f.worker.runBatch(); f.member.setState("LATE");
        f.reconciler.reconcileFromDue();
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull(); assertThat(f.calls).hasSize(1);
    }

    @Test
    void coldStartStopCannotSettleBeforeTheRecoveringCapacityLedgerIsRebuilt() throws Exception {
        Fixture f = new Fixture(true);
        assertThat(f.capacity.admissionState()).isEqualTo(DataAnalysisAdmissionState.RECOVERING);
        f.cancel();
        f.worker.runBatch();
        assertThat(f.capacity.admissionState()).isEqualTo(DataAnalysisAdmissionState.RECOVERING);
        assertThat(f.stop.getState()).isEqualTo("PENDING");
        assertThat(f.stop.getLastError()).isEqualTo("sandbox_settlement_incomplete");
        assertThat(f.calls).isEmpty();
        verify(f.stopMapper, never()).confirmSandboxTerminal(anyLong(), any(), any(), any());
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.anchor()).isNotNull();
        f.startup().onReady();
        assertThat(f.capacity.admissionState()).isEqualTo(DataAnalysisAdmissionState.OPEN);
        assertThat(((java.util.concurrent.atomic.AtomicInteger)
                ReflectionTestUtils.getField(f.capacity, "usedUnits")).get()).isEqualTo(1);
        assertThat(f.capacity.restoreReservation(f.reservation)).isEqualTo(DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME);
        // 模拟重试记录的下次可见时刻已经到达；领取仍按状态、代际、令牌与租期条件。
        f.stop.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
        f.worker.runBatch(); f.reconciler.reconcileFromDue();
        assertThat(f.stop.getState()).isEqualTo("CONFIRMED");
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
        assertThat(f.calls).hasSize(1);
        assertThat(((java.util.concurrent.atomic.AtomicInteger)
                ReflectionTestUtils.getField(f.capacity, "usedUnits")).get()).isZero();
    }

    @Test
    void failedApiNodeCancellationIsRepairedBeforeTheConfirmedSqlSessionCloses() throws Exception {
        Fixture f = new Fixture(); f.addExtraNode("RUNNABLE"); f.nodeCancelFailures = 1;
        f.cancel(); f.worker.runBatch();
        assertThat(f.stop.getState()).isEqualTo("CONFIRMED");
        f.reconciler.reconcileFromDue(); f.reconciler.reconcileFromDue();
        assertThat(f.extraNode.getState()).isEqualTo("CANCELED");
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
        verify(f.nodes, times(2)).cancel(eq(f.extraNode.identity()), eq(0L), eq(3), eq("run_explicitly_canceled"));
    }

    @Test
    void interruptionAfterPersistentCancelIntentRepairsTheIndependentWaitingNode() throws Exception {
        Fixture f = new Fixture(); f.addExtraNode("WAITING");
        assertThat(f.anchors.persistCancelDisposition(RUN, OPERATION, AgentRunStatus.EXECUTING)).isTrue();
        assertThat(f.run.getRunControlVersion()).isEqualTo(1L);
        f.reconciler.reconcileFromDue(); f.worker.runBatch(); f.reconciler.reconcileFromDue();
        assertThat(f.stop.getState()).isEqualTo("CONFIRMED");
        assertThat(f.extraNode.getState()).isEqualTo("CANCELED");
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
        verify(f.nodes).cancel(eq(f.extraNode.identity()), eq(0L), eq(3), eq("run_explicitly_canceled"));
    }

    @Test
    void aChangedNodeClaimRejectsCancellationAndPreservesTheOriginalSqlSession() throws Exception {
        Fixture f = new Fixture(); f.addExtraNode("RUNNABLE"); f.nodeCancelFailures = 1;
        f.cancel(); f.worker.runBatch(); f.advanceNodeEpochOnCancel = true;
        f.reconciler.reconcileFromDue();
        assertThat(f.extraNode.getState()).isEqualTo("RUNNABLE");
        assertThat(f.extraNode.getClaimEpoch()).isEqualTo(4);
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(f.anchor()).isNotNull();
        assertThat(f.stop.getState()).isEqualTo("CONFIRMED");
        verifyNoInteractions(f.finalization);
        f.advanceNodeEpochOnCancel = false; f.reconciler.reconcileFromDue();
        assertThat(f.extraNode.getState()).isEqualTo("CANCELED");
        assertThat(f.run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
        assertThat(f.anchor()).isNull();
    }

    private static final class Fixture {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final AgentRun run = new AgentRun();
        final WaitGroup group = new WaitGroup();
        final WaitMember member = new WaitMember();
        WaitMemberStopTask stop;
        final AgentRunMapper runMapper = mock(AgentRunMapper.class);
        final WaitGroupMapper groupMapper = mock(WaitGroupMapper.class);
        final WaitMemberStopMapper stopMapper = mock(WaitMemberStopMapper.class);
        final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
        final NodeWorkItemStore nodes = mock(NodeWorkItemStore.class);
        NodeWorkItem extraNode;
        int nodeCancelFailures;
        boolean advanceNodeEpochOnCancel;
        final AgentRunEventService events = mock(AgentRunEventService.class);
        final AgentRunFinalizationService finalization = mock(AgentRunFinalizationService.class);
        final ToolJobResumeService resume = mock(ToolJobResumeService.class);
        final AtomicReference<String> redisStatus = new AtomicReference<>("EXECUTING");
        final Map<String, DataAnalysisObservabilityCall> calls = new LinkedHashMap<>();
        DataAnalysisTerminalEnvelope recordedEnvelope;
        final DataAnalysisCapacityProperties capacityConfig = new DataAnalysisCapacityProperties();
        final DataAnalysisCapacityServiceImpl capacity = new DataAnalysisCapacityServiceImpl(capacityConfig);
        final DataAnalysisReservation reservation = new DataAnalysisReservation(OPERATION,
                new DataAnalysisOperationIdentity(RUN, DURABLE_CALL, 1), DataAnalysisResourceClass.STANDARD,
                1, DataAnalysisReservationState.TASK_ATTACHED, TASK,
                Instant.parse("2026-10-09T00:00:00Z"));
        final DataAnalysisEstimate estimate = new DataAnalysisEstimate(10, 1024, 1, 0.1,
                0, List.of(), DataAnalysisResourceClass.STANDARD, 1);
        final PythonSandboxService sandbox = mock(PythonSandboxService.class);
        final ToolJobRedisCache cache = mock(ToolJobRedisCache.class);
        final ToolJobAnchorService anchors;
        final LangchainRunControlService control;
        final CanceledWaitMemberStopWorker worker;
        final ToolJobReconciler reconciler;
        final CanceledSqlWaitMemberRecovery recovery;
        String otherUnfinished;
        int cancelChainFailures;
        boolean corruptUsageOnClose;
        boolean replaceOperationOnClose;
        int finalizerWriteRejected;
        int clearRejected;

        Fixture() throws Exception { this(false); }

        Fixture(boolean coldStart) throws Exception {
            run.setId(RUN); run.setUserId(USER); run.setStatus(AgentRunStatus.EXECUTING);
            run.setSchedulerVersion("DUAL_POOL_V2"); run.setDeploymentId("stable");
            run.setDeploymentGenerationId(GENERATION); run.setLaneTag("lane-test");
            run.setRunControlVersion(0L); run.setSnapshotJson("{}");
            ToolJobAnchor anchor = new ToolJobAnchor();
            anchor.setOperationId(OPERATION); anchor.setToolCallId(DURABLE_CALL); anchor.setAttempt(1);
            anchor.setToolName("executeQuery"); anchor.setTaskId(TASK); anchor.setAnchorState("ATTACHED");
            anchor.setAutoResume(true); anchor.setRequestFingerprint(FINGERPRINT);
            anchor.setReservationJson(json.writeValueAsString(reservation));
            anchor.setEstimateJson(json.writeValueAsString(estimate));
            run.setToolJobAnchorJson(anchor.toJson());
            group.setId(7L); group.setRunId(RUN); group.setState("WAITING");
            group.setPlanGeneration(0); group.setNodeId("todo-1"); group.setNodeAttempt(0);
            group.setSegmentSequence(0); group.setNextSegmentSequence(1); group.setModelTurn(1);
            group.setSchedulerVersion("DUAL_POOL_V2");
            member.setId(41L); member.setGroupId(7L); member.setRunId(RUN);
            member.setMemberIdentity("call-1"); member.setToolCallId("call-1");
            member.setToolName("executeQuery"); member.setState("RUNNING");
            member.setExternalOperationId(OPERATION);
            member.setDispatchProofJson(json.writeValueAsString(new WaitMemberDispatchProof(
                    WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, OPERATION, TASK, FINGERPRINT,
                    json.writeValueAsString(SPEC), json.writeValueAsString(estimate),
                    json.writeValueAsString(reservation), "2026-10-09T00:00:00Z",
                    JsonFormat.printer().print(ExecuteRequest.newBuilder().setOperationId(OPERATION)
                            .setRequestFingerprint(FINGERPRINT).setCode("print('query')").build()))));
            if (!coldStart) capacity.recover(List.of(reservation), capacityConfig.getMaxUnits(), capacityConfig.getMaxHeavyActive());
            installRunMapperConditions(); installWaitMapperConditions(); installStopMapperConditions();
            anchors = new ToolJobAnchorService(runMapper);
            var ownership = GatewayTestFixtures.withIdentity(runMapper, "stable", GENERATION);
            var groups = new MybatisWaitGroupStore(groupMapper);
            doAnswer(i -> { redisStatus.set(i.getArgument(1)); return null; })
                    .when(stateStore).markRunStatus(eq(RUN), anyString());
            when(stateStore.loadRunStatus(RUN)).thenAnswer(i -> Optional.ofNullable(redisStatus.get()));
            LangchainRunReadService reads = mock(LangchainRunReadService.class);
            when(reads.requireWritableRun(RUN, USER)).thenAnswer(i -> run);
            when(reads.requireReadableRun(RUN, USER)).thenAnswer(i -> run);
            AgentRunObservabilityService observation = mock(AgentRunObservabilityService.class);
            when(observation.attachObservabilityToSnapshot(eq(RUN), any(), eq(AgentRunStatus.CANCELED)))
                    .thenReturn("{}");
            control = new LangchainRunControlService(reads, runMapper, events, stateStore, observation,
                    mock(LangchainLinearRunPipeline.class), mock(AgentRunCreditSettlementService.class),
                    anchors, finalization, ownership);
            SchedulerVersionPolicy policy = new SchedulerVersionPolicy(null);
            ReflectionTestUtils.setField(control, "schedulerVersionPolicy", policy);
            ReflectionTestUtils.setField(control, "waitGroupStore", groups);
            when(nodes.listUnfinishedByRun(RUN)).thenAnswer(i -> extraNode != null && !extraNode.stateEnum().isTerminal()
                    ? List.of(json.convertValue(extraNode, NodeWorkItem.class)) : List.of());
            when(nodes.cancel(any(), anyLong(), anyInt(), anyString())).thenAnswer(i -> {
                if (nodeCancelFailures > 0) { nodeCancelFailures--; throw new IllegalStateException("node cancellation temporarily unavailable"); }
                if (advanceNodeEpochOnCancel) extraNode.setClaimEpoch(extraNode.getClaimEpoch() + 1);
                if (extraNode == null || extraNode.stateEnum().isTerminal()
                        || !extraNode.identity().equals(i.getArgument(0))
                        || extraNode.getRunControlVersion() != (long) i.getArgument(1)
                        || extraNode.getClaimEpoch() != (int) i.getArgument(2)) return NodeWorkItemMutationResult.rejected(null);
                extraNode.setState("CANCELED"); return NodeWorkItemMutationResult.success();
            });
            ReflectionTestUtils.setField(control, "nodeWorkItemStore", nodes);
            DataAnalysisObservabilityService realRecorder = new DataAnalysisObservabilityService(
                    runMapper, stateStore, json);
            DataAnalysisTerminalRecorder recorder = envelope -> {
                recordedEnvelope = envelope;
                var outcome = realRecorder.upsert(envelope);
                if (outcome == DataAnalysisUpsertOutcome.INSERTED
                        || outcome == DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME) {
                    calls.put(envelope.operationId(), DataAnalysisObservabilityCall.fromEnvelope(envelope));
                }
                return outcome;
            };
            WaitMemberSettlement settlement = new WaitMemberSettlement(capacity, recorder, json);
            ToolJobConfig config = new ToolJobConfig();
            settlement.setDispatchStore(new PythonSandboxDispatchStoreImpl(anchors, cache, config,
                    mock(ObjectProvider.class), mock(LangchainSchedulerMetrics.class)));
            PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
            when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
            worker = new CanceledWaitMemberStopWorker(new MybatisWaitMemberStopStore(stopMapper), groups,
                    sandbox, settlement, json, tx, ownership, 2, 120, 5);
            ToolJobFinalizer finalizer = new ToolJobFinalizer(anchors, cache, capacity,
                    resume, config, mock(FinanceRecordChannelProcessor.class),
                    mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class),
                    mock(FinanceResultModelAdapter.class), runMapper, finalization, ownership);
            reconciler = new ToolJobReconciler(cache, anchors, finalizer,
                    resume, config, capacity, ownership);
            ReflectionTestUtils.setField(reconciler, "waitGroupStore", groups);
            ReflectionTestUtils.setField(reconciler, "sandboxService", sandbox);
            recovery = new CanceledSqlWaitMemberRecovery(runMapper, groups,
                    new MybatisWaitMemberStopStore(stopMapper), nodes, finalization, json);
            ReflectionTestUtils.setField(reconciler, "canceledSqlWaitMemberRecovery", recovery);
            when(cache.fetchDue(20)).thenReturn(Set.of(RUN));
            when(sandbox.getTaskByOperationId(any())).thenReturn(GetTaskByOperationIdResponse.newBuilder()
                    .setFound(true).setTaskId(TASK).setRequestFingerprint(FINGERPRINT).build());
            when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                    .setOutcome(CancelOutcome.CANCELED).setTaskId(TASK).setStatus("CANCELED").build());
            when(sandbox.getTaskStatus(any())).thenReturn(TaskStatusResponse.newBuilder()
                    .setTaskId(TASK).setStatus("CANCELED").setFinishedAt("2026-10-09T00:01:00Z").build());
            when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                    .setTaskId(TASK).setStatus("CANCELED").setError("canceled").build());
        }

        void addExtraNode(String state) {
            extraNode = new NodeWorkItem(); extraNode.setId(88L); extraNode.setRunId(RUN);
            extraNode.setPlanGeneration(0); extraNode.setNodeId("other-todo"); extraNode.setNodeAttempt(0);
            extraNode.setSegmentSequence(0); extraNode.setRunControlVersion(0L); extraNode.setClaimEpoch(3);
            extraNode.setSchedulerVersion("DUAL_POOL_V2"); extraNode.setState(state);
        }

        ToolJobStartupRecovery startup() {
            var startup = new ToolJobStartupRecovery(anchors, cache, capacity, capacityConfig,
                    mock(ToolJobFinalizer.class), resume, new ToolJobConfig(),
                    GatewayTestFixtures.withIdentity(runMapper, "stable", GENERATION),
                    new MybatisWaitGroupStore(groupMapper));
            ReflectionTestUtils.setField(startup, "canceledSqlWaitMemberRecovery", recovery);
            return startup;
        }

        void makePreparing() throws Exception {
            DataAnalysisReservation preparing = new DataAnalysisReservation(reservation.reservationId(),
                    reservation.identity(), reservation.resourceClass(), reservation.capacityUnits(),
                    DataAnalysisReservationState.PREPARING, null, reservation.acquiredAt());
            ToolJobAnchor a = anchor(); a.setTaskId(null); a.setAnchorState("PREPARING");
            a.setReservationJson(json.writeValueAsString(preparing)); run.setToolJobAnchorJson(a.toJson());
            WaitMemberDispatchProof old = WaitMemberDispatchProof.fromJson(json, member.getDispatchProofJson()).orElseThrow();
            member.setDispatchProofJson(json.writeValueAsString(new WaitMemberDispatchProof(2, OPERATION, null,
                    FINGERPRINT, old.canonicalCreateSpecJson(), old.estimateJson(), json.writeValueAsString(preparing),
                    old.submittedAt(), old.createRequestJson())));
            capacity.recover(List.of(preparing), capacityConfig.getMaxUnits(), capacityConfig.getMaxHeavyActive());
            when(sandbox.getTaskByOperationId(any())).thenReturn(GetTaskByOperationIdResponse.newBuilder()
                    .setFound(false).build());
        }

        String cancel() { return control.cancelRun(CancelAgentRunRequest.newBuilder()
                .setId(RUN).setUserId(USER).build()).getStatus(); }
        ToolJobAnchor anchor() { return anchors.loadAnchor(RUN); }

        DataAnalysisReleaseRequest releaseRequest() {
            return new DataAnalysisReleaseRequest(recordedEnvelope.reservation(),
                    new DataAnalysisReleaseProof.Terminal(recordedEnvelope),
                    DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
        }

        void installRunMapperConditions() {
            when(runMapper.findById(RUN)).thenAnswer(i -> run);
            when(runMapper.findByIdForDeployment(RUN, "stable", GENERATION)).thenAnswer(i -> run);
            when(runMapper.listActiveToolJobAnchorsForDeployment(eq("stable"), eq(GENERATION), anyInt()))
                    .thenAnswer(i -> List.of(run));
            when(runMapper.casUpdateDataAnalysisObservability(eq(RUN), any(), anyString())).thenAnswer(i -> {
                var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(run.getSnapshotJson());
                var previous = root.get("data_analysis_observability");
                var expected = i.getArgument(1) == null ? null : json.readTree((String) i.getArgument(1));
                if (!Objects.equals(previous, expected)) return 0;
                root.set("data_analysis_observability", json.readTree((String) i.getArgument(2)));
                run.setSnapshotJson(root.toString()); return 1;
            });
            when(runMapper.findByIdAndUser(RUN, USER)).thenAnswer(i -> run);
            when(runMapper.findByIdAndUserForDeployment(RUN, USER, "stable", GENERATION)).thenAnswer(i -> run);
            when(runMapper.persistCancelDisposition(eq(RUN), any(), eq(OPERATION))).thenAnswer(i -> {
                ToolJobAnchor a = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
                if (run.getStatus() != i.getArgument(1) || !OPERATION.equals(a.getOperationId())) return 0;
                if (!"CANCELED".equals(a.getRunDisposition())) run.setRunControlVersion(run.getRunControlVersion() + 1);
                a.setAutoResume(false); a.setRunDisposition("CANCELED"); run.setToolJobAnchorJson(a.toJson()); return 1;
            });
            when(runMapper.updateSnapshotIfStatus(eq(RUN), eq(USER), any(), any())).thenAnswer(i -> {
                if (run.getStatus() != i.getArgument(2)) return 0;
                run.setSnapshotJson(i.getArgument(3)); return 1;
            });
            when(runMapper.updateToolJobAnchor(eq(RUN), anyString(), any())).thenAnswer(i -> {
                ToolJobAnchor a = ToolJobAnchor.fromJson(i.getArgument(1));
                ToolJobAnchor current = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
                boolean statusMatches = run.getStatus() == i.getArgument(2)
                        || i.getArgument(2) == AgentRunStatus.WAITING_TOOL_JOB
                        && run.getStatus() == AgentRunStatus.WAITING && "PAUSED".equals(current.getRunDisposition());
                if (!statusMatches || current.isAutoResume() != a.isAutoResume()
                        || "CANCELED".equals(current.getRunDisposition()) && !"CANCELED".equals(a.getRunDisposition())) {
                    finalizerWriteRejected++; return 0;
                }
                run.setToolJobAnchorJson(a.toJson()); return 1;
            });
            when(runMapper.clearActiveToolJobAnchor(eq(RUN), any(), any())).thenAnswer(i -> {
                ToolJobAnchor a = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
                if (run.getStatus() != i.getArgument(1) || !a.getOperationId().equals(i.getArgument(2))
                        || "CANCELED".equals(a.getRunDisposition())) { clearRejected++; return 0; }
                run.setToolJobAnchorJson("{}"); return 1;
            });
            when(runMapper.completeCanceledSqlWaitMember(eq(RUN), any(), any(), anyLong(), any(), any(), any()))
                    .thenAnswer(i -> {
                        if (corruptUsageOnClose) {
                            var root = json.readTree(run.getSnapshotJson());
                            ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("data_analysis_observability")
                                    .path("calls").get(0).path("reservation")).put("capacityUnits", 99);
                            run.setSnapshotJson(root.toString());
                        }
                        if (replaceOperationOnClose) {
                            ToolJobAnchor replacement = anchor(); replacement.setOperationId(RUN + ":other-call:1");
                            run.setToolJobAnchorJson(replacement.toJson());
                        }
                        ToolJobAnchor a = anchor();
                        if (a == null || !"DUAL_POOL_V2".equals(run.getSchedulerVersion())
                                || !Set.of(AgentRunStatus.EXECUTING, AgentRunStatus.WAITING_TOOL_JOB,
                                AgentRunStatus.WAITING, AgentRunStatus.RECEIVED, AgentRunStatus.FAILED,
                                AgentRunStatus.CANCELED, AgentRunStatus.COMPLETED, AgentRunStatus.PARTIAL,
                                AgentRunStatus.EXPIRED).contains(run.getStatus())
                                || !"executeQuery".equals(a.getToolName()) || !"CANCELED".equals(a.getRunDisposition())
                                || a.isAutoResume() || !a.getOperationId().equals(i.getArgument(1))
                                || !a.getRequestFingerprint().equals(i.getArgument(2))
                                || !a.getReservationJson().equals(i.getArgument(5))
                                || member.getId() != (long) i.getArgument(3)
                                || !"executeQuery".equals(member.getToolName())
                                || !Set.of("CANCELED", "LATE").contains(member.getState())
                                || !member.getExternalOperationId().equals(i.getArgument(1))
                                || !json.readTree(member.getDispatchProofJson()).equals(json.readTree((String) i.getArgument(4)))
                                || stop == null || !"CONFIRMED".equals(stop.getState())
                                || !stop.getOperationId().equals(i.getArgument(1))
                                || !stop.getRequestFingerprint().equals(i.getArgument(2))
                                || !stop.getRunId().equals(RUN) || !stop.getGroupId().equals(member.getGroupId())
                                || !Objects.equals(stop.getTaskId(), stop.getTerminalTaskId())
                                || otherUnfinished != null || extraNode != null && !extraNode.stateEnum().isTerminal()
                                || Set.of("WAITING", "READY").contains(group.getState())
                                || Set.of("PENDING", "RUNNING").contains(member.getState())) return 0;
                        var proof = WaitMemberDispatchProof.fromJson(json, member.getDispatchProofJson()).orElseThrow();
                        if (!proof.replayable() || !proof.operationId().equals(i.getArgument(1))
                                || !proof.requestFingerprint().equals(i.getArgument(2))
                                || proof.taskId() != null && !proof.taskId().equals(stop.getTerminalTaskId())
                                || a.getTaskId() != null && !a.getTaskId().equals(stop.getTerminalTaskId())) return 0;
                        boolean terminalPresent = false;
                        for (var call : json.readTree(run.getSnapshotJson()).path("data_analysis_observability").path("calls")) {
                            if (stop.getOperationId().equals(call.path("operationId").asText())
                                    && stop.getTerminalTaskId().equals(call.path("taskId").asText())
                                    && stop.getTerminalStatus().equals(call.path("terminalStatus").asText())
                                    && call.path("reservation").path("state").asText().equals("TERMINAL_CONFIRMED")
                                    && !call.path("terminalAt").asText().isBlank()
                                    && call.path("reservation").equals(json.readTree((String) i.getArgument(6)))) terminalPresent = true;
                        }
                        if (!terminalPresent) return 0;
                        if (!Set.of(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.COMPLETED,
                                AgentRunStatus.PARTIAL, AgentRunStatus.EXPIRED).contains(run.getStatus())) {
                            run.setStatus(AgentRunStatus.CANCELED);
                        }
                        run.setToolJobAnchorJson("{}"); return 1;
                    });
            when(runMapper.closeResidualCanceledAnchorOnTerminalRun(eq(RUN), any())).thenAnswer(i -> {
                ToolJobAnchor a = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
                if (!Set.of(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.COMPLETED,
                        AgentRunStatus.PARTIAL, AgentRunStatus.EXPIRED).contains(run.getStatus())
                        || !OPERATION.equals(i.getArgument(1)) || !"CANCELED".equals(a.getRunDisposition())
                        || a.isAutoResume() || !Set.of("EVENT", "CAS_STATUS", "RESUME_READY", "CANCELED")
                        .contains(a.getFinalizerStep() == null ? "" : a.getFinalizerStep())) return 0;
                run.setToolJobAnchorJson("{}"); return 1;
            });
        }

        void installWaitMapperConditions() {
            when(groupMapper.listOpenGroupsByRun(eq(RUN), anyLong(), anyInt())).thenAnswer(i ->
                    Set.of("WAITING", "READY").contains(group.getState()) && (long) i.getArgument(1) < group.getId()
                            ? List.of(group) : List.of());
            when(groupMapper.findMemberByOperation(RUN, OPERATION)).thenAnswer(i -> member);
            when(groupMapper.findGroupById(7L)).thenAnswer(i -> group);
            when(groupMapper.listMembers(7L)).thenAnswer(i -> List.of(member));
            when(groupMapper.scanUnresolvedPythonMembersForCapacity(eq("stable"), eq(GENERATION), anyLong(), anyInt()))
                    .thenAnswer(i -> stop != null && !"CONFIRMED".equals(stop.getState())
                            && member.getId() > (long) i.getArgument(2) ? List.of(member) : List.of());
            when(groupMapper.cancelChain(7L)).thenAnswer(i -> {
                if (cancelChainFailures > 0) { cancelChainFailures--; throw new IllegalStateException("database temporarily unavailable"); }
                WaitChainCancelRow row = new WaitChainCancelRow();
                boolean changed = Set.of("WAITING", "READY").contains(group.getState());
                row.setGroupsCanceled(changed ? 1 : 0); row.setMembersCanceled(0);
                row.setSegmentsCanceled(changed ? 1 : 0); row.setNotificationsCanceled(0);
                if (changed) {
                    group.setState("CANCELED");
                    if (Set.of("PENDING", "RUNNING").contains(member.getState())) {
                        member.setState("CANCELED"); row.setMembersCanceled(1);
                        if (Set.of("executePython", "executeQuery").contains(member.getToolName())
                                && member.getDispatchProofJson() != null && stop == null) {
                            stop = new WaitMemberStopTask(); stop.setId(9L); stop.setWaitMemberId(41L);
                            stop.setRunId(RUN); stop.setGroupId(7L); stop.setOperationId(OPERATION);
                            stop.setRequestFingerprint(FINGERPRINT);
                            stop.setTaskId(WaitMemberDispatchProof.fromJson(json, member.getDispatchProofJson())
                                    .orElseThrow().taskId());
                            stop.setCancelRequestId("wait-member-41"); stop.setState("PENDING");
                            stop.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1)); stop.setAttemptCount(0);
                        }
                    }
                }
                return row;
            });
        }

        void installStopMapperConditions() {
            when(stopMapper.claimDue(any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
                OffsetDateTime now = i.getArgument(4);
                if (stop == null || !run.getDeploymentId().equals(i.getArgument(0))
                        || !run.getDeploymentGenerationId().equals(i.getArgument(1))
                        || !("PENDING".equals(stop.getState()) && !stop.getNextAttemptAt().isAfter(now)
                        || "CLAIMED".equals(stop.getState()) && !stop.getLeaseUntil().isAfter(now))) return null;
                stop.setState("CLAIMED"); stop.setClaimedBy(i.getArgument(2)); stop.setClaimToken(i.getArgument(3));
                stop.setLeaseUntil(i.getArgument(5)); stop.setAttemptCount(stop.getAttemptCount() + 1); return stop.getId();
            });
            when(stopMapper.findById(9L)).thenAnswer(i -> stop);
            when(stopMapper.findByWaitMemberId(41L)).thenAnswer(i -> stop);
            when(stopMapper.retry(eq(9L), any(), any(), any())).thenAnswer(i -> {
                if (stop == null || !"CLAIMED".equals(stop.getState())
                        || !stop.getClaimToken().equals(i.getArgument(1))
                        || !stop.getLeaseUntil().isAfter(OffsetDateTime.now())) return 0;
                stop.setState("PENDING"); stop.setClaimToken(null); stop.setLeaseUntil(null);
                stop.setNextAttemptAt(i.getArgument(2)); stop.setLastError(i.getArgument(3)); return 1;
            });
            when(stopMapper.confirmSandboxTerminal(eq(9L), any(), any(), any())).thenAnswer(i -> {
                DataAnalysisObservabilityCall call = null;
                var persisted = json.readTree(run.getSnapshotJson()).path("data_analysis_observability").path("calls");
                for (var item : persisted) if (OPERATION.equals(item.path("operationId").asText())) {
                    call = json.treeToValue(item, DataAnalysisObservabilityCall.class);
                }
                if (stop == null || !"CLAIMED".equals(stop.getState())
                        || !stop.getClaimToken().equals(i.getArgument(1)) || !stop.getLeaseUntil().isAfter(OffsetDateTime.now())
                        || stop.getTaskId() != null && !stop.getTaskId().equals(i.getArgument(2)) || call == null || !TASK.equals(call.taskId())
                        || !call.terminalStatus().equals(i.getArgument(3)) || call.terminalAt() == null
                        || call.reservation().state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) return 0;
                stop.setState("CONFIRMED"); stop.setTaskId(TASK); stop.setTerminalTaskId(TASK); stop.setTerminalStatus(i.getArgument(3));
                stop.setClaimToken(null); stop.setLeaseUntil(null); return 1;
            });
        }
    }
}
