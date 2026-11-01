package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemClaim;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.tooljob.ToolJobAnchorService;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointRequest;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointWriter;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DualPoolToolJobCoordinatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private NodeWorkItemStore store;
    private ToolJobAnchorService anchorService;
    private ToolJobCheckpointWriter checkpointWriter;
    private DualPoolDispatcher dispatcher;
    private DualPoolToolJobCoordinator coordinator;

    @BeforeEach
    void setUp() {
        store = mock(NodeWorkItemStore.class);
        anchorService = mock(ToolJobAnchorService.class);
        checkpointWriter = mock(ToolJobCheckpointWriter.class);
        dispatcher = mock(DualPoolDispatcher.class);
        coordinator = new DualPoolToolJobCoordinator(
                store, anchorService, checkpointWriter, dispatcher, mapper);
    }









    private NodeWorkItem item(String state, int claimEpoch) {
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId("run-1");
        item.setPlanGeneration(2);
        item.setNodeId("todo-2");
        item.setNodeAttempt(0);
        item.setSegmentSequence(0);
        item.setState(state);
        item.setContextVersion(7L);
        item.setRunControlVersion(9L);
        item.setClaimEpoch(claimEpoch);
        return item;
    }

    private ToolJobAnchor anchor(int claimEpoch) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setToolCallId("call-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-1");
        anchor.setWorkItemPlanGeneration(2);
        anchor.setWorkItemNodeId("todo-2");
        anchor.setWorkItemNodeAttempt(0);
        anchor.setWorkItemSegmentSequence(0);
        anchor.setWorkItemContextVersion(7L);
        anchor.setWorkItemRunControlVersion(9L);
        anchor.setWorkItemClaimEpoch(claimEpoch);
        anchor.setDatasetSnapshotJson("{}");
        anchor.setDatasetSnapshotDigest("sha256:test");
        return anchor;
    }
}
