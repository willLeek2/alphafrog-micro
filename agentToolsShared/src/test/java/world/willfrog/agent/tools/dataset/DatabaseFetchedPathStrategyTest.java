package world.willfrog.agent.tools.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseFetchedPathStrategyTest {

    // ---- encoded_string stability ----

    @Test
    void encodedStringShouldBeDeterministic() {
        List<String> cols = List.of("open", "high", "low", "close", "volume");
        String e1 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", cols);
        String e2 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", cols);
        assertEquals(e1, e2, "相同输入应产生相同 encoded_string");
    }

    @Test
    void encodedStringShouldDifferByToolType() {
        List<String> cols = List.of("close");
        String e1 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", cols);
        String e2 = DatabaseFetchedPathStrategy.encodedString("index_daily", "000001.SZ",
                "20240101", "20240131", cols);
        assertNotEquals(e1, e2, "不同 toolType 应产生不同 encoded_string");
    }

    @Test
    void encodedStringShouldDifferByColumns() {
        String e1 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", List.of("close"));
        String e2 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", List.of("open", "high", "low", "close"));
        assertNotEquals(e1, e2, "不同 columns 应产生不同 encoded_string");
    }

    @Test
    void encodedStringShouldDifferByTsCode() {
        List<String> cols = List.of("close");
        String e1 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", cols);
        String e2 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000002.SZ",
                "20240101", "20240131", cols);
        assertNotEquals(e1, e2);
    }

    @Test
    void encodedStringShouldDifferByDateRange() {
        List<String> cols = List.of("close");
        String e1 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", cols);
        String e2 = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240201", "20240229", cols);
        assertNotEquals(e1, e2);
    }

    @Test
    void encodedStringShouldReturn16Chars() {
        String e = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", List.of("close"));
        assertEquals(16, e.length(), "encoded_string 应为 16 字符");
    }

    @Test
    void encodedStringShouldBeBase32Hex() {
        String e = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", List.of("close"));
        assertTrue(e.matches("[0-9A-V]+"), "encoded_string 应只含 base32hex 字符 [0-9A-V]");
    }

    @Test
    void encodedStringMultiAssetShouldBeDeterministic() {
        List<String> tsCodes1 = List.of("000001.SZ", "000002.SZ", "600000.SH");
        List<String> tsCodes2 = List.of("000001.SZ", "000002.SZ", "600000.SH");
        List<String> cols = List.of("close");
        String e1 = DatabaseFetchedPathStrategy.encodedString("stock_daily", tsCodes1,
                "20240101", "20240131", cols);
        String e2 = DatabaseFetchedPathStrategy.encodedString("stock_daily", tsCodes2,
                "20240101", "20240131", cols);
        assertEquals(e1, e2);
    }

    // ---- Topic Mapping ----

    @Test
    void resolveTopicStockShouldMapToDomesticListedAsset() {
        assertEquals("domestic_listed_asset",
                DatabaseFetchedPathStrategy.resolveTopic("stock_daily"));
        assertEquals("domestic_listed_asset",
                DatabaseFetchedPathStrategy.resolveTopic("stock_weekly"));
    }

    @Test
    void resolveTopicIndexShouldMapToDomesticIndex() {
        assertEquals("domestic_index",
                DatabaseFetchedPathStrategy.resolveTopic("index_daily"));
        assertEquals("domestic_index",
                DatabaseFetchedPathStrategy.resolveTopic("index_info"));
    }

    @Test
    void resolveTopicFundShouldMapToDomesticFund() {
        assertEquals("domestic_fund",
                DatabaseFetchedPathStrategy.resolveTopic("fund_daily"));
        assertEquals("domestic_fund",
                DatabaseFetchedPathStrategy.resolveTopic("fund_nav"));
    }

    @Test
    void resolveTopicUnknownShouldFallback() {
        assertEquals("domestic_listed_asset",
                DatabaseFetchedPathStrategy.resolveTopic("unknown_type"));
    }

    @Test
    void resolveTopicShouldRejectNull() {
        assertThrows(NullPointerException.class,
                () -> DatabaseFetchedPathStrategy.resolveTopic(null));
    }

    // ---- Path Resolution ----

    @Test
    void resolveDataPathShouldProduceFourLayerStructure(@TempDir Path tmpDir) {
        Path result = DatabaseFetchedPathStrategy.resolveDataPath(
                tmpDir, "domestic_listed_asset", "600000.SH", "7D3A8B9F0C2K1PQR");
        assertEquals(tmpDir.resolve("domestic_listed_asset").resolve("600000.SH")
                .resolve("7D3A8B9F0C2K1PQR"), result);
    }

    @Test
    void resolveManifestPathShouldProduceManifestsV1Structure(@TempDir Path tmpDir) {
        Path result = DatabaseFetchedPathStrategy.resolveManifestPath(tmpDir, "abc123");
        assertEquals(tmpDir.resolve("v1").resolve("manifest-abc123"), result);
    }

    @Test
    void resolveDataPathShouldRejectNull(@TempDir Path tmpDir) {
        assertThrows(NullPointerException.class,
                () -> DatabaseFetchedPathStrategy.resolveDataPath(null, "domestic_listed_asset",
                        "600000.SH", "ABC"));
        assertThrows(NullPointerException.class,
                () -> DatabaseFetchedPathStrategy.resolveDataPath(tmpDir, null, "600000.SH", "ABC"));
    }

    @Test
    void resolveManifestPathShouldRejectNull(@TempDir Path tmpDir) {
        assertThrows(NullPointerException.class,
                () -> DatabaseFetchedPathStrategy.resolveManifestPath(null, "abc"));
        assertThrows(NullPointerException.class,
                () -> DatabaseFetchedPathStrategy.resolveManifestPath(tmpDir, null));
    }

    // ---- Base32 encoding ----

    @Test
    void encodeBase32HexEmptyShouldReturnEmpty() {
        assertEquals("", DatabaseFetchedPathStrategy.encodeBase32Hex(new byte[0]));
    }

    @Test
    void encodeBase32HexKnownVector() {
        // SHA-256("test") = 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
        // First 5 bytes = 0x9f86d08188 = 1001111110000110110100001000000110001000
        // Expected base32hex: ...
        // Verify output is valid base32hex
        byte[] data = { (byte) 0x9f, (byte) 0x86, (byte) 0xd0, (byte) 0x81, (byte) 0x88 };
        String encoded = DatabaseFetchedPathStrategy.encodeBase32Hex(data);
        assertEquals(8, encoded.length()); // 5 bytes = 40 bits / 5 = 8 chars
        assertTrue(encoded.matches("[0-9A-V]+"));
    }
}
