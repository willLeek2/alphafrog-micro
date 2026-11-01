package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        when(runMapper.updateExecutionCheckpoint(
                anyString(), anyString(), any(), anyString())).thenReturn(1);
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
