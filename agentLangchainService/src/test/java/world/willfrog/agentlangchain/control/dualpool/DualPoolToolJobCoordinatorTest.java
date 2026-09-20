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

    @Test
    void suspendCapturesTodoCheckpointBeforeReleasingWorkItem() throws Exception {
        NodeWorkItem item = item("EXECUTING", 4);
        NodeWorkItemClaim claim = new NodeWorkItemClaim(
                item.identity(), "worker-a", 4, OffsetDateTime.now().plusMinutes(1));
        ToolJobAnchor anchor = anchor(4);
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(checkpointWriter.captureAndSave(any())).thenReturn(true);
        when(store.suspendForToolJob(any(), any(), any(), any(), any(), eq(1)))
                .thenReturn(NodeWorkItemMutationResult.success());
        JsonNode payload = mapper.readTree("""
                {"todo":{"id":"todo-2","sequence":6},"completedContext":[],"toolCallsUsed":2}
                """);

        NodeWorkItemMutationResult result = coordinator.suspend(
                item, claim, payload,
                LangchainTodoNodeResult.builder()
                        .suspended(true)
                        .pendingToolCallId("call-1")
                        .pendingAttempt(1)
                        .build(),
                3);

        assertThat(result.applied()).isTrue();
        ArgumentCaptor<ToolJobCheckpointRequest> checkpoint =
                ArgumentCaptor.forClass(ToolJobCheckpointRequest.class);
        verify(checkpointWriter).captureAndSave(checkpoint.capture());
        assertThat(checkpoint.getValue().getTodoId()).isEqualTo("todo-2");
        assertThat(checkpoint.getValue().getSequence()).isEqualTo(6);
        assertThat(checkpoint.getValue().getToolCallsUsed()).isEqualTo(3);
        verify(store).suspendForToolJob(
                item.identity(), new NodeWorkItemVersions(7, 9, 4), "worker-a",
                "run-1:call-1:1", "call-1", 1);
    }

    @Test
    void promoteAndRecoverUseSameDurableWorkItem() {
        ToolJobAnchor anchor = anchor(3);
        NodeWorkItem waiting = item("WAITING", 3);
        when(store.findByIdentity(waiting.identity())).thenReturn(Optional.of(waiting));
        when(store.promoteToolJobResumable(any(), any(), any(), any(), any()))
                .thenReturn(NodeWorkItemMutationResult.success());

        assertThat(coordinator.promoteResumable("run-1", anchor)).isTrue();

        verify(store).promoteToolJobResumable(
                eq(waiting.identity()), eq(new NodeWorkItemVersions(7, 9, 3)),
                eq("run-1:call-1:1"), any(), any());
        verify(dispatcher).offerNode(waiting.identity());
    }

    @Test
    void restartRequeuesClaimedResumeOnlyWithNextEpoch() {
        ToolJobAnchor anchor = anchor(3);
        anchor.setResumeState(DualPoolToolJobCoordinator.RESUME_STATE);
        NodeWorkItem claimed = item("CLAIMED", 4);
        when(store.findByIdentity(claimed.identity())).thenReturn(Optional.of(claimed));
        when(store.requeueInterruptedToolJob(any(), any(), any()))
                .thenReturn(NodeWorkItemMutationResult.success());

        assertThat(coordinator.recoverResumable("run-1", anchor)).isTrue();

        verify(store).requeueInterruptedToolJob(
                claimed.identity(), new NodeWorkItemVersions(7, 9, 4), "run-1:call-1:1");
        verify(dispatcher).offerNode(claimed.identity());
    }

    @Test
    void resumedResultKeepsRunBudgetButStoresOnlyNodeDelta() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"toolCallsUsed":2,"toolJobResume":{
                  "terminalStatus":"SUCCEEDED","terminalResultPreview":"done","toolCallsUsed":5}}
                """);

        LangchainTodoNodeResult result = coordinator.resumeResult(payload);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("done");
        assertThat(result.getToolCallsUsed()).isEqualTo(3);
        assertThat(coordinator.resumeTotalToolCalls(payload)).isEqualTo(5);
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
