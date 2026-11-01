package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ToolJobCheckpointFailureRecoveryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();









    private ToolJobCheckpointRequest request() throws Exception {
        CompletedTodoRecord todo = new CompletedTodoRecord();
        todo.setTodoId("todo-1");
        todo.setSequence(1);
        todo.setOutput("done");
        return ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1)
                .taskId("task-1").expectedCheckpointVersion(1)
                .todoId("todo-2").sequence(2).completedTodos(List.of(todo))
                .datasetSnapshotJson("{\"datasets\":[]}").datasetSnapshotDigest("digest")
                .datasetRefsJson("[\"ds-1\"]").toolCallsUsed(3)
                .estimateJson("{\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}").build();
    }

    private ToolJobAnchor equivalentAnchor(ToolJobCheckpointRequest request) throws Exception {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(request.getOperationId());
        anchor.setToolCallId(request.getToolCallId());
        anchor.setAttempt(request.getAttempt());
        anchor.setTaskId(request.getTaskId());
        anchor.setTodoId(request.getTodoId());
        anchor.setSequence(request.getSequence());
        anchor.setCompletedTodosJson(objectMapper.writeValueAsString(request.getCompletedTodos()));
        anchor.setDatasetSnapshotJson(request.getDatasetSnapshotJson());
        anchor.setDatasetSnapshotDigest(request.getDatasetSnapshotDigest());
        anchor.setDatasetRefsJson(request.getDatasetRefsJson());
        anchor.setToolCallsUsed(request.getToolCallsUsed());
        anchor.setEstimateJson(request.getEstimateJson());
        return anchor;
    }
}
