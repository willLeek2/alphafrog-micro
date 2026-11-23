package world.willfrog.agentlangchain.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.MarketQueryTexts;
import world.willfrog.agent.platform.service.ToolDescriptionTexts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketQueryVisibilityTest {

    @AfterEach
    void clearContext() {
        AgentContext.clear();
    }

    @Test
    void adminDescriptionKeepsMarketStepsAndOmitsRejectionWording() {
        String description = ToolDescriptionTexts.require("executeQuery");
        assertTrue(description.contains("market_tables"));
        assertTrue(description.contains("market_schema"));
        assertTrue(description.contains("market_db"));
        assertFalse(description.contains("没有该标记时"));
        assertFalse(description.contains("普通用户看到的是同一份说明"));
    }

    @Test
    void hiddenRunDropsExecuteQueryAndRewritesStockSearch() {
        AgentContext.setMarketQueryVisible(false);
        List<ToolSpecification> visible = MarketQueryVisibility.apply(List.of(
                ToolSpecification.builder().name("executeQuery").description("原说明").build(),
                ToolSpecification.builder().name("searchStock").description("原说明").build(),
                ToolSpecification.builder().name("executePython").description("保持").build()));

        assertTrue(visible.stream().noneMatch(spec -> "executeQuery".equals(spec.name())));
        String stock = visible.get(0).description();
        assertFalse(containsMarketWords(stock));
        assertFalse(stock.contains("executeQuery"));
        assertTrue(stock.contains("关键词"));
        assertTrue(visible.get(1).description().equals("保持"));
        assertFalse(containsMarketWords(MarketQueryTexts.hiddenCapability("searchStock")));
    }

    @Test
    void unsetOrAdminRunKeepsOriginalDescriptions() {
        List<ToolSpecification> original = List.of(
                ToolSpecification.builder().name("executeQuery").description("原说明").build());
        assertTrue(MarketQueryVisibility.apply(original).get(0).description().equals("原说明"));
        AgentContext.setMarketQueryVisible(true);
        assertTrue(MarketQueryVisibility.apply(original).get(0).description().equals("原说明"));
    }

    private static boolean containsMarketWords(String text) {
        return text.contains("market_db")
                || text.contains("market_tables")
                || text.contains("market_schema")
                || text.contains("行情库")
                || text.contains("管理员");
    }
}
