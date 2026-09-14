package world.willfrog.agent.tools.dataset;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 数据集三层目录路径策略：{@code {toolType}/{scopeHash}/{datasetId}}。
 *
 * <p>scopeHash 由加盐 SHA-256 计算，前 16 个十六进制字符组成，用于分散文件系统压力。
 * 注意：v2.3.1 方案写"md5"，实现升级为 SHA-256，安全性更好，熵相同。
 */
public final class DatasetPathStrategy {

    /** v1 加盐值，用于 scopeHash 计算避免裸哈希碰撞。 */
    private static final String SALT = "af-dataset-v1";

    /** datasetId 只允许 [A-Za-z0-9._-]，拒绝路径分隔符和 ../ 遍历。 */
    private static final Pattern SAFE_DATASET_ID = Pattern.compile("^[A-Za-z0-9._-]+$");

    private DatasetPathStrategy() {}

    /**
     * 校验 datasetId 不含路径遍历字符（../、绝对路径、特殊字符）。
     *
     * @throws IllegalArgumentException 如果 datasetId 为 null、空白或含非法字符
     */
    public static void validateDatasetId(String datasetId) {
        Objects.requireNonNull(datasetId, "datasetId 不能为 null");
        if (datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId 不能为空");
        }
        if (!SAFE_DATASET_ID.matcher(datasetId).matches()) {
            throw new IllegalArgumentException("datasetId 含非法字符: " + datasetId);
        }
    }

    private static void validateComponent(String label, String value) {
        Objects.requireNonNull(value, label + " 不能为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        // 拒绝 . 和 ..（即使匹配白名单也是路径遍历）
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(label + " 不能为 '.' 或 '..': " + value);
        }
        if (!SAFE_DATASET_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " 含非法字符: " + value);
        }
    }

    /**
     * 计算三层路径：{@code datasetRoot/toolType/scopeHash/datasetId}。
     * 三个路径组件均做白名单校验，防止路径遍历。
     *
     * @throws NullPointerException 如果任一参数为 null
     * @throws IllegalArgumentException 如果 toolType、scopeHash 或 datasetId 含非法字符
     */
    public static Path resolvePath(Path datasetRoot, String toolType, String scopeHash, String datasetId) {
        Objects.requireNonNull(datasetRoot, "datasetRoot 不能为 null");
        validateComponent("toolType", toolType);
        validateComponent("scopeHash", scopeHash);
        validateComponent("datasetId", datasetId);
        return datasetRoot.resolve(toolType).resolve(scopeHash).resolve(datasetId);
    }

    /**
     * 单资产 scopeHash（salt + toolType + tsCode + startDate + endDate）。
     *
     * @throws NullPointerException 如果任一参数为 null
     */
    public static String scopeHash(String toolType, String tsCode, String startDate, String endDate) {
        Objects.requireNonNull(toolType, "toolType 不能为 null");
        Objects.requireNonNull(tsCode, "tsCode 不能为 null");
        Objects.requireNonNull(startDate, "startDate 不能为 null");
        Objects.requireNonNull(endDate, "endDate 不能为 null");
        String input = SALT + "|" + toolType + "|" + tsCode + "|" + startDate + "|" + endDate;
        return sha256Hex(input).substring(0, 16);
    }

    /**
     * 多资产 scopeHash（salt + toolType + ts_codes + startDate + endDate）。
     *
     * @param sortedTsCodes 调用方已去重并按自然序排序的 ts_code 列表。去重和排序均为调用方职责，
     *                       本方法不做额外排序/去重；若传入未去重或未排序列表，将产生不同的 scopeHash。
     * @throws NullPointerException 如果任一参数为 null
     */
    public static String scopeHash(String toolType, List<String> sortedTsCodes, String startDate, String endDate) {
        Objects.requireNonNull(toolType, "toolType 不能为 null");
        Objects.requireNonNull(sortedTsCodes, "sortedTsCodes 不能为 null");
        Objects.requireNonNull(startDate, "startDate 不能为 null");
        Objects.requireNonNull(endDate, "endDate 不能为 null");
        String input = SALT + "|" + toolType + "|" + String.join(",", sortedTsCodes) + "|" + startDate + "|" + endDate;
        return sha256Hex(input).substring(0, 16);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
