package world.willfrog.agentlangchain.execution;

import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointFailureRecoveryService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已冻结计划的步骤执行与“长工具挂起”交接协调器。
 *
 * <p>Run 启动/规划仍由 pipeline 负责；本类只选择 LINEAR/DAG 执行器，并保证 checkpoint
 * 先于 suspended 事件确认成功写入数据库。这样步骤编排不再与 Run 启动、终态写入混在一个方法里。</p>
 */
@Slf4j
final class LangchainWorkflowStepCoordinator {

    private final LangchainLinearWorkflowExecutor linearExecutor;
    private final LangchainDagWorkflowExecutor dagExecutor;
    private final AgentRunEventService eventService;
    private final LangchainToolJobCheckpointCoordinator checkpointCoordinator;

    LangchainWorkflowStepCoordinator(LangchainLinearWorkflowExecutor linearExecutor,
                                     LangchainDagWorkflowExecutor dagExecutor,
                                     AgentRunEventService eventService,
                                     LangchainToolJobCheckpointCoordinator checkpointCoordinator) {
        this.linearExecutor = linearExecutor;
        this.dagExecutor = dagExecutor;
        this.eventService = eventService;
        this.checkpointCoordinator = checkpointCoordinator;
    }

    Outcome execute(String runId,
                    String userId,
                    LangchainWorkflowRequest request,
                    LangchainTodoPlan plan,
                    boolean useDag) {
        LangchainWorkflowResult result = useDag
                ? dagExecutor.executePlanned(request, plan)
                : linearExecutor.executePlanned(request, plan);
        if (!result.isSuspended()) {
            return new Outcome(result, false);
        }
        if (useDag) {
            throw new IllegalStateException("dag_workflow_suspended_without_frontier_checkpoint");
        }
        LangchainToolJobCheckpointCoordinator.Attempt checkpoint = checkpointCoordinator.persist(runId, result);
        if (!checkpoint.persisted()
                && checkpointCoordinator.recordFailure(runId, userId, result, checkpoint.request())
                != ToolJobCheckpointFailureRecoveryService.Outcome.HEALTHY_CHECKPOINT) {
            return new Outcome(result, true);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run_id", runId);
        payload.put("tool_call_id", nvl(result.getPendingToolCallId()));
        payload.put("attempt", result.getPendingAttempt());
        payload.put("todo_id", nvl(result.getSuspendedTodoId()));
        payload.put("todo_sequence", result.getSuspendedTodoSequence() == null
                ? 0 : result.getSuspendedTodoSequence());
        payload.put("workflow", "linear");
        eventService.append(runId, userId, "TOOL_CALL_SUSPENDED", payload);
        log.info("LangChain run {} suspended for external tool job {}", runId, result.getPendingToolCallId());
        return new Outcome(result, true);
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    record Outcome(LangchainWorkflowResult result, boolean workerReleased) {
    }
}
