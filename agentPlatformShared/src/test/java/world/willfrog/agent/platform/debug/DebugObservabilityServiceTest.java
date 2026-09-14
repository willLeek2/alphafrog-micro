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

    @Test
    void disabledPathShouldNotCreateFiles() throws Exception {
        DebugObservabilityService service = newService(tempDir);
        service.openRunSession(DebugObservabilityRequest.disabled(), "run-1", "user-1");
        service.emit(Map.of("eventType", "should_not_write"));
        service.closeRunSession("run-1");

        try (Stream<Path> paths = Files.list(tempDir)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void enabledPathShouldWriteSandboxJsonl() throws Exception {
        DebugObservabilityService service = newService(tempDir);
        DebugObservabilityRequest request = new DebugObservabilityRequest(true, null, "20260625-105143");
        AgentContext.setRunId("run-abc");
        service.openRunSession(request, "run-abc", "user-1");
        assertTrue(service.isEnabled());
        service.emit(Map.of(
                "eventType", "sandbox_poll",
                "status", "OK",
                "remoteStatus", "RUNNING"
        ));
        service.closeRunSession("run-abc");

        Path sandboxRoot = tempDir.resolve("sandbox");
        try (Stream<Path> runFiles = Files.find(sandboxRoot, 3,
                (path, attrs) -> path.getFileName().toString().equals("sandbox-run-abc.jsonl"))) {
            Path runFile = runFiles.findFirst().orElseThrow();
            Path sessionDir = runFile.getParent();
            assertTrue(Files.exists(sessionDir.resolve("manifest.json")));
            assertEquals("sandbox", sessionDir.getParent().getParent().getFileName().toString());
            assertTrue(sessionDir.getParent().getFileName().toString().matches("\\d{8}-\\d{2}"));
            assertTrue(sessionDir.getFileName().toString().matches("\\d{8}-\\d{6}_run-run-abc-[0-9a-f-]+"));
            assertTrue(Files.exists(runFile));
            String content = Files.readString(runFile);
            assertTrue(content.contains("sandbox_poll"));
            assertFalse(content.contains("Bearer "));
            assertFalse(content.contains("eyJ"));
        }
    }

    @Test
    void sanitizeShouldRedactTokenLikeValues() {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("auth", "Bearer abc.def.ghi");
        DebugObservabilityService.sanitize(event);
        assertEquals("<redacted>", event.get("auth"));
    }

    @Test
    void parseContextJsonShouldAcceptBooleanShorthand() {
        DebugObservabilityRequest request = DebugObservabilityRequest.parseContextJson(
                "{\"debugObservability\":true}", objectMapper);
        assertTrue(request.enabled());
    }

    @Test
    void parseContextJsonShouldReadDebugObservabilityBlock() {
        String contextJson = """
                {"debugObservability":{"enabled":true,"stressBatchId":"batch-1"}}
                """;
        DebugObservabilityRequest request = DebugObservabilityRequest.parseContextJson(contextJson, objectMapper);
        assertTrue(request.enabled());
        assertEquals("batch-1", request.stressBatchId());
    }

    @Test
    void buildSessionDirShouldUseUtcPlusEightHourBuckets() {
        DebugObservabilityService service = newService(tempDir);
        Path sessionDir = service.buildSessionDir("session-1", Instant.parse("2026-06-26T04:50:32Z"));

        assertEquals(tempDir.resolve("sandbox")
                .resolve("20260626-12")
                .resolve("20260626-125032_session-1"), sessionDir);
    }

    @Test
    void jsonlAppenderShouldWriteSandboxHttpEvent() throws Exception {
        Path sessionDir = tempDir.resolve("session-1");
        Files.createDirectories(sessionDir);

        DebugObservabilityJsonlAppender.append(
                sessionDir,
                "run-abc",
                "sess-1",
                "pythonSandboxGatewayService",
                objectMapper,
                Map.of("eventType", "sandbox_http", "method", "POST", "httpStatus", 200, "durationMs", 12)
        );

        Path runFile = sessionDir.resolve("sandbox-run-abc.jsonl");
        assertTrue(Files.exists(runFile));
        assertTrue(Files.readString(runFile).contains("sandbox_http"));
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
