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

    @Test
    void packageNameMismatchIsNotCompatible() {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceMethodSuggestionRenderer.TargetEnvironment env =
                new FinanceMethodSuggestionRenderer.TargetEnvironment(
                        "env-1",
                        List.of(new FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi(
                                "other_package", "1.0.3", "1.0")));
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
