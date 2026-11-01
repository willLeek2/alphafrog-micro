package world.willfrog.agent.platform.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptFileLoaderTest {


    @Test
    void loadMissingFileShouldReturnEmptyString() {
        String content = PromptFileLoader.load("test/this-file-does-not-exist.txt");
        assertEquals("", content);
    }

}
