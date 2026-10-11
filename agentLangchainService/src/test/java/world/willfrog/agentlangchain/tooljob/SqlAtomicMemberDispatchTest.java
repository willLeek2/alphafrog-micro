package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.wait.*;
import world.willfrog.agent.platform.workitem.*;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.workflow.*;
import world.willfrog.alphafrogmicro.sandbox.idl.*;
import world.willfrog.agentlangchain.control.dualpool.DualPoolToolJobExecutionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlAtomicMemberDispatchTest {
    @TempDir Path temp;
    enum Mode { FRESH, BEFORE, AFTER, INTERRUPTED_COMMITTED, INTERRUPTED_NOT_WRITTEN, BUSY_READBACK, CANCELED_READBACK, CANCELED_NO_WRITE, RESTORE_FAILED, CAS_ZERO_NO_WRITE, CAS_ZERO_COMMITTED, COMMIT_INTERRUPTED }
    @AfterEach void cleanThread() { Thread.interrupted(); MemberPreparingInterruption.consume(); AgentContext.clear(); }

    @ParameterizedTest @EnumSource(Mode.class)
    void realSqlUsesOneAtomicPreparingStepAndOnlyCreatesAfterCommit(Mode mode) throws Exception {
        var f = new SqlAtomicMemberPreparingTest.Fixture();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Path csv = temp.resolve("sample.csv");
        Files.writeString(csv, "n\n1\n");
        Files.writeString(temp.resolve("sample.meta.json"), "{\"rowCount\":1,\"bytes\":4}");
        AgentRunDatasetRegistry datasets = mock(AgentRunDatasetRegistry.class);
        when(datasets.findDatasetByNumber("run", 1)).thenReturn(Optional.of(
                AgentRunDatasetEntry.forDataset(1, "data", csv.toString(), "stock", "sample.csv")));
        ToolJobAnchorService service = mock(ToolJobAnchorService.class);
        when(service.claimPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any())).thenAnswer(call -> {
            if (mode == Mode.INTERRUPTED_NOT_WRITTEN || mode == Mode.CANCELED_NO_WRITE) {
                if (mode == Mode.CANCELED_NO_WRITE) {
                    f.run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.CANCELED);
                    doReturn(0).when(f.members).countPreparingSqlMemberOwner(anyString(), anyLong(), anyString(), anyString(), anyString());
                    doReturn("CANCELED").when(f.members).lockPreparingSqlMemberContext("run", 7, "member");
                }
                Thread.currentThread().interrupt(); throw new IllegalStateException("commit failed before write");
            }
            if (mode == Mode.CAS_ZERO_NO_WRITE || mode == Mode.CAS_ZERO_COMMITTED) {
                doReturn(0).when(f.runs).claimPreparingToolJobAnchor(anyString(), anyString(), any());
                if (mode == Mode.CAS_ZERO_COMMITTED) {
                    f.run.setToolJobAnchorJson(((ToolJobAnchor) call.getArgument(1)).toJson());
                    f.member.setDispatchProofJson(call.getArgument(4));
                    doReturn(0).when(f.members).countPreparingSqlMemberOwner(anyString(), anyLong(), anyString(), anyString(), anyString());
                }
                Thread.currentThread().interrupt();
            }
            boolean committed = f.service.claimPreparingWaitMember(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6));
            if (mode == Mode.BUSY_READBACK) {
                when(f.runs.countInFlightExecuteQueryByUser(anyString(), anyString(), anyString(), anyInt())).thenReturn(1);
                throw new IllegalStateException("commit response lost");
            }
            if (mode == Mode.CANCELED_READBACK) {
                f.run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.CANCELED);
                doReturn(0).when(f.members).countPreparingSqlMemberOwner(anyString(), anyLong(), anyString(), anyString(), anyString());
                throw new IllegalStateException("commit response lost");
            }
            if (mode == Mode.INTERRUPTED_COMMITTED) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("commit response lost");
            }
            if (mode == Mode.COMMIT_INTERRUPTED) Thread.currentThread().interrupt();
            return committed;
        });
        when(service.readPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any())).thenAnswer(call ->
                f.service.readPreparingWaitMember(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6)));
        ToolJobConfig config = new ToolJobConfig(); config.setReconcilerIntervalMs(1);
        PythonSandboxDispatchStoreImpl store = spy(new PythonSandboxDispatchStoreImpl(service,
                mock(ToolJobRedisCache.class), config, mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics.class)));
        DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
        when(capacity.reserve(any(), any())).thenAnswer(call -> {
            DataAnalysisOperationIdentity identity = call.getArgument(0);
            DataAnalysisEstimate estimate = call.getArgument(1);
            return new DataAnalysisReservation(identity.reservationId(), identity, estimate.resourceClass(),
                    estimate.capacityUnits(), DataAnalysisReservationState.PREPARING, null, Instant.now());
        });
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        if (mode == Mode.RESTORE_FAILED) when(capacity.restoreReservation(any())).thenThrow(new IllegalStateException("ledger unavailable"));
        AgentRunBudgetService budget = mock(AgentRunBudgetService.class);
        when(budget.remainingWallClockMs()).thenReturn(600_000L);
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        when(sandbox.createTask(any())).thenAnswer(call -> {
            assertThat(f.transactions.commits).isGreaterThan(0);
            assertThat(f.member.getDispatchProofJson()).isNotNull();
            ExecuteRequest request = call.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task").setStatus("PENDING")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });
        WaitGroupStore groups = mock(WaitGroupStore.class);
        SqlQueryTools query = new SqlQueryTools(json);
        ReflectionTestUtils.setField(query, "agentRunDatasetRegistry", datasets);
        ReflectionTestUtils.setField(query, "dataAnalysisCapacityProperties", new DataAnalysisCapacityProperties());
        ReflectionTestUtils.setField(query, "pythonSandboxDispatchStore", store);
        ReflectionTestUtils.setField(query, "dataAnalysisCapacityService", capacity);
        ReflectionTestUtils.setField(query, "dataAnalysisTerminalRecorder", mock(DataAnalysisTerminalRecorder.class));
        ReflectionTestUtils.setField(query, "agentRunBudgetService", budget);
        ReflectionTestUtils.setField(query, "pythonSandboxService", sandbox);
        ReflectionTestUtils.setField(query, "waitGroupStore", groups);
        var finance = mock(world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader.class);
        when(finance.frozenSnapshotJson()).thenReturn("{\"enabled\":true}");
        ReflectionTestUtils.setField(query, "financeRecordChannelConfigLoader", finance);
        ReflectionTestUtils.setField(query, "toolJobFaultInjector", (ToolJobFaultInjector) (runId, point) -> {
            if (mode == Mode.BEFORE && point.equals(ToolJobFaultInjector.BEFORE_SANDBOX_SUBMIT)
                    || mode == Mode.AFTER && point.equals(ToolJobFaultInjector.AFTER_SANDBOX_ACCEPTED)) {
                throw new ToolJobInjectedInterruption("fixture", point);
            }
        });
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run", "call", 1);
        AgentContext.setRunId("run");
        try (var scope = WaitGroupMemberExecutionContext.install(new WaitGroupMemberExecutionContext.Snapshot(
                "run", 7, "member", 0, "call", identity.operationId(), "segment"));
             var node = DualPoolToolJobExecutionContext.install(new NodeWorkItemIdentity("run", 2, "node", 1, 0),
                     new NodeWorkItemVersions(4, 5, 1), "worker", "{}")) {
            if (mode == Mode.INTERRUPTED_NOT_WRITTEN || mode == Mode.CANCELED_NO_WRITE || mode == Mode.CAS_ZERO_NO_WRITE) {
                var result = json.readTree(query.executeQuery("SELECT n FROM t1 LIMIT 1", "1", "INTERACTIVE"));
                assertThat(result.path("ok").asBoolean()).isFalse();
                assertThat(result.path("error").path("code").asText()).isEqualTo("WAIT_GROUP_PREPARING_NOT_RECORDED");
                f.assertNeitherWritten();
                verify(capacity).releaseReservation(any());
            } else if (mode == Mode.BEFORE || mode == Mode.AFTER) {
                assertThatThrownBy(() -> { String returned = query.executeQuery("SELECT n FROM t1 LIMIT 1", "1", "INTERACTIVE");
                    throw new AssertionError("Unexpected returned result: " + returned); })
                        .isInstanceOf(ToolJobInjectedInterruption.class);
            } else {
                assertThatThrownBy(() -> { String returned = query.executeQuery("SELECT n FROM t1 LIMIT 1", "1", "INTERACTIVE");
                    throw new AssertionError("Unexpected returned result: " + returned); })
                        .isInstanceOf(WaitGroupMemberPendingException.class);
            }
        }
        if (mode == Mode.COMMIT_INTERRUPTED) {
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(MemberPreparingInterruption.consume()).isTrue();
            assertThat(MemberPreparingInterruption.consume()).isFalse();
            verify(service, never()).readPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any());
        }
        int createCount = mode == Mode.FRESH || mode == Mode.AFTER || mode == Mode.RESTORE_FAILED ? 1 : 0;
        verify(sandbox, times(createCount)).createTask(any());
        verify(store, never()).clearActive(any(), any());
        verify(store, never()).persistPreparing(any(), any());
        verifyNoInteractions(groups);
        verify(capacity).reserve(any(), any());
        verify(finance).frozenSnapshotJson();
        if (mode != Mode.INTERRUPTED_NOT_WRITTEN && mode != Mode.CANCELED_NO_WRITE && mode != Mode.CAS_ZERO_NO_WRITE) {
            if (mode == Mode.CAS_ZERO_COMMITTED) {
                verify(f.members, never()).recordMemberPreparing(anyLong(), anyString(), anyString(), anyString());
            } else {
                verify(f.members).recordMemberPreparing(eq(7L), eq("member"), eq(identity.operationId()), anyString());
            }
            var proof = WaitMemberDispatchProof.fromJson(json, f.member.getDispatchProofJson()).orElseThrow();
            assertThat(WaitMemberDurableRequestResolver.hasValidRequest(proof)).isTrue();
            ToolJobAnchor persisted = ToolJobAnchor.fromJson(f.run.getToolJobAnchorJson());
            assertThat(persisted.getCreateRequestJson()).isEqualTo(proof.createRequestJson());
            assertThat(persisted.getReservationJson()).isEqualTo(proof.reservationJson());
            assertThat(persisted.getFinanceRecordLimitsJson()).isEqualTo("{\"enabled\":true}");
            assertThat(persisted.getWorkItemClaimEpoch()).isEqualTo(1);
            assertThat(persisted.getWorkItemClaimedBy()).isEqualTo("worker");
            verify(capacity, never()).releaseReservation(any());
            if (mode == Mode.BEFORE || mode == Mode.AFTER) {
                // 用刚刚原子提交的真实SQL证明走原恢复解析器，接受后窗口不得再发第二次create。
                when(sandbox.getTaskByOperationId(any())).thenReturn(mode == Mode.AFTER
                        ? GetTaskByOperationIdResponse.newBuilder().setFound(true).setTaskId("task")
                            .setRequestFingerprint(proof.requestFingerprint()).build()
                        : GetTaskByOperationIdResponse.getDefaultInstance());
                var recovered = WaitMemberDurableRequestResolver.resolveOutcome(proof, sandbox,
                        () -> f.service.renewExecuteQueryReplayClaim("run", 7, "member", proof.operationId(),
                                proof.requestFingerprint(), proof.createRequestJson(), 2, 5));
                assertThat(recovered.taskId()).isEqualTo("task");
                verify(sandbox, times(1)).createTask(any());
                if (mode == Mode.AFTER) {
                    verify(f.runs, never()).renewExecuteQueryReplayClaim(anyString(), anyLong(), anyString(),
                            anyString(), anyString(), anyString(), anyLong(), anyLong());
                } else {
                    verify(f.runs).renewExecuteQueryReplayClaim(eq("run"), eq(7L), eq("member"),
                            eq(proof.operationId()), eq(proof.requestFingerprint()), eq(proof.createRequestJson()), eq(2L), eq(5L));
                }
            }
        }
    }
}
