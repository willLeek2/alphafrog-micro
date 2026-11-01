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







    private static void touch(Path file, long millis) throws Exception {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                java.time.Instant.now().toEpochMilli() + millis));
    }




}
