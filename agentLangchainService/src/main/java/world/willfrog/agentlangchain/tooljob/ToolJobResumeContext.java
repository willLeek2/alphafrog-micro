package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;

import java.util.Collections;
import java.util.List;

/**
 * DTO passed from T3 resume service to Codex pipeline resume launcher.
 * Contains everything needed to skip planner, restore completed todos,
 * and inject the terminal tool result into the current todo.
 */
public class ToolJobResumeContext {

    private String runId;
    private String todoId;
    private String resumeToken;
    private long resumeLeaseVersion;  // incremented on each new claim
    private List<CompletedTodoRecord> completedTodos = Collections.emptyList();
    private String datasetSnapshotJson;
    private String datasetSnapshotDigest;
    private int toolCallsUsed;
    private boolean terminalSuccess;
    private String terminalResultPreview;
    private String terminalRawRef;

    public ToolJobResumeContext() {}

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getTodoId() { return todoId; }
    public void setTodoId(String todoId) { this.todoId = todoId; }

    public String getResumeToken() { return resumeToken; }
    public void setResumeToken(String resumeToken) { this.resumeToken = resumeToken; }

    public long getResumeLeaseVersion() { return resumeLeaseVersion; }
    public void setResumeLeaseVersion(long resumeLeaseVersion) { this.resumeLeaseVersion = resumeLeaseVersion; }

    public List<CompletedTodoRecord> getCompletedTodos() { return completedTodos; }
    public void setCompletedTodos(List<CompletedTodoRecord> completedTodos) { this.completedTodos = completedTodos; }

    public String getDatasetSnapshotJson() { return datasetSnapshotJson; }
    public void setDatasetSnapshotJson(String datasetSnapshotJson) { this.datasetSnapshotJson = datasetSnapshotJson; }

    public String getDatasetSnapshotDigest() { return datasetSnapshotDigest; }
    public void setDatasetSnapshotDigest(String datasetSnapshotDigest) { this.datasetSnapshotDigest = datasetSnapshotDigest; }

    public int getToolCallsUsed() { return toolCallsUsed; }
    public void setToolCallsUsed(int toolCallsUsed) { this.toolCallsUsed = toolCallsUsed; }

    public boolean isTerminalSuccess() { return terminalSuccess; }
    public void setTerminalSuccess(boolean terminalSuccess) { this.terminalSuccess = terminalSuccess; }

    public String getTerminalResultPreview() { return terminalResultPreview; }
    public void setTerminalResultPreview(String terminalResultPreview) { this.terminalResultPreview = terminalResultPreview; }

    public String getTerminalRawRef() { return terminalRawRef; }
    public void setTerminalRawRef(String terminalRawRef) { this.terminalRawRef = terminalRawRef; }
}
