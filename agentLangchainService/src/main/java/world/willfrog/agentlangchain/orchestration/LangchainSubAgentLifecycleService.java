package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.tools.subagent.SubAgentControlHandler;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D06 子代理的生产生命周期实现。
 *
 * <p>接受与终态写入同一 Agent Run 的 durable 事件；进程内 Future 只负责当前 JVM 的实际执行。
 * 已完成结果可在句柄清理或重启后通过事件重读。未完成任务不跨 JVM 恢复，等待会明确返回
 * {@code RECOVERY_UNSUPPORTED}，不会永久挂起或在新进程偷偷重复执行。</p>
 */
@Service
@Slf4j
public class LangchainSubAgentLifecycleService implements SubAgentControlHandler {

    static final String ACCEPTED_EVENT = "SUB_AGENT_ACCEPTED";
    static final String RUNNING_EVENT = "SUB_AGENT_RUNNING";
    static final String TERMINAL_EVENT = "SUB_AGENT_TERMINAL";
    private static final String SPAWN_TOOL = "spawnSubAgent";
    private static final String WAIT_TOOL = "waitForSubAgent";
    private static final int GOAL_MAX_CHARS = 2_000;
    private static final int CONTEXT_MAX_CHARS = 2_000;
    private static final Set<String> CONTROL_TOOLS = Set.of(SPAWN_TOOL, WAIT_TOOL);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final LangchainTodoNodeExecutor todoExecutor;
    private final LangchainRunExecutionGuard executionGuard;
    private final AgentRunEventService eventService;
    private final AgentPromptService promptService;
    private final AgentLlmProperties llmProperties;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutScheduler;
    private final String ownerInstanceId = UUID.randomUUID().toString();
    private final Map<String, LiveHandle> liveHandles = new java.util.concurrent.ConcurrentHashMap<>();
    /** 当前进程对终态写库失败的有界诚实结果缓存；它不是 durable 真相。 */
    private final Map<String, TerminalState> transientTerminalFailures =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int TRANSIENT_TERMINAL_FAILURE_LIMIT = 256;
    private final Object spawnLock = new Object();

    @Value("${agent.sub-agent.max-wait-millis:30000}")
    private long maxWaitMillis = 30_000L;

    @Value("${agent.sub-agent.default-wait-millis:5000}")
    private long defaultWaitMillis = 5_000L;

    @Value("${agent.sub-agent.task-timeout-millis:120000}")
    private long taskTimeoutMillis = 120_000L;

    @Value("${agent.sub-agent.result-max-chars:4096}")
    private int resultMaxChars = 4096;

    public LangchainSubAgentLifecycleService(
            LangchainTodoNodeExecutor todoExecutor,
            LangchainRunExecutionGuard executionGuard,
            AgentRunEventService eventService,
            AgentPromptService promptService,
            AgentLlmProperties llmProperties,
            AgentLlmLocalConfigLoader localConfigLoader,
            ObjectMapper objectMapper,
            @Qualifier("langchainSubAgentExecutor") ExecutorService executor,
            @Qualifier("langchainSubAgentTimeoutScheduler") ScheduledExecutorService timeoutScheduler) {
        this.todoExecutor = todoExecutor;
        this.executionGuard = executionGuard;
        this.eventService = eventService;
        this.promptService = promptService;
        this.llmProperties = llmProperties;
        this.localConfigLoader = localConfigLoader;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.timeoutScheduler = timeoutScheduler;
    }

