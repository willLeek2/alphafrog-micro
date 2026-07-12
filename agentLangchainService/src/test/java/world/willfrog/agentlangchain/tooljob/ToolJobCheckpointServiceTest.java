package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolJobCheckpointServiceTest {

    @Mock
    private AgentRunMapper agentRunMapper;

    @Mock
    private ToolJobAnchorService anchorService;

    private ToolJobCheckpointService service;

    @BeforeEach
    void setUp() {
        service = new ToolJobCheckpointService(agentRunMapper, anchorService);
    }

    private static ToolJobAnchor buildAnchor() {
        ToolJobAnchor a = new ToolJobAnchor();
        a.setOperationId("run-1:tc-1:1");
        a.setTaskId("task-123");
        a.setToolCallId("tc-1");
        a.setAttempt(1);
        a.setSchemaVersion(1);
        return a;
    }

    private static AgentRun buildRun() {
        AgentRun r = new AgentRun();
        r.setId("run-1");
        r.setStatus(AgentRunStatus.EXECUTING);
        r.setToolJobAnchorJson(buildAnchor().toJson());
        return r;
    }

    @Test
    void shouldCaptureAndSaveValidCheckpoint() {
        AgentRun run = buildRun();
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(anchorService.checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING))).thenReturn(true);

        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1")
                .toolCallId("tc-1")
                .attempt(1)
                .taskId("task-123")
                .todoId("todo_3")
                .sequence(3)
                .completedTodos(List.of())
                .datasetSnapshotJson("{\"digest\":\"abc\"}")
                .datasetSnapshotDigest("abc123")
                .toolCallsUsed(2)
                .estimateJson("{\"cpu\":100}")
                .build();

        boolean result = service.captureAndSave(req);
        assertThat(result).isTrue();
        verify(anchorService).checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING));
    }

    @Test
    void shouldRejectBlankRunId() {
        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectRunNotFound() {
        when(agentRunMapper.findById("run-99")).thenReturn(null);
        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-99").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectOperationIdMismatch() {
        AgentRun run = buildRun();
        when(agentRunMapper.findById("run-1")).thenReturn(run);

        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-2:1") // different from anchor's "run-1:tc-1:1"
                .build();

        boolean result = service.captureAndSave(req);
        assertThat(result).isFalse();
        verify(anchorService, never()).checkpointUpdate(any(), any(), any());
    }

    @Test
    void shouldRejectToolCallIdMismatch() {
        AgentRun run = buildRun();
        when(agentRunMapper.findById("run-1")).thenReturn(run);

        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .toolCallId("tc-2") // anchor has tc-1
                .build();

        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectAttemptMismatch() {
        AgentRun run = buildRun();
        when(agentRunMapper.findById("run-1")).thenReturn(run);

        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .attempt(2) // anchor has 1
                .build();

        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectTaskIdMismatch() {
        AgentRun run = buildRun();
        when(agentRunMapper.findById("run-1")).thenReturn(run);

        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .taskId("task-999")
                .build();

        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldReturnFalseWhenCasFails() {
        AgentRun run = buildRun();
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(anchorService.checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING))).thenReturn(false);

        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1")
                .toolCallId("tc-1")
                .attempt(1)
                .taskId("task-123")
                .todoId("todo_3")
                .sequence(3)
                .datasetSnapshotJson("{\"digest\":\"abc\"}")
                .datasetSnapshotDigest("abc123")
                .toolCallsUsed(2)
                .build();

        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldPassIdentityWhenFieldsNotSet() {
        // Fields not set in request → identity check is skipped (graceful)
        AgentRun run = buildRun();
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(anchorService.checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING))).thenReturn(true);

        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .datasetSnapshotJson("{\"digest\":\"abc\"}")
                .datasetSnapshotDigest("abc123")
                .toolCallsUsed(2)
                .build();

        assertThat(service.captureAndSave(req)).isTrue();
    }

    @Test
    void shouldAcceptCheckpointWithTodos() {
        AgentRun run = buildRun();
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(anchorService.checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING))).thenReturn(true);

        CompletedTodoRecord t1 = new CompletedTodoRecord();
        t1.setTodoId("todo_1");
        t1.setDescription("fetch data");
        t1.setSequence(1);

        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1")
                .taskId("task-123")
                .todoId("todo_2")
                .completedTodos(List.of(t1))
                .datasetSnapshotJson("{\"digest\":\"abc\"}")
                .datasetSnapshotDigest("abc123")
                .toolCallsUsed(1)
                .build();

        assertThat(service.captureAndSave(req)).isTrue();
    }
}
