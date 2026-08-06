package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
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
import static world.willfrog.agentlangchain.orchestration.LangchainRunSchedulerTestSupport.immediateScheduler;

/**
 * 260623-agent-service-deprecation task #47 (P0-2)：
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
        AgentEventService eventService = mock(AgentEventService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);

        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId("u1");
        run.setExt("{}");
        when(runMapper.findById(RUN_ID)).thenReturn(run);
        when(eventService.isRunnable(RUN_ID, "u1")).thenReturn(true);
        lenient().when(eventService.extractCaptureLlmRequests(any())).thenReturn(false);
        lenient().when(eventService.extractRunConfig(any())).thenReturn(AgentEventService.RunConfig.defaults());
        lenient().when(stageModelResolver.resolve(any())).thenReturn(
                new LangchainRunStageModelResolver.StageModels(null, null, null, "ep", "model", List.of()));
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("t1").sequence(1).description("x").build()))
                .build());
        when(linear.executePlanned(any(), any())).thenReturn(LangchainLinearWorkflowResult.builder()
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
                registryProvider,
                mock(ObjectProvider.class)
        );
    }

    /**
     * 验收口径 (1)：正常 success 路径 — executeRun 返回后 registry 的 per-run 状态被清掉。
     */
    @Test
    @SuppressWarnings("unchecked")
    void executeRunSuccessPathShouldResetRegistryAfterCompletion() {
        AgentRunDatasetRegistry registry = new AgentRunDatasetRegistry();
        // 预先往 registry 灌一个 DATASET event 模拟 "run 期间落盘已发生"
        registry.onDatasetPersisted(new DatasetPersistedEvent(this, RUN_ID, "ds-a",
                "/data/domestic_listed_asset/600000.SH/ds-a/a.csv", "600000.SH", "a.csv"));
        // 触发一次 snapshot 确保 raw → numbering 分配走完
        registry.snapshot(RUN_ID);
        assertFalse(registry.snapshot(RUN_ID).isEmpty(), "precondition: registry should have ds-a before reset");

        ObjectProvider<AgentRunDatasetRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        LangchainLinearRunPipelineImpl pipeline = buildPipeline(provider);
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId("u1");
        pipeline.executeRun(run);

        // P0-2 验收：run 完成后 registry 该 run 的状态应被清掉（reset hook 走 finally 块）
        assertFalse(registry.hasRunState(RUN_ID),
                "executeRun finally block should reset AgentRunDatasetRegistry per-run state");
    }

    /**
     * 验收口径 (2)：workflow 抛运行时异常时，registry 的 run state 依然被清掉（finally 语义）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void executeRunExceptionPathShouldStillResetRegistry() {
        AgentRunDatasetRegistry registry = new AgentRunDatasetRegistry();
        registry.onDatasetPersisted(new DatasetPersistedEvent(this, RUN_ID, "ds-a",
                "/data/domestic_listed_asset/600000.SH/ds-a/a.csv", "600000.SH", "a.csv"));
        assertFalse(registry.snapshot(RUN_ID).isEmpty(), "precondition: registry should have ds-a before reset");

        ObjectProvider<AgentRunDatasetRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        // buildPipeline 的 linear executor 用的是 mock，但这里直接复用会走 success 路径；
        // 想走异常路径需要单独构造。最简单：让 planner.plan 抛 RuntimeException，
        // 触发 executeRun 的 catch 块，最终走 finally。
        LangchainLinearRunPipelineImpl pipeline = buildPipelineThrowingPlanner(provider);

        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId("u1");
        // 不应向外抛 — catch 块把异常收敛
        pipeline.executeRun(run);

        assertFalse(registry.hasRunState(RUN_ID),
                "executeRun finally block should reset registry even when workflow throws");
    }

    @SuppressWarnings("unchecked")
    private LangchainLinearRunPipelineImpl buildPipelineThrowingPlanner(ObjectProvider<AgentRunDatasetRegistry> registryProvider) {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentEventService eventService = mock(AgentEventService.class);
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        LangchainRunExecutionGuard executionGuard = mock(LangchainRunExecutionGuard.class);

        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId("u1");
        run.setExt("{}");
        when(runMapper.findById(RUN_ID)).thenReturn(run);
        when(eventService.isRunnable(RUN_ID, "u1")).thenReturn(true);
        lenient().when(eventService.extractCaptureLlmRequests(any())).thenReturn(false);
        lenient().when(eventService.extractRunConfig(any())).thenReturn(AgentEventService.RunConfig.defaults());
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
                registryProvider,
                mock(ObjectProvider.class)
        );
    }

    /**
     * 验收口径 (3)：provider 返回 null（bean 未启用场景）— pipeline 不抛 NPE，静默跳过。
     */
    @Test
    @SuppressWarnings("unchecked")
    void executeRunShouldTolerateNullRegistryProvider() {
        ObjectProvider<AgentRunDatasetRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        LangchainLinearRunPipelineImpl pipeline = buildPipeline(provider);
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId("u1");
        // 不抛异常，finally 直接走 null 分支
        pipeline.executeRun(run);
    }

    /**
     * 验收口径 (4)：provider.getIfAvailable() 抛异常 — 被 finally 块内的 try-catch 吞掉，
     * 不影响 AgentContext.clear() 和后续路径。
     */
    @Test
    @SuppressWarnings("unchecked")
    void executeRunShouldSwallowRegistryProviderException() {
        ObjectProvider<AgentRunDatasetRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(new RuntimeException("simulated bean lookup failure"));

        LangchainLinearRunPipelineImpl pipeline = buildPipeline(provider);
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId("u1");
        // 不抛异常 — finally 内 try-catch 兜住
        pipeline.executeRun(run);
    }
}