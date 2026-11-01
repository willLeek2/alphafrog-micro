package world.willfrog.agentlangchain.execution;

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
import world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.control.LangchainRunSchedulerTestSupport.immediateScheduler;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

/**
 * linear 端 publishFailure 把 failureMetadata 透传到 WORKFLOW_FAILED event payload。
 *
 * <p>覆盖两条路径：
 * <ul>
 *   <li><b>主路径</b>：result.failureMetadata 非空 → payload 含 {@code empty_output_observation} 子 map</li>
 *   <li><b>Fallback 路径</b>：result.failureMetadata 为空 + failureReason 含 "empty_todo_output" → payload <b>不</b>含 observation（仅 failureReason 让 mapper 归类）</li>
 * </ul>
 */
class LangchainLinearRunPipelineEmptyOutputTest {



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
        ,
                world.willfrog.agentlangchain.gateway.GatewayTestFixtures.permissive());
    }
}
