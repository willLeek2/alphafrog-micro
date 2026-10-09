package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SqlWaitMemberSessionAdmissionTest {
    @TempDir Path temp;

    @Test
    void secondQueryInSameRunIsRetryableBusyBeforeMemberProofOrSandboxCreate() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path csv = temp.resolve("sample.csv");
        Files.writeString(csv, "n\n1\n");
        Files.writeString(temp.resolve("sample.meta.json"), "{\"rowCount\":1,\"bytes\":4}");
        AgentRunDatasetRegistry registry = mock(AgentRunDatasetRegistry.class);
        when(registry.findDatasetByNumber("run-1", 1)).thenReturn(Optional.of(
                AgentRunDatasetEntry.forDataset(1, "data-1", csv.toString(), "stock", "sample.csv")));
        ToolJobAnchor first = new ToolJobAnchor();
        first.setToolName(ToolJobAnchor.EXECUTE_QUERY_TOOL);
        first.setOperationId("run-1:first:1");
        first.setTaskId("task-first");
        first.setAnchorState("ATTACHED");
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        run.setToolJobAnchorJson(first.toJson());
        AgentRunMapper runs = mock(AgentRunMapper.class);
        when(runs.findById("run-1")).thenReturn(run);
        ToolJobAnchorService anchors = new ToolJobAnchorService(runs);
        PythonSandboxDispatchStore store = mock(PythonSandboxDispatchStore.class);
        when(store.persistPreparing(eq("run-1"), any())).thenAnswer(invocation ->
                anchors.claimPreparing("run-1", invocation.getArgument(1), AgentRunStatus.EXECUTING));
        DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
        when(capacity.reserve(any(), any())).thenAnswer(invocation -> {
            DataAnalysisOperationIdentity identity = invocation.getArgument(0);
            DataAnalysisEstimate estimate = invocation.getArgument(1);
            return new DataAnalysisReservation(identity.reservationId(), identity, estimate.resourceClass(),
                    estimate.capacityUnits(), DataAnalysisReservationState.PREPARING, null, Instant.now());
        });
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        AgentRunBudgetService budget = mock(AgentRunBudgetService.class);
        when(budget.remainingWallClockMs()).thenReturn(600_000L);
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        WaitGroupStore groups = mock(WaitGroupStore.class);
        SqlQueryTools query = new SqlQueryTools(mapper);
        ReflectionTestUtils.setField(query, "agentRunDatasetRegistry", registry);
        ReflectionTestUtils.setField(query, "dataAnalysisCapacityProperties", new DataAnalysisCapacityProperties());
        ReflectionTestUtils.setField(query, "pythonSandboxDispatchStore", store);
        ReflectionTestUtils.setField(query, "dataAnalysisCapacityService", capacity);
        ReflectionTestUtils.setField(query, "dataAnalysisTerminalRecorder", mock(DataAnalysisTerminalRecorder.class));
        ReflectionTestUtils.setField(query, "agentRunBudgetService", budget);
        ReflectionTestUtils.setField(query, "pythonSandboxService", sandbox);
        ReflectionTestUtils.setField(query, "waitGroupStore", groups);
        DataAnalysisOperationIdentity second = new DataAnalysisOperationIdentity("run-1", "second", 1);
        var member = new WaitGroupMemberExecutionContext.Snapshot(
                "run-1", 7L, "member-second", 1, "second", second.operationId(), "segment");
        AgentContext.setRunId("run-1");
        try (var ignored = WaitGroupMemberExecutionContext.install(member)) {
            var output = mapper.readTree(query.executeQuery("SELECT n FROM t1 LIMIT 1", "1", "INTERACTIVE"));
            assertThat(output.path("ok").asBoolean()).isFalse();
            assertThat(output.path("error").path("code").asText()).isEqualTo("SESSION_QUERY_IN_PROGRESS");
            assertThat(output.path("error").path("details").path("retryable").asBoolean()).isTrue();
        } finally {
            AgentContext.clear();
        }
        verify(runs).lockExecuteQuerySession("user-1");
        verify(runs, never()).claimPreparingToolJobAnchor(any(), any(), any());
        verifyNoInteractions(sandbox, groups);
        verify(capacity).releaseReservation(argThat(request ->
                request.reason() == DataAnalysisReleaseReason.CREATE_NOT_STARTED
                        && request.reservation().operationId().equals(second.operationId())));
        verify(store, never()).clearActive(any(), any());
        assertThat(ToolJobAnchor.fromJson(run.getToolJobAnchorJson()).getTaskId()).isEqualTo("task-first");
    }
}
