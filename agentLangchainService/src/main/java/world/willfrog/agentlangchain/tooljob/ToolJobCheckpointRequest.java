package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;

import java.util.Collections;
import java.util.List;

/**
 * pipeline 在让出 worker 之前捕获的不可变 checkpoint 写入请求。
 *
 * <p>身份字段用于数据库 CAS；上下文字段用于新 worker 恢复；版本必须由调用方
 * 显式传入，禁止 Builder 的默认 0 被误认为合法 expected version。</p>
 */
public class ToolJobCheckpointRequest {

    // runId 定位数据库 Run 行。
    private final String runId;
    // operationId、toolCallId、attempt、taskId 组成冻结的外部任务身份。
    private final String operationId;
    private final String toolCallId;
    private final int attempt;
    private final String taskId;
    // expectedCheckpointVersion 是本写者捕获时的版本栅栏。
    private final int expectedCheckpointVersion;
    // todoId + sequence 描述恢复注入位置。
    private final String todoId;
    private final int sequence;
    // completedTodos 保存不会重新执行的计划前缀。
    private final List<CompletedTodoRecord> completedTodos;
    // snapshot 正文与 digest 成对保存并在写入/恢复两端校验。
    private final String datasetSnapshotJson;
    private final String datasetSnapshotDigest;
    // datasetRefsJson 兼容恢复已注册的结果引用。
    private final String datasetRefsJson;
    // toolCallsUsed 延续 Run 工具调用预算。
    private final int toolCallsUsed;
    // estimateJson 供终态 envelope 和容量释放使用。
    private final String estimateJson;
    // 单独记录 setter 是否调用，区分“明确期望 0”与“忘记设置版本”。
    private final boolean versionExplicitlySet;

    private ToolJobCheckpointRequest(Builder builder) {
        // 构造后所有字段均不可变，写库期间不会被 pipeline 线程继续修改。
        this.runId = builder.runId;
        this.operationId = builder.operationId;
        this.toolCallId = builder.toolCallId;
        this.attempt = builder.attempt;
        this.taskId = builder.taskId;
        this.versionExplicitlySet = builder.versionExplicitlySet;
        this.expectedCheckpointVersion = builder.expectedCheckpointVersion;
        this.todoId = builder.todoId;
        this.sequence = builder.sequence;
        // 包装为不可修改列表，避免 capture 与 SQL 写入之间发生内容漂移。
        this.completedTodos = builder.completedTodos != null
                ? Collections.unmodifiableList(builder.completedTodos) : null;
        this.datasetSnapshotJson = builder.datasetSnapshotJson;
        this.datasetSnapshotDigest = builder.datasetSnapshotDigest;
        this.datasetRefsJson = builder.datasetRefsJson;
        this.toolCallsUsed = builder.toolCallsUsed;
        this.estimateJson = builder.estimateJson;
    }

    public String getRunId() { return runId; }
    public String getOperationId() { return operationId; }
    public String getToolCallId() { return toolCallId; }
    public int getAttempt() { return attempt; }
    public String getTaskId() { return taskId; }
    public int getExpectedCheckpointVersion() { return expectedCheckpointVersion; }
    public boolean isVersionExplicitlySet() { return versionExplicitlySet; }
    public String getTodoId() { return todoId; }
    public int getSequence() { return sequence; }
    public List<CompletedTodoRecord> getCompletedTodos() { return completedTodos; }
    public String getDatasetSnapshotJson() { return datasetSnapshotJson; }
    public String getDatasetSnapshotDigest() { return datasetSnapshotDigest; }
    public String getDatasetRefsJson() { return datasetRefsJson; }
    public int getToolCallsUsed() { return toolCallsUsed; }
    public String getEstimateJson() { return estimateJson; }

    public static Builder builder(String runId) {
        return new Builder(runId);
    }

    public static class Builder {
        private final String runId;
        private String operationId;
        private String toolCallId;
        private int attempt;
        private String taskId;
        private int expectedCheckpointVersion;
        private boolean versionExplicitlySet;
        private String todoId;
        private int sequence;
        private List<CompletedTodoRecord> completedTodos = Collections.emptyList();
        private String datasetSnapshotJson;
        private String datasetSnapshotDigest;
        private String datasetRefsJson;
        private int toolCallsUsed;
        private String estimateJson;

        private Builder(String runId) { this.runId = runId; }

        public Builder operationId(String operationId) { this.operationId = operationId; return this; }
        public Builder toolCallId(String toolCallId) { this.toolCallId = toolCallId; return this; }
        public Builder attempt(int attempt) { this.attempt = attempt; return this; }
        public Builder taskId(String taskId) { this.taskId = taskId; return this; }
        public Builder expectedCheckpointVersion(int expectedCheckpointVersion) {
            // 保存捕获时版本；数据库 SQL 会以它作为 WHERE 条件。
            this.expectedCheckpointVersion = expectedCheckpointVersion;
            // 即使版本值为 0，也要记住调用方确实显式提供过。
            this.versionExplicitlySet = true;
            return this;
        }
        public Builder todoId(String todoId) { this.todoId = todoId; return this; }
        public Builder sequence(int sequence) { this.sequence = sequence; return this; }
        public Builder completedTodos(List<CompletedTodoRecord> completedTodos) { this.completedTodos = completedTodos; return this; }
        public Builder datasetSnapshotJson(String datasetSnapshotJson) { this.datasetSnapshotJson = datasetSnapshotJson; return this; }
        public Builder datasetSnapshotDigest(String datasetSnapshotDigest) { this.datasetSnapshotDigest = datasetSnapshotDigest; return this; }
        public Builder datasetRefsJson(String datasetRefsJson) { this.datasetRefsJson = datasetRefsJson; return this; }
        public Builder toolCallsUsed(int toolCallsUsed) { this.toolCallsUsed = toolCallsUsed; return this; }
        public Builder estimateJson(String estimateJson) { this.estimateJson = estimateJson; return this; }

        public ToolJobCheckpointRequest build() {
            // 忘记版本会把默认 0 当 CAS 条件，可能在旧数据上意外成功，因此构造期即拒绝。
            if (!versionExplicitlySet) {
                throw new IllegalStateException(
                        "expectedCheckpointVersion must be explicitly set; missing value may silently accept wrong version");
            }
            // 所有业务字段的完整类型校验由 CheckpointService 在读取最新 anchor 后执行。
            return new ToolJobCheckpointRequest(this);
        }
    }
}
