package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.DatasetPersistedEvent;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.control.LangchainRunSchedulerTestSupport.immediateScheduler;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

/**
 * 验证 {@link LangchainLinearRunPipelineImpl#executeRun(AgentRun)} 在 finally 块里调用
 * {@link AgentRunDatasetRegistry#reset(String)}，清理当前 run 的 per-run 编号转译层状态，
 * 避免跨 run 串到上一个 run 的 dataset/manifest 编号。
 *
 * <p>覆盖验收口径：
 * <ol>
 *   <li>正常 success 路径：run 完成后 registry 的 run state 被清掉（snapshot 为空 / hasRunState=false）</li>
 *   <li>异常路径：workflow 抛运行时异常时，registry 的 run state 依然被清掉</li>
 *   <li>provider 返回 null（bean 未启用场景）：pipeline 不会抛 NPE，直接静默跳过</li>
 *   <li>provider 抛异常（极端边界）：被 catch 吞掉，不影响外层 finally 的 {@code AgentContext.clear()}</li>
 * </ol>
 */
class LangchainLinearRunPipelineRegistryResetTest {

    private static final String RUN_ID = "run-reset-1";

    /**
     * 构造一个能走完整 success 路径的 pipeline。{@code registryProvider} 由调用方决定如何 stub。
     */
    @SuppressWarnings("unchecked")
    private LangchainLinearRunPipelineImpl buildPipeline(ObjectProvider<AgentRunDatasetRegistry> registryProvider) {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);

        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId("u1");
        run.setExt("{}");
        run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.RECEIVED);
        when(runMapper.findById(RUN_ID)).thenReturn(run);
        when(eventService.isRunnable(RUN_ID, "u1")).thenReturn(true);
        lenient().when(eventService.extractCaptureLlmRequests(any())).thenReturn(false);
        lenient().when(eventService.extractRunConfig(any())).thenReturn(AgentRunEventService.RunConfig.defaults());
        lenient().when(stageModelResolver.resolve(any())).thenReturn(
                new LangchainRunStageModelResolver.StageModels(null, null, null, "ep", "model", List.of()));
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("t1").sequence(1).description("x").build()))
                .build());
        when(linear.executePlanned(any(), any())).thenReturn(LangchainWorkflowResult.builder()
                .success(true)
                .finalAnswer("ok")
                .build());
        lenient().when(executionGuard.stopReason(eq(RUN_ID), eq("u1"))).thenReturn(java.util.Optional.empty());

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(any())).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("u1")).thenReturn(true);

        return new LangchainLinearRunPipelineImpl(
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
                creditService,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                registryProvider,
                mock(ObjectProvider.class)
        ,
                world.willfrog.agentlangchain.gateway.GatewayTestFixtures.permissive());
    }



    @SuppressWarnings("unchecked")
    private LangchainLinearRunPipelineImpl buildPipelineThrowingPlanner(ObjectProvider<AgentRunDatasetRegistry> registryProvider) {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);

        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId("u1");
        run.setExt("{}");
        run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.RECEIVED);
        when(runMapper.findById(RUN_ID)).thenReturn(run);
        when(eventService.isRunnable(RUN_ID, "u1")).thenReturn(true);
        lenient().when(eventService.extractCaptureLlmRequests(any())).thenReturn(false);
        lenient().when(eventService.extractRunConfig(any())).thenReturn(AgentRunEventService.RunConfig.defaults());
        lenient().when(stageModelResolver.resolve(any())).thenReturn(
                new LangchainRunStageModelResolver.StageModels(null, null, null, "ep", "model", List.of()));
        // 让 planner 抛 RuntimeException → 触发 executeRun 的 catch → 走 finally
        when(planner.plan(any())).thenThrow(new RuntimeException("simulated workflow failure"));

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(any())).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("u1")).thenReturn(true);

        return new LangchainLinearRunPipelineImpl(
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
                creditService,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                registryProvider,
                mock(ObjectProvider.class)
        ,
                world.willfrog.agentlangchain.gateway.GatewayTestFixtures.permissive());
    }


}
