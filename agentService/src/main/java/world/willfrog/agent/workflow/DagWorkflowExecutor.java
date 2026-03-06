package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.context.AgentContext;
import world.willfrog.agent.entity.AgentRun;
import world.willfrog.agent.graph.SubAgentRunner;
import world.willfrog.agent.service.AgentCreditService;
import world.willfrog.agent.service.AgentEventService;
import world.willfrog.agent.service.AgentLlmRequestSnapshotBuilder;
import world.willfrog.agent.service.AgentObservabilityService;
import world.willfrog.agent.service.AgentPromptService;
import world.willfrog.agent.service.AgentRunStateStore;
import world.willfrog.agent.tool.ToolRouter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 DAG 依赖图的并行工作流执行器。
 * <p>
 * 将 TodoPlan 构建为有向无环图，然后按拓扑顺序并行执行入度为 0 的节点，
 * 节点完成后减少后继节点入度，入度变为 0 的后继节点递归提交执行。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DagWorkflowExecutor implements WorkflowExecutor {

    private final DagBuilder dagBuilder;
    private final AgentEventService eventService;
    private final AgentPromptService promptService;
    private final ToolRouter toolRouter;
    private final SubAgentRunner subAgentRunner;
    private final TodoParamResolver paramResolver;
    private final ToolCallCounter toolCallCounter;
    private final AgentRunStateStore stateStore;
    private final AgentLlmRequestSnapshotBuilder llmRequestSnapshotBuilder;
    private final AgentObservabilityService observabilityService;
    private final AgentCreditService creditService;
    @Qualifier("dagExecutor")
    private final ExecutorService dagExecutor;
    private final ObjectMapper objectMapper;

    @Value("${agent.flow.workflow.max-tool-calls:20}")
    private int defaultMaxToolCalls;

    @Value("${agent.dag.executor.timeout-minutes:10}")
    private int timeoutMinutes;

    @Override
    public WorkflowExecutionResult execute(LinearWorkflowExecutor.WorkflowRequest request) {
        AgentRun run = request.getRun();
        String runId = run.getId();
        String userId = request.getUserId();

        ExecutionGraph graph;
        try {
            graph = dagBuilder.buildGraph(request.getTodoPlan());
        } catch (DagValidationException e) {
            log.error("DAG validation failed for runId={}: {}", runId, e.getMessage());
            return WorkflowExecutionResult.builder()
                    .paused(false)
                    .success(false)
                    .failureReason("dag_validation_failed: " + e.getMessage())
                    .finalAnswer("")
                    .completedItems(List.of())
                    .context(Map.of())
                    .toolCallsUsed(0)
                    .build();
        }

        if (graph.getTotalNodes() == 0) {
            return WorkflowExecutionResult.builder()
                    .paused(false)
                    .success(true)
                    .finalAnswer("")
                    .completedItems(List.of())
                    .context(Map.of())
                    .toolCallsUsed(0)
                    .build();
        }

        WorkflowState state = stateStore.loadWorkflowState(runId)
                .orElseGet(() -> WorkflowState.builder()
                        .currentIndex(0)
                        .completedItems(new ArrayList<>())
                        .context(new LinkedHashMap<>())
                        .completedNodeIds(new HashSet<>())
                        .runningNodeIds(new HashSet<>())
                        .executionModeName("DAG")
                        .toolCallsUsed(0)
                        .savedAt(Instant.now())
                        .build());

        toolCallCounter.reset(runId);
        toolCallCounter.set(runId, state.getToolCallsUsed());

        // 检查是否可运行
        if (!eventService.isRunnable(runId, userId)) {
            eventService.append(runId, userId, "WORKFLOW_PAUSED", Map.of(
                    "total_nodes", graph.getTotalNodes(),
                    "tool_calls_used", toolCallCounter.get(runId)
            ));
            return WorkflowExecutionResult.builder()
                    .paused(true)
                    .success(false)
                    .failureReason("")
                    .finalAnswer("")
                    .completedItems(new ArrayList<>(state.getCompletedItems()))
                    .context(new LinkedHashMap<>(state.getContext()))
                    .toolCallsUsed(toolCallCounter.get(runId))
                    .build();
        }

        Set<String> completed = ConcurrentHashMap.newKeySet();
        if (state.getCompletedNodeIds() != null) {
            completed.addAll(state.getCompletedNodeIds());
        }
        Map<String, TodoExecutionRecord> context = new ConcurrentHashMap<>(
                state.getContext() == null ? Map.of() : state.getContext());
        List<TodoItem> allProcessedItems = java.util.Collections.synchronizedList(new ArrayList<>(
                state.getCompletedItems() == null ? List.of() : state.getCompletedItems()));
        boolean[] hasFailure = {false};

        int nodesToProcess = graph.getTotalNodes() - completed.size();
        if (nodesToProcess <= 0) {
            stateStore.clearWorkflowState(runId);
            String finalAnswer = generateFinalAnswer(request, allProcessedItems, context);
            return WorkflowExecutionResult.builder()
                    .paused(false).success(true).finalAnswer(finalAnswer)
                    .completedItems(new ArrayList<>(allProcessedItems))
                    .context(new LinkedHashMap<>(context))
                    .toolCallsUsed(toolCallCounter.get(runId))
                    .build();
        }

        CountDownLatch latch = new CountDownLatch(nodesToProcess);

        eventService.append(runId, userId, "DAG_EXECUTION_STARTED", Map.of(
                "total_nodes", graph.getTotalNodes(),
                "nodes_to_process", nodesToProcess
        ));

        // 提交所有入度为 0 的就绪节点
        for (String nodeId : graph.getNodesWithZeroIndegree()) {
            if (!completed.contains(nodeId)) {
                submitNode(nodeId, graph, request, completed, context, allProcessedItems,
                        hasFailure, latch);
            }
        }

        // 等待所有节点完成
        try {
            boolean finished = latch.await(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                log.error("DAG execution timeout for runId={}: completed={}, total={}",
                        runId, completed.size(), graph.getTotalNodes());
                saveCheckpoint(runId, state, completed, context, allProcessedItems);
                return WorkflowExecutionResult.builder()
                        .paused(false)
                        .success(false)
                        .failureReason("dag_execution_timeout")
                        .finalAnswer("")
                        .completedItems(new ArrayList<>(allProcessedItems))
                        .context(new LinkedHashMap<>(context))
                        .toolCallsUsed(toolCallCounter.get(runId))
                        .build();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveCheckpoint(runId, state, completed, context, allProcessedItems);
            return WorkflowExecutionResult.builder()
                    .paused(true)
                    .success(false)
                    .failureReason("interrupted")
                    .finalAnswer("")
                    .completedItems(new ArrayList<>(allProcessedItems))
                    .context(new LinkedHashMap<>(context))
                    .toolCallsUsed(toolCallCounter.get(runId))
                    .build();
        }

        stateStore.clearWorkflowState(runId);
        String finalAnswer = generateFinalAnswer(request, allProcessedItems, context);

        eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", Map.of(
                "total_nodes", graph.getTotalNodes(),
                "completed_nodes", completed.size(),
                "has_failure", hasFailure[0],
                "tool_calls_used", toolCallCounter.get(runId)
        ));

        if (hasFailure[0]) {
            return WorkflowExecutionResult.builder()
                    .paused(false)
                    .success(false)
                    .failureReason("todo_partial_failed")
                    .finalAnswer(finalAnswer)
                    .completedItems(new ArrayList<>(allProcessedItems))
                    .context(new LinkedHashMap<>(context))
                    .toolCallsUsed(toolCallCounter.get(runId))
                    .build();
        }
        return WorkflowExecutionResult.builder()
                .paused(false)
                .success(true)
                .failureReason("")
                .finalAnswer(finalAnswer)
                .completedItems(new ArrayList<>(allProcessedItems))
                .context(new LinkedHashMap<>(context))
                .toolCallsUsed(toolCallCounter.get(runId))
                .build();
    }

    /**
     * 将节点提交到线程池异步执行。完成后自动调度后继节点。
     */
    private void submitNode(String todoId,
                            ExecutionGraph graph,
                            LinearWorkflowExecutor.WorkflowRequest request,
                            Set<String> completed,
                            Map<String, TodoExecutionRecord> context,
                            List<TodoItem> allProcessedItems,
                            boolean[] hasFailure,
                            CountDownLatch latch) {
        dagExecutor.submit(() -> {
            String currentRunId = request.getRun().getId();
            String currentUserId = request.getUserId();
            AgentContext.setRunId(currentRunId);
            try {
                TodoItem item = graph.getNode(todoId);
                if (item == null || completed.contains(todoId)) {
                    return;
                }

                item.setStatus(TodoStatus.RUNNING);
                eventService.append(currentRunId, currentUserId, "TODO_STARTED", Map.of(
                        "todo_id", nvl(item.getId()),
                        "sequence", item.getSequence(),
                        "type", item.getType() == null ? "TOOL_CALL" : item.getType().name(),
                        "tool", nvl(item.getToolName()),
                        "parallel", true
                ));

                TodoExecutionRecord record = executeTodo(request, item, context);

                item.setCompletedAt(Instant.now());
                item.setResultSummary(nvl(record.getSummary()));
                item.setOutput(nvl(record.getOutput()));

                if (record.isSuccess()) {
                    item.setStatus(TodoStatus.COMPLETED);
                    context.put(item.getId(), record);
                    eventService.append(currentRunId, currentUserId, "TODO_FINISHED", Map.of(
                            "todo_id", nvl(item.getId()),
                            "success", true,
                            "summary", nvl(record.getSummary()),
                            "output_preview", preview(record.getOutput()),
                            "tool_calls_used", toolCallCounter.get(currentRunId)
                    ));
                } else {
                    item.setStatus(TodoStatus.FAILED);
                    hasFailure[0] = true;
                    eventService.append(currentRunId, currentUserId, "TODO_FAILED", Map.of(
                            "todo_id", nvl(item.getId()),
                            "success", false,
                            "summary", nvl(record.getSummary()),
                            "output_preview", preview(record.getOutput()),
                            "tool_calls_used", toolCallCounter.get(currentRunId)
                    ));
                }
                allProcessedItems.add(item);
                completed.add(todoId);

                // 调度后继节点
                for (String successor : graph.getSuccessors(todoId)) {
                    int newDegree = graph.decrementIndegree(successor);
                    if (newDegree == 0 && !completed.contains(successor)) {
                        submitNode(successor, graph, request, completed, context,
                                allProcessedItems, hasFailure, latch);
                    }
                }
            } catch (Exception e) {
                log.error("DAG node execution failed for todoId={}, runId={}", todoId, currentRunId, e);
                completed.add(todoId);

                // 如果该节点失败，也需要释放后继节点的 latch（否则会永远等待）
                // 通过 countDown 来释放后继节点
                for (String successor : graph.getSuccessors(todoId)) {
                    int newDegree = graph.decrementIndegree(successor);
                    if (newDegree == 0 && !completed.contains(successor)) {
                        submitNode(successor, graph, request, completed, context,
                                allProcessedItems, hasFailure, latch);
                    }
                }
            } finally {
                AgentContext.clear();
                latch.countDown();
            }
        });
    }

    private TodoExecutionRecord executeTodo(LinearWorkflowExecutor.WorkflowRequest request,
                                            TodoItem item,
                                            Map<String, TodoExecutionRecord> context) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        TodoType type = item.getType() == null ? TodoType.TOOL_CALL : item.getType();

        if (type == TodoType.THOUGHT) {
            return TodoExecutionRecord.builder()
                    .success(true)
                    .output(nvl(item.getReasoning()))
                    .summary(nvl(item.getReasoning()))
                    .toolCallsUsed(0)
                    .build();
        }

        if (type == TodoType.SUB_AGENT) {
            eventService.append(runId, userId, "SUB_AGENT_STARTED", Map.of(
                    "todo_id", nvl(item.getId()),
                    "goal", nvl(item.getReasoning())
            ));
            Map<String, Object> resolvedParams = paramResolver.resolve(item.getParams(), context);
            String goal = nvl(item.getReasoning()).isBlank()
                    ? "请完成任务: " + nvl(item.getId())
                    : nvl(item.getReasoning());
            Set<String> whitelist = request.getToolSpecifications().stream()
                    .map(ToolSpecification::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            SubAgentRunner.SubAgentResult subResult = subAgentRunner.run(
                    SubAgentRunner.SubAgentRequest.builder()
                            .runId(runId)
                            .userId(userId)
                            .taskId(item.getId())
                            .goal(goal)
                            .context(safeWrite(Map.of(
                                    "user_goal", nvl(request.getUserGoal()),
                                    "resolved_params", resolvedParams,
                                    "done", context
                            )))
                            .seedArgs(resolvedParams)
                            .toolWhitelist(whitelist)
                            .toolSpecifications(request.getToolSpecifications())
                            .maxSteps(10)
                            .endpointName(request.getEndpointName())
                            .endpointBaseUrl(request.getEndpointBaseUrl())
                            .modelName(request.getModelName())
                            .build(),
                    request.getModel()
            );

            int usedCalls = subResult.getSteps() == null ? 1 : Math.max(1, subResult.getSteps().size());
            toolCallCounter.increment(runId, usedCalls);

            if (!subResult.isSuccess()) {
                return TodoExecutionRecord.builder()
                        .success(false)
                        .output(nvl(subResult.getError()))
                        .summary(nvl(subResult.getError()))
                        .toolCallsUsed(usedCalls)
                        .build();
            }
            return TodoExecutionRecord.builder()
                    .success(true)
                    .output(nvl(subResult.getAnswer()))
                    .summary(preview(subResult.getAnswer()))
                    .toolCallsUsed(usedCalls)
                    .build();
        }

        // TOOL_CALL
        if (toolCallCounter.isLimitReached(runId, defaultMaxToolCalls)) {
            return TodoExecutionRecord.builder()
                    .success(false)
                    .output("")
                    .summary("tool_call_limit_reached")
                    .toolCallsUsed(0)
                    .build();
        }

        Map<String, Object> resolvedParams = paramResolver.resolve(item.getParams(), context);
        String toolName = nvl(item.getToolName());

        int creditsConsumed = creditService.calculateToolCredits(toolName, false);
        eventService.append(runId, userId, "TOOL_CALL_STARTED", Map.of(
                "todo_id", nvl(item.getId()),
                "tool_name", toolName,
                "toolName", toolName,
                "parameters", resolvedParams
        ));

        ToolRouter.ToolInvocationResult invokeResult = toolRouter.invokeWithMeta(toolName, resolvedParams);
        toolCallCounter.increment(runId, 1);

        eventService.append(runId, userId, "TOOL_CALL_FINISHED", Map.of(
                "todo_id", nvl(item.getId()),
                "tool_name", toolName,
                "toolName", toolName,
                "success", invokeResult.isSuccess(),
                "creditsConsumed", creditsConsumed,
                "output_preview", preview(invokeResult.getOutput())
        ));

        return TodoExecutionRecord.builder()
                .success(invokeResult.isSuccess())
                .output(nvl(invokeResult.getOutput()))
                .summary(preview(invokeResult.getOutput()))
                .toolCallsUsed(1)
                .failureCategory(invokeResult.isSuccess() ? "" : "RUNTIME")
                .build();
    }

    private synchronized void saveCheckpoint(String runId,
                                             WorkflowState state,
                                             Set<String> completed,
                                             Map<String, TodoExecutionRecord> context,
                                             List<TodoItem> allProcessedItems) {
        state.setCompletedNodeIds(new HashSet<>(completed));
        state.setCompletedItems(new ArrayList<>(allProcessedItems));
        state.setContext(new LinkedHashMap<>(context));
        state.setToolCallsUsed(toolCallCounter.get(runId));
        state.setSavedAt(Instant.now());
        state.setExecutionModeName("DAG");
        stateStore.saveWorkflowState(runId, state);
    }

    private String generateFinalAnswer(LinearWorkflowExecutor.WorkflowRequest request,
                                       List<TodoItem> completed,
                                       Map<String, TodoExecutionRecord> context) {
        String runId = request.getRun().getId();
        String userId = request.getUserId();
        eventService.append(runId, userId, "FINAL_ANSWER_GENERATING", Map.of(
                "completed_items", completed == null ? 0 : completed.size()
        ));

        List<Map<String, Object>> summary = new ArrayList<>();
        for (TodoItem item : completed == null ? List.<TodoItem>of() : completed) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", nvl(item.getId()));
            row.put("sequence", item.getSequence());
            row.put("type", item.getType() == null ? "" : item.getType().name());
            row.put("status", item.getStatus() == null ? "" : item.getStatus().name());
            row.put("summary", nvl(item.getResultSummary()));
            summary.add(row);
        }

        List<ChatMessage> messages = List.of(
                new SystemMessage(promptService.workflowFinalSystemPrompt()),
                new UserMessage("用户需求: " + nvl(request.getUserGoal())
                        + "\n执行摘要: " + safeWrite(summary)
                        + "\n执行上下文: " + safeWrite(context))
        );

        AgentContext.setPhase(AgentObservabilityService.PHASE_SUMMARIZING);
        AgentContext.setStage("workflow_final_answer");
        long llmStartedAt = System.currentTimeMillis();
        ChatResponse response;
        try {
            response = request.getModel().chat(messages);
        } finally {
            AgentContext.clearStage();
        }
        long llmCompletedAt = System.currentTimeMillis();
        String answer = response.aiMessage() == null ? "" : nvl(response.aiMessage().text());

        Map<String, Object> llmRequestSnapshot = llmRequestSnapshotBuilder.buildChatCompletionsRequest(
                request.getEndpointName(),
                request.getEndpointBaseUrl(),
                request.getModelName(),
                messages,
                request.getToolSpecifications(),
                Map.of("stage", "workflow_final_answer")
        );
        observabilityService.recordLlmCall(
                runId,
                AgentObservabilityService.PHASE_SUMMARIZING,
                response.metadata() != null ? response.metadata().tokenUsage() : null,
                llmCompletedAt - llmStartedAt,
                llmStartedAt,
                llmCompletedAt,
                request.getEndpointName(),
                request.getModelName(),
                null,
                llmRequestSnapshot,
                answer
        );

        eventService.append(runId, userId, "FINAL_ANSWER_COMPLETED", Map.of(
                "answer_preview", preview(answer)
        ));
        return answer;
    }

    private String safeWrite(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String nvl(String text) {
        return text == null ? "" : text;
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
