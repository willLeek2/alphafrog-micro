package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.prompt.PromptRunSelection;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运行时覆盖叠加权威默认版本的行为：生效切换、加载校验拒绝、回落与指纹留存。 */
class PromptAuthorityOverlayTest {

    private final PromptAuthority authority = PromptAuthority.shared();

    @AfterEach
    void cleanUp() {
        authority.clearOverlay();
    }

    @Test
    void overlayChangesEffectiveTextsAndDigest() {
        String baseText = authority.prompts().getAgentRunSystemPrompt();
        String baseDigest = authority.bundleDigest();
        String baseTool = authority.requireToolDescription("searchWeb");

        authority.applyOverlay(
                Map.of("agentRunSystemPrompt", baseText + "\n运行时覆盖标记"),
                Map.of("searchWeb", baseTool + " 运行时覆盖标记"));

        assertTrue(authority.prompts().getAgentRunSystemPrompt().endsWith("运行时覆盖标记"));
        assertTrue(authority.requireToolDescription("searchWeb").endsWith("运行时覆盖标记"));
        assertNotEquals(baseDigest, authority.bundleDigest());
        assertEquals(baseDigest, authority.baseBundleDigest());
    }

    @Test
    void clearOverlayRestoresDefaults() {
        String baseText = authority.prompts().getAgentRunSystemPrompt();
        authority.applyOverlay(Map.of("agentRunSystemPrompt", baseText + " X"), Map.of());
        assertNotEquals(baseText, authority.prompts().getAgentRunSystemPrompt());

        authority.clearOverlay();

        assertEquals(baseText, authority.prompts().getAgentRunSystemPrompt());
    }

    @Test
    void overlayKeepsProjectionValidationAgainstDefaults() {
        String baseText = authority.prompts().getAgentRunSystemPrompt();
        authority.applyOverlay(Map.of("agentRunSystemPrompt", baseText + " X"), Map.of());

        // 投影校验对着默认版本：默认正文的投影依然合法，覆盖不影响该语义。
        authority.validateText("agentRunSystemPrompt", baseText, "test projection");
        PromptConfigurationException e = assertThrows(PromptConfigurationException.class,
                () -> authority.validateText("agentRunSystemPrompt", baseText + " X", "test projection"));
        assertEquals("projection_mismatch", e.reason());
    }

    @Test
    void overlayRejectsUnknownFieldAndKeepsCurrentEffective() {
        String effectiveBefore = authority.prompts().getAgentRunSystemPrompt();
        String digestBefore = authority.bundleDigest();

        PromptConfigurationException e = assertThrows(PromptConfigurationException.class,
                () -> authority.applyOverlay(Map.of("noSuchPromptField", "text"), Map.of()));

        assertEquals("unknown_field", e.reason());
        assertEquals(effectiveBefore, authority.prompts().getAgentRunSystemPrompt());
        assertEquals(digestBefore, authority.bundleDigest());
    }

    @Test
    void overlayRejectsMissingPlaceholders() {
        String template = authority.prompts().getPlanningStrategyStage();
        assertTrue(template.contains("{{"), "基线模板应包含占位符");

        PromptConfigurationException e = assertThrows(PromptConfigurationException.class,
                () -> authority.applyOverlay(Map.of("planningStrategyStage", "没有占位符的替换文本"), Map.of()));

        assertEquals("overlay_placeholder_missing", e.reason());
    }

    @Test
    void overlayRejectsBlankTextAndUnresolvedFileReference() {
        assertEquals("blank", assertThrows(PromptConfigurationException.class,
                () -> authority.applyOverlay(Map.of("agentRunSystemPrompt", "  "), Map.of())).reason());
        assertEquals("unresolved_file_reference", assertThrows(PromptConfigurationException.class,
                () -> authority.applyOverlay(
                        Map.of("agentRunSystemPrompt", "file:prompts/agent/agent_run_system.txt"), Map.of())).reason());
    }

    @Test
    void overlayRejectsUnknownToolName() {
        assertEquals("tool_description_unknown", assertThrows(PromptConfigurationException.class,
                () -> authority.applyOverlay(Map.of(), Map.of("notARegisteredTool", "text"))).reason());
    }

    @Test
    void overlayRejectsInvalidCapabilityCatalogJson() {
        assertEquals("overlay_catalog_invalid", assertThrows(PromptConfigurationException.class,
                () -> authority.applyOverlay(Map.of("toolCapabilityCatalog", "不是 JSON"), Map.of())).reason());
    }

    @Test
    void overlayCapabilityCatalogOverrideChangesEffectiveCatalog() {
        String overridden = "{\"searchWeb\": \"覆盖后的能力说明\"}";

        authority.applyOverlay(Map.of("toolCapabilityCatalog", overridden), Map.of());

        assertEquals(overridden, authority.prompts().getToolCapabilityCatalog());
        assertNotEquals(authority.baseBundleDigest(), authority.bundleDigest());
    }

    @Test
    void digestHistoryKeepsFrozenSelectionsValidAcrossOverlayPush() {
        AgentPromptService promptService = new AgentPromptService(
                new AgentLlmProperties(), new AgentLlmLocalConfigLoader(new ObjectMapper()));
        PromptRunSelection frozen = promptService.snapshotPromptSelection("run-1", "user-1", null);
        promptService.validatePromptSelection(frozen);

        String baseText = authority.prompts().getAgentRunSystemPrompt();
        authority.applyOverlay(Map.of("agentRunSystemPrompt", baseText + " 覆盖一版"), Map.of());
        promptService.validatePromptSelection(frozen);

        authority.applyOverlay(Map.of("agentRunSystemPrompt", baseText + " 覆盖二版"), Map.of());
        promptService.validatePromptSelection(frozen);

        PromptRunSelection stale = new PromptRunSelection(
                PromptRunSelection.SCHEMA_VERSION,
                frozen.bundleVersion(),
                frozen.variant(),
                "sha256:0000",
                "sha256:0000",
                LocalDate.now());
        assertThrows(PromptConfigurationException.class, () -> promptService.validatePromptSelection(stale));
    }
}
