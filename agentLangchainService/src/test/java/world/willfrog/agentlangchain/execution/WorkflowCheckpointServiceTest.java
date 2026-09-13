package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowCheckpointServiceTest {

    private AgentRunMapper runMapper;
    private RunRawRefStore rawRefStore;
    private ObjectMapper objectMapper;
    private WorkflowCheckpointService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentRunMapper.class);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        @SuppressWarnings("unchecked")
        ObjectProvider<RunRawRefStore> rawRefProvider = mock(ObjectProvider.class);
        rawRefStore = mock(RunRawRefStore.class);
        when(rawRefProvider.getIfAvailable()).thenReturn(rawRefStore);
        service = new WorkflowCheckpointService(
                runMapper, objectMapper, rawRefProvider, new ToolRetrySafetyCatalog());
        when(runMapper.updateExecutionCheckpoint(anyString(), anyString(), anyString())).thenReturn(1);
    }

    @Test
    void toolStartIsDurableBeforeExecutionAndUnsafeDagRestartIsRejected() throws Exception {
        service.initializeDag("run-1", "user-1");
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(runMapper).updateExecutionCheckpoint(anyString(), anyString(), jsonCaptor.capture());

        AgentRun run = run("run-1", "user-1", jsonCaptor.getValue());
        when(runMapper.findById("run-1")).thenReturn(run);
        service.markToolStarted("run-1", "user-1", "spawnSubAgent");

        verify(runMapper, org.mockito.Mockito.times(2))
                .updateExecutionCheckpoint(anyString(), anyString(), jsonCaptor.capture());
        run.setExecutionCheckpointJson(jsonCaptor.getAllValues().get(2));

        assertThatThrownBy(() -> service.parseAndValidateDagRestart(run))
                .hasMessage("workflow_restart_unsafe_tool_started:spawnSubAgent");
    }

    @Test
    void successfulLinearBoundaryClearsCurrentTodoToolJournal() throws Exception {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("read").build()))
                .build();
        service.initializeLinear("run-1", "user-1", plan);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(runMapper).updateExecutionCheckpoint(anyString(), anyString(), jsonCaptor.capture());
        AgentRun run = run("run-1", "user-1", jsonCaptor.getValue());
        when(runMapper.findById("run-1")).thenReturn(run);

        service.markToolStarted("run-1", "user-1", "searchWeb");
        verify(runMapper, org.mockito.Mockito.times(2))
                .updateExecutionCheckpoint(anyString(), anyString(), jsonCaptor.capture());
        String markedJson = jsonCaptor.getAllValues().get(2);
        assertThat(objectMapper.readValue(markedJson, WorkflowExecutionCheckpoint.class).getStartedTools())
                .containsExactly("searchWeb");

        service.persistLinearProgress("run-1", "user-1", plan,
                List.of(LangchainCompletedTodo.builder()
                        .todoId("todo_1").sequence(1).description("read")
                        .output("done").summary("done").build()), 1);
        verify(runMapper, org.mockito.Mockito.times(3))
                .updateExecutionCheckpoint(anyString(), anyString(), jsonCaptor.capture());
        WorkflowExecutionCheckpoint completed = objectMapper.readValue(
                jsonCaptor.getAllValues().get(5), WorkflowExecutionCheckpoint.class);
        assertThat(completed.getStartedTools()).isEmpty();
        assertThat(completed.getNextTodoId()).isEqualTo(WorkflowExecutionCheckpoint.FINAL_TODO_ID);
    }

    @Test
    void restartValidatesSequentialAndCompactionRawRefs() throws Exception {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("read").build()))
                .build();
        String compactionRef = "raw_01234567-89ab-cdef-0123-456789abcdef";
        WorkflowExecutionCheckpoint checkpoint = service.persistLinearProgress(
                "run-1", "user-1", plan,
                List.of(LangchainCompletedTodo.builder()
                        .todoId("todo_1")
                        .sequence(1)
                        .description("read")
                        .modelOutput("short=raw_ref_001 compact=" + compactionRef)
                        .output("done")
                        .summary("done")
                        .build()),
                1);
        AgentRun run = run("run-1", "user-1", objectMapper.writeValueAsString(checkpoint));

        service.parseAndValidate(run, plan);

        verify(rawRefStore).read("run-1", "user-1", "raw_ref_001", 0, 1, null);
        verify(rawRefStore).read("run-1", "user-1", compactionRef, 0, 1, null);
    }

    @Test
    void deploymentIdentityCheckpointWriteUsesTheRowCurrentStatus() {
        String generation = "gen-" + "a".repeat(64);
        ReflectionTestUtils.setField(service, "deploymentIdentityProvider",
                (DeploymentIdentityProvider) () -> new DeploymentIdentity("beta-main-001", generation));
        AgentRun run = run("run-1", "user-1", null);
        // 长工具恢复窗口：Run 停在 RECEIVED 直到 markHandoffAccepted。
        run.setStatus(AgentRunStatus.RECEIVED);
        when(runMapper.findByIdForDeployment("run-1", "beta-main-001", generation)).thenReturn(run);
        when(runMapper.updateExecutionCheckpointForDeployment(
                anyString(), anyString(), anyString(), anyString(), any(), anyString())).thenReturn(1);

        service.initializeLinear("run-1", "user-1", linearPlan());

        ArgumentCaptor<AgentRunStatus> statusCaptor = ArgumentCaptor.forClass(AgentRunStatus.class);
        verify(runMapper).updateExecutionCheckpointForDeployment(
                eq("run-1"), eq("user-1"), eq("beta-main-001"), eq(generation),
                statusCaptor.capture(), anyString());
        assertThat(statusCaptor.getValue()).isEqualTo(AgentRunStatus.RECEIVED);
    }

    @Test
    void deploymentIdentityCheckpointWriteKeepsExecutingWhenRowIsExecuting() {
        String generation = "gen-" + "a".repeat(64);
        ReflectionTestUtils.setField(service, "deploymentIdentityProvider",
                (DeploymentIdentityProvider) () -> new DeploymentIdentity("beta-main-001", generation));
        AgentRun run = run("run-1", "user-1", null);
        run.setStatus(AgentRunStatus.EXECUTING);
        when(runMapper.findByIdForDeployment("run-1", "beta-main-001", generation)).thenReturn(run);
        when(runMapper.updateExecutionCheckpointForDeployment(
                anyString(), anyString(), anyString(), anyString(), any(), anyString())).thenReturn(1);

        service.initializeLinear("run-1", "user-1", linearPlan());

        ArgumentCaptor<AgentRunStatus> statusCaptor = ArgumentCaptor.forClass(AgentRunStatus.class);
        verify(runMapper).updateExecutionCheckpointForDeployment(
                eq("run-1"), eq("user-1"), eq("beta-main-001"), eq(generation),
                statusCaptor.capture(), anyString());
        assertThat(statusCaptor.getValue()).isEqualTo(AgentRunStatus.EXECUTING);
    }

    @Test
    void deploymentIdentityCheckpointWriteFailsClosedWhenRowIsMissingOrForeign() {
        String generation = "gen-" + "a".repeat(64);
        ReflectionTestUtils.setField(service, "deploymentIdentityProvider",
                (DeploymentIdentityProvider) () -> new DeploymentIdentity("beta-main-001", generation));

        // 行不存在（默认 mock 返回 null）→ 0 行，仍映射成条带错误。
        assertThatThrownBy(() -> service.initializeLinear("run-1", "user-1", linearPlan()))
                .hasMessage("workflow_checkpoint_run_not_found");

        // userId 对不上 → 同样拒绝写入。
        AgentRun foreign = run("run-1", "someone-else", null);
        foreign.setStatus(AgentRunStatus.RECEIVED);
        when(runMapper.findByIdForDeployment("run-1", "beta-main-001", generation))
                .thenReturn(foreign);
        assertThatThrownBy(() -> service.initializeLinear("run-1", "user-1", linearPlan()))
                .hasMessage("workflow_checkpoint_run_not_found");
    }

    private static LangchainTodoPlan linearPlan() {
        return LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("read").build()))
                .build();
    }

    private AgentRun run(String runId, String userId, String checkpointJson) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        run.setExecutionCheckpointJson(checkpointJson);
        return run;
    }
}
