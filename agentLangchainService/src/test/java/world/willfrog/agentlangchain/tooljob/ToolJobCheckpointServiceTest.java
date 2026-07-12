package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();

    // Valid test data — constructed from domain types to guarantee correctness
    private static final String VALID_SNAPSHOT_JSON;
    private static final String VALID_SNAPSHOT_DIGEST;
    private static final String VALID_REFS_JSON = "[]";
    private static final String VALID_ESTIMATE_JSON;

    static {
        try {
            var snapshot = AgentRunDatasetSnapshot.empty();
            VALID_SNAPSHOT_JSON = om.writeValueAsString(snapshot);
            VALID_SNAPSHOT_DIGEST = snapshot.immutableDigest();

            var estimate = new DataAnalysisEstimate(0, 0, 0, 0.0, 0,
                    List.of(), DataAnalysisResourceClass.STANDARD, 1);
            VALID_ESTIMATE_JSON = om.writeValueAsString(estimate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON)
                .datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON)
                .toolCallsUsed(2)
                .estimateJson(VALID_ESTIMATE_JSON)
                .build();
    }

    // ==== Gap A: expectedCheckpointVersion must be explicitly set ====

    @Test
    void shouldRejectWhenVersionNotExplicitlySet() {
        var builder = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expectedCheckpointVersion");
    }

    @Test
    void shouldAcceptVersionExplicitlySetToZero() {
        // v0 is a valid version for new anchors; explicit set proves caller intent
        ToolJobCheckpointRequest req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(1).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(req.isVersionExplicitlySet()).isTrue();
    }

    // ==== Gap B: typed validation ====

    @Test
    void shouldRejectSnapshotLiteralNull() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("null").datasetSnapshotDigest("d")
                .datasetRefsJson(VALID_REFS_JSON)
                .toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON).build();
        assertThat(service.captureAndSave(req)).isFalse();
        verify(anchorService, never()).checkpointUpdate(any(), any(), any(),
                any(), anyInt(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void shouldRejectDigestMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest("wrong-digest")
                .datasetRefsJson(VALID_REFS_JSON)
                .toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectRefsJsonNotArray() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson("{}")
                .toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectRefsJsonWithNonStringElement() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson("[\"a\", 1]")
                .toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectRefsJsonWithNullElement() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson("[\"a\", null]")
                .toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectEstimateMissingFields() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON)
                .toolCallsUsed(0).estimateJson("{}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectEstimateMissingResourceClass() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON)
                .toolCallsUsed(0).estimateJson("{\"capacityUnits\":10}").build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectEstimateInvalidCapacityUnits() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON)
                .toolCallsUsed(0).estimateJson("{\"resourceClass\":\"STANDARD\",\"capacityUnits\":0}")
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    // ==== Existing: fail-closed for basic fields ====

    @Test
    void shouldRejectMissingTodoId() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectNullCompletedTodos() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(null)
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectNegativeSequence() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(-1).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectNegativeToolCallsUsed() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(-1).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectMissingDatasetSnapshotJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectInvalidDatasetSnapshotJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson("not-json").datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectMissingDatasetSnapshotDigest() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectMissingDatasetRefsJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectMissingEstimateJson() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    // ==== Version CAS ====

    @Test
    void shouldRejectCheckpointVersionMismatch() {
        ToolJobAnchor anchor = buildAnchor();
        anchor.setCheckpointVersion(3);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setToolJobAnchorJson(anchor.toJson());

        when(agentRunMapper.findById("run-1")).thenReturn(run);

        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldPassCheckpointVersionMatchAndWriteWithRequestVersion() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
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
        assertThat(service.captureAndSave(ToolJobCheckpointRequest.builder("")
                .expectedCheckpointVersion(0).build())).isFalse();
    }

    @Test
    void shouldRejectRunNotFound() {
        when(agentRunMapper.findById("run-99")).thenReturn(null);
        assertThat(service.captureAndSave(ToolJobCheckpointRequest.builder("run-99")
                .expectedCheckpointVersion(0).build())).isFalse();
    }

    // ==== Immutable identity ====

    @Test
    void shouldRejectOperationIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-2:1").toolCallId("tc-1").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectToolCallIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-2").attempt(1).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectAttemptMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(2).taskId("task-123")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectTaskIdMismatch() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .operationId("run-1:tc-1:1").toolCallId("tc-1").attempt(1).taskId("task-999")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
        assertThat(service.captureAndSave(req)).isFalse();
    }

    @Test
    void shouldRejectWhenIdentityMissing() {
        when(agentRunMapper.findById("run-1")).thenReturn(buildRun());
        var req = ToolJobCheckpointRequest.builder("run-1")
                .expectedCheckpointVersion(0)
                .todoId("todo_3").sequence(3).completedTodos(List.of())
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(0).estimateJson(VALID_ESTIMATE_JSON)
                .build();
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
                .datasetSnapshotJson(VALID_SNAPSHOT_JSON).datasetSnapshotDigest(VALID_SNAPSHOT_DIGEST)
                .datasetRefsJson(VALID_REFS_JSON).toolCallsUsed(1).estimateJson(VALID_ESTIMATE_JSON)
                .build();

        assertThat(service.captureAndSave(req)).isTrue();
    }
}
