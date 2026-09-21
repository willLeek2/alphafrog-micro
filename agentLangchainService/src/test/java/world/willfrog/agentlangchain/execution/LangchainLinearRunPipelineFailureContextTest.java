package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.control.LangchainRunSchedulerTestSupport.immediateScheduler;

/**
 * 写终态失败时重建上下文：这条路不许碰模型解析。
 *
 * <p>阶段模型解析会因为「这条 Run 自己的配置有问题」而拒绝——夹具在跑的中途被停用、脚本位置重建
 * 不了、请求上下文里的夹具编号读不出来，都是这一类。拿这些拒绝挡着写失败，Run 会停在执行中：没有
 * 失败事件、没有失败原因，只能等过期或者人工收拾。</p>
 */
class LangchainLinearRunPipelineFailureContextTest {

    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final LangchainRunStageModelResolver stageModelResolver =
            mock(LangchainRunStageModelResolver.class);
    private final LangchainFollowUpContextSupport followUpContextSupport =
            mock(LangchainFollowUpContextSupport.class);

    /** 一份能读出来的计划：字段就是计划自己的那几个。 */
    private static final String PLAN_JSON =
            "{\"analysis\":\"先把数据查出来\",\"items\":[],\"executionMode\":\"LINEAR\"}";

    @Test
    void theFailureContextIsBuiltWithoutAskingForAnyModel() {
        when(runMapper.findById("run-1")).thenReturn(executingRun(PLAN_JSON));
        when(stageModelResolver.resolve(any())).thenThrow(new IllegalStateException(
                "夹具在跑的中途被停用了"));
        when(followUpContextSupport.resolve(any()))
                .thenReturn(new LangchainFollowUpContextSupport.ExecutionContext("把这件事做完", ""));

        LangchainLinearRunPipelineImpl.DualPoolNodeContext context =
                pipeline().rebuildDualPoolNodeContextForFailure("run-1");

        assertThat(context).isNotNull();
        assertThat(context.userGoal()).isEqualTo("把这件事做完");
        assertThat(context.run().getId()).isEqualTo("run-1");
        assertThat(context.plan()).isNotNull();
        assertThat(context.stageModels()).as("写失败用不到模型，所以不去解析").isNull();
        verify(stageModelResolver, never()).resolve(any());
    }

    @Test
    void anUnreadablePlanComesBackAsEmptySoTheCallerWritesTheFailureWithoutOne() {
        when(runMapper.findById("run-1")).thenReturn(executingRun("{不是 JSON"));

        assertThat(pipeline().rebuildDualPoolNodeContextForFailure("run-1")).isNull();
        verify(stageModelResolver, never()).resolve(any());
    }

    @Test
    void aRunThatIsNoLongerExecutingHasNoFailureContextToRebuild() {
        AgentRun run = executingRun(PLAN_JSON);
        run.setStatus(AgentRunStatus.CANCELED);
        when(runMapper.findById("run-1")).thenReturn(run);

        assertThat(pipeline().rebuildDualPoolNodeContextForFailure("run-1")).isNull();
        verify(stageModelResolver, never()).resolve(any());
    }

    /** 这份上下文是为写失败准备的：拿它去写成功结果要当场停住，不能在没有模型的情况下广播终态。 */
    @Test
    void aFailureOnlyContextRefusesToCarryASuccessResult() {
        when(runMapper.findById("run-1")).thenReturn(executingRun(PLAN_JSON));
        LangchainLinearRunPipelineImpl.DualPoolNodeContext context =
                new LangchainLinearRunPipelineImpl.DualPoolNodeContext(
                        executingRun(PLAN_JSON), null, "把这件事做完", null, null);

        boolean durable = pipeline().persistDualPoolWorkflowResult(context,
                LangchainWorkflowResult.builder().success(true).finalAnswer("答案").build());

        assertThat(durable).isFalse();
    }

    /**
     * 读计划要用与线上同一套能力的解析器：计划类只有全参构造器，靠参数名才能读回来，
     * 那份能力由 Spring 装配的解析器带上（这里显式装上同一个模块）。
     */
    private static ObjectMapper planMapper() {
        return JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
                .build();
    }

    private LangchainLinearRunPipelineImpl pipeline() {
        return new LangchainLinearRunPipelineImpl(
                mock(world.willfrog.agentlangchain.planning.LangchainAiPlanner.class),
                mock(LangchainLinearWorkflowExecutor.class),
                mock(world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor.class),
                stageModelResolver,
                runMapper,
                mock(AgentRunEventService.class),
                planMapper(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new LangchainFailureMapper(),
                followUpContextSupport,
                mock(world.willfrog.agent.platform.service.AgentMessageService.class),
                mock(world.willfrog.agentlangchain.control.LangchainRunExecutionGuard.class),
                immediateScheduler(),
                mock(AgentCreditService.class),
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                world.willfrog.agentlangchain.gateway.GatewayTestFixtures.permissive());
    }

    private static AgentRun executingRun(String planJson) {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("7");
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setPlanJson(planJson);
        return run;
    }
}
