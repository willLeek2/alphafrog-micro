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
import static org.mockito.ArgumentMatchers.*;
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
        a.setCheckpointVersion(0);
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
                eq(AgentRunStatus.EXECUTING), any(), anyInt(), any(),
                any(), any(), any(), anyInt(), any())).thenReturn(true);

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

        assertThat(service.captureAndSave(req)).isTrue();
    }

    @Test
    void shouldRejectBlankRunId() {
        assertThat(service.captureAndSave(ToolJobCheckpointRequest.builder("").build())).isFalse();
    }

    @Test
    void shouldRejectRunNotFound() {
        when(agentRunMapper.findById("run-99")).thenReturn(null);
        assertThat(service.captureAndSave(ToolJobCheckpointRequest.builder("run-99").build())).isFalse();
    }

    @Test
    void shouldRejectOperationIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-2:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").toolCallsUsed(0).build();
        assertThat(service.captureAndSave(req)).isFalse();
        verify(anchorService, never()).checkpointUpdate(any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldRejectToolCallIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-2").attempt(1).taskId("task-123")
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").toolCallsUsed(0).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectAttemptMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(2).taskId("task-123")
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").toolCallsUsed(0).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectTaskIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-999")
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").toolCallsUsed(0).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldReturnFalseWhenCasFails() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        when(anchorService.checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING), any(), anyInt(), any(),
                any(), any(), any(), anyInt(), any())).thenReturn(false);

        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .todoId("todo_3").sequence(3)
                .datasetSnapshotJson("{\"digest\":\"abc\"}").datasetSnapshotDigest("abc123")
                .toolCallsUsed(2).build();

        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectWhenIdentityMissing() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());

        var req = ToolJobCheckpointRequest.builder("run-1")
                .datasetSnapshotJson("{\"digest\":\"abc\"}").datasetSnapshotDigest("abc123")
                .toolCallsUsed(2).build();

        // Mandatory identity — all 4 fields must be present
        assertThat(service.captureAndSave(req)).isFalse();
        verify(anchorService, never()).checkpointUpdate(any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldAcceptCheckpointWithTodos() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        when(anchorService.checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING), any(), anyInt(), any(),
                any(), any(), any(), anyInt(), any())).thenReturn(true);

        CompletedTodoRecord t1 = new CompletedTodoRecord();
        t1.setTodoId("todo_1");
        t1.setDescription("fetch data");
        t1.setSequence(1);

        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .todoId("todo_2").completedTodos(List.of(t1))
                .datasetSnapshotJson("{\"digest\":\"abc\"}").datasetSnapshotDigest("abc123")
                .toolCallsUsed(1).build();

        assertThat(service.captureAndSave(req)).isTrue();
    }
}
