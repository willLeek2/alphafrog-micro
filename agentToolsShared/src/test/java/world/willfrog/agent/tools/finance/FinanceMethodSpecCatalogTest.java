package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link FinanceMethodSpecCatalog} 能从构建产物加载 canonical JSON、复算摘要并建立索引。
 *
 * <p>测试依赖 Maven generate-resources 阶段生成的 {@code target/generated-resources/finance/method-specs/v1/}
 * 目录，该目录已通过 pom.xml 的 resource 配置加入测试 classpath。</p>
 */
class FinanceMethodSpecCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldLoadAllCanonicalSpecs() {
        FinanceMethodSpecCatalog catalog = new FinanceMethodSpecCatalog(objectMapper);
        List<FinanceMethodSpec> all = catalog.listAll();
        assertEquals(3, all.size(), "首批目录应包含 CAGR、年化波动率、夏普比率三个方法");
    }

    @Test
    void shouldFindByMethodId() {
        FinanceMethodSpecCatalog catalog = new FinanceMethodSpecCatalog(objectMapper);
        Optional<FinanceMethodSpec> spec = catalog.findByMethodId("finance.growth.cagr");
        assertTrue(spec.isPresent());
        assertEquals("复合年均增长率", spec.get().getDisplayName());
        assertEquals("1.0.0", spec.get().getVersion());
        assertEquals(3, spec.get().getParameters().size());
        assertNotNull(spec.get().getParameters().get("periods"));
    }

    @Test
    void shouldFindByExactTriple() {
        FinanceMethodSpecCatalog catalog = new FinanceMethodSpecCatalog(objectMapper);
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        Optional<FinanceMethodSpec> found = catalog.find(
                cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest());
        assertTrue(found.isPresent());
        assertEquals(cagr.getSpecDigest(), found.get().getSpecDigest());
    }

    @Test
    void shouldRejectUnknownDigest() {
        FinanceMethodSpecCatalog catalog = new FinanceMethodSpecCatalog(objectMapper);
        Optional<FinanceMethodSpec> found = catalog.find(
                "finance.growth.cagr", "1.0.0", "sha256:deadbeef");
        assertFalse(found.isPresent());
    }

    @Test
    void shouldRecomputeAndMatchSpecDigest() {
        // 如果摘要复算不一致，构造器会抛 IllegalStateException，本测试走到这里即说明一致。
        FinanceMethodSpecCatalog catalog = new FinanceMethodSpecCatalog(objectMapper);
        assertFalse(catalog.listAll().isEmpty());
        for (FinanceMethodSpec spec : catalog.listAll()) {
            assertTrue(spec.getSpecDigest().startsWith("sha256:"));
        }
    }
}
