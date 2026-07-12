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

    private static ToolJobCheckpointRequest validRequest() {
        return ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1")
                .toolCallId("tc-1")
                .attempt(1)
                .taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3")
                .sequence(3)
                .completedTodos(List.of())
                .datasetSnapshotJson("{\"digest\":\"abc\"}")
                .datasetSnapshotDigest("abc123")
                .datasetRefsJson("[]")
                .toolCallsUsed(2)
                .estimateJson("{\"cpu\":100}")
                .build();
    }

    // ========== Gap 2: fail-closed validation ==========

    @Test
    void shouldRejectMissingTodoId() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
        verify(anchorService, never()).checkpointUpdate(any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldRejectNullCompletedTodos() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(null)
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
        verify(anchorService, never()).checkpointUpdate(any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldRejectNegativeSequence() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(-1).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectNegativeToolCallsUsed() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(-1).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectMissingDatasetSnapshotJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectInvalidDatasetSnapshotJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("not-json").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectMissingDatasetSnapshotDigest() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectMissingDatasetRefsJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectInvalidDatasetRefsJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("bad")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectMissingEstimateJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectInvalidEstimateJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("not-json").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    // ========== Gap 1: checkpointVersion CAS + stale request ==========

    @Test
    void shouldRejectCheckpointVersionMismatch() {
        ToolJobAnchor anchor = buildAnchor();
        anchor.setCheckpointVersion(3); // anchor already at v3
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setToolJobAnchorJson(anchor.toJson());

        when(agentRunMapper.findById("run-1")).thenReturn(run);

        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0) // stale: request captured at v0
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();

        assertThat(service.captureAndSave(req)).isFalse();
        verify(anchorService, never()).checkpointUpdate(any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldPassCheckpointVersionMatchAndWriteWithRequestVersion() {
        AgentRun run = buildRun(); // anchor at v0
        when(agentRunMapper.findById("run-1")).thenReturn(run);
        when(anchorService.checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING), any(), anyInt(), any(),
                any(), any(), any(), anyInt(), any())).thenReturn(true);

        assertThat(service.captureAndSave(validRequest())).isTrue();
    }

    @Test
    void shouldReturnFalseWhenCasFails() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        when(anchorService.checkpointUpdate(eq("run-1"), any(ToolJobAnchor.class),
                eq(AgentRunStatus.EXECUTING), any(), anyInt(), any(),
                any(), any(), any(), anyInt(), any())).thenReturn(false);

        assertThat(service.captureAndSave(validRequest())).isFalse();
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

    // ========== immutable identity: still fail-closed ==========

    @Test
    void shouldRejectOperationIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-2:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectToolCallIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-2").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectAttemptMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(2).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectTaskIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-999")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectWhenIdentityMissing() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
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
                .expectedCheckpointVersion(0)
                .todoId("todo_2").completedTodos(List.of(t1))
                .datasetSnapshotJson("{}").datasetSnapshotDigest("d").datasetRefsJson("[]")
                .toolCallsUsed(1).estimateJson("{\"cpu\":50}").build();

        assertThat(service.captureAndSave(req)).isTrue();
    }
}
