package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemClaim;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agentlangchain.execution.FreshRunPipeline;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipelineImpl;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.execution.LangchainWorkflowRequest;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseDualPoolWorkHandlerFaultInterruptionTest {

    @Test
    void injectedWorkerLossPreservesExecutingWorkItemForLeaseRecovery() throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        FreshRunPipeline pipeline = mock(FreshRunPipeline.class);
        NodeWorkItemStore store = mock(NodeWorkItemStore.class);
        NodeWorkPlanAdapter planAdapter = mock(NodeWorkPlanAdapter.class);
        LangchainTodoNodeExecutor executor = mock(LangchainTodoNodeExecutor.class);
        AgentRunEventService events = mock(AgentRunEventService.class);
        DualPoolDispatcher dispatcher = mock(DualPoolDispatcher.class);
        DualPoolRunAdmissionRegistry admission = mock(DualPoolRunAdmissionRegistry.class);
        SchedulerVersionPolicy schedulerPolicy = mock(SchedulerVersionPolicy.class);
        DualPoolToolJobCoordinator coordinator = mock(DualPoolToolJobCoordinator.class);
        DatabaseDualPoolWorkHandler handler = new DatabaseDualPoolWorkHandler(
                runMapper, pipeline, store, planAdapter, executor, events, new ObjectMapper(),
                dispatcher, admission, schedulerPolicy, coordinator, 30L, 32);

        NodeWorkItem item = runnableTodo();
        NodeWorkItemIdentity identity = item.identity();
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setPlanGeneration(2);
        run.setRunControlVersion(9L);
        NodeWorkItemClaim claim = new NodeWorkItemClaim(
                identity, "worker-a", 4, OffsetDateTime.now().plusMinutes(1));
        LangchainWorkflowRequest workflowRequest = mock(LangchainWorkflowRequest.class);
        LangchainLinearRunPipelineImpl.DualPoolNodeContext context =
                new LangchainLinearRunPipelineImpl.DualPoolNodeContext(
                        run, null, "goal", null, workflowRequest);

        when(admission.isAdmitted("run-1")).thenReturn(true);
        when(store.findByIdentity(identity)).thenReturn(Optional.of(item));
        when(runMapper.findById("run-1")).thenReturn(run);
        when(schedulerPolicy.isDualPool(run)).thenReturn(true);
        when(store.claim(any(), any(), any(), any(), any())).thenReturn(Optional.of(claim));
        when(store.startExecution(any(), anyInt(), any()))
                .thenReturn(NodeWorkItemMutationResult.success());
        when(pipeline.rebuildDualPoolNodeContext("run-1")).thenReturn(context);
        when(executor.execute(any(), any(), any(), any(), any()))
                .thenThrow(new ToolJobInjectedInterruption("scenario-1", "BEFORE_SANDBOX_SUBMIT"));

        handler.executeNode(identity);

        verify(store, never()).reportExecutionFailure(any(), anyInt(), any(), any());
        verify(dispatcher, never()).offerRun(any());
        verify(pipeline).clearDualPoolNodeContext("run-1");
    }

    private NodeWorkItem runnableTodo() {
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId("run-1");
        item.setPlanGeneration(2);
        item.setNodeId("todo-1");
        item.setNodeAttempt(0);
        item.setSegmentSequence(0);
        item.setState("RUNNABLE");
        item.setContextVersion(7L);
        item.setRunControlVersion(9L);
        item.setClaimEpoch(3);
        item.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V1.name());
        item.setPayloadJson("""
                {"kind":"TODO","workflow":"LINEAR","toolCallsUsed":0,
                 "todo":{"id":"todo-1","sequence":1,"description":"run python"},
                 "completedContext":[],"datasetRefs":{}}
                """);
        return item;
    }
}
