package world.willfrog.agent.tools.dataset;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * 4 层 database_fetched 存储路径策略：{@code database_fetched/<topic>/<tsCode>/<encodedString>/<csv>}。
 *
 * <p>encoded_string = base32hex(sha256(type|tsCode|start|end|col1,col2,…)).substring(0, 16)
 * 字母表：RFC 4648 base32hex — 0123456789ABCDEFGHIJKLMNOPQRSTUV
 *
 * <p>V1 topic 列表：domestic_listed_asset / domestic_index / domestic_fund
 */
public final class DatabaseFetchedPathStrategy {

    /** base32hex 字母表（RFC 4648 §7） */
    private static final char[] BASE32HEX = "0123456789ABCDEFGHIJKLMNOPQRSTUV".toCharArray();

    /** encoded_string 截取长度（16 字符 ≈ 80 bits） */
    private static final int ENCODED_LENGTH = 16;

    private DatabaseFetchedPathStrategy() {}

    // ---- Topic Mapping ----

    /**
     * 将 toolType 映射到 V1 topic。
     * 映射规则基于 spec §A.3：stock_* → domestic_listed_asset, index_* → domestic_index, fund_* → domestic_fund。
     */
    public static String resolveTopic(String toolType) {
        Objects.requireNonNull(toolType, "toolType 不能为 null");
        if (toolType.startsWith("stock_")) {
            return "domestic_listed_asset";
        }
        if (toolType.startsWith("index_")) {
            return "domestic_index";
        }
        if (toolType.startsWith("fund_")) {
            return "domestic_fund";
        }
        if ("market_data_advanced_search".equals(toolType)) {
            return "advanced_search";
        }
        // Fallback for unknown toolTypes — keeps data accessible under a generic topic
        return "domestic_listed_asset";
    }

    // ---- Encoded String ----

    /**
     * 计算单资产 encoded_string：base32hex(sha256(type|tsCode|start|end|cols)).substring(0,16)。
     */
    public static String encodedString(String type, String tsCode, String startDate, String endDate,
                                       List<String> columns) {
        Objects.requireNonNull(type, "type 不能为 null");
        Objects.requireNonNull(tsCode, "tsCode 不能为 null");
        Objects.requireNonNull(startDate, "startDate 不能为 null");
        Objects.requireNonNull(endDate, "endDate 不能为 null");
        Objects.requireNonNull(columns, "columns 不能为 null");

        String cols = String.join(",", columns);
        String input = type + "|" + tsCode + "|" + startDate + "|" + endDate + "|" + cols;
        return encodeBase32Hex(sha256(input)).substring(0, ENCODED_LENGTH);
    }

    /**
     * 计算多资产 encoded_string（用于 manifest / batch 场景）。
     * 调用方负责 tsCodes 的去重与排序。
     */
    public static String encodedString(String type, List<String> sortedTsCodes, String startDate,
                                       String endDate, List<String> columns) {
        Objects.requireNonNull(type, "type 不能为 null");
        Objects.requireNonNull(sortedTsCodes, "sortedTsCodes 不能为 null");
        Objects.requireNonNull(startDate, "startDate 不能为 null");
        Objects.requireNonNull(endDate, "endDate 不能为 null");
        Objects.requireNonNull(columns, "columns 不能为 null");

        String tsCodes = String.join(",", sortedTsCodes);
        String cols = String.join(",", columns);
        String input = type + "|" + tsCodes + "|" + startDate + "|" + endDate + "|" + cols;
        return encodeBase32Hex(sha256(input)).substring(0, ENCODED_LENGTH);
    }

    // ---- Path Resolution ----

    /**
     * 解析 4 层路径：{@code root/<topic>/<tsCode>/<encodedString>/}。
     */
    public static Path resolveDataPath(Path databaseFetchedRoot, String topic, String tsCode,
                                       String encodedString) {
        Objects.requireNonNull(databaseFetchedRoot, "databaseFetchedRoot 不能为 null");
        Objects.requireNonNull(topic, "topic 不能为 null");
        Objects.requireNonNull(tsCode, "tsCode 不能为 null");
        Objects.requireNonNull(encodedString, "encodedString 不能为 null");
        return databaseFetchedRoot.resolve(topic).resolve(tsCode).resolve(encodedString);
    }

    /**
     * 解析 manifest 路径：{@code manifestsRoot/v1/manifest-<id>/manifest.json}。
     */
    public static Path resolveManifestPath(Path manifestsRoot, String manifestId) {
        Objects.requireNonNull(manifestsRoot, "manifestsRoot 不能为 null");
        Objects.requireNonNull(manifestId, "manifestId 不能为 null");
        return manifestsRoot.resolve("v1").resolve("manifest-" + manifestId);
    }

    // ---- Internal ----

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * RFC 4648 §7 base32hex 编码。
     * 每 5 bits 映射到字母表 0-9A-V，末尾不足 5 bits 时低位补 0。
     */
    static String encodeBase32Hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;

        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32HEX[(buffer >> bitsLeft) & 0x1F]);
            }
        }
        // Flush remaining bits
        if (bitsLeft > 0) {
            sb.append(BASE32HEX[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return sb.toString();
    }
}
