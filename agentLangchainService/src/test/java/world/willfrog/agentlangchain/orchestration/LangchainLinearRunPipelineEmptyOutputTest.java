package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.orchestration.LangchainRunSchedulerTestSupport.immediateScheduler;

/**
 * ccmax #59: linear 端 publishFailure 把 failureMetadata 透传到 WORKFLOW_FAILED event payload。
 *
 * <p>覆盖两条路径：
 * <ul>
 *   <li><b>主路径</b>：result.failureMetadata 非空 → payload 含 {@code empty_output_observation} 子 map</li>
 *   <li><b>Fallback 路径</b>：result.failureMetadata 为空 + failureReason 含 "empty_todo_output" → payload <b>不</b>含 observation（仅 failureReason 让 mapper 归类）</li>
 * </ul>
 */
class LangchainLinearRunPipelineEmptyOutputTest {

    @Test
    void publishFailure_shouldIncludeEmptyOutputObservationWhenFailureMetadataProvided() {
        // 模拟 executor 在主路径下构造的 failureMetadata
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("todo_id", "todo_1");
        observation.put("todo_sequence", 1);
        observation.put("stage", "todo_execution");
        observation.put("model", "OpenRouterProviderRoutedChatModel");
        observation.put("provider", "OpenRouterProviderRoutedChatModel");
        observation.put("finish_reason", "blank_after_trim");
        observation.put("raw_output_length", 7);
        observation.put("trimmed_output_length", 0);
        observation.put("budget_hit", false);
        observation.put("last_non_empty_todo_id", "todo_0");
        observation.put("previous_todo_total_length", 50L);
        observation.put("current_todo_prompt_budget_chars", 200);
        observation.put("recovery_attempted", true);
        observation.put("recovery_outcome", "still_blank");

        AgentRun run = new AgentRun();
        run.setId("run-empty-1");
        run.setUserId("user-1");
        run.setExt("{}");

        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        when(runMapper.findById("run-empty-1")).thenReturn(run);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        when(eventService.isRunnable("run-empty-1", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(run.getExt())).thenReturn(false);
        when(eventService.extractEndpointName(run.getExt())).thenReturn("openrouter");
        when(eventService.extractModelName(run.getExt())).thenReturn("kimi");
        when(eventService.extractUserGoal(run.getExt())).thenReturn("goal");
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentRunEventService.RunConfig.defaults());
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter", "kimi", List.of()));

        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("x").build()))
                .build());

        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        when(linear.executePlanned(any(), any())).thenReturn(LangchainLinearWorkflowResult.builder()
                .success(false)
                .failureReason("empty_todo_output_after_recovery:todo_1")
                .failureMetadata(observation)
                .toolCallsUsed(2)
                .build());

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("user-1")).thenReturn(true);

        LangchainLinearRunPipelineImpl pipeline = newPipeline(planner, linear, runMapper, eventService,
                stageModelResolver, followUpContextSupport, creditService);

        pipeline.executeRun(run);

        // 抓取 eventService.append 的实际 payload
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-empty-1"), eq("user-1"),
                eq("WORKFLOW_FAILED"), payloadCaptor.capture());
        Map<String, Object> captured = payloadCaptor.getValue();

        // 主路径断言：empty_output_observation 子 map 完整透传 13 个字段
        assertThat(captured).containsKey("empty_output_observation");
        @SuppressWarnings("unchecked")
        Map<String, Object> obsOut = (Map<String, Object>) captured.get("empty_output_observation");
        assertThat(obsOut).containsEntry("todo_id", "todo_1");
        assertThat(obsOut).containsEntry("todo_sequence", 1);
        assertThat(obsOut).containsEntry("stage", "todo_execution");
        assertThat(obsOut).containsEntry("model", "OpenRouterProviderRoutedChatModel");
        assertThat(obsOut).containsEntry("provider", "OpenRouterProviderRoutedChatModel");
        assertThat(obsOut).containsEntry("finish_reason", "blank_after_trim");
        assertThat(obsOut).containsEntry("raw_output_length", 7);
        assertThat(obsOut).containsEntry("trimmed_output_length", 0);
        assertThat(obsOut).containsEntry("budget_hit", false);
        assertThat(obsOut).containsEntry("last_non_empty_todo_id", "todo_0");
        assertThat(obsOut).containsEntry("previous_todo_total_length", 50L);
        assertThat(obsOut).containsEntry("current_todo_prompt_budget_chars", 200);
        assertThat(obsOut).containsEntry("recovery_attempted", true);
        assertThat(obsOut).containsEntry("recovery_outcome", "still_blank");
        // 14 字段全到齐（防新增字段未覆盖）
        assertThat(obsOut).hasSize(14);
        // engine key 仍在
        assertThat(captured).containsEntry("engine", "agentLangchainService");
    }

    @Test
    void publishFailure_shouldNotIncludeEmptyOutputObservationInFallbackPath() {
        // Fallback 路径：failureMetadata 为空 + failureReason 包含 empty_todo_output
        // 行为：log debug，不伪造 observation（关键：不能凭空造数据污染观测）
        AgentRun run = new AgentRun();
        run.setId("run-empty-2");
        run.setUserId("user-1");
        run.setExt("{}");

        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        when(runMapper.findById("run-empty-2")).thenReturn(run);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        when(eventService.isRunnable("run-empty-2", "user-1")).thenReturn(true);
        when(eventService.extractCaptureLlmRequests(run.getExt())).thenReturn(false);
        when(eventService.extractEndpointName(run.getExt())).thenReturn("openrouter");
        when(eventService.extractModelName(run.getExt())).thenReturn("kimi");
        when(eventService.extractUserGoal(run.getExt())).thenReturn("goal");
        when(eventService.extractRunConfig(run.getExt())).thenReturn(AgentRunEventService.RunConfig.defaults());
        LangchainRunStageModelResolver stageModelResolver = mock(LangchainRunStageModelResolver.class);
        when(stageModelResolver.resolve(run)).thenReturn(new LangchainRunStageModelResolver.StageModels(
                null, null, null, "openrouter", "kimi", List.of()));

        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        when(planner.plan(any())).thenReturn(LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder().id("todo_1").sequence(1).description("x").build()))
                .build());

        LangchainLinearWorkflowExecutor linear = mock(LangchainLinearWorkflowExecutor.class);
        // failureMetadata=null（fallback 路径特征）
        when(linear.executePlanned(any(), any())).thenReturn(LangchainLinearWorkflowResult.builder()
                .success(false)
                .failureReason("empty_todo_output:todo_1")
                .toolCallsUsed(1)
                .build());

        LangchainFollowUpContextSupport followUpContextSupport = mock(LangchainFollowUpContextSupport.class);
        when(followUpContextSupport.resolve(run)).thenReturn(
                new LangchainFollowUpContextSupport.ExecutionContext("goal", ""));

        AgentCreditService creditService = mock(AgentCreditService.class);
        lenient().when(creditService.hasPositiveCredit("user-1")).thenReturn(true);

        LangchainLinearRunPipelineImpl pipeline = newPipeline(planner, linear, runMapper, eventService,
                stageModelResolver, followUpContextSupport, creditService);

        pipeline.executeRun(run);

        // 抓取 eventService.append 的实际 payload
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-empty-2"), eq("user-1"),
                eq("WORKFLOW_FAILED"), payloadCaptor.capture());
        Map<String, Object> captured = payloadCaptor.getValue();

        // Fallback 关键断言：payload 中 <b>不</b>含 empty_output_observation key
        // （这避免空 observation 子 map 污染 timeline + 报告）
        assertThat(captured).doesNotContainKey("empty_output_observation");
        // engine key 仍存在（pipeline 一直会写）
        assertThat(captured).containsEntry("engine", "agentLangchainService");
    }

    // ========== 辅助方法 ==========

    private static LangchainLinearRunPipelineImpl newPipeline(LangchainAiPlanner planner,
                                                              LangchainLinearWorkflowExecutor linear,
                                                              AgentRunMapper runMapper,
                                                              AgentRunEventService eventService,
                                                              LangchainRunStageModelResolver stageModelResolver,
                                                              LangchainFollowUpContextSupport followUpContextSupport,
                                                              AgentCreditService creditService) {
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
                mock(LangchainRunExecutionGuard.class),
                immediateScheduler(),
                creditService,
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class)
        );
    }
}
