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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 DAG 依赖图的并行工作流执行器。
 * <p>
 * 将 TodoPlan 构建为有向无环图，然后按拓扑顺序并行执行入度为 0 的节点，
 * 节点完成后减少后继节点入度，入度变为 0 的后继节点加入就绪队列。
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

    @Value("${agent.dag.executor.poll-timeout-minutes:5}")
    private int pollTimeoutMinutes;

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

        WorkflowState state = stateStore.loadWorkflowState(runId)
                .orElseGet(() -> WorkflowState.builder()
                        .currentIndex(0)
                        .completedItems(new ArrayList<>())
                        .context(new LinkedHashMap<>())
                        .completedNodeIds(new java.util.HashSet<>())
                        .runningNodeIds(new java.util.HashSet<>())
                        .executionModeName("DAG")
                        .toolCallsUsed(0)
                        .savedAt(Instant.now())
                        .build());

        toolCallCounter.reset(runId);
        toolCallCounter.set(runId, state.getToolCallsUsed());

        Set<String> completed = ConcurrentHashMap.newKeySet();
        if (state.getCompletedNodeIds() != null) {
            completed.addAll(state.getCompletedNodeIds());
        }
        Map<String, TodoExecutionRecord> context = new ConcurrentHashMap<>(
                state.getContext() == null ? Map.of() : state.getContext());
        List<TodoItem> allProcessedItems = java.util.Collections.synchronizedList(new ArrayList<>(
                state.getCompletedItems() == null ? List.of() : state.getCompletedItems()));
        boolean[] hasFailure = {false};

        BlockingQueue<String> readyQueue = new LinkedBlockingQueue<>();
        for (String nodeId : graph.getNodesWithZeroIndegree()) {
            if (!completed.contains(nodeId)) {
                readyQueue.offer(nodeId);
            }
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

        eventService.append(runId, userId, "DAG_EXECUTION_STARTED", Map.of(
                "total_nodes", graph.getTotalNodes(),
                "initial_ready", readyQueue.size()
        ));

        List<CompletableFuture<Void>> futures = java.util.Collections.synchronizedList(new ArrayList<>());

        while (completed.size() < graph.getTotalNodes()) {
            if (!eventService.isRunnable(runId, userId)) {
                saveCheckpoint(runId, state, completed, context, allProcessedItems);
                eventService.append(runId, userId, "WORKFLOW_PAUSED", Map.of(
                        "completed_nodes", completed.size(),
                        "total_nodes", graph.getTotalNodes(),
                        "tool_calls_used", toolCallCounter.get(runId)
                ));
                return WorkflowExecutionResult.builder()
                        .paused(true)
                        .success(false)
                        .failureReason("")
                        .finalAnswer("")
                        .completedItems(new ArrayList<>(allProcessedItems))
                        .context(new LinkedHashMap<>(context))
                        .toolCallsUsed(toolCallCounter.get(runId))
                        .build();
            }

            String todoId;
            try {
                todoId = readyQueue.poll(pollTimeoutMinutes, TimeUnit.MINUTES);
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

            if (todoId == null) {
                // Timeout - check if there are still running futures
                boolean allDone = futures.stream().allMatch(CompletableFuture::isDone);
                if (allDone && completed.size() < graph.getTotalNodes()) {
                    log.error("DAG execution timeout: completed={}, total={}",
                            completed.size(), graph.getTotalNodes());
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
                continue;
            }

            if (completed.contains(todoId)) {
                continue;
            }

            TodoItem item = graph.getNode(todoId);
            if (item == null) {
                completed.add(todoId);
                continue;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                // 显式传递 runId，不依赖 ThreadLocal
                String currentRunId = request.getRun().getId();
                String currentUserId = request.getUserId();
                AgentContext.setRunId(currentRunId);
                try {
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

                    // 减少后继节点入度，入度变为 0 时加入就绪队列
                    for (String successor : graph.getSuccessors(todoId)) {
                        int newDegree = graph.decrementIndegree(successor);
                        if (newDegree == 0 && !completed.contains(successor)) {
                            readyQueue.offer(successor);
                        }
                    }

                    // 保存检查点
                    saveCheckpoint(currentRunId, state, completed, context, allProcessedItems);
                } finally {
                    AgentContext.clear();
                }
            }, dagExecutor);

            futures.add(future);
        }

        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

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
        state.setCompletedNodeIds(new java.util.HashSet<>(completed));
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
