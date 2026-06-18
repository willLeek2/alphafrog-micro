package world.willfrog.agent.service.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspacePathResolverTest {

    @TempDir
    Path tempRoot;

    WorkspacePathResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WorkspacePathResolver(
                tempRoot.resolve("workspace").toString(),
                tempRoot.resolve("datasets").toString()
        );
    }

    @Test
    void sanitizeUsername_normal_returnsStableName() {
        assertEquals("alice", resolver.sanitizeUsername("alice"));
        assertEquals("alice_2024", resolver.sanitizeUsername("Alice-2024"));
    }

    @Test
    void sanitizeUsername_stripPathTraversalChars() {
        // 路径分隔符必须被 strip
        assertFalse(resolver.sanitizeUsername("ali/ce").contains("/"));
        assertFalse(resolver.sanitizeUsername("ali\\ce").contains("\\"));
        assertFalse(resolver.sanitizeUsername("al:i:ce").contains(":"));
    }

    @Test
    void sanitizeUsername_mixedAsciiAndChinese_fallsBackToAsciiPart() {
        // alice王 → strip 后保留 alice
        String result = resolver.sanitizeUsername("alice王");
        assertTrue(result.startsWith("alice"));
    }

    @Test
    void sanitizeUsername_emptyAfterStrip_fallsBackToHash() {
        String result = resolver.sanitizeUsername("王");
        // 全中文 strip 后无安全字符 → hash fallback
        assertTrue(result.startsWith("u"));
        assertEquals(16, result.length());
    }

    @Test
    void sanitizeUsername_reservedName_fallsBackToHash() {
        String result = resolver.sanitizeUsername("admin");
        // admin 是保留名 → hash fallback
        assertTrue(result.startsWith("u"));
    }

    @Test
    void sanitizeUsername_truncateAt32() {
        String longName = "a".repeat(64);
        String result = resolver.sanitizeUsername(longName);
        assertTrue(result.length() <= 32);
    }

    @Test
    void resolveUserDir_validInput_returnsPath() {
        Path userDir = resolver.resolveUserDir(123L, "alice");
        assertTrue(userDir.toString().contains("123_alice"));
    }

    @Test
    void resolveUserDir_zeroUserId_throws() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveUserDir(0L, "alice"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveUserDir(-1L, "alice"));
    }

    @Test
    void resolveUserDir_nullUsername_throws() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveUserDir(1L, null));
    }

    @Test
    void resolveRunDir_validInput_returnsNestedPath() {
        Path runDir = resolver.resolveRunDir("run-abc", 123L, "alice");
        assertTrue(runDir.toString().contains("123_alice"));
        assertTrue(runDir.toString().contains("runs"));
        assertTrue(runDir.toString().contains("run-abc"));
    }

    @Test
    void validateRelativePath_traversalRejected() {
        Path base = tempRoot.resolve("workspace/123_alice/runs/run-1");
        assertThrows(SecurityException.class, () -> resolver.validateRelativePath(base, "../secret.json"));
        assertThrows(SecurityException.class, () -> resolver.validateRelativePath(base, "/etc/passwd"));
        assertThrows(SecurityException.class, () -> resolver.validateRelativePath(base, "sub/../../../etc"));
    }

    @Test
    void resolveDatasetRef_validId_returnsUnderDatasetPath() {
        Path ref = resolver.resolveDatasetRef("stock-000001.SZ-20240101-20240131-abc");
        assertTrue(ref.toString().contains("datasets"));
        assertTrue(ref.toString().endsWith("stock-000001.SZ-20240101-20240131-abc"));
    }

    @Test
    void resolveDatasetRef_blankId_throws() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveDatasetRef(""));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveDatasetRef(null));
    }
}
