package world.willfrog.agent.platform.artifact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolOutputReadResult {
    private String content;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private world.willfrog.alphafrogmicro.common.racing.prompt.PromptText contentPrompt;
    private boolean hasMore;
    private int nextOffset;
    private int totalLength;

    public ToolOutputReadResult(String content, boolean hasMore, int nextOffset, int totalLength) {
        this(content, null, hasMore, nextOffset, totalLength);
    }
}
