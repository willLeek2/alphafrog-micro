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



    // ==== Gap B: typed validation ====









    // ==== Existing: fail-closed for basic fields ====










    // ==== Version CAS ====






    // ==== Immutable identity ====






}
