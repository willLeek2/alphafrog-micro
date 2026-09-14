package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link FinanceMethodKnowledgeCatalog} 对方法知识目录的解析。
 */
class FinanceMethodKnowledgeCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceMethodKnowledgeCatalog catalog = new FinanceMethodKnowledgeCatalog(objectMapper);

    @Test
    void shouldResolveCagrKnowledge() {
        FinanceMethodSpec cagr = new FinanceMethodSpecCatalog(objectMapper)
                .findByMethodId("finance.growth.cagr").orElseThrow();
        Optional<FinanceMethodKnowledgeCatalog.KnowledgeEntry> entry =
                catalog.resolve(cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest());
        assertTrue(entry.isPresent());
        assertEquals("agent_guides/finance_method_knowledge.md", entry.get().document());
        assertEquals("#finance-growth-cagr", entry.get().section());
    }

    @Test
    void shouldResolveAllThreeMethods() {
        FinanceMethodSpecCatalog specCatalog = new FinanceMethodSpecCatalog(objectMapper);
        for (FinanceMethodSpec spec : specCatalog.listAll()) {
            Optional<FinanceMethodKnowledgeCatalog.KnowledgeEntry> entry =
                    catalog.resolve(spec.getMethodId(), spec.getVersion(), spec.getSpecDigest());
            assertTrue(entry.isPresent(), spec.getMethodId() + " should have knowledge entry");
        }
    }
}
