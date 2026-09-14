package world.willfrog.agent.platform.dataanalysis;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 写入持久化 ToolJob anchor 或工作流 checkpoint 的已完成 Todo 快照。
 *
 * <p>恢复线程不能依赖原 worker 的堆内对象，因此这里只保存重新构造
 * {@code LangchainCompletedTodo} 所需的稳定字段。恢复时按 sequence 还原顺序，
 * 重新注册 output 中的 dataset ref，并跳过这些 Todo，绝不重新调用 planner。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompletedTodoRecord {

    // todoId 用于恢复循环去重，已经完成的节点必须直接跳过。
    private String todoId;
    // sequence 保存 planner 给出的稳定顺序，用于检查 checkpoint 是否越过挂起节点。
    private int sequence;
    // description 恢复后继续作为后续 Todo 的任务语义上下文。
    private String description;
    // summary 是面向后续模型的短摘要，避免只剩结构化输出而失去语义。
    private String summary;
    // modelOutput 保存节点原始模型输出，供需要原文的恢复路径使用。
    private String modelOutput;
    // output 保存最终结构化输出，dataset ref 也从这里重新注册。
    private String output;
    // toolCalls 记录该 Todo 的调用量；run 级计数另存在 anchor.toolCallsUsed。
    private int toolCalls;

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
