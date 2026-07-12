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

    @Test
    void equivalentHealthyNewerCheckpointWinsWithoutFailureDisposition() throws Exception {
        ToolJobAnchorService anchors = mock(ToolJobAnchorService.class);
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobCheckpointRequest request = request();
        ToolJobAnchor newer = equivalentAnchor(request);
        newer.setCheckpointVersion(2);
        when(anchors.loadAnchor("run-1")).thenReturn(newer);
        ToolJobCheckpointFailureRecoveryService service =
                new ToolJobCheckpointFailureRecoveryService(anchors, mapper, objectMapper);

        assertThat(service.handleFailure(request))
                .isEqualTo(ToolJobCheckpointFailureRecoveryService.Outcome.HEALTHY_CHECKPOINT);
        verify(anchors, never()).markCheckpointFailed(any(), any());
        verify(mapper, never()).markToolJobCheckpointFailurePending(
                any(), any(), any(), anyInt(), any(), anyInt(), any());
    }

    @Test
    void nonEquivalentNewerCheckpointGetsDurableRetryOwner() throws Exception {
        ToolJobAnchorService anchors = mock(ToolJobAnchorService.class);
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobCheckpointRequest request = request();
        ToolJobAnchor incomplete = equivalentAnchor(request);
        incomplete.setCheckpointVersion(2);
        incomplete.setDatasetRefsJson("[\"different\"]");
        when(anchors.loadAnchor("run-1")).thenReturn(incomplete);
        when(anchors.markCheckpointFailed(request, "durable_checkpoint_write_failed"))
                .thenReturn(false);
        when(mapper.markToolJobCheckpointFailurePending(
                eq("run-1"), eq("run-1:tc-1:1"), eq("tc-1"), eq(1), eq("task-1"), eq(1), any()))
                .thenReturn(1);
        ToolJobCheckpointFailureRecoveryService service =
                new ToolJobCheckpointFailureRecoveryService(anchors, mapper, objectMapper);

        assertThat(service.handleFailure(request))
                .isEqualTo(ToolJobCheckpointFailureRecoveryService.Outcome.RETRY_OWNED);
        ArgumentCaptor<String> marker = ArgumentCaptor.forClass(String.class);
        verify(mapper).markToolJobCheckpointFailurePending(
                eq("run-1"), eq("run-1:tc-1:1"), eq("tc-1"), eq(1), eq("task-1"), eq(1), marker.capture());
        assertThat(marker.getValue())
                .startsWith(ToolJobCheckpointFailureRecoveryService.MARKER_PREFIX);
    }

    @Test
    void incompleteNewerCheckpointIsNotAcceptedAsHealthy() throws Exception {
        ToolJobAnchorService anchors = mock(ToolJobAnchorService.class);
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobCheckpointRequest request = request();
        ToolJobAnchor incomplete = equivalentAnchor(request);
        incomplete.setCheckpointVersion(2);
        incomplete.setDatasetSnapshotJson(null);
        when(anchors.loadAnchor("run-1")).thenReturn(incomplete);
        when(anchors.markCheckpointFailed(request, "durable_checkpoint_write_failed"))
                .thenReturn(false);
        when(mapper.markToolJobCheckpointFailurePending(
                eq("run-1"), any(), any(), anyInt(), any(), anyInt(), any())).thenReturn(1);
        ToolJobCheckpointFailureRecoveryService service =
                new ToolJobCheckpointFailureRecoveryService(anchors, mapper, objectMapper);

        assertThat(service.handleFailure(request))
                .isEqualTo(ToolJobCheckpointFailureRecoveryService.Outcome.RETRY_OWNED);
    }

    @Test
    void reconcilerRetryConsumesDurableMarkerOnlyAfterResolution() throws Exception {
        ToolJobAnchorService anchors = mock(ToolJobAnchorService.class);
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobCheckpointRequest request = request();
        when(anchors.loadAnchor("run-1")).thenReturn(null);
        when(anchors.markCheckpointFailed(request, "durable_checkpoint_write_failed"))
                .thenReturn(false);
        when(mapper.markToolJobCheckpointFailurePending(
                eq("run-1"), any(), any(), anyInt(), any(), anyInt(), any())).thenReturn(1);
        ToolJobCheckpointFailureRecoveryService service =
                new ToolJobCheckpointFailureRecoveryService(anchors, mapper, objectMapper);
        assertThat(service.handleFailure(request))
                .isEqualTo(ToolJobCheckpointFailureRecoveryService.Outcome.RETRY_OWNED);
        ArgumentCaptor<String> marker = ArgumentCaptor.forClass(String.class);
        verify(mapper).markToolJobCheckpointFailurePending(
                eq("run-1"), any(), any(), anyInt(), any(), anyInt(), marker.capture());
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.WAITING_TOOL_JOB);
        ToolJobAnchor current = equivalentAnchor(request);
        current.setCheckpointVersion(1);
        run.setToolJobAnchorJson(current.toJson());
        run.setLastError(marker.getValue());
        when(mapper.findById("run-1")).thenReturn(run);
        when(anchors.markCheckpointFailed(any(ToolJobCheckpointRequest.class),
                eq("durable_checkpoint_write_failed"))).thenReturn(true);
        when(mapper.clearToolJobCheckpointFailurePending("run-1", marker.getValue())).thenReturn(1);

        assertThat(service.retryPending("run-1")).isTrue();
        verify(mapper).clearToolJobCheckpointFailurePending("run-1", marker.getValue());
    }

    @Test
    void staleWriterCannotAttachMarkerAfterOwnerChanges() throws Exception {
        ToolJobAnchorService anchors = mock(ToolJobAnchorService.class);
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobCheckpointRequest request = request();
        ToolJobAnchor old = equivalentAnchor(request);
        old.setCheckpointVersion(1);
        when(anchors.loadAnchor("run-1")).thenReturn(old);
        when(anchors.markCheckpointFailed(request, "durable_checkpoint_write_failed"))
                .thenReturn(false);
        when(mapper.markToolJobCheckpointFailurePending(
                eq("run-1"), any(), any(), anyInt(), any(), anyInt(), any())).thenReturn(0);

        ToolJobAnchor replacement = equivalentAnchor(request);
        replacement.setOperationId("run-1:tc-2:1");
        replacement.setToolCallId("tc-2");
        replacement.setTaskId("task-2");
        replacement.setCheckpointVersion(2);
        AgentRun replacedRun = new AgentRun();
        replacedRun.setId("run-1");
        replacedRun.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.WAITING_TOOL_JOB);
        replacedRun.setToolJobAnchorJson(replacement.toJson());
        when(mapper.findById("run-1")).thenReturn(replacedRun);

        ToolJobCheckpointFailureRecoveryService service =
                new ToolJobCheckpointFailureRecoveryService(anchors, mapper, objectMapper);

        assertThat(service.handleFailure(request))
                .isEqualTo(ToolJobCheckpointFailureRecoveryService.Outcome.SUPERSEDED);
        verify(anchors, times(1)).markCheckpointFailed(
                request, "durable_checkpoint_write_failed");
    }

    @Test
    void sameOwnerMarkerCasMissGetsBoundedFailureDispositionRetry() throws Exception {
        ToolJobAnchorService anchors = mock(ToolJobAnchorService.class);
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobCheckpointRequest request = request();
        ToolJobAnchor current = equivalentAnchor(request);
        current.setCheckpointVersion(1);
        when(anchors.loadAnchor("run-1")).thenReturn(current);
        when(anchors.markCheckpointFailed(request, "durable_checkpoint_write_failed"))
                .thenReturn(false, true);
        when(mapper.markToolJobCheckpointFailurePending(
                eq("run-1"), any(), any(), anyInt(), any(), anyInt(), any())).thenReturn(0);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.WAITING_TOOL_JOB);
        run.setToolJobAnchorJson(current.toJson());
        run.setLastError("unrelated_error");
        when(mapper.findById("run-1")).thenReturn(run);

        ToolJobCheckpointFailureRecoveryService service =
                new ToolJobCheckpointFailureRecoveryService(anchors, mapper, objectMapper);

        assertThat(service.handleFailure(request))
                .isEqualTo(ToolJobCheckpointFailureRecoveryService.Outcome.FAILURE_OWNED);
        verify(anchors, times(2)).markCheckpointFailed(
                request, "durable_checkpoint_write_failed");
    }

    @Test
    void reconcilerCompareClearsMarkerWhoseFrozenOwnerWasReplaced() throws Exception {
        ToolJobAnchorService anchors = mock(ToolJobAnchorService.class);
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobCheckpointRequest request = request();
        ToolJobCheckpointFailureRecoveryService.PendingFailure pending =
                ToolJobCheckpointFailureRecoveryService.PendingFailure.from(request);
        String marker = ToolJobCheckpointFailureRecoveryService.MARKER_PREFIX
                + objectMapper.writeValueAsString(pending);

        ToolJobAnchor replacement = equivalentAnchor(request);
        replacement.setOperationId("run-1:tc-new:1");
        replacement.setToolCallId("tc-new");
        replacement.setTaskId("task-new");
        replacement.setCheckpointVersion(2);
        when(anchors.loadAnchor("run-1")).thenReturn(replacement);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.WAITING_TOOL_JOB);
        run.setToolJobAnchorJson(replacement.toJson());
        run.setLastError(marker);
        when(mapper.findById("run-1")).thenReturn(run);
        when(mapper.clearToolJobCheckpointFailurePending("run-1", marker)).thenReturn(1);

        ToolJobCheckpointFailureRecoveryService service =
                new ToolJobCheckpointFailureRecoveryService(anchors, mapper, objectMapper);

        assertThat(service.retryPending("run-1")).isTrue();
        verify(anchors, never()).markCheckpointFailed(any(), any());
        verify(mapper).clearToolJobCheckpointFailurePending("run-1", marker);
    }

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
