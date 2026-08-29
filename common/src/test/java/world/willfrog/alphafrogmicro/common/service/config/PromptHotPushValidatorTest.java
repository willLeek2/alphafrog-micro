package world.willfrog.alphafrogmicro.common.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.config.PromptHotPushIndex;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigValidationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptHotPushValidatorTest {

    private static final String EMPTY_DIGEST =
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final PromptHotPushValidator validator = PromptHotPushValidator.shared();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void index_shouldDescribeOverlayTypeAndToolNames() {
        PromptHotPushIndex index = validator.index();
        assertEquals("agent-prompt-overlay", index.configType());
        assertEquals("agent-prompt-overlay.json", index.dataId());
        assertEquals("alphafrog-config", index.group());
        assertTrue(index.toolNames().contains("executePython"));
        assertTrue(index.allowedToolNames().contains("executePython"));
        assertTrue(index.textFields().contains("todoRetryContextInstruction"));
        assertTrue(index.allowedPromptKeys().contains("todoRetryContextInstruction"));
    }

    @Test
    void formatVersionNotOne_shouldReject() {
        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", 2);
        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> validator.validateRoot(root));
        assertTrue(error.getMessage().contains("formatVersion"));

        ObjectNode missing = mapper.createObjectNode();
        assertThrows(ConfigValidationException.class, () -> validator.validateRoot(missing));
    }

    @Test
    void unknownTopLevelKey_shouldReject() {
        ObjectNode root = overlay();
        root.put("defaultEndpoint", "openrouter");
        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> validator.validateRoot(root));
        assertTrue(error.getMessage().contains("未知顶层字段"));
    }

    @Test
    void unknownPromptField_shouldReject() {
        ObjectNode root = overlay();
        root.putObject("prompts").put("notAPromptField", "x");
        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> validator.validateRoot(root));
        assertTrue(error.getMessage().contains("未知字段"));
    }

    @Test
    void unknownToolName_shouldReject() {
        ObjectNode root = overlay();
        root.putObject("toolDescriptions").put("notARegisteredTool", "说明正文");
        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> validator.validateRoot(root));
        assertTrue(error.getMessage().contains("未知字段"));
        assertTrue(error.getMessage().contains("notARegisteredTool"));
    }

    @Test
    void missingPlaceholder_shouldReject() {
        ObjectNode root = overlay();
        root.putObject("prompts").put("todoRetryContextInstruction", "没有占位符的覆盖正文");
        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> validator.validateRoot(root));
        assertTrue(error.getMessage().contains("todoRetryContextInstruction"));
        assertTrue(error.getMessage().contains("{{toolName}}"));
    }

    @Test
    void completePlaceholders_shouldPass() {
        ObjectNode root = overlay();
        root.putObject("prompts").put("todoRetryContextInstruction",
                "{{toolName}} {{toolSafety}} {{failureCategory}} {{failureSummary}} {{previousArguments}}");
        validator.validateRoot(root);
    }

    @Test
    void fileReference_shouldReject() {
        ObjectNode root = overlay();
        root.putObject("prompts").put("todoRetryContextInstruction",
                "file:prompts/todo/todo_retry_context.txt");
        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> validator.validateRoot(root));
        assertTrue(error.getMessage().contains("文件引用"));
    }

    @Test
    void extraPlaceholder_shouldPass() {
        ObjectNode root = overlay();
        root.putObject("prompts").put("todoRetryContextInstruction",
                "{{toolName}} {{toolSafety}} {{failureCategory}} {{failureSummary}} {{previousArguments}} {{extraHint}}");
        validator.validateRoot(root);
    }

    @Test
    void invalidJson_shouldReject() {
        assertThrows(ConfigValidationException.class, () -> validator.validateContentJson("{"));
    }

    @Test
    void canonicalDigest_shouldBeStableForSameContent() {
        String left = "{\"formatVersion\":1,\"prompts\":{\"b\":\"two\",\"a\":\"one\"},"
                + "\"toolDescriptions\":{\"executePython\":\"py\"}}";
        String right = "{\"toolDescriptions\":{\"executePython\":\"py\"},"
                + "\"prompts\":{\"a\":\"one\",\"b\":\"two\"},\"formatVersion\":1}";
        assertEquals(validator.canonicalDigest(left), validator.canonicalDigest(right));
        assertTrue(validator.canonicalDigest(left).startsWith("sha256:"));
    }

    @Test
    void emptyOverlay_shouldHaveDefinedDigest() {
        assertEquals(EMPTY_DIGEST, validator.canonicalDigest(null));
        assertEquals(EMPTY_DIGEST, validator.canonicalDigest(""));
        assertEquals(EMPTY_DIGEST, validator.canonicalDigest("{}"));
        assertEquals(EMPTY_DIGEST, validator.canonicalDigest("{\"formatVersion\":1}"));
    }

    @Test
    void diffPromptFields_shouldListChangedKeys() {
        String from = "{\"prompts\":{\"todoRetryContextInstruction\":\"a\"}}";
        String to = "{\"prompts\":{\"todoRetryContextInstruction\":\"b\",\"toolSummarySystemPrompt\":\"s\"}}";
        List<String> changed = validator.diffPromptFields(from, to);
        assertTrue(changed.contains("todoRetryContextInstruction"));
        assertTrue(changed.contains("toolSummarySystemPrompt"));
    }

    @Test
    void diffToolDescriptions_shouldListChangedKeys() {
        String from = "{\"toolDescriptions\":{\"executePython\":\"old\"}}";
        String to = "{\"toolDescriptions\":{\"executePython\":\"new\",\"searchWeb\":\"web\"}}";
        List<String> changed = validator.diffToolDescriptions(from, to);
        assertTrue(changed.contains("executePython"));
        assertTrue(changed.contains("searchWeb"));
        List<String> overlay = validator.diffOverlayFields(from, to);
        assertTrue(overlay.contains("executePython"));
        assertTrue(overlay.contains("searchWeb"));
    }

    private ObjectNode overlay() {
        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", 1);
        return root;
    }
}
