package world.willfrog.alphafrogmicro.frontend.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthObservabilityManagerTest {

    private AuthObservabilityProperties properties(Path tempDir) {
        AuthObservabilityProperties props = new AuthObservabilityProperties();
        props.setOutputRoot(tempDir.toString());
        return props;
    }

    @Test
    void enable_shouldCreateSessionWithDefaults(@TempDir Path tempDir) {
        AuthObservabilityManager manager = new AuthObservabilityManager(
                new ObjectMapper(), properties(tempDir));

        AuthObservabilityManager.EnableResult result = manager.enable(
                "admin", "debug", null, null, false, true);

        assertTrue(result.isSuccess());
        assertNotNull(result.getSession());
        assertTrue(result.getSession().isEnabled());
        assertTrue(Files.exists(Path.of(result.getSession().getOutputDir(), "manifest.json")));
    }

    @Test
    void enable_withoutForce_shouldConflictWhenActiveSessionExists(@TempDir Path tempDir) {
        AuthObservabilityManager manager = new AuthObservabilityManager(
                new ObjectMapper(), properties(tempDir));
        manager.enable("admin", "first", null, null, false, true);

        AuthObservabilityManager.EnableResult result = manager.enable(
                "admin", "second", null, null, false, true);

        assertTrue(result.isConflict());
        assertFalse(result.isSuccess());
    }

    @Test
    void enable_withForce_shouldReplaceActiveSession(@TempDir Path tempDir) {
        AuthObservabilityManager manager = new AuthObservabilityManager(
                new ObjectMapper(), properties(tempDir));
        AuthObservabilitySession first = manager.enable("admin", "first", null, null, false, true).getSession();

        AuthObservabilityManager.EnableResult result = manager.enable(
                "admin", "second", null, null, true, true);

        assertTrue(result.isSuccess());
        assertFalse(first.isEnabled());
        assertNotNull(result.getSession().getDebugSessionId());
    }

    @Test
    void enable_withoutScopeAndWithoutForceAllUsers_shouldReject(@TempDir Path tempDir) {
        AuthObservabilityManager manager = new AuthObservabilityManager(
                new ObjectMapper(), properties(tempDir));

        AuthObservabilityManager.EnableResult result = manager.enable(
                "admin", "debug", null, null, false, false);

        assertFalse(result.isSuccess());
        assertFalse(result.isConflict());
        assertNotNull(result.getError());
    }

    @Test
    void disable_shouldCloseSessionAndStopWriter(@TempDir Path tempDir) {
        AuthObservabilityManager manager = new AuthObservabilityManager(
                new ObjectMapper(), properties(tempDir));
        AuthObservabilitySession session = manager.enable(
                "admin", "debug", null, null, false, true).getSession();

        boolean disabled = manager.disable(null, "ADMIN_DISABLED");

        assertTrue(disabled);
        assertFalse(session.isEnabled());
        assertEquals("ADMIN_DISABLED", session.getStoppedReason());
    }

    @Test
    void getActiveSession_shouldReturnNullAfterTtlExpiry(@TempDir Path tempDir) throws InterruptedException {
        AuthObservabilityProperties props = properties(tempDir);
        props.getAuth().setDefaultTtlSeconds(1);
        AuthObservabilityManager manager = new AuthObservabilityManager(
                new ObjectMapper(), props);
        manager.enable("admin", "debug", 1, null, false, true);

        Thread.sleep(1100);

        assertNull(manager.getActiveSession());
    }

    @Test
    void emit_shouldRespectScope_sampleUsers(@TempDir Path tempDir) {
        AuthObservabilityManager manager = new AuthObservabilityManager(
                new ObjectMapper(), properties(tempDir));
        AuthObservabilityScope scope = new AuthObservabilityScope();
        scope.setSampleUsers(java.util.List.of("alice"));
        AuthObservabilitySession session = manager.enable(
                "admin", "debug", null, scope, false, false).getSession();
        long linesBefore = session.getLinesWritten().get();

        manager.emitAuthContextRejected(
                "r1", "/api/foo", "GET", "bob", true, "HEADER",
                null, false, null, "NO_TOKEN", null, null, null);
        manager.emitAuthContextRejected(
                "r2", "/api/foo", "GET", "alice", true, "HEADER",
                null, false, null, "NO_TOKEN", null, null, null);

        assertEquals(1, session.getLinesWritten().get() - linesBefore);
    }

    @Test
    void hashToken_shouldReturnStablePrefix() {
        String hash1 = AuthObservabilityManager.hashToken("test-token-123");
        String hash2 = AuthObservabilityManager.hashToken("test-token-123");
        assertNotNull(hash1);
        assertEquals(hash1, hash2);
        assertTrue(hash1.length() <= 12);
    }
}
