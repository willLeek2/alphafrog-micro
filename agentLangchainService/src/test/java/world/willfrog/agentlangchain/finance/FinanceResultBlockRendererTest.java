package world.willfrog.agentlangchain.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceResultBlockRendererTest {

    private final FinanceResultBlockRenderer renderer = new FinanceResultBlockRenderer();

    @Test
    void renderTableExactShape() {
        List<FinanceResultBlockRenderer.Row> rows = List.of(
                new FinanceResultBlockRenderer.Row("CAGR", "12.47%", "年化复合增长率"),
                new FinanceResultBlockRenderer.Row("夏普比率", "1.25", "风险调整后收益")
        );
        String expected = "| 方法 | 结果 | 如何计算 |\n"
                + "|---|---:|---|\n"
                + "| CAGR | 12.47% | 年化复合增长率 |\n"
                + "| 夏普比率 | 1.25 | 风险调整后收益 |";
        assertEquals(expected, renderer.renderTable(rows));
    }

    @Test
    void renderTableEscapesCells() {
        List<FinanceResultBlockRenderer.Row> rows = List.of(
                new FinanceResultBlockRenderer.Row("a|b", "1\r\n2", "x\ny\rz")
        );
        String expected = "| 方法 | 结果 | 如何计算 |\n"
                + "|---|---:|---|\n"
                + "| a\\|b | 1 2 | x y z |";
        assertEquals(expected, renderer.renderTable(rows));
    }

    @Test
    void renderTableEmptyReturnsEmpty() {
        assertEquals("", renderer.renderTable(null));
        assertEquals("", renderer.renderTable(List.of()));
    }

    @Test
    void escapeCellRules() {
        assertEquals("", renderer.escapeCell(null));
        assertEquals("", renderer.escapeCell("   "));
        assertEquals("a\\|b", renderer.escapeCell("a|b"));
        assertEquals("a b", renderer.escapeCell("a\nb"));
        assertEquals("a b", renderer.escapeCell("a\rb"));
        assertEquals("a b", renderer.escapeCell("a\r\nb"));
        assertEquals("a b c", renderer.escapeCell("  a\r\nb\nc  "));
    }

    @Test
    void formatValuePercent() {
        assertEquals("12.47%", renderer.formatValue(new BigDecimal("0.1247"), "percent"));
        assertEquals("0.00%", renderer.formatValue(0, "percent"));
        assertEquals("0.00%", renderer.formatValue(0.0, "percent"));
        assertEquals("9.00e-05", renderer.formatValue(0.00009, "percent"));
        assertEquals("-9.00e-05", renderer.formatValue(-0.00009, "percent"));
        assertEquals("-15.67%", renderer.formatValue(new BigDecimal("-0.1567"), "percent"));
        assertEquals("12.35%", renderer.formatValue(new BigDecimal("0.123455"), "percent"));
        assertEquals("12.35%", renderer.formatValue(new BigDecimal("0.123455"), "PERCENT"));
    }

    @Test
    void formatValueNumberDefault() {
        assertEquals("2.5", renderer.formatValue(new BigDecimal("2.5"), null));
        assertEquals("1000", renderer.formatValue(new BigDecimal("1000"), ""));
        assertEquals("", renderer.formatValue(null, "number"));
        assertEquals("1000", renderer.formatValue(new BigDecimal("1000.00"), "number"));
        assertEquals("2.5", renderer.formatValue(2.5, "unknown"));
    }

    @Test
    void stableBlockIdDeterministicAndFormat() throws Exception {
        String runId = "run-42";
        List<String> records = List.of("r1", "r2", "r3");

        String id1 = renderer.stableBlockId(runId, records);
        String id2 = renderer.stableBlockId(runId, records);

        assertTrue(id1.startsWith("sha256:"));
        assertEquals(64, id1.length() - "sha256:".length());
        assertEquals(id1, id2);

        String expected = computeExpectedBlockId(runId, records);
        assertEquals(expected, id1);
    }

    @Test
    void stableBlockIdChangesWithRunId() {
        List<String> records = List.of("r1", "r2");
        assertNotEquals(
                renderer.stableBlockId("run-a", records),
                renderer.stableBlockId("run-b", records)
        );
    }

    @Test
    void stableBlockIdChangesWithRecordOrder() {
        List<String> order1 = List.of("r1", "r2");
        List<String> order2 = List.of("r2", "r1");
        assertNotEquals(
                renderer.stableBlockId("run-x", order1),
                renderer.stableBlockId("run-x", order2)
        );
    }

    @Test
    void stableBlockIdBlankInputs() throws Exception {
        String id = renderer.stableBlockId("", List.of());
        assertTrue(id.startsWith("sha256:"));
        assertEquals(64, id.length() - "sha256:".length());
        assertEquals(computeExpectedBlockId("", List.of()), id);
    }

    private String computeExpectedBlockId(String runId, List<String> records) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String element : List.of(runId, FinanceResultBlockRenderer.RENDERER_VERSION)) {
            updateExpectedDigest(digest, element);
        }
        for (String element : records) {
            updateExpectedDigest(digest, element == null ? "" : element);
        }
        return "sha256:" + HexFormat.of().withLowerCase().formatHex(digest.digest());
    }

    private void updateExpectedDigest(MessageDigest digest, String element) {
        byte[] bytes = element.getBytes(StandardCharsets.UTF_8);
        ByteBuffer length = ByteBuffer.allocate(4);
        length.putInt(bytes.length);
        digest.update(length.array());
        digest.update(bytes);
    }
}
