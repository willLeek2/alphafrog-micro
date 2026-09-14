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
    private boolean hasMore;
    private int nextOffset;
    private int totalLength;
}
