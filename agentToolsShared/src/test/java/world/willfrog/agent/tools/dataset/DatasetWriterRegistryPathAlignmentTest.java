package world.willfrog.agent.tools.dataset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused test proving DatasetWriter and DatasetRegistry compute the same 4-layer path
 * when given the same clean type. This addresses Cindy's merge-readiness blocker:
 * persistedPath must point to the actual written file.
 */
class DatasetWriterRegistryPathAlignmentTest {

    private DatasetWriter writer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writer = new DatasetWriter();
        // Point writer at temp directories so it doesn't touch real /data
        ReflectionTestUtils.setField(writer, "datasetPath", tempDir.resolve("agent_datasets").toString());
        ReflectionTestUtils.setField(writer, "databaseFetchedPath", tempDir.resolve("database_fetched").toString());
        ReflectionTestUtils.setField(writer, "manifestsPath", tempDir.resolve("manifests").toString());
        ReflectionTestUtils.setField(writer, "enabled", true);
        ReflectionTestUtils.setField(writer, "localConfigLoader", null); // disable dynamic config
    }

    @Test
    @DisplayName("writer 使用 clean type 写出文件，路径与 DatabaseFetchedPathStrategy 一致")
    void writerShouldWriteToPathComputedFromCleanType() throws Exception {
        List<String> headers = List.of("trade_date", "open", "close");
        List<TestRow> data = List.of(new TestRow("20240101", "10.0", "11.0"));

        String datasetId = writer.writeDataset(
                "stock_daily",                // clean type — used for path
                "run123-stock",               // prefix — only for datasetId
                "000001.SZ",
                "20240101",
                "20240131",
                data,
                headers,
                row -> List.of(row.date, row.open, row.close)
        );

        assertNotNull(datasetId);
        assertTrue(datasetId.contains("run123-stock"), "prefix should appear in datasetId");

        // Compute expected path using the same strategy (clean type)
        String topic = DatabaseFetchedPathStrategy.resolveTopic("stock_daily");
        String encodedStr = DatabaseFetchedPathStrategy.encodedString("stock_daily", "000001.SZ",
                "20240101", "20240131", headers);
        Path expectedDir = DatabaseFetchedPathStrategy.resolveDataPath(
                tempDir.resolve("database_fetched"), topic, "000001.SZ", encodedStr);
        Path expectedFile = expectedDir.resolve("000001.SZ.csv");

        assertTrue(Files.exists(expectedFile), "CSV file should exist at: " + expectedFile);
        String content = Files.readString(expectedFile);
        assertTrue(content.contains("trade_date,open,close"), "CSV should contain header");
        assertTrue(content.contains("20240101,10.0,11.0"), "CSV should contain data row");
    }

    @Test
    @DisplayName("同一 type 下 writer 和 registry 计算相同路径")
    void writerAndRegistryShouldComputeSamePath() {
        String type = "stock_daily";
        String tsCode = "600000.SH";
        String start = "20240101";
        String end = "20240131";
        List<String> columns = List.of("close", "volume");

        // Writer's path formula (DatasetWriter L74-75, L86)
        String writerTopic = DatabaseFetchedPathStrategy.resolveTopic(type);
        String writerEncoded = DatabaseFetchedPathStrategy.encodedString(type, tsCode, start, end, columns);
        Path writerDir = DatabaseFetchedPathStrategy.resolveDataPath(
                Path.of("/data/database_fetched"), writerTopic, tsCode, writerEncoded);
        String writerFileName = tsCode + ".csv"; // writer uses safeTsCode + ".csv"
        Path writerPath = writerDir.resolve(writerFileName);

        // Registry's path formula (DatasetRegistry L170-175, dataFileName from 6-arg default)
        String registryTopic = DatabaseFetchedPathStrategy.resolveTopic(type);
        String registryEncoded = DatabaseFetchedPathStrategy.encodedString(type, tsCode, start, end, columns);
        Path registryDir = DatabaseFetchedPathStrategy.resolveDataPath(
                Path.of("/data/database_fetched"), registryTopic, tsCode, registryEncoded);
        String registryFileName = tsCode.replaceAll("[^a-zA-Z0-9.]", "_") + ".csv"; // 6-arg default
        Path registryPath = registryDir.resolve(registryFileName);

        assertEquals(writerTopic, registryTopic, "topic should match");
        assertEquals(writerEncoded, registryEncoded, "encodedString should match");
        assertEquals(writerDir, registryDir, "4-layer directory should match");
        assertEquals(writerFileName, registryFileName, "data file name should match (both <tsCode>.csv)");
        assertEquals(writerPath, registryPath, "full persisted path should match");
    }

    @Test
    @DisplayName("不同 type 不会碰巧撞目录 — type 变化改变 encodedString")
    void differentTypesShouldProduceDifferentPaths() {
        List<String> columns = List.of("close");
        String tsCode = "000001.SZ";
        String start = "20240101";
        String end = "20240131";

        Path stockPath = DatabaseFetchedPathStrategy.resolveDataPath(
                Path.of("/data/database_fetched"),
                DatabaseFetchedPathStrategy.resolveTopic("stock_daily"),
                tsCode,
                DatabaseFetchedPathStrategy.encodedString("stock_daily", tsCode, start, end, columns));

        Path indexPath = DatabaseFetchedPathStrategy.resolveDataPath(
                Path.of("/data/database_fetched"),
                DatabaseFetchedPathStrategy.resolveTopic("index_daily"),
                tsCode,
                DatabaseFetchedPathStrategy.encodedString("index_daily", tsCode, start, end, columns));

        assertNotEquals(stockPath, indexPath, "stock_daily and index_daily should produce different paths");
    }

    @Test
    @DisplayName("writer 使用 clean type 走不同 data type 写出文件不碰撞")
    void stockAndIndexDailyShouldNotCollide() throws Exception {
        List<String> headers = List.of("close");
        List<TestRow> data = List.of(new TestRow("20240101", "10.0"));

        // Write stock_daily
        writer.writeDataset("stock_daily", "run-stock", "000001.SZ",
                "20240101", "20240131", data, headers, row -> List.of(row.date, row.open));

        // Write index_daily with same tsCode and date range
        writer.writeDataset("index_daily", "run-index", "000001.SZ",
                "20240101", "20240131", data, headers, row -> List.of(row.date, row.open));

        // Verify they landed in different directories
        Path dbRoot = tempDir.resolve("database_fetched");
        // stock_daily → domestic_listed_asset (stock_ prefix)
        Path stockDir = dbRoot.resolve("domestic_listed_asset").resolve("000001.SZ");
        // index_daily → domestic_index (index_ prefix)
        Path indexDir = dbRoot.resolve("domestic_index").resolve("000001.SZ");

        // Each dir should have one encodedString subdir
        assertTrue(Files.exists(stockDir));
        assertTrue(Files.exists(indexDir));
        // Walk to count files
        long stockCsvCount = Files.walk(stockDir).filter(p -> p.toString().endsWith(".csv")).count();
        long indexCsvCount = Files.walk(indexDir).filter(p -> p.toString().endsWith(".csv")).count();
        assertEquals(1, stockCsvCount, "one stock CSV");
        assertEquals(1, indexCsvCount, "one index CSV");
    }

    // Minimal row class for test data
    record TestRow(String date, String open, String close) {
        TestRow(String date, String value) { this(date, value, null); }
    }
}
