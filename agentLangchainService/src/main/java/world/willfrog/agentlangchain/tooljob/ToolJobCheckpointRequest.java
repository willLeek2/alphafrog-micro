package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;

import java.util.Collections;
import java.util.List;

/**
 * Immutable checkpoint payload captured by the pipeline before suspending
 * for a slow tool job. Contains everything needed to restore execution state
 * when the sandbox result arrives and the resume launcher takes over.
 */
public class ToolJobCheckpointRequest {

    private final String runId;
    private final String operationId;
    private final String toolCallId;
    private final int attempt;
    private final String taskId;
    private final int expectedCheckpointVersion;
    private final String todoId;
    private final int sequence;
    private final List<CompletedTodoRecord> completedTodos;
    private final String datasetSnapshotJson;
    private final String datasetSnapshotDigest;
    private final String datasetRefsJson;
    private final int toolCallsUsed;
    private final String estimateJson;
    private final boolean versionExplicitlySet;

    private ToolJobCheckpointRequest(Builder builder) {
        this.runId = builder.runId;
        this.operationId = builder.operationId;
        this.toolCallId = builder.toolCallId;
        this.attempt = builder.attempt;
        this.taskId = builder.taskId;
        this.versionExplicitlySet = builder.versionExplicitlySet;
        this.expectedCheckpointVersion = builder.expectedCheckpointVersion;
        this.todoId = builder.todoId;
        this.sequence = builder.sequence;
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
            this.expectedCheckpointVersion = expectedCheckpointVersion;
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
            if (!versionExplicitlySet) {
                throw new IllegalStateException(
                        "expectedCheckpointVersion must be explicitly set; missing value may silently accept wrong version");
            }
            return new ToolJobCheckpointRequest(this);
        }
    }
}
