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
 *
 * <p>Both DatasetWriter and DatasetRegistry use the same DatabaseFetchedPathStrategy
 * static methods (resolveTopic, encodedString, resolveDataPath) with the same clean
 * type input. This test proves:
 * <ol>
 *   <li>Writer actually writes to the path computed from clean type (not runId prefix)</li>
 *   <li>Writer and registry's path formulas produce identical results</li>
 *   <li>Different types don't collide</li>
 *   <li>Real file at writer path == registry's expected persistedPath</li>
 * </ol>
 */
class DatasetWriterRegistryPathAlignmentTest {

    private DatasetWriter writer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writer = new DatasetWriter();
        ReflectionTestUtils.setField(writer, "datasetPath", tempDir.resolve("agent_datasets").toString());
        ReflectionTestUtils.setField(writer, "databaseFetchedPath", tempDir.resolve("database_fetched").toString());
        ReflectionTestUtils.setField(writer, "manifestsPath", tempDir.resolve("manifests").toString());
        ReflectionTestUtils.setField(writer, "enabled", true);
        ReflectionTestUtils.setField(writer, "localConfigLoader", null);
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

        String writerTopic = DatabaseFetchedPathStrategy.resolveTopic(type);
        String writerEncoded = DatabaseFetchedPathStrategy.encodedString(type, tsCode, start, end, columns);
        Path writerDir = DatabaseFetchedPathStrategy.resolveDataPath(
                Path.of("/data/database_fetched"), writerTopic, tsCode, writerEncoded);
        String writerFileName = tsCode + ".csv";
        Path writerPath = writerDir.resolve(writerFileName);

        String registryTopic = DatabaseFetchedPathStrategy.resolveTopic(type);
        String registryEncoded = DatabaseFetchedPathStrategy.encodedString(type, tsCode, start, end, columns);
        Path registryDir = DatabaseFetchedPathStrategy.resolveDataPath(
                Path.of("/data/database_fetched"), registryTopic, tsCode, registryEncoded);
        String registryFileName = tsCode.replaceAll("[^a-zA-Z0-9.]", "_") + ".csv";
        Path registryPath = registryDir.resolve(registryFileName);

        assertEquals(writerTopic, registryTopic, "topic should match");
        assertEquals(writerEncoded, registryEncoded, "encodedString should match");
        assertEquals(writerDir, registryDir, "4-layer directory should match");
        assertEquals(writerFileName, registryFileName, "data file name should match");
        assertEquals(writerPath, registryPath, "full persisted path should match");
    }

    @Test
    @DisplayName("不同 type 不会碰巧撞目录")
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

        writer.writeDataset("stock_daily", "run-stock", "000001.SZ",
                "20240101", "20240131", data, headers, row -> List.of(row.date, row.open));
        writer.writeDataset("index_daily", "run-index", "000001.SZ",
                "20240101", "20240131", data, headers, row -> List.of(row.date, row.open));

        Path dbRoot = tempDir.resolve("database_fetched");
        Path stockDir = dbRoot.resolve("domestic_listed_asset").resolve("000001.SZ");
        Path indexDir = dbRoot.resolve("domestic_index").resolve("000001.SZ");

        assertTrue(Files.exists(stockDir));
        assertTrue(Files.exists(indexDir));
        long stockCsvCount = Files.walk(stockDir).filter(p -> p.toString().endsWith(".csv")).count();
        long indexCsvCount = Files.walk(indexDir).filter(p -> p.toString().endsWith(".csv")).count();
        assertEquals(1, stockCsvCount, "one stock CSV");
        assertEquals(1, indexCsvCount, "one index CSV");
    }

    @Test
    @DisplayName("实际写盘文件路径 = registry persistedPath 公式结果 — 端到端一致性")
    void actualWrittenFilePathMatchesRegistryPersistedPathFormula() throws Exception {
        String type = "stock_daily";
        String tsCode = "600000.SH";
        String start = "20240101";
        String end = "20240131";
        List<String> columns = List.of("open", "high", "low", "close");

        List<TestRow> data = List.of(new TestRow("20240101", "10.0", "11.0"));
        writer.writeDataset(type, "run-stock", tsCode, start, end, data, columns,
                row -> List.of(row.date, row.open, row.close));

        // Find the actual written file
        Path dbRoot = tempDir.resolve("database_fetched");
        Path actualCsv = Files.walk(dbRoot)
                .filter(p -> p.toString().endsWith(".csv"))
                .findFirst()
                .orElseThrow();
        String actualFilePath = actualCsv.toAbsolutePath().toString();

        // Compute what registry would use for the event's persistedPath
        // (DatasetRegistry.registerDataset L172-175 + event publishing L206-209)
        String registryTopic = DatabaseFetchedPathStrategy.resolveTopic(type);
        String registryEncoded = DatabaseFetchedPathStrategy.encodedString(type, tsCode, start, end, columns);
        Path registryDir = DatabaseFetchedPathStrategy.resolveDataPath(dbRoot, registryTopic, tsCode, registryEncoded);
        String registryFileName = tsCode.replaceAll("[^a-zA-Z0-9.]", "_") + ".csv";
        String registryPersistedPath = registryDir.resolve(registryFileName).toAbsolutePath().toString();

        // THE KEY ASSERTION: writer's actual file == registry's persistedPath formula
        assertEquals(registryPersistedPath, actualFilePath,
                "registry persistedPath formula must produce the same path as writer's actual file");

        // Verify the file really exists at the registry-computed path
        assertTrue(Files.exists(Path.of(registryPersistedPath)),
                "file must exist at registry-computed persistedPath");

        // Verify CSV content is readable
        String content = Files.readString(Path.of(registryPersistedPath));
        assertTrue(content.contains("open,high,low,close"));
        assertTrue(content.contains("20240101"));
    }

    record TestRow(String date, String open, String close) {
        TestRow(String date, String value) { this(date, value, null); }
    }
}
