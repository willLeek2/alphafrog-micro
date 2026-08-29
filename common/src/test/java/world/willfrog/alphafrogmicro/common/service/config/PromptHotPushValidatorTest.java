package world.willfrog.alphafrogmicro.common.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.config.PromptHotPushIndex;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigValidationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptHotPushValidatorTest {

    private final PromptHotPushValidator validator = PromptHotPushValidator.shared();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void index_shouldDescribeExistingAgentLlmConfigType() {
        PromptHotPushIndex index = validator.index();
        assertEquals("agent-llm", index.configType());
        assertEquals("agent-llm.json", index.dataId());
        assertEquals("alphafrog-config", index.group());
        assertTrue(index.textFields().contains("todoRetryContextInstruction"));
        assertFalse(index.requiredPlaceholders("todoRetryContextInstruction").isEmpty());
    }

    @Test
    void missingPlaceholder_shouldReject() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode prompts = root.putObject("prompts");
        prompts.put("todoRetryContextInstruction", "没有占位符的覆盖正文");

        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> validator.validateRoot(root));
        assertTrue(error.getMessage().contains("todoRetryContextInstruction"));
        assertTrue(error.getMessage().contains("{{toolName}}"));
    }

    @Test
    void unknownPromptField_shouldReject() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("prompts").put("notAPromptField", "x");

        ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> validator.validateRoot(root));
        assertTrue(error.getMessage().contains("未知字段"));
    }

    @Test
    void completePlaceholders_shouldPass() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode prompts = root.putObject("prompts");
        prompts.put("todoRetryContextInstruction",
                "{{toolName}} {{toolSafety}} {{failureCategory}} {{failureSummary}} {{previousArguments}}");

        validator.validateRoot(root);
    }

    @Test
    void fileReference_shouldSkipPlaceholderCheck() {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("prompts").put("todoRetryContextInstruction",
                "file:prompts/todo/todo_retry_context.txt");
        validator.validateRoot(root);
    }

    @Test
    void noPromptsSection_shouldPass() {
        ObjectNode root = mapper.createObjectNode();
        root.put("defaultEndpoint", "openrouter");
        validator.validateRoot(root);
    }

    @Test
    void diffPromptFields_shouldListChangedKeys() throws Exception {
        String from = "{\"prompts\":{\"todoRetryContextInstruction\":\"a\"}}";
        String to = "{\"prompts\":{\"todoRetryContextInstruction\":\"b\",\"toolSummarySystemPrompt\":\"s\"}}";
        List<String> changed = validator.diffPromptFields(from, to);
        assertTrue(changed.contains("todoRetryContextInstruction"));
        assertTrue(changed.contains("toolSummarySystemPrompt"));
    }

    @Test
    void invalidJson_shouldReject() {
        assertThrows(ConfigValidationException.class, () -> validator.validateContentJson("{"));
    }
}
