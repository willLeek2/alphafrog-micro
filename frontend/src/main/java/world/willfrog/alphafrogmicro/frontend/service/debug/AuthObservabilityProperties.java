package world.willfrog.alphafrogmicro.frontend.service.debug;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alphafrog.debug.observability")
@Data
public class AuthObservabilityProperties {

    private String outputRoot = "/app/logs/agent-debug-observability";

    private Auth auth = new Auth();

    @Data
    public static class Auth {
        private int defaultTtlSeconds = 1800;
        private long maxBytesPerSession = 209_715_200L;
        private long maxFileSizeBytes = 52_428_800L;
    }
}
