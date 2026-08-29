package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.config.PromptHotPushIndex;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 热推送预检索引必须跟着 classpath 权威字段走，避免两份清单各改各的。 */
class PromptHotPushIndexConsistencyTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)\\}\\}");

    @Test
    void hotPushIndex_shouldMatchAuthorityTextFieldsAndPlaceholders() {
        PromptHotPushIndex index = PromptHotPushIndex.shared();
        PromptAuthority authority = PromptAuthority.shared();

        assertEquals("agent-llm", index.configType());
        assertEquals(new LinkedHashSet<>(authority.textFieldNames()), new LinkedHashSet<>(index.textFields()));

        for (String field : index.textFields()) {
            LinkedHashSet<String> fromFile = new LinkedHashSet<>();
            Matcher matcher = PLACEHOLDER.matcher(authority.expectedText(field));
            while (matcher.find()) {
                fromFile.add(matcher.group(1));
            }
            assertEquals(fromFile, new LinkedHashSet<>(index.requiredPlaceholders(field)),
                    "占位符清单与权威文件不一致: " + field);
        }
        assertTrue(index.textFields().contains("toolSummarySystemPrompt"));
    }
}