    @Override
    public String spawn(Map<String, Object> params) {
        String runId = trim(AgentContext.getRunId());
        String userId = trim(AgentContext.getUserId());
        String goal = bounded(trim(first(params, "goal", "arg0")), GOAL_MAX_CHARS);
        String context = bounded(trim(first(params, "context", "arg1")), CONTEXT_MAX_CHARS);
        if (runId.isEmpty() || userId.isEmpty()) {
            return error(SPAWN_TOOL, "SUB_AGENT_RUN_CONTEXT_REQUIRED",
                    "Sub-agent spawn requires the current run and user context", Map.of());
        }
        if (!promptService.subAgentEnabled()) {
            return error(SPAWN_TOOL, "SUB_AGENT_DISABLED", "Sub-agent execution is disabled", Map.of());
        }
        if (AgentRunObservabilityService.PHASE_SUB_AGENT.equals(AgentContext.getPhase())) {
            return error(SPAWN_TOOL, "SUB_AGENT_RECURSION_FORBIDDEN",
                    "A child agent cannot create another child agent", Map.of());
        }
        if (goal.isEmpty()) {
            return error(SPAWN_TOOL, "SUB_AGENT_GOAL_REQUIRED", "goal must not be blank", Map.of());
        }
        Optional<String> stop = executionGuard.stopReason(runId, userId);
        if (stop.isPresent()) {
            return error(SPAWN_TOOL, "RUN_INTERRUPTED", "The parent run cannot start new work",
                    Map.of("reason", stop.get()));
        }
        Optional<LangchainSubAgentExecutionContext.Environment> environment =
                LangchainSubAgentExecutionContext.current();
        if (environment.isEmpty() || environment.get().parentRequest() == null) {
            return error(SPAWN_TOOL, "SUB_AGENT_EXECUTION_CONTEXT_REQUIRED",
                    "Sub-agent spawn is only available inside the active LangChain tool loop", Map.of());
        }

        String toolCallId = trim(AgentContext.getToolCallId());
        if (toolCallId.isEmpty()) {
            return error(SPAWN_TOOL, "SUB_AGENT_TOOL_CALL_ID_REQUIRED",
                    "Sub-agent spawn requires the stable id of the current tool call", Map.of());
        }
        String subAgentId = stableId(runId, toolCallId);
        String key = handleKey(runId, subAgentId);
        synchronized (spawnLock) {
            Optional<AgentRunEvent> existingAccepted = acceptedEvent(runId, subAgentId);
            if (existingAccepted.isPresent()) {
                return existingSpawnResponse(runId, subAgentId);
            }
            int active = activeSubAgentCount(runId);
            int maxCount = Math.max(1, promptService.maxSubAgentCount());
            if (active >= maxCount) {
                return error(SPAWN_TOOL, "SUB_AGENT_LIMIT_EXCEEDED",
                        "The current run already has the maximum number of active child agents",
                        Map.of("active", active, "max", maxCount));
            }

            int maxToolRoundTrips = resolveMaxToolCallsPerSubAgent();
            // 当前生产实现把一个子代理建模为一个带工具循环的 Todo，因此有效逻辑步骤数是 1。
            // 配置仍作为上限读取；不能在事件里虚报一个尚未真正实现的多步骤执行器。
            int configuredMaxSteps = Math.max(1, promptService.maxSubAgentSteps());
            int maxSteps = Math.min(configuredMaxSteps, 1);
            Map<String, Object> acceptedPayload = new LinkedHashMap<>();
            acceptedPayload.put("subAgentId", subAgentId);
            acceptedPayload.put("runId", runId);
            acceptedPayload.put("status", Status.ACCEPTED.name());
            acceptedPayload.put("ownerInstanceId", ownerInstanceId);
            acceptedPayload.put("goal", goal);
            acceptedPayload.put("context", context);
            acceptedPayload.put("maxSteps", maxSteps);
            acceptedPayload.put("maxToolRoundTrips", maxToolRoundTrips);
            acceptedPayload.put("acceptedAt", Instant.now().toString());
            boolean inserted = eventService.appendOnce(runId, userId, ACCEPTED_EVENT,
                    acceptedKey(subAgentId), acceptedPayload);
            if (!inserted) {
                return existingSpawnResponse(runId, subAgentId);
            }

            LiveHandle handle = new LiveHandle(runId, userId, subAgentId);
            liveHandles.put(key, handle);
            try {
                Future<?> future = executor.submit(() -> runChild(
                        environment.get(), handle, goal, context, maxSteps, maxToolRoundTrips));
                handle.future = future;
                handle.timeoutFuture = timeoutScheduler.schedule(
                        () -> timeOut(handle),
                        Math.max(1L, taskTimeoutMillis),
                        TimeUnit.MILLISECONDS);
                handle.controlMonitorFuture = timeoutScheduler.scheduleWithFixedDelay(
                        () -> monitorParent(handle), 100L, 100L, TimeUnit.MILLISECONDS);
                if (handle.terminal.isDone()) {
                    cancelScheduledChecks(handle);
                }
            } catch (RejectedExecutionException rejected) {
                completeTerminal(handle, Status.FAILED, "", "SUB_AGENT_EXECUTOR_BUSY", 0);
                Future<?> future = handle.future;
                if (future != null) {
                    future.cancel(true);
                }
                return error(SPAWN_TOOL, "SUB_AGENT_EXECUTOR_BUSY",
                        "The bounded child-agent executor is full",
                        Map.of("subAgentId", subAgentId));
            }
            return success(SPAWN_TOOL, Map.of(
                    "subAgentId", subAgentId,
                    "runId", runId,
                    "status", Status.ACCEPTED.name(),
                    "maxSteps", maxSteps,
                    "maxToolRoundTrips", maxToolRoundTrips));
        }
    }

