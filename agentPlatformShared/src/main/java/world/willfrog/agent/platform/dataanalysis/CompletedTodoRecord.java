package world.willfrog.agent.platform.dataanalysis;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A completed todo stored in the durable anchor for resume.
 * Enough context to skip the planner, skip previously-completed todos,
 * and keep the subsequent prompt consistent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompletedTodoRecord {

    private String todoId;
    private String summary;       // brief output summary for context
    private int sequence;         // execution order
    private int toolCalls;        // tool calls within this todo

    public CompletedTodoRecord() {}

    public CompletedTodoRecord(String todoId, String summary, int sequence, int toolCalls) {
        this.todoId = todoId;
        this.summary = summary;
        this.sequence = sequence;
        this.toolCalls = toolCalls;
    }

    public String getTodoId() { return todoId; }
    public void setTodoId(String todoId) { this.todoId = todoId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }
}
