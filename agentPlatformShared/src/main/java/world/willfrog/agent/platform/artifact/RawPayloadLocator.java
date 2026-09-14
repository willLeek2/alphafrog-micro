package world.willfrog.agent.platform.artifact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawPayloadLocator {
    private String path;
    private String contentHash;
}