    @Override
    public String waitFor(Map<String, Object> params) {
        String runId = trim(AgentContext.getRunId());
        String userId = trim(AgentContext.getUserId());
        if (runId.isEmpty() || userId.isEmpty()) {
            return error(WAIT_TOOL, "SUB_AGENT_RUN_CONTEXT_REQUIRED",
                    "Sub-agent wait requires the current run and user context", Map.of());
        }
        if (AgentRunObservabilityService.PHASE_SUB_AGENT.equals(AgentContext.getPhase())) {
            return error(WAIT_TOOL, "SUB_AGENT_RECURSION_FORBIDDEN",
                    "A child agent cannot wait for child agents", Map.of());
        }
        List<String> ids = parseIds(params);
        if (ids.isEmpty()) {
            return error(WAIT_TOOL, "SUB_AGENT_IDS_REQUIRED",
                    "subAgentIds must contain at least one id", Map.of());
        }
        int maxIds = Math.max(1, promptService.maxSubAgentCount());
        if (ids.size() > maxIds) {
            return error(WAIT_TOOL, "SUB_AGENT_IDS_LIMIT_EXCEEDED",
                    "A wait call cannot contain more child-agent ids than the run limit",
                    Map.of("provided", ids.size(), "max", maxIds));
        }
        long waitMillis = resolveWaitMillis(first(params, "timeoutMillis", "timeout_millis", "arg1"));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);

