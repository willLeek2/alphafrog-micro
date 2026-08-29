package world.willfrog.agentlangchain.execution.dag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.exception.AgentRunFailureClass;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.workflow.TodoStatus;
import world.willfrog.agentlangchain.execution.LangchainCompletedTodo;
import world.willfrog.agentlangchain.execution.LangchainWorkflowRequest;
import world.willfrog.agentlangchain.execution.LangchainWorkflowResult;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.execution.LangchainBudgetPartialAnswerBuilder;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DAG（Directed Acyclic Graph，有向无环图）工作流执行器。
 *
 * <p>负责将 {@link LangchainTodoPlan} 中的 todo items（任务项）按依赖关系构建成 DAG 图，
 * 并通过线程池并发调度无依赖冲突的节点，实现并行执行。与线性执行器（{@link world.willfrog.agentlangchain.execution.LangchainLinearWorkflowExecutor}）
 * 的串行执行不同，DAG 执行器可以充分利用 todo 之间的并行性，显著缩短整体执行时间。</p>
 *
 * <p>核心执行流程：</p>
 * <ol>
 *   <li>将 todo items 构建为 {@link LangchainDagExecutionGraph}，并检测是否存在环（circular dependency）；</li>
 *   <li>为每个 todo 节点创建 {@link CompletableFuture} 依赖链，前置依赖完成后才调度执行；</li>
 *   <li>通过 {@link ExecutorService} 线程池并发执行就绪节点；</li>
 *   <li>支持失败传播：若某节点失败，其所有下游节点自动标记为 SKIPPED（跳过）；</li>
 *   <li>所有节点完成后，调用 {@link LangchainTodoNodeExecutor#writeFinalAnswer} 生成最终答案。</li>
 * </ol>
 *
 * <p>线程安全设计：</p>
 * <ul>
 *   <li>{@link LangchainDagSharedContext} 使用 {@link java.util.concurrent.CopyOnWriteArrayList}
 *       和 {@link ConcurrentHashMap} 保证 completed todos 和 dataset refs（数据集引用）的线程安全共享；</li>
 *   <li>{@link LangchainDagStateRecorder} 通过 synchronized 锁将节点状态持久化到 Redis，供前端实时查询进度；</li>
 *   <li>每个工作线程通过 {@link AgentContext.ContextSnapshot} 恢复父线程的运行上下文，确保 observability（可观测性）trace 不丢失。</li>
 * </ul>
 *
 * <p>这个类本身不直接与 LLM 对话，也不直接调用工具；它只负责 DAG 的拓扑调度、
 * 并发控制和失败传播。具体的单个 todo 执行逻辑（LLM 对话、tool-calling 工具调用循环）
 * 委托给 {@link LangchainTodoNodeExecutor}。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LangchainDagWorkflowExecutor {

    /**
     * DAG 执行阶段的 phase 标识，用于 AgentContext 和 observability trace 标记。
     * 每个节点会在此前缀后附加自己的 todoId，如 "dag_execution_todo_3"。
     */
    private static final String PHASE_DAG_EXECUTION = "dag_execution";

    private final LangchainTodoNodeExecutor todoNodeExecutor;
    private final LangchainDagStateRecorder stateRecorder;
    private final AgentRunEventService eventService;
    private final LangchainRunExecutionGuard executionGuard;
    private final AgentPromptService promptService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<world.willfrog.agent.platform.service.AgentRunStateStore> stateStoreProvider;

    /**
     * DAG 执行线程池大小，默认 4。线程数受限于两个因素：
     * 1) 配置上限（避免并发 LLM 调用过多导致 rate limit，速率限制）；
     * 2) 实际 todo 数量（避免创建过多空闲线程）。
     */
    @Value("${agent.langchain.dag.thread-pool-size:4}")
    private int dagThreadPoolSize;

    @Value("${agent.dag.recovery-judge.enabled:false}")
    private boolean recoveryJudgeEnabled;

    /**
     * DAG 工作流的主入口。由 {@link world.willfrog.agentlangchain.execution.LangchainLinearRunPipelineImpl}
     * 在判断应使用 DAG 模式后调用。
     *
     * <p>整体执行流程：</p>
     * <ol>
     *   <li>校验 request 和 plan 的合法性；</li>
     *   <li>将 todo items 构建为 DAG 图并检测环；</li>
     *   <li>发送 DAG_EXECUTION_STARTED 事件；</li>
     *   <li>调用 {@link #executeDagParallel} 并发执行所有节点（核心调度逻辑）；</li>
     *   <li>检查是否被用户取消（cancel/pause）；</li>
     *   <li>遍历所有节点结果：若存在失败或中断，返回对应状态；</li>
     *   <li>所有节点成功 → 设置 phase 为 summarizing，调用 writeFinalAnswer 生成最终答案。</li>
     * </ol>
     *
     * @param request 工作流请求，包含 runId、userId、用户目标、各阶段 ChatModel 等
     * @param plan    planner 生成的 todo 计划，包含 items 及其依赖关系
     * @return 工作流执行结果，成功时包含 finalAnswer（最终答案），失败时包含 failureReason
     */
    public LangchainWorkflowResult executePlanned(LangchainWorkflowRequest request,
                                                        LangchainTodoPlan plan) {
        validate(request, plan);
        // toolCalls 是原子计数器，整个 DAG 的所有节点与恢复判定器共享，各自累加自己的工具调用次数
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            applyRunContext(request);
            // 设置工作流形态为 DAG，供下游组件（ToolRouter / EventService / Observability）区分 linear vs dag
            AgentContext.setWorkflow("dag");
            List<TodoItem> items = plan.getItems() == null ? List.of() : plan.getItems();
            // 从 todo items 构建 DAG 图：解析每个 item 的 dependsOn（依赖列表），检测环
            LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(items);
            // 环意味着存在 A→B→A 这类互相依赖，DAG 无法调度，直接返回失败
            if (graph.hasCycle()) {
                return failure(plan, List.of(), "dag_circular_dependency", toolCalls.get());
            }

            String runId = request.getRunId();
            String userId = request.getUserId();
            if (!isBlank(runId) && !isBlank(userId)) {
                eventService.append(runId, userId, "DAG_EXECUTION_STARTED", Map.of(
                        "run_id", runId,
                        "node_count", items.size()
                ));
            }

            // sharedContext 是线程安全的共享上下文：存放已完成 todo 的结果 + 各节点产生的 dataset refs（数据集引用），
            // 后续节点通过 sharedContext 看到前面节点的产出，writeFinalAnswer 也从中取 completedTodos
            LangchainDagSharedContext sharedContext = new LangchainDagSharedContext();
            DagParallelRun parallelRun = executeDagParallel(graph, items, request, sharedContext, toolCalls);

            // 节点全部调度完成后，再一次检查 cancel/pause —— 用户可能在节点执行期间点了取消
            List<LangchainCompletedTodo> completedTodos = new ArrayList<>(sharedContext.completedTodosSnapshot());
            Optional<String> stopBeforeAnswer = executionGuard.stopReason(runId, userId);
            if (stopBeforeAnswer.isPresent()) {
                return interrupted(plan, completedTodos, stopBeforeAnswer.get(), toolCalls.get());
            }

            // 开关开启且存在失败节点时，用模型判定能否跳过部分失败节点、用已完成节点生成部分答案。
            // 额度已超限时绕过该判定：再问模型会立刻再次触发 RunBudgetException。
            // 直接走 handleBudgetExhaustion（有已完成节点则拼部分答案，否则立即失败）。
            if (hasFailedNode(parallelRun.results(), items)) {
                Map<String, Object> budgetMeta = findBudgetFailureMetadata(parallelRun.results());
                if (budgetMeta != null) {
                    return handleBudgetExhaustion(request, plan, completedTodos,
                            nvl(firstFailedReason(parallelRun.results())), budgetMeta, toolCalls.get());
                }
                if (recoveryJudgeEnabled) {
                    LangchainWorkflowResult judgeResult = tryRecoveryJudge(
                            request, plan, graph, items, parallelRun.results(), sharedContext,
                            completedTodos, toolCalls, runId, userId);
                    if (judgeResult != null) {
                        return judgeResult;
                    }
                    // 恢复判定器返回 null = 不同意跳过 → 不生成部分答案，继续走下方普通失败路径
                }
            }

            // 遍历所有节点结果。这里需要区分三种情况：
            //   1) RUN_INTERRUPTED → 用户主动 cancel/pause，走 interrupted 路径；
            //   2) 失败或 null → 普通执行失败，走 failure 路径；
            //   3) 全部成功 → 不走这里，落到下方 final answer 路径
            for (TodoItem item : items) {
                LangchainTodoNodeResult nodeResult = parallelRun.results().get(item.getId());
                // RUN_INTERRUPTED 由 executeNode 在检测到 cancel/pause 时写入，summary 格式为 "RUN_INTERRUPTED:<controlStatus>"
                if (nodeResult != null && nodeResult.getSummary() != null
                        && nodeResult.getSummary().startsWith("RUN_INTERRUPTED:")) {
                    String controlStatus = nodeResult.getSummary().substring("RUN_INTERRUPTED:".length());
                    return interrupted(plan, completedTodos, controlStatus, toolCalls.get());
                }
                // 失败：nodeResult.isSuccess() == false；未执行：nodeResult 为 null（调度异常）
                if (nodeResult == null || !nodeResult.isSuccess()) {
                    String reason = nodeResult == null ? "No result" : nvl(nodeResult.getSummary());
                    if (!isBlank(runId) && !isBlank(userId)) {
                        appendDagCompleted(runId, userId, false, reason, toolCalls.get());
                    }
                    return failure(plan, completedTodos, reason, toolCalls.get());
                }
            }

            // 所有节点成功 → 进入 summarizing 阶段，让 writeFinalAnswer 把各节点的输出串成自然语言最终答案
            AgentContext.setPhase("summarizing");
            AgentContext.setStage("final_answer");
            String finalAnswer = todoNodeExecutor.writeFinalAnswer(request, completedTodos);
            // writeFinalAnswer 底层调用 LLM，极端情况下可能返回空字符串
            if (isBlank(finalAnswer)) {
                return failure(plan, completedTodos, "empty_final_answer", toolCalls.get());
            }
            if (!isBlank(runId) && !isBlank(userId)) {
                appendDagCompleted(runId, userId, true, null, toolCalls.get());
            }
            return LangchainWorkflowResult.builder()
                    .success(true)
                    .finalAnswer(finalAnswer.trim())
                    .plan(plan)
                    .completedTodos(completedTodos)
                    .toolCallsUsed(toolCalls.get())
                    .build();
        } catch (Exception e) {
            log.error("LangChain DAG workflow failed", e);
            return LangchainWorkflowResult.builder()
                    .success(false)
                    .failureReason(e.getMessage())
                    .plan(plan)
                    .toolCallsUsed(toolCalls.get())
                    .build();
        } finally {
            // 清理 ThreadLocal（线程本地变量），避免线程池复用下一个 run 时上下文串扰
            AgentContext.clear();
        }
    }

    /**
     * 并发执行 DAG 所有节点的核心调度方法。
     *
     * <p>调度机制：</p>
     * <ol>
     *   <li>为每个 todo item 创建一个 {@link CompletableFuture}，通过 {@link #scheduleNode} 递归构建依赖链；</li>
     *   <li>每个节点的 CompletableFuture 会先等待其所有前置依赖（dependencies）完成后才触发执行；</li>
     *   <li>就绪的节点被提交到 {@link ExecutorService} 线程池异步执行；</li>
     *   <li>所有节点的 CompletableFuture 通过 {@code CompletableFuture.allOf(...).get(30, TimeUnit.MINUTES)}
     *       等待完成，总超时 30 分钟。</li>
     * </ol>
     *
     * <p>线程上下文传递：父线程的 {@link AgentContext.ContextSnapshot} 被捕获，
     * 每个工作线程在执行节点前恢复该快照，确保 runId/userId/phase 等上下文在 observability trace 中正确关联。</p>
     *
     * @param graph         DAG 执行图，包含节点映射和依赖关系
     * @param items         所有 todo items
     * @param request       工作流请求
     * @param sharedContext 线程安全的共享上下文，用于传递 completed todos 和 dataset refs
     * @param toolCalls     原子计数器，统计整个 DAG 执行过程中的总 tool call 次数
     * @return 包含所有节点执行结果的 DagParallelRun
     * @throws Exception 当总超时或其他调度异常时抛出
     */
    private DagParallelRun executeDagParallel(LangchainDagExecutionGraph graph,
                                              List<TodoItem> items,
                                              LangchainWorkflowRequest request,
                                              LangchainDagSharedContext sharedContext,
                                              AtomicInteger toolCalls) throws Exception {
        return executeDagParallel(graph, items, request, sharedContext, toolCalls,
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    /**
     * 带预填充结果的 DAG 并行执行（用于恢复判定器同意跳过失败节点后的重调度）。
     *
     * <p>当恢复判定器判定某些失败节点可以跳过时，调用方在 {@code nodeSuccess} 中
     * 将这些节点标记为 true，使得被它们阻塞的下游节点在新一轮调度中被释放。</p>
     *
     * @param preResults    已有的节点执行结果（已完成的节点不会被重新调度）
     * @param preNodeSuccess 已有的节点成功状态（recovered 节点标为 true 以释放依赖）
     */
    private DagParallelRun executeDagParallel(LangchainDagExecutionGraph graph,
                                              List<TodoItem> items,
                                              LangchainWorkflowRequest request,
                                              LangchainDagSharedContext sharedContext,
                                              AtomicInteger toolCalls,
                                              Map<String, LangchainTodoNodeResult> preResults,
                                              Map<String, Boolean> preNodeSuccess) throws Exception {
        String runId = request.getRunId();
        String userId = request.getUserId();
        // ConcurrentHashMap 保证多线程并发读写安全：不同节点的工作线程会同时写入各自的结果和 nodeSuccess
        Map<String, LangchainTodoNodeResult> results = new ConcurrentHashMap<>(preResults);
        Map<String, Boolean> nodeSuccess = new ConcurrentHashMap<>(preNodeSuccess);
        // workflowStateLock 用于保护 stateRecorder 对 nodeStates + Redis 的原子写入
        Object workflowStateLock = new Object();
        // LinkedHashMap 保持节点状态按插入顺序排列，前端轮询时能按调度顺序展示每个节点的当前状态
        Map<String, TodoItem> nodeStates = new LinkedHashMap<>();
        // 捕获父线程的 AgentContext 快照，工作线程在执行节点前恢复此快照，确保 observability trace 关联到正确的 runId/userId
        AgentContext.ContextSnapshot parentContext = AgentContext.captureRunContext();

        // 线程池大小 = min(配置上限, 实际节点数)，至少为 1。
        // 当节点数少于配置上限时不需要创建多余线程（避免空闲线程浪费），但不能超过配置上限（避免并发 LLM 调用打满 rate limit）
        int poolSize = Math.max(1, Math.min(dagThreadPoolSize, items.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        AtomicInteger completedCount = new AtomicInteger();
        try {
            Map<String, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();
            for (TodoItem item : items) {
                scheduleNode(graph, items, item, request, sharedContext, toolCalls, results, nodeSuccess,
                        workflowStateLock, nodeStates, parentContext, executor, completedCount, futures);
            }
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);
            // 30 分钟是 DAG 总执行超时。50 个节点、每个节点最坏情况下几次 LLM 调用 + 工具调用，
            // 30 分钟通常足够；如果超时说明 DAG 中有节点真正卡死，抛出异常由上层 catch 处理
            return new DagParallelRun(results);
        } catch (TimeoutException e) {
            throw new RuntimeException("DAG execution failed: timeout", e);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 递归调度单个 DAG 节点的执行。
     *
     * <p>关键设计：使用 {@link Map#computeIfAbsent} 保证每个节点只被调度一次，
     * 即使多个下游节点都依赖它。这是 DAG 调度中的经典去重模式。</p>
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>先递归调度该节点的所有前置依赖（dependencies）；</li>
     *   <li>通过 {@code CompletableFuture.allOf(dependencyFutures)} 等待所有依赖完成；</li>
     *   <li>依赖全部完成后，通过 {@code thenRunAsync} 将该节点的实际执行逻辑提交到线程池。</li>
     * </ol>
     *
     * <p>这种方式天然支持并行：没有依赖关系的节点会同时被提交到线程池执行；
     * 有依赖关系的节点会在前置节点完成后自动触发。</p>
     *
     * @return 该节点的 CompletableFuture，下游节点可依赖此 Future
     */
    private CompletableFuture<Void> scheduleNode(LangchainDagExecutionGraph graph,
                                                 List<TodoItem> items,
                                                 TodoItem item,
                                                 LangchainWorkflowRequest request,
                                                 LangchainDagSharedContext sharedContext,
                                                 AtomicInteger toolCalls,
                                                 Map<String, LangchainTodoNodeResult> results,
                                                 Map<String, Boolean> nodeSuccess,
                                                 Object workflowStateLock,
                                                 Map<String, TodoItem> nodeStates,
                                                 AgentContext.ContextSnapshot parentContext,
                                                 ExecutorService executor,
                                                 AtomicInteger completedCount,
                                                 Map<String, CompletableFuture<Void>> futures) {
        return futures.computeIfAbsent(item.getId(), ignored -> {
            // 获取该节点的所有前置依赖的 CompletableFuture
            CompletableFuture<?>[] dependencyFutures = graph.getDependencies(item.getId())
                    .stream()
                    .map(depId -> graph.getItemMap().get(depId))
                    .filter(dep -> dep != null)
                    .map(dep -> scheduleNode(graph, items, dep, request, sharedContext, toolCalls, results,
                            nodeSuccess, workflowStateLock, nodeStates, parentContext, executor, completedCount,
                            futures))
                    .toArray(CompletableFuture[]::new);
            // 所有依赖完成后，异步执行当前节点
            return CompletableFuture.allOf(dependencyFutures)
                    .thenRunAsync(() -> executeNode(graph, items, item, request, sharedContext, toolCalls,
                            results, nodeSuccess, workflowStateLock, nodeStates, parentContext, completedCount),
                            executor);
        });
    }

    /**
     * 实际执行单个 DAG 节点的逻辑。在工作线程中被调用。
     *
     * <p>执行流程（按顺序）：</p>
     * <ol>
     *   <li><b>取消检查</b>：若用户已发送 cancel/pause，节点直接标记为 RUN_INTERRUPTED；</li>
     *   <li><b>上下文恢复</b>：恢复父线程的 AgentContext，确保 observability trace 关联正确；</li>
     *   <li><b>依赖失败检查</b>：若任一前置依赖未成功（nodeSuccess 不为 true），
     *       当前节点标记为 SKIPPED（跳过），并发送 DAG_NODE_SKIPPED 事件；</li>
     *   <li><b>状态持久化</b>：将节点状态标记为 RUNNING，写入 Redis；</li>
     *   <li><b>执行 todo</b>：调用 {@link LangchainTodoNodeExecutor#execute} 进行实际的 LLM 对话和 tool-calling；</li>
     *   <li><b>结果处理</b>：成功 → merge 结果到 sharedContext，标记 COMPLETED；
     *       失败 → 标记 FAILED，发送 DAG_NODE_FAILED 事件。</li>
     * </ol>
     *
     * <p>工具调用计数器 {@code toolCalls} 是原子变量，整个 DAG 的所有节点共享同一个计数器，
     * 累加各自执行过程中的 tool call 次数。</p>
     *
     * <p>Dataset refs（数据集引用）通过 {@code localRefs} 快照机制传递：
     * 每个节点执行前复制当前共享的 dataset refs，执行后将新的 refs merge 回 sharedContext。
     * 这样后续节点可以看到前面所有节点产生的数据集引用。</p>
     */
    private void executeNode(LangchainDagExecutionGraph graph,
                             List<TodoItem> items,
                             TodoItem item,
                             LangchainWorkflowRequest request,
                             LangchainDagSharedContext sharedContext,
                             AtomicInteger toolCalls,
                             Map<String, LangchainTodoNodeResult> results,
                             Map<String, Boolean> nodeSuccess,
                             Object workflowStateLock,
                             Map<String, TodoItem> nodeStates,
                             AgentContext.ContextSnapshot parentContext,
                             AtomicInteger completedCount) {
        String runId = request.getRunId();
        String userId = request.getUserId();
        long nodeStartMs = 0;
        try {
            // 0. 恢复判定器重调度：已有结果的节点跳过，避免重复执行
            LangchainTodoNodeResult existing = results.get(item.getId());
            if (existing != null) {
                nodeSuccess.putIfAbsent(item.getId(), existing.isSuccess());
                return;
            }
            // 1. 检查是否被用户取消（cancel/pause）
            Optional<String> stop = executionGuard.stopReason(runId, userId);
            if (stop.isPresent()) {
                LangchainTodoNodeResult interrupted = LangchainTodoNodeResult.builder()
                        .success(false)
                        .summary("RUN_INTERRUPTED:" + stop.get())
                        .build();
                results.put(item.getId(), interrupted);
                nodeSuccess.put(item.getId(), false);
                stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                        item, TodoStatus.FAILED, interrupted, toolCalls.get());
                emitEventBestEffort(runId, userId, "TODO_NODE_FAILED", todoNodeResultPayload(
                        item, false, interrupted.getSummary(), 0, 0, "RUN_CANCELED",
                        null, false, null));
                return;
            }
            // 2. 恢复父线程的 AgentContext，确保 observability trace 关联正确
            AgentContext.restoreRunContext(parentContext);
            // 3. 检查前置依赖是否失败，实现失败传播
            String failedDependency = findFailedDependency(graph.getDependencies(item.getId()), nodeSuccess);
            if (failedDependency != null) {
                LangchainTodoNodeResult skipped = LangchainTodoNodeResult.skipped(failedDependency);
                results.put(item.getId(), skipped);
                nodeSuccess.put(item.getId(), false);
                stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                        item, TodoStatus.SKIPPED, skipped, toolCalls.get());
                emitEventBestEffort(runId, userId, "DAG_NODE_SKIPPED", Map.of(
                        "todo_id", item.getId(),
                        "failed_dependency", failedDependency
                ));
                Map<String, Object> skippedPayload = new LinkedHashMap<>();
                skippedPayload.put("todo_id", item.getId());
                skippedPayload.put("todo_sequence", item.getSequence());
                skippedPayload.put("workflow", nvl(AgentContext.getWorkflow(), "dag"));
                skippedPayload.put("phase", "execution");
                skippedPayload.put("reason", "dependency_failed");
                skippedPayload.put("failed_dependency", failedDependency);
                emitEventBestEffort(runId, userId, "TODO_NODE_SKIPPED", skippedPayload);
                return;
            }

            // 4. 设置当前节点的上下文和 phase
            AgentContext.setTodoContext(item.getId(), item.getSequence());
            AgentContext.setPhase(PHASE_DAG_EXECUTION + "_" + item.getId());
            // 5. 持久化 RUNNING 状态到 Redis，并发射统一 TODO_NODE_STARTED
            stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                    item, TodoStatus.RUNNING, null, toolCalls.get());
            emitEventBestEffort(runId, userId, "TODO_NODE_STARTED", todoNodeStartedPayload(item));
            // 6. 数据集引用传递：每个节点执行前复制一份当前的 dataset refs 快照作为 localRefs，
            //    执行完成后将 localRefs merge 回 sharedContext。这样节点 A 产生的 dataset refs
            //    能被后续节点 B 看到（例如 A 查了行情数据写入 dataset，B 可以直接复用而非重新查询）
            Map<String, String> localRefs = new ConcurrentHashMap<>(sharedContext.datasetRefsSnapshot());
            // 记录节点开始时间，用于计算执行耗时（duration_ms），写入统一的 TODO_NODE_COMPLETED/FAILED 事件
            nodeStartMs = System.currentTimeMillis();
            LangchainTodoNodeResult record = todoNodeExecutor.execute(
                    request,
                    item,
                    sharedContext.completedTodosSnapshot(),
                    localRefs,
                    toolCalls);
            // merge 回 sharedContext：后续节点可以看到本节点产生的新的 dataset refs
            sharedContext.mergeDatasetRefs(localRefs);
            results.put(item.getId(), record);
            nodeSuccess.put(item.getId(), record.isSuccess());
            if (record.isSuccess()) {
                // 成功：将结果加入共享上下文，后续依赖节点可以看到
                sharedContext.addCompletedTodo(LangchainCompletedTodo.builder()
                        .todoId(item.getId())
                        .sequence(item.getSequence())
                        .description(item.getDescription())
                        .output(record.getOutput())
                        .summary(record.getSummary())
                        .build());
                long durationMs = System.currentTimeMillis() - nodeStartMs;
                stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                        item, TodoStatus.COMPLETED, record, toolCalls.get());
                emitEventBestEffort(runId, userId, "DAG_NODE_COMPLETED", Map.of(
                        "todo_id", item.getId(),
                        "tool_calls_used", record.getToolCallsUsed()
                ));
                emitEventBestEffort(runId, userId, "TODO_NODE_COMPLETED", todoNodeResultPayload(
                        item, true, record.getSummary(), durationMs, record.getToolCallsUsed(),
                        null, todoRetryEventMetadata(record),
                        record.isRecovered(), record.getRecoveryOutcome()));
            } else {
                long durationMs = System.currentTimeMillis() - nodeStartMs;
                // 失败：状态记录和事件通知，下游节点会因 nodeSuccess=false 而被跳过
                stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                        item, TodoStatus.FAILED, record, toolCalls.get());
                emitEventBestEffort(runId, userId, "DAG_NODE_FAILED", Map.of(
                        "todo_id", item.getId(),
                        "summary", nvl(record.getSummary())
                ));
                emitEventBestEffort(runId, userId, "TODO_NODE_FAILED", todoNodeResultPayload(
                        item, false, record.getSummary(), durationMs, 0,
                        null, record.getFailureMetadata(), false, null));
            }
        } catch (Throwable t) {
            // 异步线程边界：必须拦住 Error，否则工作线程退出后 CompletableFuture 完不成，整个 DAG 挂死。
            // 编程错误仍要让异步结果结束，但标成未知缺陷，不和普通节点失败混在一起。
            boolean unknownDefect = AgentRunFailureClass.containsError(t);
            if (unknownDefect) {
                log.error("DAG node {} hit unknown defect; async result still completed", item.getId(), t);
            } else {
                log.error("Failed to execute DAG node {}", item.getId(), t);
            }
            long catchDurationMs = nodeStartMs > 0 ? System.currentTimeMillis() - nodeStartMs : 0;
            Map<String, Object> catchMetadata = unknownDefect ? unknownDefectMetadata(t) : null;
            LangchainTodoNodeResult failed = LangchainTodoNodeResult.failure(
                    "DAG execution failed: " + nvl(t.getMessage()), catchMetadata);
            results.put(item.getId(), failed);
            nodeSuccess.put(item.getId(), false);
            stateRecorder.persistNodeState(runId, items, workflowStateLock, nodeStates,
                    item, TodoStatus.FAILED, failed, toolCalls.get());
            emitEventBestEffort(runId, userId, "TODO_NODE_FAILED", todoNodeResultPayload(
                    item, false, failed.getSummary(), catchDurationMs, 0,
                    null, catchMetadata, false, null));
        } finally {
            // 增加完成计数（用于监控和调试），并清理 ThreadLocal
            completedCount.incrementAndGet();
            AgentContext.clear();
        }
    }

    /**
     * 检查给定节点的所有前置依赖是否都已成功完成。
     *
     * <p>失败传播的核心方法：如果任一依赖的 nodeSuccess 不为 true，
     * 返回该失败依赖的 ID，当前节点将被标记为 SKIPPED。</p>
     *
     * @param dependencies 当前节点的依赖 ID 集合
     * @param nodeSuccess  全局节点成功状态映射
     * @return 第一个失败依赖的 ID，若全部成功则返回 null
     */
    private String findFailedDependency(Set<String> dependencies, Map<String, Boolean> nodeSuccess) {
        for (String depId : dependencies) {
            if (!Boolean.TRUE.equals(nodeSuccess.get(depId))) {
                return depId;
            }
        }
        return null;
    }

    // ========== DAG 恢复判定器 ==========

    private boolean hasFailedNode(Map<String, LangchainTodoNodeResult> results, List<TodoItem> items) {
        for (TodoItem item : items) {
            LangchainTodoNodeResult nodeResult = results.get(item.getId());
            if (nodeResult == null || !nodeResult.isSuccess()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试 DAG 恢复判定：跳过部分失败节点、用已完成节点生成部分答案。
     *
     * @return PARTIAL 结果（同意跳过）、null（不同意，走原有失败路径）、
     *         或原始 failure 结果（判定调用本身失败）
     */
    private LangchainWorkflowResult tryRecoveryJudge(
            LangchainWorkflowRequest request,
            LangchainTodoPlan plan,
            LangchainDagExecutionGraph graph,
            List<TodoItem> items,
            Map<String, LangchainTodoNodeResult> results,
            LangchainDagSharedContext sharedContext,
            List<LangchainCompletedTodo> completedTodos,
            AtomicInteger toolCalls,
            String runId,
            String userId) {

        if (!isBlank(runId) && !isBlank(userId)) {
            eventService.append(runId, userId, "DAG_RECOVERY_JUDGE_STARTED", Map.of(
                    "run_id", runId
            ));
        }

        // 保存恢复判定前的 phase/stage，无论判定成功还是异常，finally 里都要恢复
        String previousPhase = AgentContext.getPhase();
        String previousStage = AgentContext.getStage();
        try {
            // 1. 构建恢复判定器的 system prompt + user message（user message 里包含所有 todo 的状态摘要和已完成节点的产出）
            String systemPrompt = promptService.reactSystemPrompt();
            String userMessage = promptService.dagRecoveryJudgeStageInstruction()
                    + "\n\n"
                    + buildRecoveryJudgeUserMessage(items, graph, results, sharedContext, request);

            // 2. 设置 judge 专用的 phase/stage，让 ChatModel 产生的 trace 归属到 dag_recovery_judge，方便在 observability 中过滤
            AgentContext.setPhase("dag_recovery_judge");
            AgentContext.setStage("judge");

            // 3. 调用 execution 阶段模型做判定（复用请求中的 execution model）
            ChatModel judgeModel = request.executionModelOrDefault();
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(systemPrompt),
                            UserMessage.from(userMessage)
                    ))
                    .build();

            ChatResponse response = judgeModel.doChat(chatRequest);
            AiMessage aiMessage = response.aiMessage();
            String responseText = aiMessage.text();

            // 4. 解析 LLM 返回的结构化 JSON。期望字段：
            //    decision: "YES" | "NO"
            //    skipTodoIds: ["todo_3", "todo_5"] — judge 认为可以跳过的失败节点
            //    rationale: 判定理由（最长保留 500 字）
            Map<String, Object> judgeResult = objectMapper.readValue(
                    responseText, new TypeReference<Map<String, Object>>() {});
            String decision = String.valueOf(judgeResult.getOrDefault("decision", "NO"));
            @SuppressWarnings("unchecked")
            List<String> skipTodoIds = judgeResult.get("skipTodoIds") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            String rationale = String.valueOf(judgeResult.getOrDefault("rationale", ""));
            String truncatedRationale = rationale.length() > 500 ? rationale.substring(0, 500) : rationale;
            // recoveryJudgeDecisionId 是本次 judge 决策的唯一标识（由服务端生成，不等同于 LLM traceId），
            // 前端和 observability 通过它把 judge 决策和被跳过的 TODO_NODE_SKIPPED 事件串起来
            String recoveryJudgeDecisionId = java.util.UUID.randomUUID().toString().replace("-", "");

            if (!"YES".equalsIgnoreCase(decision)) {
                if (!isBlank(runId) && !isBlank(userId)) {
                    eventService.append(runId, userId, "DAG_RECOVERY_JUDGE_FINISHED", Map.of(
                            "decision", "NO",
                            "rationale", truncatedRationale,
                            "recovery_judge_decision_id", recoveryJudgeDecisionId
                    ));
                }
                return null;
            }

            // YES 判定 → 对 judge 返回的 skipTodoIds 做一次校验：
            //   validSkipIds：plan 中确实存在的节点 → 标记为 recovered，释放下游依赖
            //   invalidSkipIds：judge 幻觉出来的不存在的 ID → 记录到事件但不影响调度
            List<String> validSkipIds = skipTodoIds.stream()
                    .filter(id -> graph.getItemMap().containsKey(id))
                    .toList();
            List<String> invalidSkipIds = skipTodoIds.stream()
                    .filter(id -> !graph.getItemMap().containsKey(id))
                    .toList();

            // 发射 DAG_RECOVERY_JUDGE_FINISHED + 被跳节点对应的 TODO_NODE_SKIPPED 事件，供前端渲染
            if (!isBlank(runId) && !isBlank(userId)) {
                Map<String, Object> judgeFinishedPayload = new LinkedHashMap<>();
                judgeFinishedPayload.put("decision", "YES");
                judgeFinishedPayload.put("skipTodoIds", validSkipIds);
                judgeFinishedPayload.put("rationale", truncatedRationale);
                judgeFinishedPayload.put("recovery_judge_decision_id", recoveryJudgeDecisionId);
                if (!invalidSkipIds.isEmpty()) {
                    judgeFinishedPayload.put("invalid_skip_todo_ids", invalidSkipIds);
                }
                eventService.append(runId, userId, "DAG_RECOVERY_JUDGE_FINISHED", judgeFinishedPayload);

                for (String skipId : validSkipIds) {
                    TodoItem skipItem = graph.getItemMap().get(skipId);
                    Map<String, Object> skippedPayload = new LinkedHashMap<>();
                    skippedPayload.put("todo_id", skipId);
                    skippedPayload.put("todo_sequence", skipItem.getSequence());
                    skippedPayload.put("reason", "judge_recovery");
                    skippedPayload.put("recovery_judge_decision_id", recoveryJudgeDecisionId);
                    skippedPayload.put("workflow", "dag");
                    skippedPayload.put("phase", "dag_recovery_judge");
                    eventService.append(runId, userId, "TODO_NODE_SKIPPED", skippedPayload);
                }
                // 将 judge recovery 跳过的有效节点写入 WorkflowState
                patchWorkflowStateForRecovery(runId, validSkipIds);
            }

            // 准备第二遍 DAG 执行。关键：第二遍只重新调度那些"因为依赖了被跳过节点而被 SKIPPED"的下游节点。
            // 三类节点做不同处理：
            //   1) judge 决定跳过的节点 → 放入 preResults，标记 success=true，释放依赖链；
            //   2) 第一遍就成功的节点 → 保留结果，不重新执行；
            //   3) 第一遍因依赖好节点而被 SKIPPED 的下游节点 → 不放入 preResults，让第二遍重新调度。
            Map<String, LangchainTodoNodeResult> preResults = new LinkedHashMap<>();
            Map<String, Boolean> preNodeSuccess = new ConcurrentHashMap<>();
            Set<String> skipSet = Set.copyOf(validSkipIds);
            for (TodoItem item : items) {
                LangchainTodoNodeResult r = results.get(item.getId());
                if (r == null) continue;
                if (skipSet.contains(item.getId())) {
                    // judge 决定跳过的失败节点 → 放入 recovered 占位结果，标记 success 以释放下游
                    preResults.put(item.getId(), LangchainTodoNodeResult.success(
                            "[recovered by judge] " + truncatedRationale, 0));
                    preNodeSuccess.put(item.getId(), true);
                } else if (r.isSuccess()) {
                    // 第一遍就成功的节点 → 保留原结果，不重跑
                    preResults.put(item.getId(), r);
                    preNodeSuccess.put(item.getId(), true);
                } else {
                    // 不在 skipSet 中的失败节点 → 保留失败状态，executeNode 的 guard 会跳过
                    preNodeSuccess.put(item.getId(), false);
                    // SKIPPED（因依赖失败而被跳过的）节点：不放入 preResults，让第二遍重新调度
                    if (!r.isSuccess() && r.getSummary() != null
                            && r.getSummary().startsWith("Skipped:")) {
                        // 不放入 preResults → 第二遍中 scheduleNode 会为该节点创建新 Future
                    } else {
                        preResults.put(item.getId(), r);
                    }
                }
            }

            // 第二遍 DAG 执行：已有结果的节点（preResults 中的）会被 executeNode guard 跳过，
            // 仅重新调度之前因依赖失败节点而被 SKIPPED 的下游节点
            AgentContext.setPhase(PHASE_DAG_EXECUTION);
            DagParallelRun secondRun = executeDagParallel(graph, items, request, sharedContext,
                    toolCalls, preResults, preNodeSuccess);

            // 合并两遍结果：第二遍的新结果覆盖第一遍（被重新调度的节点用新结果），
            // 第一遍的成功结果和 recovered 占位结果保留
            Map<String, LangchainTodoNodeResult> mergedResults = new LinkedHashMap<>(results);
            secondRun.results().forEach((k, v) -> mergedResults.put(k, v));

            // 检查第二遍后是否仍有失败（skipSet 中的 recovered 节点不算失败）
            for (TodoItem item : items) {
                if (skipSet.contains(item.getId())) continue;
                LangchainTodoNodeResult nodeResult = mergedResults.get(item.getId());
                if (nodeResult == null || !nodeResult.isSuccess()) {
                    String reason = nodeResult == null ? "No result after recovery" : nvl(nodeResult.getSummary());
                    appendDagCompleted(runId, userId, false, reason, toolCalls.get());
                    return failure(plan, completedTodos, reason, toolCalls.get());
                }
            }

            // 重置 phase 为 summarizing，生成部分可交付答案
            AgentContext.setPhase("summarizing");
            AgentContext.setStage("final_answer");
            List<LangchainCompletedTodo> allCompleted = new ArrayList<>(sharedContext.completedTodosSnapshot());
            String finalAnswer = todoNodeExecutor.writeFinalAnswer(request, allCompleted);

            appendDagCompletedPartial(runId, userId, "PARTIAL by recovery judge", toolCalls.get());
            return LangchainWorkflowResult.builder()
                    .success(false)
                    .partial(true)
                    .failureReason("PARTIAL by recovery judge: " + truncatedRationale)
                    .finalAnswer(isBlank(finalAnswer) ? null : finalAnswer.trim())
                    .plan(plan)
                    .completedTodos(allCompleted)
                    .toolCallsUsed(toolCalls.get())
                    .skippedTodoIds(validSkipIds)
                    .recoveryJudgeDecisionId(recoveryJudgeDecisionId)
                    .recoveryRationale(truncatedRationale)
                    .build();

        } catch (Exception e) {
            // judge 调用本身异常（如 LLM 超时、JSON 解析失败）→ 打 warn 日志 + 事件，
            // 返回 null 让 executePlanned 继续走原有失败路径，不阻塞 DAG 的失败处理
            log.warn("DAG recovery judge failed, falling back to normal failure path: {}", e.getMessage());
            if (!isBlank(runId) && !isBlank(userId)) {
                eventService.append(runId, userId, "DAG_RECOVERY_JUDGE_FINISHED", Map.of(
                        "decision", "ERROR",
                        "error", nvl(e.getMessage()),
                        "error_type", e.getClass().getSimpleName()
                ));
            }
            return null;
        } finally {
            // 恢复 judge 前的 phase/stage，避免 judge 的上下文污染后续流程（如 summarizing 阶段）
            AgentContext.setPhase(previousPhase != null ? previousPhase : "");
            AgentContext.setStage(previousStage != null ? previousStage : "");
        }
    }

    /**
     * 构建恢复判定器的用户消息。
     * 把「所有 todo 的状态 + 已完成节点的产出摘要」拼成一个结构化文本，
     * 让 LLM 能根据依赖关系和已完成产出来判断哪些失败节点可以安全跳过。
     */
    private String buildRecoveryJudgeUserMessage(
            List<TodoItem> items,
            LangchainDagExecutionGraph graph,
            Map<String, LangchainTodoNodeResult> results,
            LangchainDagSharedContext sharedContext,
            LangchainWorkflowRequest request) {

        StringBuilder sb = new StringBuilder();
        sb.append("【用户目标】\n").append(request.getUserGoal()).append("\n\n");
        sb.append("【Todo 列表及依赖关系】\n");
        for (TodoItem item : items) {
            LangchainTodoNodeResult result = results.get(item.getId());
            String status;
            String detail = "";
            if (result == null) {
                status = "UNKNOWN";
                detail = "（未执行）";
            } else if (result.isSuccess()) {
                status = "COMPLETED";
            } else {
                status = "FAILED";
                detail = result.getSummary() != null ? "，错误: " + truncate(result.getSummary(), 200) : "";
            }
            Set<String> deps = graph.getDependencies(item.getId());
            String depStr = deps.isEmpty() ? "无" : String.join(", ", deps);
            sb.append("- ").append(item.getId()).append(": \"").append(truncate(item.getDescription(), 150))
                    .append("\" (依赖: ").append(depStr)
                    .append(") → 状态: ").append(status).append(detail).append("\n");
        }
        sb.append("\n【已完成节点的产出摘要】\n");
        boolean hasCompleted = false;
        for (LangchainCompletedTodo ct : sharedContext.completedTodosSnapshot()) {
            hasCompleted = true;
            String summary = ct.getSummary() != null ? truncate(ct.getSummary(), 200) : "（无摘要）";
            sb.append(ct.getTodoId()).append(": ").append(summary).append("\n");
        }
        if (!hasCompleted) {
            sb.append("（无）\n");
        }
        return sb.toString();
    }

    /**
     * 把被 judge 跳过的节点在 WorkflowState（Redis 中的工作流状态快照）里也标记为 SKIPPED。
     * 这样前端通过 HITL 轮询拿到的工作流状态和 judge 决策保持一致，不会出现"节点在 DAG 里已经被跳过，
     * 但前端状态还是 RUNNING"的展示偏差。
     */
    private void patchWorkflowStateForRecovery(String runId, List<String> skipTodoIds) {
        if (isBlank(runId) || skipTodoIds.isEmpty()) return;
        try {
            world.willfrog.agent.platform.service.AgentRunStateStore stateStore = stateStoreProvider.getIfAvailable();
            if (stateStore == null) return;
            var existing = stateStore.loadWorkflowState(runId);
            if (existing.isEmpty()) return;
            var ws = existing.get();
            if (ws.getCompletedItems() != null) {
                for (var item : ws.getCompletedItems()) {
                    if (skipTodoIds.contains(item.getId())) {
                        item.setStatus(TodoStatus.SKIPPED);
                        item.setResultSummary("[recovered by judge]");
                    }
                }
            }
            stateStore.saveWorkflowState(runId, ws);
        } catch (Exception e) {
            log.warn("Failed to patch WorkflowState for recovery judge: {}", e.getMessage());
        }
    }

    // ========== 原有方法 ==========

    /**
     * 发送 DAG 执行完成事件，无论成功或失败都会调用。
     *
     * @param success      是否所有节点都成功
     * @param failureReason 失败原因（成功时为 null）
     * @param toolCalls    总 tool call 次数
     */
    private void appendDagCompletedPartial(String runId, String userId, String failureReason, int toolCalls) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("partial", true);
        payload.put("failure_reason", nvl(failureReason));
        payload.put("total_tool_calls_used", toolCalls);
        eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", payload);
    }

    private void appendDagCompleted(String runId, String userId, boolean success, String failureReason, int toolCalls) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", success);
        payload.put("total_tool_calls_used", toolCalls);
        if (!success) {
            payload.put("failure_reason", nvl(failureReason));
        }
        eventService.append(runId, userId, "DAG_EXECUTION_COMPLETED", payload);
    }

    /**
     * 构建失败结果对象。
     */
    private LangchainWorkflowResult failure(LangchainTodoPlan plan,
                                                  List<LangchainCompletedTodo> completedTodos,
                                                  String reason,
                                                  int toolCallsUsed) {
        return LangchainWorkflowResult.builder()
                .success(false)
                .failureReason(reason)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .build();
    }

    /**
     * 带 failureMetadata 的失败重载，让额度等结构化失败原因能透传到 pipeline。
     * 原 4 参 failure() 保留，供 DAG 普通失败路径（非额度）继续走。
     */
    private LangchainWorkflowResult failure(LangchainTodoPlan plan,
                                                  List<LangchainCompletedTodo> completedTodos,
                                                  String reason,
                                                  int toolCallsUsed,
                                                  Map<String, Object> failureMetadata) {
        return LangchainWorkflowResult.builder()
                .success(false)
                .failureReason(reason)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .failureMetadata(failureMetadata)
                .build();
    }

    /**
     * 构建被中断结果对象（用户 cancel/pause）。
     */
    private LangchainWorkflowResult interrupted(LangchainTodoPlan plan,
                                                      List<LangchainCompletedTodo> completedTodos,
                                                      String controlStatus,
                                                      int toolCallsUsed) {
        return LangchainWorkflowResult.builder()
                .success(false)
                .interrupted(true)
                .failureReason("RUN_INTERRUPTED:" + controlStatus)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .build();
    }

    // ========== 额度用尽时用已完成节点生成部分答案（DAG 端） ==========

    /**
     * 扫描所有节点结果，返回第一个带 {@code budget_exceeded=true} 的 failureMetadata；无则返 null。
     * 与 Linear 端的 {@code handleBudgetExhaustion} 入口共用；Linear 端在 single-node 失败时直接拿到，
     * DAG 端需要遍历是因为多节点并行时可能有多个失败。
     */
    private Map<String, Object> findBudgetFailureMetadata(Map<String, LangchainTodoNodeResult> results) {
        if (results == null) return null;
        for (LangchainTodoNodeResult r : results.values()) {
            if (r == null) continue;
            Map<String, Object> meta = r.getFailureMetadata();
            if (meta != null && Boolean.TRUE.equals(meta.get("budget_exceeded"))) {
                return meta;
            }
        }
        return null;
    }

    /** 取首个失败节点的 summary/failureReason 作为 budget exhausted 的对外 reason。 */
    private String firstFailedReason(Map<String, LangchainTodoNodeResult> results) {
        if (results == null) return "RUN_BUDGET_EXCEEDED";
        for (LangchainTodoNodeResult r : results.values()) {
            if (r != null && !r.isSuccess()) {
                String s = r.getFailureReason();
                if (s == null) s = r.getSummary();
                if (s != null && !s.isBlank()) return s;
            }
        }
        return "RUN_BUDGET_EXCEEDED";
    }

    /**
     * DAG 端额度用尽降级，逻辑与 Linear 一致（共用 {@link LangchainBudgetPartialAnswerBuilder}）。
     * 区别：还会发 {@code DAG_EXECUTION_COMPLETED} 事件（部分完成 / 立即失败）保持与普通 DAG 完成事件兼容。
     */
    private LangchainWorkflowResult handleBudgetExhaustion(LangchainWorkflowRequest request,
                                                                  LangchainTodoPlan plan,
                                                                  List<LangchainCompletedTodo> completedTodos,
                                                                  String reason,
                                                                  Map<String, Object> budgetMetadata,
                                                                  int toolCallsUsed) {
        String runId = request.getRunId();
        String userId = request.getUserId();
        String dimension = nvl(String.valueOf(budgetMetadata.get("dimension")), "unknown");
        long actual = toLong(budgetMetadata.get("actual"));
        long limit = toLong(budgetMetadata.get("limit"));
        double ratio = toDouble(budgetMetadata.get("ratio"));

        if (!completedTodos.isEmpty()) {
            LangchainBudgetPartialAnswerBuilder.PartialAnswer partial =
                    LangchainBudgetPartialAnswerBuilder.build(completedTodos);
            String partialReason = "RUN_BUDGET_EXCEEDED:" + dimension + ":" + actual + "/" + limit
                    + " — partial answer built from " + partial.includedTodoCount() + " completed todo(s)";
            LangchainWorkflowResult result = LangchainWorkflowResult.builder()
                    .success(false)
                    .partial(true)
                    .failureReason(partialReason)
                    .finalAnswer(partial.finalAnswer())
                    .plan(plan)
                    .completedTodos(completedTodos)
                    .toolCallsUsed(toolCallsUsed)
                    .failureMetadata(budgetMetadata)
                    .build();
            if (!isBlank(runId) && !isBlank(userId)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.putAll(LangchainBudgetPartialAnswerBuilder.completedTodoIdsPayload(completedTodos));
                payload.put("dimension", dimension);
                payload.put("actual", actual);
                payload.put("limit", limit);
                payload.put("ratio", ratio);
                payload.put("final_answer", partial.finalAnswer());
                payload.put("final_answer_length", partial.finalAnswerLength());
                payload.put("final_answer_included_todo_count", partial.includedTodoCount());
                payload.put("final_answer_skipped_todo_count", partial.skippedTodoCount());
                payload.put("final_answer_original_total_length", partial.originalTotalLength());
                payload.put("tool_calls_used", toolCallsUsed);
                payload.put("failure_reason", partialReason);
                try {
                    eventService.append(runId, userId, "WORKFLOW_PARTIAL_BUDGET", payload);
                } catch (Exception e) {
                    // 事件失败不影响降级结果
                }
                appendDagCompletedPartial(runId, userId, partialReason, toolCallsUsed);
            }
            return result;
        }

        String failedReason = "RUN_BUDGET_EXCEEDED:" + dimension + ":" + actual + "/" + limit
                + " — no completed todo, fail-fast";
        LangchainWorkflowResult result = LangchainWorkflowResult.builder()
                .success(false)
                .failureReason(failedReason)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .failureMetadata(budgetMetadata)
                .build();
        if (!isBlank(runId) && !isBlank(userId)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dimension", dimension);
            payload.put("actual", actual);
            payload.put("limit", limit);
            payload.put("ratio", ratio);
            payload.put("completed_todo_count", 0);
            payload.put("tool_calls_used", toolCallsUsed);
            payload.put("failure_reason", failedReason);
            try {
                eventService.append(runId, userId, "WORKFLOW_FAILED_BUDGET", payload);
            } catch (Exception e) {
                // 事件失败不影响降级结果
            }
            appendDagCompleted(runId, userId, false, failedReason, toolCallsUsed);
        }
        return result;
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException nfe) {
                return 0L;
            }
        }
        return 0L;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException nfe) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /**
     * 将 request 中的 runId/userId/webSearchEnabled 设置到 AgentContext。
     * 在 DAG 执行开始前调用，使 observability trace 能关联到正确的 run。
     */
    private void applyRunContext(LangchainWorkflowRequest request) {
        if (!isBlank(request.getRunId())) {
            AgentContext.setRunId(request.getRunId());
        }
        if (!isBlank(request.getUserId())) {
            AgentContext.setUserId(request.getUserId());
        }
        AgentContext.setWebSearchEnabled(Boolean.TRUE.equals(request.getWebSearchEnabled()));
    }

    /**
     * 校验 request 和 plan 的合法性。
     *
     * <p>必须满足：request 非空、plan 非空且有 items、execution model（执行阶段模型）已配置、
     * user goal（用户目标）非空。任何一项不满足都抛出 IllegalArgumentException，
     * 由上层 {@link #executePlanned} 的 catch 块捕获并包装为失败结果。</p>
     */
    private void validate(LangchainWorkflowRequest request, LangchainTodoPlan plan) {
        if (request == null) {
            throw new IllegalArgumentException("dag_workflow_request_required");
        }
        if (plan == null || plan.getItems() == null || plan.getItems().isEmpty()) {
            throw new IllegalArgumentException("dag_workflow_plan_required");
        }
        if (request.executionModelOrDefault() == null) {
            throw new IllegalArgumentException("dag_workflow_chat_model_required");
        }
        if (isBlank(request.getUserGoal())) {
            throw new IllegalArgumentException("dag_workflow_user_goal_required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 判断中断原因是否为用户主动取消（cancel），用于区分 cancel 和 budget 等其他中断类型。 */
    private static boolean isInterruptedCancel(String reason) {
        return reason != null && reason.startsWith("RUN_INTERRUPTED:CANCEL");
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    /** null/空安全的回退取值：主值非空返回主值，否则返回 fallback（空则返 ""）。 */
    private String nvl(String primary, String fallback) {
        if (!isBlank(primary)) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * 构造统一的 TODO_NODE_STARTED 事件 payload —— 包含 todo_id、sequence、workflow 形态、
     * phase 和启动时间戳。前端可通过 SSE 订阅此事件来渲染 DAG 节点的"开始执行"状态。
     */
    private Map<String, Object> todoNodeStartedPayload(TodoItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("todo_id", item.getId());
        payload.put("todo_sequence", item.getSequence());
        payload.put("workflow", nvl(AgentContext.getWorkflow(), "dag"));
        payload.put("phase", "execution");
        payload.put("started_at", System.currentTimeMillis());
        return payload;
    }

    /**
     * 构造统一的 TODO_NODE_COMPLETED / TODO_NODE_FAILED 事件 payload。
     * @param success   节点是否成功完成
     * @param summary   节点的输出摘要或失败原因文本
     * @param durationMs 节点执行耗时（毫秒），用于前端展示和各节点耗时对比
     * @param toolCalls 该节点工具调用次数（完成时传入，失败时传 0）
     */
    private Map<String, Object> todoNodeResultPayload(TodoItem item, boolean success,
                                                       String summary, long durationMs, int toolCalls) {
        return todoNodeResultPayload(item, success, summary, durationMs, toolCalls, null, null, false, null);
    }

    /**
     * 带显式 errorCode 的重载版本。
     * 如果不传 errorCode 且 success=false，会自动根据 summary 判断是否为 cancel 导致的失败
     * 并填入 {@code RUN_CANCELED} 错误码。
     */
    private Map<String, Object> todoNodeResultPayload(TodoItem item, boolean success,
                                                       String summary, long durationMs, int toolCalls,
                                                       String errorCode) {
        return todoNodeResultPayload(item, success, summary, durationMs, toolCalls, errorCode,
                null, false, null);
    }

    /**
     * 带结构化观测 + recovery 标记的完整重载。
     * <ul>
     *   <li>{@code failureMetadata} 非空时按 {@link LangchainTodoNodeResult#routeFailureMetadataField} 语义路由
     *       到 {@code budget_failure} / {@code empty_output_observation} / {@code failure_metadata} 子 map
     *       （防止额度失败的元数据被误写进 empty_output_observation）；</li>
     *   <li>{@code recovered=true} 时写入 {@code recovered=true} + {@code recovery_outcome}，便于压测报告统计 success after recovery；</li>
     *   <li>{@code errorCode} 与 RUN_CANCELED 推断逻辑保持兼容。</li>
     * </ul>
     */
    private Map<String, Object> todoNodeResultPayload(TodoItem item, boolean success,
                                                       String summary, long durationMs, int toolCalls,
                                                       String errorCode,
                                                       Map<String, Object> failureMetadata,
                                                       boolean recovered, String recoveryOutcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("todo_id", item.getId());
        payload.put("todo_sequence", item.getSequence());
        payload.put("workflow", nvl(AgentContext.getWorkflow(), "dag"));
        payload.put("phase", "execution");
        payload.put("success", success);
        payload.put("duration_ms", durationMs);
        if (toolCalls > 0) {
            payload.put("tool_calls_used", toolCalls);
        }
        String resolvedErrorCode = errorCode;
        if (!success && isBlank(resolvedErrorCode) && isInterruptedCancel(summary)) {
            resolvedErrorCode = "RUN_CANCELED";
        }
        if (!success && !isBlank(resolvedErrorCode)) {
            payload.put("error_code", resolvedErrorCode);
        }
        if (!success && !isBlank(summary)) {
            payload.put("failure_reason", summary);
        }
        if (success && recovered) {
            payload.put("recovered", true);
            if (!isBlank(recoveryOutcome)) {
                payload.put("recovery_outcome", recoveryOutcome);
            }
        }
        if (failureMetadata != null && !failureMetadata.isEmpty()) {
            if (failureMetadata.containsKey("todo_retry_attempts")) {
                payload.put("todo_retry_attempts", failureMetadata.get("todo_retry_attempts"));
                if (failureMetadata.get("todo_retry_outcome") != null) {
                    payload.put("todo_retry_outcome", failureMetadata.get("todo_retry_outcome"));
                }
            }
            // failureMetadata 按语义路由到 budget_failure / empty_output_observation / failure_metadata，
            // 避免额度失败被误写进 empty_output_observation。
            String field = LangchainTodoNodeResult.routeFailureMetadataField(failureMetadata);
            if (field != null && !success) {
                payload.put(field, failureMetadata);
            }
        }
        return payload;
    }

    private Map<String, Object> todoRetryEventMetadata(LangchainTodoNodeResult result) {
        if (result == null || result.getTodoRetryAttempts() <= 0) {
            return null;
        }
        return Map.of(
                "todo_retry_attempts", result.getTodoRetryAttempts(),
                "todo_retry_outcome", nvl(result.getTodoRetryOutcome(), "success"));
    }

    private static Map<String, Object> unknownDefectMetadata(Throwable throwable) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("unknown_defect", true);
        meta.put("failure_class", AgentRunFailureClass.UNKNOWN_DEFECT.wireName());
        meta.put("throwable_type", throwable.getClass().getName());
        return meta;
    }

    /**
     * 尽力写入事件（不抛异常）—— 事件系统不可用时不会中断 DAG 节点执行。
     * <p>用于统一的 TODO_NODE_* 生命周期事件和 DAG_NODE_* 状态事件。
     * runId 或 userId 为空时直接跳过（例如测试环境或未初始化上下文时）。</p>
     */
    private void emitEventBestEffort(String runId, String userId, String eventType,
                                     Map<String, Object> payload) {
        if (isBlank(runId) || isBlank(userId)) {
            return;
        }
        try {
            eventService.append(runId, userId, eventType, payload);
        } catch (Exception e) {
            // 事件系统不可用（例如 Redis 宕机）不应阻塞 DAG 执行
        }
    }

    /**
     * DAG 并行执行的内部结果包装，持有所有节点的执行结果映射。
     */
    private record DagParallelRun(Map<String, LangchainTodoNodeResult> results) {
    }
}
