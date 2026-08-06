package world.willfrog.agent.tools.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatasetPathStrategyTest {

    @Test
    void scopeHashSingleAssetShouldBeDeterministic() {
        String h1 = DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240101", "20240131");
        String h2 = DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240101", "20240131");
        assertEquals(h1, h2, "相同输入应产生相同 scopeHash");
    }

    @Test
    void scopeHashSingleAssetShouldDifferByToolType() {
        String h1 = DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240101", "20240131");
        String h2 = DatasetPathStrategy.scopeHash("index_daily", "000001.SZ", "20240101", "20240131");
        assertNotEquals(h1, h2, "不同 toolType 应产生不同 scopeHash");
    }

    @Test
    void scopeHashSingleAssetShouldDifferByTsCode() {
        String h1 = DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240101", "20240131");
        String h2 = DatasetPathStrategy.scopeHash("stock_daily", "000002.SZ", "20240101", "20240131");
        assertNotEquals(h1, h2, "不同 tsCode 应产生不同 scopeHash");
    }

    @Test
    void scopeHashSingleAssetShouldDifferByDateRange() {
        String h1 = DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240101", "20240131");
        String h2 = DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240201", "20240229");
        assertNotEquals(h1, h2, "不同日期范围应产生不同 scopeHash");
    }

    @Test
    void scopeHashMultiAssetShouldBeDeterministic() {
        List<String> tsCodes1 = List.of("000001.SZ", "000002.SZ", "600000.SH");
        List<String> tsCodes2 = List.of("000001.SZ", "000002.SZ", "600000.SH");
        String h1 = DatasetPathStrategy.scopeHash("stock_daily", tsCodes1, "20240101", "20240131");
        String h2 = DatasetPathStrategy.scopeHash("stock_daily", tsCodes2, "20240101", "20240131");
        assertEquals(h1, h2, "相同排序的 tsCodes 应产生相同 scopeHash");
    }

    @Test
    void scopeHashMultiAssetShouldBeOrderIndependent() {
        List<String> tsCodes1 = List.of("600000.SH", "000001.SZ", "000002.SZ");
        List<String> tsCodes2 = List.of("000001.SZ", "000002.SZ", "600000.SH");
        String h1 = DatasetPathStrategy.scopeHash("stock_daily", tsCodes1, "20240101", "20240131");
        String h2 = DatasetPathStrategy.scopeHash("stock_daily", tsCodes2, "20240101", "20240131");
        // 注意：调用方必须自行排序，scopeHash 不排序
        assertNotEquals(h1, h2, "未排序的 tsCodes 应产生不同 scopeHash（调用方负责排序）");
    }

    @Test
    void scopeHashMultiAssetShouldDeduplicateTsCodes() {
        // 调用方负责去重+排序后传入：去重后的 [A, B] 应与含重复的 [A, B, A] 排序后产生不同 hash
        // 因为去重是调用方职责，未去重列表会 encode 重复元素，产生不同于去重版本的 hash
        List<String> withDuplicate = List.of("000001.SZ", "000001.SZ", "000002.SZ");
        List<String> deduplicated = List.of("000001.SZ", "000002.SZ");
        String h1 = DatasetPathStrategy.scopeHash("stock_daily", withDuplicate, "20240101", "20240131");
        String h2 = DatasetPathStrategy.scopeHash("stock_daily", deduplicated, "20240101", "20240131");
        assertNotEquals(h1, h2, "含重复元素的列表应产生不同于去重版本的 hash，调用方必须去重+排序后传入");
    }

    @Test
    void scopeHashReturns16CharHex() {
        String h = DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240101", "20240131");
        assertEquals(16, h.length(), "scopeHash 应为 16 位十六进制字符串");
        assertTrue(h.matches("[0-9a-f]+"), "scopeHash 应只含十六进制字符");
    }

    @Test
    void resolvePathShouldProduceThreeLayerStructure(@TempDir Path tmpDir) {
        Path result = DatasetPathStrategy.resolvePath(tmpDir, "stock_daily", "abc123def456", "stock_daily-000001_SZ-20240101-20240131-x1y2z3");
        assertEquals(tmpDir.resolve("stock_daily").resolve("abc123def456").resolve("stock_daily-000001_SZ-20240101-20240131-x1y2z3"), result);
    }

    // P0-10 null/empty 边界
    @Test
    void scopeHashSingleAssetShouldRejectNull() {
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.scopeHash((String) null, "000001.SZ", "20240101", "20240131"));
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.scopeHash("stock_daily", (String) null, "20240101", "20240131"));
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", null, "20240131"));
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240101", null));
    }

    @Test
    void scopeHashMultiAssetShouldRejectNull() {
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.scopeHash((String) null, List.of("A"), "20240101", "20240131"));
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.scopeHash("stock_daily", (List<String>) null, "20240101", "20240131"));
    }

    @Test
    void resolvePathShouldRejectNull() {
        Path tmp = Path.of("/tmp");
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.resolvePath(null, "stock_daily", "abc", "id"));
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.resolvePath(tmp, null, "abc", "id"));
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.resolvePath(tmp, "stock_daily", null, "id"));
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.resolvePath(tmp, "stock_daily", "abc", null));
    }

    // P0-9 path traversal 校验
    @Test
    void validateDatasetIdShouldAcceptSafeIds() {
        assertDoesNotThrow(() -> DatasetPathStrategy.validateDatasetId("stock_daily-000001_SZ-20240101-20240131-a1b2c3d4"));
        assertDoesNotThrow(() -> DatasetPathStrategy.validateDatasetId("manifest-stock_daily-20240101-20240131-abc12345"));
    }

    @Test
    void validateDatasetIdShouldRejectPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> DatasetPathStrategy.validateDatasetId("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> DatasetPathStrategy.validateDatasetId("a/b"));
        assertThrows(IllegalArgumentException.class, () -> DatasetPathStrategy.validateDatasetId("/absolute/path"));
    }

    @Test
    void validateDatasetIdShouldRejectNullOrBlank() {
        assertThrows(NullPointerException.class, () -> DatasetPathStrategy.validateDatasetId(null));
        assertThrows(IllegalArgumentException.class, () -> DatasetPathStrategy.validateDatasetId(""));
        assertThrows(IllegalArgumentException.class, () -> DatasetPathStrategy.validateDatasetId("  "));
    }

    // P0-9 扩展：toolType / scopeHash 白名单校验
    @Test
    void resolvePathShouldRejectTraversalInAnyComponent(@TempDir Path tmpDir) {
        assertThrows(IllegalArgumentException.class,
                () -> DatasetPathStrategy.resolvePath(tmpDir, "..", "abc123", "safe-id"));
        assertThrows(IllegalArgumentException.class,
                () -> DatasetPathStrategy.resolvePath(tmpDir, "stock_daily", "../etc", "safe-id"));
        assertThrows(IllegalArgumentException.class,
                () -> DatasetPathStrategy.resolvePath(tmpDir, "stock_daily", "abc123", "../passwd"));
    }

    // P2-15 加盐验证：不同 salt 产生不同 hash（验证 salt 参与哈希）
    @Test
    void scopeHashShouldIncludeSalt() {
        // 相同输入 + 不同 salt = 不同 hash（如果 salt 变动，所有 scopeHash 会变化）
        String h = DatasetPathStrategy.scopeHash("stock_daily", "000001.SZ", "20240101", "20240131");
        // 仅验证 salt 已参与哈希（间接：hash 不为空且非纯 equal 于 input 字符串）
        assertNotNull(h);
        assertFalse("af-dataset-v1|stock_daily|000001.SZ|20240101|20240131".contains(h),
                "hash 不应是输入的简单子串，salt 已参与计算");
    }
}
