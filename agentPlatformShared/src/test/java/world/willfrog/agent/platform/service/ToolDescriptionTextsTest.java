package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.util.PromptFileLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolDescriptionTextsTest {

    @Test
    void require_shouldLoadExecutePythonFromExistingAuthorityFile() {
        String fromIndex = ToolDescriptionTexts.require("executePython");
        String fromFile = PromptFileLoader.load("prompts/python/execute_python_tool_description.txt")
                .replaceAll("\n+$", "");
        assertEquals(fromFile, fromIndex);
        assertFalse(fromIndex.isBlank());
    }

    @Test
    void require_shouldFailWhenToolUnknown() {
        assertThrows(PromptConfigurationException.class, () -> ToolDescriptionTexts.require("notARealTool"));
    }
}
