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
    void canonicalDigest_shouldBeStableForSameContent() {
        String left = "{\"formatVersion\":1,\"prompts\":{\"b\":\"two\",\"a\":\"one\"},"
                + "\"toolDescriptions\":{\"executePython\":\"py\"}}";
        String right = "{\"toolDescriptions\":{\"executePython\":\"py\"},"
                + "\"prompts\":{\"a\":\"one\",\"b\":\"two\"},\"formatVersion\":1}";
        assertEquals(validator.canonicalDigest(left), validator.canonicalDigest(right));
        assertTrue(validator.canonicalDigest(left).startsWith("sha256:"));
    }




    private ObjectNode overlay() {
        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", 1);
        return root;
    }
}
