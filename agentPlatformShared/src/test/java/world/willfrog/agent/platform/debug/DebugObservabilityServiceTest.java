package world.willfrog.agent.platform.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.context.AgentContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DebugObservabilityServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }








    private DebugObservabilityService newService(Path root) {
        return new DebugObservabilityService(objectMapper) {
            @Override
            Path rootDir() {
                return root;
            }
        };
    }
}
