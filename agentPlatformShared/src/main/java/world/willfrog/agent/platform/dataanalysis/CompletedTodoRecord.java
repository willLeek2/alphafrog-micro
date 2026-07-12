package world.willfrog.agent.platform.dataanalysis;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A completed todo stored in the durable anchor for resume.
 * Mirrors LangchainCompletedTodo fields needed to reconstruct
 * LLM previous-todo context without re-running the planner.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompletedTodoRecord {

    private String todoId;
    private int sequence;
    private String description;     // todo description / title
    private String summary;         // brief output summary
    private String modelOutput;     // raw model output (LLM response text)
    private String output;          // structured final output
    private int toolCalls;          // tool calls within this todo

    public CompletedTodoRecord() {}

    public String getTodoId() { return todoId; }
    public void setTodoId(String todoId) { this.todoId = todoId; }

    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getModelOutput() { return modelOutput; }
    public void setModelOutput(String modelOutput) { this.modelOutput = modelOutput; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public int getToolCalls() { return toolCalls; }
    public void setToolCalls(int toolCalls) { this.toolCalls = toolCalls; }
}