        while (true) {
            Optional<String> stop = executionGuard.stopReason(runId, userId);
            if (stop.isPresent()) {
                cancelLiveForRun(runId, "RUN_INTERRUPTED:" + stop.get());
                return error(WAIT_TOOL, "RUN_INTERRUPTED", "The parent run stopped while waiting",
                        Map.of("reason", stop.get(), "results", collectStates(runId, ids, false)));
            }
            List<Map<String, Object>> states = collectStates(runId, ids, false);
            if (states.stream().allMatch(this::isReturnedTerminal)) {
                return waitResponse(states);
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return waitResponse(collectStates(runId, ids, true));
            }
            List<CompletableFuture<TerminalState>> live = liveTerminalFutures(runId, ids);
            if (live.isEmpty()) {
                return waitResponse(states);
            }
            long sliceMillis = Math.max(1L, Math.min(200L,
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                CompletableFuture.anyOf(live.toArray(CompletableFuture[]::new))
                        .get(sliceMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Re-check the durable state and parent control signal every bounded slice.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelLiveForRun(runId, "WAIT_INTERRUPTED");
                return error(WAIT_TOOL, "WAIT_INTERRUPTED", "Sub-agent wait was interrupted",
                        Map.of("results", collectStates(runId, ids, false)));
            } catch (ExecutionException ignored) {
                // Terminal persistence is read again at the top of the loop.
            }
        }
    }

    private void runChild(LangchainSubAgentExecutionContext.Environment environment,
                          LiveHandle handle,
                          String goal,
                          String context,
                          int maxSteps,
                          int maxToolRoundTrips) {
        AgentContext.restoreRunContext(environment.runContext());
        AgentContext.setPhase(AgentRunObservabilityService.PHASE_SUB_AGENT);
        AgentContext.setStage("sub_agent_execution");
        AgentContext.setSubAgentStepIndex(1);
        eventService.appendOnce(handle.runId, handle.userId, RUNNING_EVENT,
                runningKey(handle.subAgentId), Map.of(
                        "subAgentId", handle.subAgentId,
                        "status", Status.RUNNING.name(),
                        "ownerInstanceId", ownerInstanceId,
                        "startedAt", Instant.now().toString()));
        try {
            List<ToolSpecification> childTools = environment.parentRequest().getToolSpecifications() == null
                    ? List.of()
                    : environment.parentRequest().getToolSpecifications().stream()
                            .filter(spec -> !CONTROL_TOOLS.contains(spec.name()))
                            .toList();
            String childGoal = context.isEmpty() ? goal : goal + "\n\nContext:\n" + context;
            LangchainLinearWorkflowRequest childRequest = LangchainLinearWorkflowRequest.builder()
                    .runId(handle.runId)
                    .userId(handle.userId)
                    .userGoal(childGoal)
                    .dialogueContext(context)
                    .model(environment.parentRequest().getModel())
                    .planningModel(environment.parentRequest().getPlanningModel())
                    .executionModel(environment.parentRequest().getExecutionModel())
                    .finalAnswerModel(environment.parentRequest().getFinalAnswerModel())
                    .toolSpecifications(childTools)
                    .maxTodos(1)
                    .maxToolRoundTrips(maxToolRoundTrips)
                    .webSearchEnabled(environment.parentRequest().getWebSearchEnabled())
                    .codeInterpreterEnabled(environment.parentRequest().getCodeInterpreterEnabled())
                    .build();
            TodoItem item = TodoItem.builder()
                    .id("sub_agent_" + handle.subAgentId)
                    .sequence(1)
                    .description(childGoal)
                    .status(TodoStatus.PENDING)
                    .createdAt(Instant.now())
                    .parallelizable(false)
                    .build();
            AtomicInteger runToolCalls = environment.runToolCalls();
            if (runToolCalls == null) {
                completeTerminal(handle, Status.FAILED, "",
                        "SUB_AGENT_RUN_TOOL_BUDGET_CONTEXT_MISSING", 0);
                return;
            }
            Map<String, String> childDatasetRefs = new LinkedHashMap<>(environment.datasetRefs());
            LangchainTodoNodeResult result = todoExecutor.execute(
                    childRequest, item, List.of(), childDatasetRefs, runToolCalls);
            int childToolCalls = Math.max(0, result.getToolCallsUsed());
            if (result.isSuspended()) {
                completeTerminal(handle, Status.FAILED, "",
                        "SUB_AGENT_DURABLE_TOOL_SUSPEND_UNSUPPORTED", childToolCalls);
            } else if (result.isSuccess()) {
                completeTerminal(handle, Status.SUCCEEDED, result.getOutput(), "", childToolCalls);
            } else if (result.getFailureReason() != null
                    && result.getFailureReason().startsWith("RUN_INTERRUPTED:")) {
                completeTerminal(handle, Status.CANCELED, "", result.getFailureReason(), childToolCalls);
            } else {
                completeTerminal(handle, Status.FAILED, "", trim(result.getFailureReason()), childToolCalls);
            }
        } catch (Throwable failure) {
            Status status = Thread.currentThread().isInterrupted() ? Status.CANCELED : Status.FAILED;
            completeTerminal(handle, status, "", trim(failure.getMessage()), 0);
        } finally {
            AgentContext.clear();
        }
    }

    private void timeOut(LiveHandle handle) {
        if (handle.terminal.isDone()) {
            return;
        }
        completeTerminal(handle, Status.TIMEOUT, "", "SUB_AGENT_TASK_TIMEOUT", 0);
        Future<?> future = handle.future;
        if (future != null) {
            future.cancel(true);
        }
    }

    private void monitorParent(LiveHandle handle) {
        if (handle.terminal.isDone()) {
            cancelScheduledChecks(handle);
            return;
        }
        Optional<String> stop;
        try {
            stop = executionGuard.stopReason(handle.runId, handle.userId);
        } catch (RuntimeException transientFailure) {
            // scheduleWithFixedDelay 会在任务抛异常后永久停止后续轮询。这里保留监控任务，
            // 让一次短暂的存储读取失败不会令子代理永远失去父 Run 的取消传播。
            log.warn("Sub-agent parent control check failed and will retry: runId={}, subAgentId={}",
                    handle.runId, handle.subAgentId, transientFailure);
            return;
        }
        if (stop.isEmpty()) {
            return;
        }
        completeTerminal(handle, Status.CANCELED, "", "RUN_INTERRUPTED:" + stop.get(), 0);
        Future<?> future = handle.future;
        if (future != null) {
            future.cancel(true);
        }
    }

    private void completeTerminal(LiveHandle handle,
                                  Status status,
                                  String result,
                                  String error,
                                  int toolCallsUsed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subAgentId", handle.subAgentId);
        payload.put("status", status.name());
        payload.put("result", bounded(result, Math.max(256, resultMaxChars)));
        payload.put("error", bounded(error, 1000));
        payload.put("toolCallsUsed", Math.max(0, toolCallsUsed));
        payload.put("ownerInstanceId", ownerInstanceId);
        payload.put("finishedAt", Instant.now().toString());
        TerminalState fallback = new TerminalState(
                status, bounded(result, resultMaxChars), bounded(error, 1000), toolCallsUsed);
        TerminalState durable;
        try {
            eventService.appendOnce(handle.runId, handle.userId, TERMINAL_EVENT,
                    terminalKey(handle.subAgentId), payload);
            durable = terminalEvent(handle.runId, handle.subAgentId)
                    .map(this::parseTerminal)
                    .orElse(fallback);
        } catch (RuntimeException persistenceFailure) {
            // PostgreSQL 终态写入失败时，不能让 wait 永久挂住、监控任务永久占槽。
            // 当前进程返回明确失败；accepted 事件仍在，因此重启后会诚实地表现为
            // RECOVERY_UNSUPPORTED，而不是伪造一个已持久化终态。
            log.error("Sub-agent terminal persistence failed: runId={}, subAgentId={}, requestedStatus={}",
                    handle.runId, handle.subAgentId, status, persistenceFailure);
            durable = new TerminalState(
                    Status.FAILED,
                    "",
                    "SUB_AGENT_TERMINAL_PERSISTENCE_FAILED",
                    Math.max(0, toolCallsUsed));
            rememberTransientTerminal(handle, durable);
        }
        handle.terminal.complete(durable);
        liveHandles.remove(handleKey(handle.runId, handle.subAgentId), handle);
        cancelScheduledChecks(handle);
    }

    private void rememberTransientTerminal(LiveHandle handle, TerminalState state) {
        transientTerminalFailures.put(handleKey(handle.runId, handle.subAgentId), state);
        while (transientTerminalFailures.size() > TRANSIENT_TERMINAL_FAILURE_LIMIT) {
            var iterator = transientTerminalFailures.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            transientTerminalFailures.remove(iterator.next());
        }
    }

    private void cancelScheduledChecks(LiveHandle handle) {
        ScheduledFuture<?> timeout = handle.timeoutFuture;
        if (timeout != null) {
            timeout.cancel(false);
        }
        ScheduledFuture<?> monitor = handle.controlMonitorFuture;
        if (monitor != null) {
            monitor.cancel(false);
        }
    }

    private String existingSpawnResponse(String runId, String subAgentId) {
        Optional<AgentRunEvent> terminal = terminalEvent(runId, subAgentId);
        if (terminal.isPresent()) {
            TerminalState state = parseTerminal(terminal.get());
            Map<String, Object> replay = new LinkedHashMap<>(stateMap(subAgentId, state));
            replay.put("runId", runId);
            replay.put("replayed", true);
            return success(SPAWN_TOOL, replay);
        }
        TerminalState transientFailure = transientTerminalFailures.get(handleKey(runId, subAgentId));
        if (transientFailure != null) {
            Map<String, Object> replay = new LinkedHashMap<>(stateMap(subAgentId, transientFailure));
            replay.put("runId", runId);
            replay.put("replayed", true);
            replay.put("durable", false);
            return success(SPAWN_TOOL, replay);
        }
        LiveHandle live = liveHandles.get(handleKey(runId, subAgentId));
        if (live != null) {
            return success(SPAWN_TOOL, Map.of(
                    "subAgentId", subAgentId,
                    "runId", runId,
                    "status", runningEvent(runId, subAgentId).isPresent()
                            ? Status.RUNNING.name() : Status.ACCEPTED.name(),
                    "replayed", true));
        }
        return error(SPAWN_TOOL, "SUB_AGENT_RECOVERY_UNSUPPORTED",
                "The child was accepted by another or previous process, but unfinished child execution is not recoverable",
                Map.of("subAgentId", subAgentId, "status", "RECOVERY_UNSUPPORTED"));
    }

    private List<Map<String, Object>> collectStates(String runId, List<String> ids, boolean markWaitTimeout) {
        List<Map<String, Object>> states = new ArrayList<>(ids.size());
        for (String id : ids) {
            Optional<AgentRunEvent> accepted = acceptedEvent(runId, id);
            if (accepted.isEmpty()) {
                states.add(Map.of("subAgentId", id, "status", "NOT_FOUND"));
                continue;
            }
            Optional<AgentRunEvent> terminal = terminalEvent(runId, id);
            if (terminal.isPresent()) {
                states.add(stateMap(id, parseTerminal(terminal.get())));
                continue;
            }
            TerminalState transientFailure = transientTerminalFailures.get(handleKey(runId, id));
            if (transientFailure != null) {
                Map<String, Object> state = new LinkedHashMap<>(stateMap(id, transientFailure));
                state.put("durable", false);
                states.add(state);
                continue;
            }
            LiveHandle live = liveHandles.get(handleKey(runId, id));
            if (live == null) {
                states.add(Map.of(
                        "subAgentId", id,
                        "status", "RECOVERY_UNSUPPORTED",
                        "error", "Unfinished child execution is not recoverable on this process"));
                continue;
            }
            String status = markWaitTimeout
                    ? "WAIT_TIMEOUT"
                    : (runningEvent(runId, id).isPresent() ? Status.RUNNING.name() : Status.ACCEPTED.name());
            states.add(Map.of("subAgentId", id, "status", status));
        }
        return states;
    }

    private String waitResponse(List<Map<String, Object>> states) {
        boolean notFound = states.stream().anyMatch(state -> "NOT_FOUND".equals(state.get("status")));
        boolean recoveryUnsupported = states.stream()
                .anyMatch(state -> "RECOVERY_UNSUPPORTED".equals(state.get("status")));
        if (notFound || recoveryUnsupported) {
            String code = notFound ? "SUB_AGENT_NOT_FOUND" : "SUB_AGENT_RECOVERY_UNSUPPORTED";
            return error(WAIT_TOOL, code,
                    notFound ? "One or more child-agent ids do not belong to this run"
                            : "One or more unfinished child agents cannot be recovered on this process",
                    Map.of("results", states));
        }
        return success(WAIT_TOOL, Map.of("results", states));
    }

    private boolean isReturnedTerminal(Map<String, Object> state) {
        String status = String.valueOf(state.get("status"));
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELED", "TIMEOUT", "NOT_FOUND", "RECOVERY_UNSUPPORTED" -> true;
            default -> false;
        };
    }

