package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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

    private AgentRun run(String runId, String userId, String checkpointJson) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        run.setExecutionCheckpointJson(checkpointJson);
        return run;
    }
}
