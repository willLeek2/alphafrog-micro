package world.willfrog.agentlangchain.tooljob;

import java.util.List;

/**
 * DTO passed from T3 resume service to Codex pipeline resume launcher.
 * Contains everything needed to skip planner, restore completed todos,
 * and inject the terminal tool result into the current todo.
 */
public class ToolJobResumeContext {

    private String runId;
    private String todoId;           // suspended todo to resume from
    private List<String> completedTodoIds;  // todos completed before suspend
    private String datasetRefsJson;  // dataset references to restore
    private int toolCallsUsed;       // tool call count at suspend point
    private boolean terminalSuccess; // whether sandbox succeeded
    private String terminalResultPreview; // tool result to inject into current todo
    private String terminalRawRef;

    public ToolJobResumeContext() {}

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getTodoId() { return todoId; }
    public void setTodoId(String todoId) { this.todoId = todoId; }

    public List<String> getCompletedTodoIds() { return completedTodoIds; }
    public void setCompletedTodoIds(List<String> completedTodoIds) { this.completedTodoIds = completedTodoIds; }

    public String getDatasetRefsJson() { return datasetRefsJson; }
    public void setDatasetRefsJson(String datasetRefsJson) { this.datasetRefsJson = datasetRefsJson; }

    public int getToolCallsUsed() { return toolCallsUsed; }
    public void setToolCallsUsed(int toolCallsUsed) { this.toolCallsUsed = toolCallsUsed; }

    public boolean isTerminalSuccess() { return terminalSuccess; }
    public void setTerminalSuccess(boolean terminalSuccess) { this.terminalSuccess = terminalSuccess; }

    public String getTerminalResultPreview() { return terminalResultPreview; }
    public void setTerminalResultPreview(String terminalResultPreview) { this.terminalResultPreview = terminalResultPreview; }

    public String getTerminalRawRef() { return terminalRawRef; }
    public void setTerminalRawRef(String terminalRawRef) { this.terminalRawRef = terminalRawRef; }
}
