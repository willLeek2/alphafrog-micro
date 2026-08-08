package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link FinanceMethodResolverCatalog} 读取 resolver-catalog.json、构造稳定顺序目录文本并暴露摘要。
 *
 * <p>测试依赖 Maven generate-resources 阶段生成的 {@code target/generated-resources/finance/method-specs/v1/resolver-catalog.json}。</p>
 */
class FinanceMethodResolverCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldLoadCatalogAndComputeDigest() {
        FinanceMethodResolverCatalog catalog = new FinanceMethodResolverCatalog(objectMapper);
        assertEquals(3, catalog.getEntries().size());
        assertNotNull(catalog.getCatalogDigest());
        assertTrue(catalog.getCatalogDigest().startsWith("sha256:"));
    }

    @Test
    void shouldExposePromptVersion() {
        FinanceMethodResolverCatalog catalog = new FinanceMethodResolverCatalog(objectMapper);
        assertNotNull(catalog.getPromptVersion());
        assertTrue(catalog.getPromptVersion().startsWith("sha256:"));
    }

    @Test
    void compactCatalogTextShouldBeStableAndContainMethods() {
        FinanceMethodResolverCatalog catalog = new FinanceMethodResolverCatalog(objectMapper);
        String text = catalog.getCompactCatalogText();
        assertTrue(text.contains("finance.growth.cagr"));
        assertTrue(text.contains("finance.risk.annualized_volatility"));
        assertTrue(text.contains("finance.risk.sharpe_ratio"));
        assertTrue(text.contains("复合年均增长率"));
        assertTrue(text.contains("alias") || text.contains("别名"));
    }

    @Test
    void renderSystemPromptShouldSubstituteCatalog() {
        FinanceMethodResolverCatalog catalog = new FinanceMethodResolverCatalog(objectMapper);
        String prompt = catalog.renderSystemPrompt();
        assertFalse(prompt.contains("{{catalog}}"));
        assertTrue(prompt.contains("finance.growth.cagr"));
    }
}
