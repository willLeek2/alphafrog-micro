package world.willfrog.agentlangchain.finance;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 金融结果块渲染器。纯确定性渲染，无外部依赖。
 */
@Component
public class FinanceResultBlockRenderer {

    public static final String RENDERER_VERSION = "1.0.0";

    public record Row(String method, String formattedValue, String howCalculated) {}

    public String renderTable(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| 方法 | 结果 | 如何计算 |\n");
        sb.append("|---|---:|---|\n");
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            sb.append("| ")
              .append(escapeCell(row.method()))
              .append(" | ")
              .append(escapeCell(row.formattedValue()))
              .append(" | ")
              .append(escapeCell(row.howCalculated()))
              .append(" |");
            if (i < rows.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public String escapeCell(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replace("|", "\\|");
        result = result.replace("\r\n", " ");
        result = result.replace("\r", " ");
        result = result.replace("\n", " ");
        return result.trim();
    }

    public String formatValue(Number value, String displayFormat) {
        if (value == null) {
            return "";
        }
        if (displayFormat != null && displayFormat.equalsIgnoreCase("percent")) {
            double v = value.doubleValue();
            if (v == 0.0) {
                return "0.00%";
            }
            if (Math.abs(v) < 0.0001) {
                return String.format(Locale.ROOT, "%.2e", v);
            }
            BigDecimal bd = new BigDecimal(value.toString())
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            return bd.toPlainString() + "%";
        }
        return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }

    public String stableBlockId(String runId, List<String> recordIdsInRecordIndexOrder) {
        List<String> inputs = List.of(
                runId == null ? "" : runId,
                RENDERER_VERSION
        );
        List<String> records = recordIdsInRecordIndexOrder == null ? List.of() : recordIdsInRecordIndexOrder;

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        for (String element : inputs) {
            updateDigest(digest, element);
        }
        for (String element : records) {
            updateDigest(digest, element == null ? "" : element);
        }

        return "sha256:" + HexFormat.of().withLowerCase().formatHex(digest.digest());
    }

    private void updateDigest(MessageDigest digest, String element) {
        byte[] bytes = element.getBytes(StandardCharsets.UTF_8);
        ByteBuffer length = ByteBuffer.allocate(4);
        length.putInt(bytes.length);
        digest.update(length.array());
        digest.update(bytes);
    }
}
