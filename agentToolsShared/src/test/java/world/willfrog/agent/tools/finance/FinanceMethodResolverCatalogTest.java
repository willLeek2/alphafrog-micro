package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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

    @Test
    void entriesCarrySpecDigestMatchingIndex() throws Exception {
        FinanceMethodResolverCatalog catalog = new FinanceMethodResolverCatalog(objectMapper);
        List<Map<String, Object>> index;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("finance/method-specs/v1/index.json")) {
            assertNotNull(is);
            index = objectMapper.readValue(is, new TypeReference<>() {});
        }
        assertEquals(3, catalog.getEntries().size());
        for (FinanceMethodResolverCatalog.ResolverCatalogEntry entry : catalog.getEntries()) {
            assertNotNull(entry.specDigest());
            assertTrue(entry.specDigest().startsWith("sha256:"));
            Map<String, Object> matched = index.stream()
                    .filter(m -> entry.methodId().equals(m.get("methodId"))
                            && entry.version().equals(m.get("version")))
                    .findFirst()
                    .orElse(null);
            assertNotNull(matched, "index should contain " + entry.methodId());
            assertEquals(matched.get("specDigest"), entry.specDigest(),
                    "resolver catalog specDigest must match index triple");
        }
    }

    @Test
    void compactCatalogTextContainsSpecDigest() {
        FinanceMethodResolverCatalog catalog = new FinanceMethodResolverCatalog(objectMapper);
        String text = catalog.getCompactCatalogText();
        for (FinanceMethodResolverCatalog.ResolverCatalogEntry entry : catalog.getEntries()) {
            assertTrue(text.contains(entry.specDigest()),
                    "compact text must contain digest for " + entry.methodId());
        }
    }
}
