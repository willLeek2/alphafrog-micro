package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.workflow.PlanExecutionMode;
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
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.Map;

/**
 * 冻结计划重启形态：进程重启后恢复一个已有冻结计划的 Run。
 * 计划获取只读数据库里的冻结 Plan（绝不调规划器），LINEAR 形态从检查点边界继续，
 * DAG 形态从整图开头重新调度——这些差异全部由 {@link #resolvePlan} 的覆盖实现表达，
 * 基类的执行与持久化阶段对形态无感知。
 */
@Component
public class FrozenPlanRestartPipeline extends LangchainLinearRunPipelineImpl {

    public FrozenPlanRestartPipeline(LangchainAiPlanner planner,
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
        return "frozen_plan_restart";
    }

    /**
     * 冻结计划重启的计划获取：只读数据库里的冻结 Plan，绝不调规划器——重启后重新规划
     * 可能重复副作用。LINEAR 形态解析检查点边界并从该边界继续；DAG 形态从整图开头
     * 重新调度（不复用旧节点进度），但仍要确认崩溃前没有启动过不安全工具。
     */
    @Override
    protected PlanResolution resolvePlan(AgentRun run, StageInputs inputs) throws Exception {
        String runId = inputs.runId();
        String userId = inputs.userId();
        if (isBlank(run.getPlanJson()) || "{}".equals(run.getPlanJson().trim())) {
            throw new IllegalStateException("workflow_restart_plan_missing");
        }
        LangchainTodoPlan plan = objectMapper.readValue(run.getPlanJson(), LangchainTodoPlan.class);
        validateFrozenPlan(plan);
        // 冻结 Plan 的生效模式同样交给 ExecutionModeResolver 一次裁完（只读裁决，不调规划器）。
        ExecutionModeResolver.Decision frozenDecision = ExecutionModeResolver.inspectFrozen(plan);
        boolean useDag = frozenDecision.useDag();
        PlanExecutionMode effectiveExecutionMode = frozenDecision.effective();
        WorkflowExecutionCheckpoint restartCheckpoint = null;
        if (useDag) {
            if (workflowCheckpointService == null) {
                throw new IllegalStateException("workflow_checkpoint_service_unavailable");
            }
            // DAG 从头重跑，但仍必须确认崩溃前没有启动过 UNSAFE 工具。
            workflowCheckpointService.parseAndValidateDagRestart(run);
            // DAG 不复用旧节点执行进度；删除仅用于 durable 展示/恢复的节点行。
            if (dagNodeMapper != null) {
                dagNodeMapper.deleteByRunId(runId);
                dagNodeMapper.clearFrontierForWorkflowRestart(runId);
            }
        } else {
            if (workflowCheckpointService == null) {
                throw new IllegalStateException("workflow_checkpoint_service_unavailable");
            }
            restartCheckpoint = workflowCheckpointService.parseAndValidate(run, plan);
        }
        int restartAttempt = run.getRestartAttempt() == null ? 0 : run.getRestartAttempt();
        eventService.appendOnce(runId, userId, "WORKFLOW_RESTARTED",
                runId + ":restart:" + restartAttempt,
                Map.of(
                        "run_id", runId,
                        "restart_attempt", restartAttempt,
                        "planner_skipped", true,
                        "workflow", useDag ? "dag" : "linear",
                        "restart_from", useDag ? "graph_start" : restartCheckpoint.getNextTodoId()
                ));
        return new PlanResolution(plan, useDag, effectiveExecutionMode, restartCheckpoint);
    }
}
