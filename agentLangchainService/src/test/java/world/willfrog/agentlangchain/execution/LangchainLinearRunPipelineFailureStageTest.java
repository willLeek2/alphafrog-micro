package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.control.LangchainRunSchedulerTestSupport.immediateScheduler;

/**
 * 失败两条路径的对外表达（行为项）：意外异常被安全网接住时，失败事件带阶段名
 * （失败点不被大 try 抹平）与失败四分类（业务拒绝 / 资源信号 / 控制流 / 未知缺陷）。
 * 预期失败不走异常通道，仍由各阶段的结果对象表达。
 */
class LangchainLinearRunPipelineFailureStageTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);








    private void assertRejectedBeforeOrdinarySideEffects(AgentRunStatus status,
                                                          boolean creditAvailable) {
        String runId = "run-rejected-" + status.name().toLowerCase();
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-1");
        run.setExt("{}");
        run.setStatus(status);

        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        when(runMapper.findById(runId)).thenReturn(run);
        AgentRunEventService eventService = mock(AgentRunEventService.class);
        LangchainAiPlanner planner = mock(LangchainAiPlanner.class);
        AgentCreditService creditService = mock(AgentCreditService.class);
        when(creditService.hasPositiveCredit("user-1")).thenReturn(creditAvailable);
        ObjectProvider<world.willfrog.agent.platform.service.AgentRunStateStore> stateStoreProvider =
                mock(ObjectProvider.class);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                planner, mock(LangchainLinearWorkflowExecutor.class),
                mock(LangchainDagWorkflowExecutor.class), mock(LangchainRunStageModelResolver.class),
                runMapper, eventService, new ObjectMapper(), mock(ObjectProvider.class),
                stateStoreProvider, mock(ObjectProvider.class), new LangchainFailureMapper(),
                mock(LangchainFollowUpContextSupport.class), mock(AgentMessageService.class),
                mock(LangchainRunExecutionGuard.class), immediateScheduler(), creditService,
                mock(AgentRunCreditSettlementService.class), mock(AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class),
                world.willfrog.agentlangchain.gateway.GatewayTestFixtures.permissive());

        pipeline.executeRun(run);

        verify(creditService, never()).hasPositiveCredit(anyString());
        verify(planner, never()).plan(any());
        verify(runMapper, never()).updateStatus(anyString(), anyString(), any(), any());
        verify(runMapper, never()).updateTerminalSnapshot(
                anyString(), anyString(), any(), any(), anyString(), eq(true), any());
        verify(eventService, never()).append(anyString(), anyString(), anyString(), any());
        verify(stateStoreProvider, never()).getIfAvailable();
    }
}
