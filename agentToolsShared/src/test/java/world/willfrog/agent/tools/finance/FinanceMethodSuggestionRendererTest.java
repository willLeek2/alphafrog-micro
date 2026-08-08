package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link FinanceMethodSuggestionRenderer} 对候选建议的补全逻辑。
 */
class FinanceMethodSuggestionRendererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceMethodSpecCatalog specCatalog = new FinanceMethodSpecCatalog(objectMapper);
    private final FinanceMethodKnowledgeCatalog knowledgeCatalog = new FinanceMethodKnowledgeCatalog(objectMapper);
    private final FinanceMethodSuggestionRenderer renderer = new FinanceMethodSuggestionRenderer(
            specCatalog, knowledgeCatalog, objectMapper);

    @Test
    void shouldRenderDefinitionAndRequiredExecutionInputs() {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        Map<String, Object> suggestion = renderer.render(
                cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                "用户希望计算复合增长率", List.of("这几年"),
                List.of("希望从哪个交易日算到哪个交易日？"), null);

        assertEquals(cagr.getMethodId(), suggestion.get("methodId"));
        assertEquals(cagr.getDisplayName(), suggestion.get("displayName"));
        assertEquals(cagr.getDefinition(), suggestion.get("definition"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) suggestion.get("requiredExecutionInputs");
        assertEquals(3, inputs.size());
        assertEquals("beginningValue", inputs.get(0).get("name"));
        assertNotNull(inputs.get(0).get("meaning"));
        assertEquals("periods", inputs.get(2).get("name"));
    }

    @Test
    void shouldResolveSourceRefsToKnowledgeSection() {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        Map<String, Object> suggestion = renderer.render(
                cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                "匹配", List.of(), List.of(), null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) suggestion.get("sourceRefs");
        assertFalse(refs.isEmpty());
        assertTrue(refs.get(0).containsKey("section"));
    }

    @Test
    void shouldNotRenderSampleWithoutTargetEnvironment() {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        Map<String, Object> suggestion = renderer.render(
                cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                "匹配", List.of(), List.of(), null);
        assertNull(suggestion.get("sample"));
        assertNull(suggestion.get("library"));
    }

    @Test
    void shouldRenderSampleWhenEnvironmentCompatible() {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceMethodSuggestionRenderer.TargetEnvironment env =
                new FinanceMethodSuggestionRenderer.TargetEnvironment(
                        "env-1",
                        List.of(new FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi(
                                "alphafrog_finance", "1.0.3", "1.0")));
        Map<String, Object> suggestion = renderer.render(
                cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                "匹配", List.of(), List.of(), env);

        @SuppressWarnings("unchecked")
        Map<String, Object> library = (Map<String, Object>) suggestion.get("library");
        assertNotNull(library);
        assertEquals("alphafrog_finance", library.get("package"));
        assertEquals(true, library.get("available"));

        String sample = (String) suggestion.get("sample");
        assertNotNull(sample);
        assertTrue(sample.contains("cagr"));
        assertTrue(sample.contains("beginningValue"));
    }

    @Test
    void shouldMarkLibraryUnavailableForIncompatibleApi() {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceMethodSuggestionRenderer.TargetEnvironment env =
                new FinanceMethodSuggestionRenderer.TargetEnvironment(
                        "env-1",
                        List.of(new FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi(
                                "alphafrog_finance", "3.0.0", "3.0")));
        Map<String, Object> suggestion = renderer.render(
                cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                "匹配", List.of(), List.of(), env);

        @SuppressWarnings("unchecked")
        Map<String, Object> library = (Map<String, Object>) suggestion.get("library");
        assertNotNull(library);
        assertNull(library.get("available"));
        assertNull(suggestion.get("sample"));
    }
}
