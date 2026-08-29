package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.debug.DebugObservabilityService;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;

/**
 * 长工具恢复形态：一个 Run 因为后台长工具挂起后，由恢复令牌与租约把它接回执行。
 * 计划来源是挂起前冻结的 Plan 加锚点里的检查点，绝不重新规划；执行走恢复专用入口
 * （跳过已完成前缀、把工具终态结果注入原挂起节点），持久化与收尾复用基类共享实现。
 * 本形态的入口是 {@link #launchResumedAsync}，不经过全新执行的阶段序列。
 */
@Component
public class ToolJobResumePipeline extends LangchainLinearRunPipelineImpl {

    public ToolJobResumePipeline(LangchainAiPlanner planner,
                                 LangchainLinearWorkflowExecutor linearWorkflowExecutor,
                                 LangchainDagWorkflowExecutor dagWorkflowExecutor,
                                 LangchainRunStageModelResolver stageModelResolver,
                                 AgentRunMapper runMapper,
                                 AgentRunEventService eventService,
                                 ObjectMapper objectMapper,
                                 ObjectProvider<ToolProvider> toolProviderProvider,
                                 ObjectProvider<AgentRunStateStore> stateStoreProvider,
                                 ObjectProvider<AgentRunObservabilityService> observabilityServiceProvider,
                                 LangchainFailureMapper failureMapper,
                                 LangchainFollowUpContextSupport followUpContextSupport,
                                 AgentMessageService messageService,
                                 LangchainRunExecutionGuard executionGuard,
                                 LangchainRunConcurrencyScheduler runConcurrencyScheduler,
                                 AgentCreditService creditService,
                                 AgentRunCreditSettlementService creditSettlementService,
                                 AgentRunFinalizationService finalizationService,
                                 AgentPromptService promptService,
                                 ObjectProvider<AgentRunDatasetRegistry> agentRunDatasetRegistryProvider,
                                 ObjectProvider<DebugObservabilityService> debugObservabilityServiceProvider) {
        super(planner, linearWorkflowExecutor, dagWorkflowExecutor, stageModelResolver, runMapper,
                eventService, objectMapper, toolProviderProvider, stateStoreProvider,
                observabilityServiceProvider, failureMapper, followUpContextSupport, messageService,
                executionGuard, runConcurrencyScheduler, creditService, creditSettlementService,
                finalizationService, promptService, agentRunDatasetRegistryProvider,
                debugObservabilityServiceProvider);
    }

    @Override
    protected String formKind() {
        return "tool_job_resume";
    }
}
