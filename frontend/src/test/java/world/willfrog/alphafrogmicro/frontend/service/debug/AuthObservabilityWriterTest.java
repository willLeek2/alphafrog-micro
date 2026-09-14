package world.willfrog.alphafrogmicro.frontend.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthObservabilityWriterTest {

    private AuthObservabilityProperties properties(Path tempDir) {
        AuthObservabilityProperties props = new AuthObservabilityProperties();
        props.setOutputRoot(tempDir.toString());
        props.getAuth().setMaxFileSizeBytes(200L);
        props.getAuth().setMaxBytesPerSession(1000L);
        return props;
    }

    private AuthObservabilitySession session(Path tempDir, String sessionId) {
        return new AuthObservabilitySession(
                sessionId, System.currentTimeMillis(),
                System.currentTimeMillis() + 3600_000L,
                tempDir.resolve(sessionId).toString(),
                "admin", "test", new AuthObservabilityScope());
    }

    @Test
    void writeEvent_shouldWriteJsonLine(@TempDir Path tempDir) throws Exception {
        AuthObservabilitySession session = session(tempDir, "s1");
        AuthObservabilityWriter writer = new AuthObservabilityWriter(
                new ObjectMapper(), session, properties(tempDir));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", "alice");
        writer.writeEvent("AUTH_CONTEXT_REJECTED", payload);
        writer.close();

        Path authFile = tempDir.resolve("s1").resolve("auth-0.jsonl");
        assertTrue(Files.exists(authFile));
        String line = Files.readString(authFile).trim();
        assertTrue(line.contains("AUTH_CONTEXT_REJECTED"));
        assertTrue(line.contains("alice"));
    }

    @Test
    void writeEvent_shouldDropSensitiveToken(@TempDir Path tempDir) throws Exception {
        AuthObservabilitySession session = session(tempDir, "s2");
        AuthObservabilityWriter writer = new AuthObservabilityWriter(
                new ObjectMapper(), session, properties(tempDir));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("raw", "Authorization: Bearer eyJxxx");
        writer.writeEvent("AUTH_CONTEXT_REJECTED", payload);
        writer.close();

        assertEquals(0, session.getLinesWritten().get());
        assertTrue(session.getDroppedBySensitiveFilter().get() > 0);
    }

    @Test
    void writeEvent_shouldRollFileWhenMaxFileSizeReached(@TempDir Path tempDir) throws Exception {
        AuthObservabilityProperties props = properties(tempDir);
        AuthObservabilitySession session = session(tempDir, "s3");
        AuthObservabilityWriter writer = new AuthObservabilityWriter(
                new ObjectMapper(), session, props);

        Map<String, Object> payload = Map.of("x", "y");
        for (int i = 0; i < 10; i++) {
            writer.writeEvent("EVENT", payload);
        }
        writer.close();

        assertTrue(Files.exists(tempDir.resolve("s3").resolve("auth-0.jsonl")));
        assertTrue(Files.exists(tempDir.resolve("s3").resolve("auth-1.jsonl")));
    }

    @Test
    void writeEvent_shouldStopAfterMaxBytes(@TempDir Path tempDir) throws Exception {
        AuthObservabilityProperties props = properties(tempDir);
        props.getAuth().setMaxBytesPerSession(50L);
        AuthObservabilitySession session = session(tempDir, "s4");
        AuthObservabilityWriter writer = new AuthObservabilityWriter(
                new ObjectMapper(), session, props);

        Map<String, Object> payload = Map.of("x", "long-value-to-exceed-budget-quickly");
        for (int i = 0; i < 10; i++) {
            writer.writeEvent("EVENT", payload);
        }
        writer.close();

        assertEquals("MAX_BYTES", session.getStoppedReason());
        assertTrue(session.getDroppedByCapacity().get() > 0);
    }
}
