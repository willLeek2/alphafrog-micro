package world.willfrog.agentlangchain.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPromptCatalogVisibilityTest {

    private static final String AUTHORITATIVE = "改用 executeQuery 查询行情库里的股票表";

    @AfterEach
    void clearContext() {
        AgentContext.clear();
    }

    @Test
    void hiddenRunReplacesAuthoritativeStockSearchDescription() {
        AgentContext.setMarketQueryVisible(false);
        PromptText actual = ToolPromptCatalog.modelDescription(authoritativeText(), "searchStock");

        assertFalse(actual.text().contains("executeQuery"));
        assertFalse(actual.text().contains("行情库"));
        assertTrue(actual.text().contains("关键词"));
        assertEquals(authoritativeText().sources(), actual.sources());
    }

    @Test
    void adminRunKeepsAuthoritativeDescriptionAndItsSource() {
        AgentContext.setMarketQueryVisible(true);
        PromptText authoritative = authoritativeText();
        PromptText actual = ToolPromptCatalog.modelDescription(authoritative, "searchStock");

        assertEquals(authoritative, actual);
    }

    @Test
    void hiddenRunKeepsOtherToolDescriptions() {
        AgentContext.setMarketQueryVisible(false);
        PromptText authoritative = authoritativeText();
        PromptText actual = ToolPromptCatalog.modelDescription(authoritative, "executePython");

        assertEquals(authoritative, actual);
    }

    private static PromptText authoritativeText() {
        return PromptText.instruction(AUTHORITATIVE, new PromptVersionSource(
                "beta", "scope", "prompt", 1, "TOOL_DESCRIPTION",
                null, null, null, null, null, null, null));
    }
}