    private List<CompletableFuture<TerminalState>> liveTerminalFutures(String runId, List<String> ids) {
        List<CompletableFuture<TerminalState>> futures = new ArrayList<>();
        for (String id : ids) {
            LiveHandle handle = liveHandles.get(handleKey(runId, id));
            if (handle != null && !handle.terminal.isDone()) {
                futures.add(handle.terminal);
            }
        }
        return futures;
    }

    private void cancelLiveForRun(String runId, String reason) {
        for (LiveHandle handle : List.copyOf(liveHandles.values())) {
            if (!runId.equals(handle.runId)) {
                continue;
            }
            completeTerminal(handle, Status.CANCELED, "", reason, 0);
            Future<?> future = handle.future;
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    private int activeSubAgentCount(String runId) {
        int active = 0;
        for (AgentRunEvent event : eventService.listByRunId(runId)) {
            if (!ACCEPTED_EVENT.equals(event.getEventType())) {
                continue;
            }
            String id = payloadText(event, "subAgentId");
            if (!id.isEmpty() && terminalEvent(runId, id).isEmpty()) {
                active++;
            }
        }
        return active;
    }

    private int resolveMaxToolCallsPerSubAgent() {
        AgentLlmProperties.Execution base = llmProperties.getRuntime() == null
                ? null : llmProperties.getRuntime().getExecution();
        AgentLlmProperties.Execution local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getExecution)
                .orElse(null);
        Integer configured = local != null && local.getMaxToolCallsPerSubAgent() != null
                ? local.getMaxToolCallsPerSubAgent()
                : (base == null ? null : base.getMaxToolCallsPerSubAgent());
        return configured == null || configured <= 0 ? 10 : Math.min(configured, 30);
    }

    private long resolveWaitMillis(Object raw) {
        long max = Math.max(1L, maxWaitMillis);
        long fallback = Math.max(1L, Math.min(defaultWaitMillis, max));
        if (raw == null) {
            return fallback;
        }
        try {
            long requested = raw instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(raw).trim());
            return Math.max(1L, Math.min(requested, max));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<String> parseIds(Map<String, Object> params) {
        Object raw = firstRaw(params, "subAgentIds", "sub_agent_ids", "subAgentId", "sub_agent_id", "arg0");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            collection.forEach(value -> addId(ids, value));
        } else if (raw != null) {
            String text = String.valueOf(raw).trim();
            if (text.startsWith("[") && text.endsWith("]")) {
                try {
                    objectMapper.readValue(text, List.class).forEach(value -> addId(ids, value));
                } catch (Exception ignored) {
                    addId(ids, text);
                }
            } else {
                for (String part : text.split(",")) {
                    addId(ids, part);
                }
            }
        }
        return List.copyOf(ids);
    }

    private void addId(Set<String> ids, Object raw) {
        String id = trim(raw);
        // 最多收集上限 + 1；调用方据此返回明确错误，不能静默丢掉多余 id。
        if (!id.isEmpty() && ids.size() <= Math.max(1, promptService.maxSubAgentCount())) {
            ids.add(id);
        }
    }

    private Optional<AgentRunEvent> acceptedEvent(String runId, String id) {
        return eventService.findByDedupeKey(runId, acceptedKey(id));
    }

    private Optional<AgentRunEvent> runningEvent(String runId, String id) {
        return eventService.findByDedupeKey(runId, runningKey(id));
    }

    private Optional<AgentRunEvent> terminalEvent(String runId, String id) {
        return eventService.findByDedupeKey(runId, terminalKey(id));
    }

    private TerminalState parseTerminal(AgentRunEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(event.getPayloadJson(), MAP_TYPE);
            Status status = Status.valueOf(String.valueOf(payload.getOrDefault("status", Status.FAILED.name())));
            return new TerminalState(
                    status,
                    trim(payload.get("result")),
                    trim(payload.get("error")),
                    toInt(payload.get("toolCallsUsed")));
        } catch (Exception malformed) {
            return new TerminalState(Status.FAILED, "", "SUB_AGENT_TERMINAL_EVENT_MALFORMED", 0);
        }
    }

