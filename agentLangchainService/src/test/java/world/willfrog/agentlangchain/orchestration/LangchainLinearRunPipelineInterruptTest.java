package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static world.willfrog.agentlangchain.orchestration.LangchainRunSchedulerTestSupport.immediateScheduler;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LangchainLinearRunPipelineInterruptTest {

    @Test
    void executeRun_shouldNotPersistCompletedWhenStoppedBeforePersist() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);

        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setExt("{}");
        when(runMapper.findById("r1")).thenReturn(run);
        when(eventService.isRunnable("r1", "u1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(any())).thenReturn(false);
        when(eventService.extractRunConfig(any())).thenReturn(AgentRunEventService.RunConfig.defaults());
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "ep", "model", List.of()));
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("t1").sequence(1).description("x").build()))
                .build());
        when(linear.executePlanned(any(), any())).thenReturn(LangchainLinearWorkflowResult.builder()
                .success(true)
                .finalAnswer("ok")
                .build());
        when(executionGuard.stopReason(eq("r1"), eq("u1")))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(AgentRunStatus.CANCELING.name()));

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                linear,
                mock(LangchainDagWorkflowExecutor.class),
                stageModelResolver,
                runMapper,
                eventService,
                new ObjectMapper(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new LangchainFailureMapper(),
                followUpContextSupport,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class),
                executionGuard,
                immediateScheduler(),
                mock(AgentCreditService.class),
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class)
        );

        pipeline.executeRun(run);

        verify(runMapper, never()).updateSnapshot(eq("r1"), eq("u1"), eq(AgentRunStatus.COMPLETED), any(), anyBoolean(), any());    }
}
