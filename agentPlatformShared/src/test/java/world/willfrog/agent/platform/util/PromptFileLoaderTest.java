package world.willfrog.agent.platform.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptFileLoaderTest {

    @Test
    void loadExistingFileShouldReturnContent() {
        // Maven test resources 路径：agentPlatformShared/src/test/resources/...
        String content = PromptFileLoader.load("test/prompt-file-loader-fixture.txt");
        assertEquals("hello from fixture\n", content);
    }

    @Test
    void loadMissingFileShouldReturnEmptyString() {
        String content = PromptFileLoader.load("test/this-file-does-not-exist.txt");
        assertEquals("", content);
    }

    @Test
    void loadBlankPathShouldReturnEmptyString() {
        assertEquals("", PromptFileLoader.load(""));
        assertEquals("", PromptFileLoader.load("   "));
        assertEquals("", PromptFileLoader.load(null));
    }
}
