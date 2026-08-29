package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import dev.langchain4j.service.tool.ToolProvider;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.platform.debug.DebugObservabilityService;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;

/**
 * 全新执行形态：没有可复用的计划，由规划器把用户目标现场变成 todo 计划。
 * 计划获取使用基类的默认实现（调规划器、冻结计划与初始检查点、发 PLAN_READY）。
 */
@Component
public class FreshRunPipeline extends LangchainLinearRunPipelineImpl {

    public FreshRunPipeline(LangchainAiPlanner planner,
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
        return "pending_plan";
    }
}