    private Map<String, Object> stateMap(String id, TerminalState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subAgentId", id);
        out.put("status", state.status.name());
        out.put("result", state.result);
        out.put("error", state.error);
        out.put("toolCallsUsed", Math.max(0, state.toolCallsUsed));
        return out;
    }

    private String payloadText(AgentRunEvent event, String field) {
        try {
            JsonNode root = objectMapper.readTree(event.getPayloadJson());
            return root.path(field).asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String success(String tool, Map<String, ?> data) {
        return write(Map.of("ok", true, "tool", tool, "data", data, "error", Map.of()));
    }

    private String error(String tool, String code, String message, Map<String, ?> details) {
        return write(Map.of(
                "ok", false,
                "tool", tool,
                "data", Map.of(),
                "error", Map.of("code", code, "message", message, "details", details)));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":{\"code\":\"SUB_AGENT_SERIALIZATION_FAILED\"}}";
        }
    }

    private static String stableId(String runId, String toolCallId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((runId + "\n" + toolCallId).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("sa_");
            for (int i = 0; i < 12; i++) {
                out.append(String.format("%02x", digest[i]));
            }
            return out.toString();
        } catch (Exception impossible) {
            return "sa_" + UUID.nameUUIDFromBytes((runId + toolCallId).getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "").substring(0, 24);
        }
    }

    private static String acceptedKey(String id) {
        return "sub-agent:" + id + ":accepted";
    }

    private static String runningKey(String id) {
        return "sub-agent:" + id + ":running";
    }

    private static String terminalKey(String id) {
        return "sub-agent:" + id + ":terminal";
    }

    private static String handleKey(String runId, String id) {
        return runId + "\n" + id;
    }

    private static Object firstRaw(Map<String, Object> params, String... names) {
        if (params == null) {
            return null;
        }
        for (String name : names) {
            if (params.containsKey(name) && params.get(name) != null) {
                return params.get(name);
            }
        }
        return null;
    }

    private static String first(Map<String, Object> params, String... names) {
        return trim(firstRaw(params, names));
    }

    private static String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String bounded(String value, int maxChars) {
        String normalized = value == null ? "" : value;
        int max = Math.max(0, maxChars);
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(trim(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    enum Status { ACCEPTED, RUNNING, SUCCEEDED, FAILED, CANCELED, TIMEOUT }

    private record TerminalState(Status status, String result, String error, int toolCallsUsed) { }

    private static final class LiveHandle {
        private final String runId;
        private final String userId;
        private final String subAgentId;
        private final CompletableFuture<TerminalState> terminal = new CompletableFuture<>();
        private volatile Future<?> future;
        private volatile ScheduledFuture<?> timeoutFuture;
        private volatile ScheduledFuture<?> controlMonitorFuture;

        private LiveHandle(String runId, String userId, String subAgentId) {
            this.runId = runId;
            this.userId = userId;
            this.subAgentId = subAgentId;
        }
    }
}
