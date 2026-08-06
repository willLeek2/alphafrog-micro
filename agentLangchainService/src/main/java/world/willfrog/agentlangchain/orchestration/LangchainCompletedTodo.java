package world.willfrog.agentlangchain.orchestration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LangchainCompletedTodo {
    private String todoId;
    private int sequence;
    private String description;
    /** LLM-facing compact output (summary + rawRef template when compaction applied). */
    private String modelOutput;
    /** Legacy/raw output kept for observability and backward compatibility. */
    private String output;
    private String summary;

    public String displayOutput() {
        if (modelOutput != null && !modelOutput.isBlank()) {
            return modelOutput;
        }
        return output == null ? "" : output;
    }
}
