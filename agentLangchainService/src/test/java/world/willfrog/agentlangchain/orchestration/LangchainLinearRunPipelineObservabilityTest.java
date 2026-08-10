package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.platform.debug.DebugObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static world.willfrog.agentlangchain.orchestration.LangchainRunSchedulerTestSupport.immediateScheduler;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangchainLinearRunPipelineObservabilityTest {

    @Test
    void executeRun_shouldInitializeObservabilityWithCaptureFlagFromExt() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);

        AgentRun run = new AgentRun();
        run.setId("run-obs-1");
        run.setUserId("user-1");
        run.setExt("""
                {"captureLlmRequests":true,"prompt_selection":{
                "schema_version":1,"bundle_version":"default-v1","variant":"control",
                "bundle_digest":"bundle-digest","capability_catalog_digest":"capability-digest",
                "reference_date":"2025-02-03"}}
                """);
        when(runMapper.findById("run-obs-1")).thenReturn(run);
        when(eventService.isRunnable("run-obs-1", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(run.getExt())).thenReturn(true);
        when(eventService.extractEndpointName(run.getExt())).thenReturn("openrouter");
        when(eventService.extractModelName(run.getExt())).thenReturn("kimi-k2.6");
        when(eventService.extractUserGoal(run.getExt())).thenReturn("goal");
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentEventService.RunConfig.defaults());
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter-plan", "kimi-k2.5", List.of()));

        @SuppressWarnings("unchecked")
        ObjectProvider<AgentObservabilityService> observabilityProvider = mock(ObjectProvider.class);
        when(observabilityProvider.getIfAvailable()).thenReturn(observabilityService);

        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRunDatasetRegistry> datasetRegistryProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DebugObservabilityService> debugObservabilityProvider = mock(ObjectProvider.class);

        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("t1").sequence(1).description("x").build()))
                .build());
        LangchainLinearWorkflowExecutor linearWorkflowExecutor = mock(LangchainLinearWorkflowExecutor.class);
        when(linearWorkflowExecutor.executePlanned(any(), any())).thenReturn(
                LangchainLinearWorkflowResult.builder().success(true).finalAnswer("ok").build());

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenAnswer(invocation -> {
            assertNotNull(AgentContext.getPromptRunSelection());
            assertEquals("default-v1", AgentContext.getPromptRunSelection().bundleVersion());
            assertEquals("bundle-digest", AgentContext.getPromptRunSelection().bundleDigest());
            return new LangchainFollowUpContextSupport.ExecutionContext("goal", "");
        });

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("user-1")).thenReturn(true);
        AgentPromptService promptService = mock(AgentPromptService.class);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                linearWorkflowExecutor,
                mock(LangchainDagWorkflowExecutor.class),
                stageModelResolver,
                runMapper,
                eventService,
                new ObjectMapper(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                observabilityProvider,
                new LangchainFailureMapper(),
                followUpContextSupport,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class),
                mock(LangchainRunExecutionGuard.class),
                immediateScheduler(),
                creditService,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                promptService,
                datasetRegistryProvider,
                debugObservabilityProvider
        );

        pipeline.executeRun(run);

        verify(observabilityService).initializeRun(
                eq("run-obs-1"),
                eq("openrouter-plan"),
                eq("kimi-k2.5"),
                eq(true));
        InOrder promptBoundaryOrder = inOrder(
                promptService, stageModelResolver, observabilityService, followUpContextSupport);
        promptBoundaryOrder.verify(promptService).validatePromptSelection(any());
        promptBoundaryOrder.verify(stageModelResolver).resolve(run);
        promptBoundaryOrder.verify(observabilityService).initializeRun(
                eq("run-obs-1"), eq("openrouter-plan"), eq("kimi-k2.5"), eq(true));
        promptBoundaryOrder.verify(followUpContextSupport).resolve(run);
    }

    @Test
    void executeRun_withPromptSelectionMismatch_shouldFailBeforeModelSummaryOrObservabilityInitialization() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentObservabilityService observabilityService = mock(AgentObservabilityService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linearWorkflowExecutor = mock(LangchainLinearWorkflowExecutor.class);
        AgentPromptService promptService = mock(AgentPromptService.class);

        AgentRun run = new AgentRun();
        run.setId("run-prompt-mismatch");
        run.setUserId("user-1");
        run.setExt("""
                {"prompt_selection":{"schema_version":1,"bundle_version":"default-v1",
                "variant":"control","bundle_digest":"stale-digest",
                "capability_catalog_digest":"capability-digest","reference_date":"2025-02-03"}}
                """);
        when(runMapper.findById("run-prompt-mismatch")).thenReturn(run);
        org.mockito.Mockito.doThrow(new IllegalStateException("prompt_selection_mismatch"))
                .when(promptService).validatePromptSelection(any());

        @SuppressWarnings("unchecked")
        ObjectProvider<AgentObservabilityService> observabilityProvider = mock(ObjectProvider.class);
        when(observabilityProvider.getIfAvailable()).thenReturn(observabilityService);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner,
                linearWorkflowExecutor,
                mock(LangchainDagWorkflowExecutor.class),
                stageModelResolver,
                runMapper,
                eventService,
                new ObjectMapper(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                observabilityProvider,
                new LangchainFailureMapper(),
                followUpContextSupport,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class),
                mock(LangchainRunExecutionGuard.class),
                immediateScheduler(),
                mock(AgentCreditService.class),
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                promptService,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class)
        );

        pipeline.executeRun(run);

        verify(promptService).validatePromptSelection(any());
        verifyNoInteractions(stageModelResolver, followUpContextSupport, planner, linearWorkflowExecutor);
        verify(observabilityService, never()).initializeRun(any(), any(), any(), anyBoolean());
    }
}
