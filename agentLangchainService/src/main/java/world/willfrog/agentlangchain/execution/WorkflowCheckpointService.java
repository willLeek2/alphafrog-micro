package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LINEAR Todo 边界 checkpoint 的唯一读写入口。
 *
 * <p>写入成功才允许执行下一个 Todo。任何超限、损坏或 rawRef 缺失都会明确失败，
 * 避免把 PostgreSQL JSONB 当无限文件存储，也避免重启后用残缺输入继续生成答案。</p>
 */
@Service
@RequiredArgsConstructor
public class WorkflowCheckpointService {

    private static final Pattern RAW_REF_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:raw_ref_[A-Za-z0-9_-]+|raw_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?![A-Za-z0-9_-])");

    private final AgentRunMapper runMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RunRawRefStore> rawRefStoreProvider;
    private final ToolRetrySafetyCatalog toolSafetyCatalog;

    @Autowired(required = false)
    private DeploymentIdentityProvider deploymentIdentityProvider;

    @Value("${agent.workflow-restart.checkpoint.max-json-chars:262144}")
    private int maxCheckpointJsonChars = 262144;

    @Value("${agent.workflow-restart.checkpoint.max-output-chars-per-todo:32768}")
    private int maxOutputCharsPerTodo = 32768;

    @Value("${agent.workflow-restart.checkpoint.max-output-chars-per-run:131072}")
    private int maxOutputCharsPerRun = 131072;

    public WorkflowExecutionCheckpoint initializeLinear(String runId,
                                                        String userId,
                                                        LangchainTodoPlan plan) {
        return persistLinearProgress(runId, userId, plan, List.of(), 0);
    }

    public WorkflowExecutionCheckpoint initializeDag(String runId, String userId) {
        requireIdentity(runId, userId);
        WorkflowExecutionCheckpoint checkpoint = new WorkflowExecutionCheckpoint();
        checkpoint.setWorkflow(WorkflowExecutionCheckpoint.DAG);
        checkpoint.setNextTodoId(null);
        checkpoint.setUpdatedAt(Instant.now());
        write(runId, userId, checkpoint);
        return checkpoint;
    }

    /**
     * 工具副作用之前同步写入安全日志。方法加锁是为了避免同一 JVM 内 DAG 并行节点
     * 对 JSONB 做读改写时互相覆盖；当前合同明确是单 Agent 实例。
     */
    public synchronized void markToolStarted(String runId, String userId, String toolName) {
        requireIdentity(runId, userId);
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("workflow_checkpoint_tool_name_required");
        }
        AgentRun run = findLocalRun(runId);
        if (run == null || !Objects.equals(userId, run.getUserId())) {
            throw new IllegalStateException("workflow_checkpoint_run_not_found");
        }
        WorkflowExecutionCheckpoint checkpoint = readCheckpoint(run.getExecutionCheckpointJson());
        LinkedHashSet<String> started = new LinkedHashSet<>();
        if (checkpoint.getStartedTools() != null) {
            started.addAll(checkpoint.getStartedTools());
        }
        started.add(toolName);
        checkpoint.setStartedTools(new ArrayList<>(started));
        checkpoint.setUpdatedAt(Instant.now());
        write(runId, userId, checkpoint);
    }

    public WorkflowExecutionCheckpoint persistLinearProgress(String runId,
                                                             String userId,
                                                             LangchainTodoPlan plan,
                                                             List<LangchainCompletedTodo> completedTodos,
                                                             int toolCallsUsed) {
        requireIdentity(runId, userId);
        List<TodoItem> items = requireLinearPlan(plan);
        List<LangchainCompletedTodo> completed = completedTodos == null
                ? List.of()
                : completedTodos.stream()
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparingInt(LangchainCompletedTodo::getSequence))
                        .toList();
        validateCompletedPrefix(items, completed);

        WorkflowExecutionCheckpoint checkpoint = new WorkflowExecutionCheckpoint();
        List<CompletedTodoRecord> records = new ArrayList<>(completed.size());
        Set<String> rawRefs = new LinkedHashSet<>();
        long totalOutputChars = 0L;
        for (LangchainCompletedTodo todo : completed) {
            String modelOutput = safe(todo.getModelOutput());
            String output = safe(todo.getOutput());
            String summary = safe(todo.getSummary());
            int todoOutputChars = modelOutput.length() + output.length() + summary.length();
            if (todoOutputChars > maxOutputCharsPerTodo) {
                throw new IllegalStateException("workflow_checkpoint_todo_output_too_large:" + todo.getTodoId());
            }
            totalOutputChars += todoOutputChars;
            if (totalOutputChars > maxOutputCharsPerRun) {
                throw new IllegalStateException("workflow_checkpoint_run_output_too_large");
            }
            CompletedTodoRecord record = new CompletedTodoRecord();
            record.setTodoId(todo.getTodoId());
            record.setSequence(todo.getSequence());
            record.setDescription(todo.getDescription());
            record.setSummary(emptyToNull(summary));
            record.setModelOutput(emptyToNull(modelOutput));
            // 避免同一字符串在 JSONB 中存两份；恢复时 displayOutput 会回退到 modelOutput。
            record.setOutput(modelOutput.equals(output) ? null : emptyToNull(output));
            records.add(record);
            collectRawRefs(modelOutput, rawRefs);
            collectRawRefs(output, rawRefs);
            collectRawRefs(summary, rawRefs);
        }
        checkpoint.setCompletedTodos(records);
        checkpoint.setNextTodoId(completed.size() < items.size()
                ? items.get(completed.size()).getId()
                : WorkflowExecutionCheckpoint.FINAL_TODO_ID);
        checkpoint.setToolCallsUsed(Math.max(0, toolCallsUsed));
        checkpoint.setRawRefs(new ArrayList<>(rawRefs));
        // 成功 Todo 的副作用已经被 checkpoint 覆盖，下一 Todo 从一份干净的安全日志开始。
        checkpoint.setStartedTools(new ArrayList<>());
        checkpoint.setUpdatedAt(Instant.now());
        write(runId, userId, checkpoint);
        return checkpoint;
    }

    public WorkflowExecutionCheckpoint parseAndValidate(AgentRun run, LangchainTodoPlan plan) {
        if (run == null) {
            throw new IllegalArgumentException("workflow_restart_run_required");
        }
        String json = run.getExecutionCheckpointJson();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            throw new IllegalStateException("workflow_checkpoint_missing");
        }
        if (json.length() > maxCheckpointJsonChars) {
            throw new IllegalStateException("workflow_checkpoint_json_too_large");
        }
        try {
            WorkflowExecutionCheckpoint checkpoint = readCheckpoint(json);
            if (!WorkflowExecutionCheckpoint.CURRENT_VERSION.equals(checkpoint.getVersion())) {
                throw new IllegalStateException("workflow_checkpoint_version_unsupported");
            }
            if (!WorkflowExecutionCheckpoint.LINEAR.equals(checkpoint.getWorkflow())) {
                throw new IllegalStateException("workflow_checkpoint_type_mismatch");
            }
            List<TodoItem> items = requireLinearPlan(plan);
            List<LangchainCompletedTodo> completed = restoreCompleted(checkpoint.getCompletedTodos());
            validateCompletedPrefix(items, completed);
            String expectedNext = completed.size() < items.size()
                    ? items.get(completed.size()).getId()
                    : WorkflowExecutionCheckpoint.FINAL_TODO_ID;
            if (!Objects.equals(expectedNext, checkpoint.getNextTodoId())) {
                throw new IllegalStateException("workflow_checkpoint_next_todo_mismatch");
            }
            validateRawRefs(run, checkpoint);
            validateReplaySafety(checkpoint);
            return checkpoint;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("workflow_checkpoint_invalid", e);
        }
    }

    /** DAG 从头重跑前只校验安全日志；不复用已完成节点或旧输出。 */
    public WorkflowExecutionCheckpoint parseAndValidateDagRestart(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("workflow_restart_run_required");
        }
        WorkflowExecutionCheckpoint checkpoint = readCheckpoint(run.getExecutionCheckpointJson());
        if (!WorkflowExecutionCheckpoint.CURRENT_VERSION.equals(checkpoint.getVersion())) {
            throw new IllegalStateException("workflow_checkpoint_version_unsupported");
        }
        if (!WorkflowExecutionCheckpoint.DAG.equals(checkpoint.getWorkflow())) {
            throw new IllegalStateException("workflow_checkpoint_type_mismatch");
        }
        validateReplaySafety(checkpoint);
        return checkpoint;
    }

    public List<LangchainCompletedTodo> restoreCompleted(List<CompletedTodoRecord> records) {
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(CompletedTodoRecord::getSequence))
                .map(record -> LangchainCompletedTodo.builder()
                        .todoId(record.getTodoId())
                        .sequence(record.getSequence())
                        .description(record.getDescription())
                        .summary(record.getSummary())
                        .modelOutput(record.getModelOutput())
                        .output(record.getOutput())
                        .build())
                .toList();
    }

    private void write(String runId, String userId, WorkflowExecutionCheckpoint checkpoint) {
        try {
            String json = objectMapper.writeValueAsString(checkpoint);
            if (json.length() > maxCheckpointJsonChars) {
                throw new IllegalStateException("workflow_checkpoint_json_too_large");
            }
            if (updateExecutionCheckpoint(runId, userId, json) != 1) {
                throw new IllegalStateException("workflow_checkpoint_run_not_found");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("workflow_checkpoint_write_failed", e);
        }
    }

    private WorkflowExecutionCheckpoint readCheckpoint(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            throw new IllegalStateException("workflow_checkpoint_missing");
        }
        if (json.length() > maxCheckpointJsonChars) {
            throw new IllegalStateException("workflow_checkpoint_json_too_large");
        }
        try {
            return objectMapper.readValue(json, WorkflowExecutionCheckpoint.class);
        } catch (Exception e) {
            throw new IllegalStateException("workflow_checkpoint_invalid", e);
        }
    }

    private AgentRun findLocalRun(String runId) {
        if (deploymentIdentityProvider == null) {
            return runMapper.findById(runId);
        }
        DeploymentIdentity local = deploymentIdentityProvider.current();
        return runMapper.findByIdForDeployment(
                runId, local.deploymentId(), local.generationId());
    }

    private int updateExecutionCheckpoint(String runId, String userId, String json) {
        if (deploymentIdentityProvider == null) {
            return runMapper.updateExecutionCheckpoint(runId, userId, json);
        }
        DeploymentIdentity local = deploymentIdentityProvider.current();
        return runMapper.updateExecutionCheckpointForDeployment(
                runId, userId, local.deploymentId(), local.generationId(),
                AgentRunStatus.EXECUTING, json);
    }

    private void validateReplaySafety(WorkflowExecutionCheckpoint checkpoint) {
        if (checkpoint.getStartedTools() == null) {
            return;
        }
        for (String toolName : checkpoint.getStartedTools()) {
            if (!toolSafetyCatalog.canReplay(toolName)) {
                throw new IllegalStateException("workflow_restart_unsafe_tool_started:" + toolName);
            }
        }
    }

    private void validateRawRefs(AgentRun run, WorkflowExecutionCheckpoint checkpoint) {
        List<String> refs = checkpoint.getRawRefs();
        if (refs == null || refs.isEmpty()) {
            return;
        }
        RunRawRefStore store = rawRefStoreProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("workflow_checkpoint_raw_ref_store_unavailable");
        }
        for (String rawRef : refs) {
            try {
                store.read(run.getId(), run.getUserId(), rawRef, 0, 1, null);
            } catch (Exception missing) {
                throw new IllegalStateException("workflow_checkpoint_raw_ref_missing:" + rawRef, missing);
            }
        }
    }

    private List<TodoItem> requireLinearPlan(LangchainTodoPlan plan) {
        if (plan == null || LangchainWorkflowRouting.shouldUseDag(plan)) {
            throw new IllegalArgumentException("linear_checkpoint_requires_linear_plan");
        }
        return plan.getItems() == null ? List.of() : plan.getItems();
    }

    private void validateCompletedPrefix(List<TodoItem> items, List<LangchainCompletedTodo> completed) {
        if (completed.size() > items.size()) {
            throw new IllegalStateException("workflow_checkpoint_completed_count_invalid");
        }
        for (int index = 0; index < completed.size(); index++) {
            TodoItem planned = items.get(index);
            LangchainCompletedTodo actual = completed.get(index);
            if (planned == null
                    || !Objects.equals(planned.getId(), actual.getTodoId())
                    || planned.getSequence() != actual.getSequence()) {
                throw new IllegalStateException("workflow_checkpoint_completed_prefix_invalid");
            }
        }
    }

    private void collectRawRefs(String text, Set<String> refs) {
        Matcher matcher = RAW_REF_PATTERN.matcher(safe(text));
        while (matcher.find()) {
            refs.add(matcher.group());
        }
    }

    private void requireIdentity(String runId, String userId) {
        if (runId == null || runId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("workflow_checkpoint_identity_required");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
