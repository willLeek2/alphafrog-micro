package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
