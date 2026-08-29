package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖文档文件加载器：应用、热更新、缺失回落、坏文档拒绝且保持当前生效版本。 */
class PromptOverlayLoaderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptAuthority authority = PromptAuthority.shared();

    @AfterEach
    void cleanUp() {
        authority.clearOverlay();
    }

    private PromptOverlayLoader newLoader(String overlayFile) {
        PromptOverlayLoader loader = new PromptOverlayLoader(mapper);
        ReflectionTestUtils.setField(loader, "overlayFile", overlayFile);
        return loader;
    }

    private String overlayJson(String promptSuffix, String toolSuffix) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", 1);
        root.put("baseBundleDigest", authority.baseBundleDigest());
        if (promptSuffix != null) {
            root.putObject("prompts")
                    .put("agentRunSystemPrompt", authority.prompts().getAgentRunSystemPrompt() + promptSuffix);
        }
        if (toolSuffix != null) {
            root.putObject("toolDescriptions")
                    .put("searchWeb", authority.requireToolDescription("searchWeb") + toolSuffix);
        }
        return mapper.writeValueAsString(root);
    }

    @Test
    void loadAppliesOverlayFromFile() throws Exception {
        Path file = tempDir.resolve("agent-prompt-overlay.json");
        Files.writeString(file, overlayJson("\n第一版覆盖", null), StandardCharsets.UTF_8);
        PromptOverlayLoader loader = newLoader(file.toString());

        loader.load();

        assertTrue(loader.current().applied());
        assertTrue(authority.prompts().getAgentRunSystemPrompt().endsWith("第一版覆盖"));
        assertEquals(1, loader.current().promptEntries());
        assertEquals(0, loader.current().toolDescriptionEntries());
        assertEquals(authority.bundleDigest(), loader.current().effectiveBundleDigest());
        assertEquals(authority.baseBundleDigest(), loader.current().baseBundleDigest());
    }

    @Test
    void loadAppliesToolDescriptionOverlay() throws Exception {
        Path file = tempDir.resolve("agent-prompt-overlay.json");
        Files.writeString(file, overlayJson(null, " 工具说明覆盖"), StandardCharsets.UTF_8);
        PromptOverlayLoader loader = newLoader(file.toString());

        loader.load();

        assertTrue(loader.current().applied());
        assertTrue(authority.requireToolDescription("searchWeb").endsWith("工具说明覆盖"));
        assertEquals(1, loader.current().toolDescriptionEntries());
    }

    @Test
    void missingFileKeepsDefaultVersion() {
        PromptOverlayLoader loader = newLoader(tempDir.resolve("absent.json").toString());

        loader.load();

        assertFalse(loader.current().applied());
        assertEquals(authority.baseBundleDigest(), authority.bundleDigest());
    }

    @Test
    void deletedFileFallsBackToDefaultVersion() throws Exception {
        Path file = tempDir.resolve("agent-prompt-overlay.json");
        Files.writeString(file, overlayJson("\n会被撤下的覆盖", null), StandardCharsets.UTF_8);
        PromptOverlayLoader loader = newLoader(file.toString());
        loader.load();
        assertTrue(loader.current().applied());

        Files.delete(file);
        loader.load();

        assertFalse(loader.current().applied());
        assertEquals(authority.baseBundleDigest(), authority.bundleDigest());
        assertFalse(authority.prompts().getAgentRunSystemPrompt().endsWith("会被撤下的覆盖"));
    }

    @Test
    void reloadPicksUpUpdatedDocument() throws Exception {
        Path file = tempDir.resolve("agent-prompt-overlay.json");
        Files.writeString(file, overlayJson("\n第一版覆盖", null), StandardCharsets.UTF_8);
        PromptOverlayLoader loader = newLoader(file.toString());
        loader.load();

        Files.writeString(file, overlayJson("\n第二版覆盖", null), StandardCharsets.UTF_8);
        loader.load();

        assertTrue(authority.prompts().getAgentRunSystemPrompt().endsWith("第二版覆盖"));
        assertTrue(loader.current().applied());
    }

    @Test
    void invalidDocumentIsRejectedAndCurrentEffectiveRetained() throws Exception {
        Path file = tempDir.resolve("agent-prompt-overlay.json");
        Files.writeString(file, overlayJson("\n有效第一版", null), StandardCharsets.UTF_8);
        PromptOverlayLoader loader = newLoader(file.toString());
        loader.load();
        assertTrue(loader.current().applied());

        Files.writeString(file, "{\"formatVersion\": 1, \"prompts\": {\"noSuchField\": \"x\"}}", StandardCharsets.UTF_8);
        loader.load();

        assertTrue(authority.prompts().getAgentRunSystemPrompt().endsWith("有效第一版"));
        assertEquals(1L, loader.overlayReloadFailureCount());
    }

    @Test
    void wrongFormatVersionIsRejected() throws Exception {
        Path file = tempDir.resolve("agent-prompt-overlay.json");
        Files.writeString(file, "{\"formatVersion\": 2, \"prompts\": {}}", StandardCharsets.UTF_8);
        PromptOverlayLoader loader = newLoader(file.toString());

        loader.load();

        assertFalse(loader.current().applied());
        assertEquals(1L, loader.overlayReloadFailureCount());
    }

    @Test
    void emptyOverlayDocumentKeepsDefaultVersion() throws Exception {
        Path file = tempDir.resolve("agent-prompt-overlay.json");
        Files.writeString(file, "{\"formatVersion\": 1}", StandardCharsets.UTF_8);
        PromptOverlayLoader loader = newLoader(file.toString());

        loader.load();

        assertFalse(loader.current().applied());
        assertEquals(authority.baseBundleDigest(), authority.bundleDigest());
        assertEquals(0L, loader.overlayReloadFailureCount());
    }

    @Test
    void blankPropertyIsNoOp() {
        PromptOverlayLoader loader = newLoader("");

        loader.load();

        assertFalse(loader.current().applied());
        assertEquals(authority.baseBundleDigest(), authority.bundleDigest());
    }
}
