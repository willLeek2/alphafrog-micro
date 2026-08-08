package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖 {@link FinanceResultModelProjector} 的投影安全规则。
 */
class FinanceResultModelProjectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceMethodSpecCatalog catalog = new FinanceMethodSpecCatalog(objectMapper);
    private final FinanceResultModelProjector projector = new FinanceResultModelProjector(catalog);

    @Test
    void shouldProjectCagrWithScalarValues() {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                        0.1246, "ratio",
                        Map.of("beginningValue", 100.0, "endingValue", 160.0, "periods", 4),
                        null, true);

        Optional<FinanceResultModelProjector.FinanceResultProjection> result = projector.project(input);
        assertTrue(result.isPresent());
        assertEquals(cagr.getDisplayName(), result.get().method());
        assertEquals(0.1246, result.get().value());
        assertEquals("ratio", result.get().unit());
        assertTrue(result.get().howCalculated().contains("100.0"));
        assertTrue(result.get().howCalculated().contains("160.0"));
        assertTrue(result.get().howCalculated().contains("4"));
    }

    @Test
    void arrayParameterShouldRenderAsLengthSummary() {
        FinanceMethodSpec vol = catalog.findByMethodId("finance.risk.annualized_volatility").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        vol.getMethodId(), vol.getVersion(), vol.getSpecDigest(),
                        0.15, "ratio_per_annum",
                        Map.of("returns", List.of(0.01, -0.02, 0.015), "periodsPerYear", 252),
                        null, true);

        Optional<FinanceResultModelProjector.FinanceResultProjection> result = projector.project(input);
        assertTrue(result.isPresent());
        assertTrue(result.get().howCalculated().contains("3 个周期收益率样本"),
                "Array parameter should render as length summary, not contents");
        assertFalse(result.get().howCalculated().contains("0.01"),
                "Array contents must not leak");
    }

    @Test
    void arrayParameterGivenScalarReturnsEmpty() {
        FinanceMethodSpec vol = catalog.findByMethodId("finance.risk.annualized_volatility").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        vol.getMethodId(), vol.getVersion(), vol.getSpecDigest(),
                        0.15, "ratio_per_annum",
                        Map.of("returns", 0.01, "periodsPerYear", 252),
                        null, true);
        assertFalse(projector.project(input).isPresent());
    }

    @Test
    void scalarParameterGivenCollectionReturnsEmpty() {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                        0.1, "ratio",
                        Map.of("beginningValue", 100.0, "endingValue", 160.0, "periods", List.of(1, 2, 3)),
                        null, true);
        assertFalse(projector.project(input).isPresent());
    }

    @Test
    void missingOptionalWithDefaultShouldUseDefault() {
        FinanceMethodSpec sharpe = catalog.findByMethodId("finance.risk.sharpe_ratio").orElseThrow();
        // 只提供 returns，其他使用默认值
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        sharpe.getMethodId(), sharpe.getVersion(), sharpe.getSpecDigest(),
                        1.2, "ratio_per_annum",
                        Map.of("returns", List.of(0.01, -0.02, 0.015)),
                        null, true);

        Optional<FinanceResultModelProjector.FinanceResultProjection> result = projector.project(input);
        assertTrue(result.isPresent());
        assertTrue(result.get().howCalculated().contains("无风险利率"),
                "Defaulted riskFreeRate should appear in narrative via its label");
    }

    @Test
    void missingRequiredParameterShouldNotBeProjectable() {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                        0.1, "ratio",
                        Map.of("beginningValue", 100.0, "endingValue", 160.0),
                        null, true);
        assertFalse(projector.project(input).isPresent());
    }

    @Test
    void missingRequiredParameterNotInNarrativeReturnsEmpty() {
        FinanceMethodSpecCatalog mockCatalog = mock(FinanceMethodSpecCatalog.class);
        FinanceResultModelProjector localProjector = new FinanceResultModelProjector(mockCatalog);

        Map<String, FinanceMethodSpec.FinanceParameter> params = new LinkedHashMap<>();
        params.put("visible", FinanceMethodSpec.FinanceParameter.builder()
                .name("visible").type("number").required(true).build());
        params.put("hidden", FinanceMethodSpec.FinanceParameter.builder()
                .name("hidden").type("integer").required(true).build());

        FinanceMethodSpec spec = FinanceMethodSpec.builder()
                .methodId("test.missing")
                .version("1.0.0")
                .specDigest("sha256:deadbeef")
                .displayName("Test")
                .parameters(params)
                .conventions(Map.of("narrative", Map.of("narrativeTemplate", "value={visible}")))
                .outputs(List.of(FinanceMethodSpec.FinanceOutput.builder()
                        .name("out").unit("unit").description("d").build()))
                .build();

        when(mockCatalog.find(any(), any(), any())).thenReturn(Optional.of(spec));

        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        spec.getMethodId(), spec.getVersion(), spec.getSpecDigest(),
                        1.0, "unit",
                        Map.of("visible", 1.0),
                        null, true);
        assertFalse(localProjector.project(input).isPresent(),
                "Required param missing even if not referenced must fail closed");
    }

    @Test
    void requiredParameterNotInNarrativeWrongTypeReturnsEmpty() {
        FinanceMethodSpecCatalog mockCatalog = mock(FinanceMethodSpecCatalog.class);
        FinanceResultModelProjector localProjector = new FinanceResultModelProjector(mockCatalog);

        Map<String, FinanceMethodSpec.FinanceParameter> params = new LinkedHashMap<>();
        params.put("visible", FinanceMethodSpec.FinanceParameter.builder()
                .name("visible").type("number").required(true).build());
        params.put("hidden", FinanceMethodSpec.FinanceParameter.builder()
                .name("hidden").type("integer").required(true).build());

        FinanceMethodSpec spec = FinanceMethodSpec.builder()
                .methodId("test.wrongtype")
                .version("1.0.0")
                .specDigest("sha256:deadbeef")
                .displayName("Test")
                .parameters(params)
                .conventions(Map.of("narrative", Map.of("narrativeTemplate", "value={visible}")))
                .outputs(List.of(FinanceMethodSpec.FinanceOutput.builder()
                        .name("out").unit("unit").description("d").build()))
                .build();
        when(mockCatalog.find(any(), any(), any())).thenReturn(Optional.of(spec));

        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        spec.getMethodId(), spec.getVersion(), spec.getSpecDigest(),
                        1.0, "unit",
                        Map.of("visible", 1.0, "hidden", "not-an-integer"),
                        null, true);
        assertFalse(localProjector.project(input).isPresent(),
                "Required param with wrong type must fail closed even if not in narrative");
    }

    @Test
    void fractionalIntegerReturnsEmpty() {
        FinanceMethodSpecCatalog mockCatalog = mock(FinanceMethodSpecCatalog.class);
        FinanceResultModelProjector localProjector = new FinanceResultModelProjector(mockCatalog);

        Map<String, FinanceMethodSpec.FinanceParameter> params = new LinkedHashMap<>();
        params.put("count", FinanceMethodSpec.FinanceParameter.builder()
                .name("count").type("integer").required(true).build());

        FinanceMethodSpec spec = FinanceMethodSpec.builder()
                .methodId("test.integer")
                .version("1.0.0")
                .specDigest("sha256:integer")
                .displayName("Integer Test")
                .parameters(params)
                .conventions(Map.of("narrative", Map.of("narrativeTemplate", "count={count}")))
                .outputs(List.of(FinanceMethodSpec.FinanceOutput.builder()
                        .name("out").unit("unit").description("d").build()))
                .build();
        when(mockCatalog.find(any(), any(), any())).thenReturn(Optional.of(spec));

        FinanceResultModelProjector.FinanceResultProjectionInput fractional =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        spec.getMethodId(), spec.getVersion(), spec.getSpecDigest(),
                        1.0, "unit",
                        Map.of("count", 1.5),
                        null, true);
        assertFalse(localProjector.project(fractional).isPresent(),
                "Fractional value for integer parameter must be rejected");

        FinanceResultModelProjector.FinanceResultProjectionInput doubleIntegral =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        spec.getMethodId(), spec.getVersion(), spec.getSpecDigest(),
                        1.0, "unit",
                        Map.of("count", 2.0),
                        null, true);
        assertTrue(localProjector.project(doubleIntegral).isPresent(),
                "Mathematical integer as double must be accepted");

        FinanceResultModelProjector.FinanceResultProjectionInput whole =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        spec.getMethodId(), spec.getVersion(), spec.getSpecDigest(),
                        1.0, "unit",
                        Map.of("count", 2),
                        null, true);
        assertTrue(localProjector.project(whole).isPresent(),
                "Integral value must be accepted");
    }

    @Test
    void partialTripleVariantsReturnEmpty() {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String[][] variants = {
                {cagr.getMethodId(), null, null},
                {null, cagr.getVersion(), null},
                {null, null, cagr.getSpecDigest()},
                {cagr.getMethodId(), cagr.getVersion(), null},
        };
        for (String[] v : variants) {
            FinanceResultModelProjector.FinanceResultProjectionInput input =
                    new FinanceResultModelProjector.FinanceResultProjectionInput(
                            v[0], v[1], v[2],
                            0.1, "ratio",
                            Map.of("beginningValue", 100.0, "endingValue", 160.0, "periods", 4),
                            null, true);
            assertFalse(projector.project(input).isPresent(),
                    "Partial triple should not project: " + v[0] + "/" + v[1] + "/" + v[2]);
        }
    }

    @Test
    void overLengthHowCalculatedReturnsEmpty() {
        FinanceMethodSpecCatalog mockCatalog = mock(FinanceMethodSpecCatalog.class);
        FinanceResultModelProjector localProjector = new FinanceResultModelProjector(mockCatalog);

        String big = "x".repeat(3000);
        Map<String, FinanceMethodSpec.FinanceParameter> params = new LinkedHashMap<>();
        params.put("big", FinanceMethodSpec.FinanceParameter.builder()
                .name("big").type("string").required(true).build());

        FinanceMethodSpec spec = FinanceMethodSpec.builder()
                .methodId("test.long")
                .version("1.0.0")
                .specDigest("sha256:long")
                .displayName("Long")
                .parameters(params)
                .conventions(Map.of("narrative", Map.of("narrativeTemplate", "value={big}")))
                .outputs(List.of(FinanceMethodSpec.FinanceOutput.builder()
                        .name("out").unit("unit").description("d").build()))
                .build();
        when(mockCatalog.find(any(), any(), any())).thenReturn(Optional.of(spec));

        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        spec.getMethodId(), spec.getVersion(), spec.getSpecDigest(),
                        1.0, "unit",
                        Map.of("big", big),
                        null, true);
        assertFalse(localProjector.project(input).isPresent());
    }

    @Test
    void wrongCanonicalUnitReturnsEmpty() {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                        0.1, "wrong_unit",
                        Map.of("beginningValue", 100.0, "endingValue", 160.0, "periods", 4),
                        null, true);
        assertFalse(projector.project(input).isPresent());
    }

    @Test
    void canonicalUnitProjects() {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                        0.1, "ratio",
                        Map.of("beginningValue", 100.0, "endingValue", 160.0, "periods", 4),
                        null, true);
        Optional<FinanceResultModelProjector.FinanceResultProjection> result = projector.project(input);
        assertTrue(result.isPresent());
        assertEquals("ratio", result.get().unit());
    }

    @Test
    void customCalculationPreservesInputUnit() {
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        null, null, null,
                        0.12, "custom_unit",
                        Map.of(), "formula", true);
        Optional<FinanceResultModelProjector.FinanceResultProjection> result = projector.project(input);
        assertTrue(result.isPresent());
        assertEquals("custom_unit", result.get().unit());
    }

    @Test
    void customCalculationShouldProjectFormulaDescription() {
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        null, null, null,
                        0.12, "ratio",
                        Map.of("periods", 4),
                        "(ending / beginning)^(1/periods) - 1", true);

        Optional<FinanceResultModelProjector.FinanceResultProjection> result = projector.project(input);
        assertTrue(result.isPresent());
        assertEquals("自定义计算", result.get().method());
        assertEquals("(ending / beginning)^(1/periods) - 1", result.get().howCalculated());
    }

    @Test
    void customCalculationWithoutFormulaShouldNotBeProjectable() {
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        null, null, null,
                        0.12, "ratio",
                        Map.of(), "", true);
        assertFalse(projector.project(input).isPresent());
    }

    @Test
    void notRenderableShouldReturnEmpty() {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                        0.1, "ratio",
                        Map.of("beginningValue", 100.0, "endingValue", 160.0, "periods", 4),
                        null, false);
        assertFalse(projector.project(input).isPresent());
    }

    @Test
    void outputShouldNotContainInternalFields() {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceResultModelProjector.FinanceResultProjectionInput input =
                new FinanceResultModelProjector.FinanceResultProjectionInput(
                        cagr.getMethodId(), cagr.getVersion(), cagr.getSpecDigest(),
                        0.1, "ratio",
                        Map.of("beginningValue", 100.0, "endingValue", 160.0, "periods", 4),
                        null, true);
        FinanceResultModelProjector.FinanceResultProjection projection = projector.project(input).orElseThrow();
        String json = projection.toString();
        assertFalse(json.contains("specDigest"));
        assertFalse(json.contains("environment"));
        assertFalse(json.contains("evidence"));
        assertFalse(json.contains("version"));
    }
}
