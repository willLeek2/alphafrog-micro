package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestWriterMetadataTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistMemberAndTotalBytesWithSchemaHints() throws Exception {
        String dataType = "stock_daily";
        String tsCode = "000001.SZ";
        String start = "20240101";
        String end = "20240131";
        List<String> columns = List.of("trade_date", "close", "amount");
        Path databaseRoot = tempDir.resolve("database_fetched");
        Path dataDir = DatabaseFetchedPathStrategy.resolveDataPath(
                databaseRoot,
                DatabaseFetchedPathStrategy.resolveTopic(dataType),
                tsCode,
                DatabaseFetchedPathStrategy.encodedString(dataType, tsCode, start, end, columns));
        Files.createDirectories(dataDir);
        Path csv = dataDir.resolve(tsCode + ".csv");
        Files.writeString(csv, "trade_date,close,amount\n20240101,10.0,1000.0\n");

        ManifestWriter writer = new ManifestWriter();
        Path manifestsRoot = tempDir.resolve("manifests");
        ReflectionTestUtils.setField(writer, "manifestsPath", manifestsRoot.toString());
        ReflectionTestUtils.setField(writer, "databaseFetchedPath", databaseRoot.toString());
        ReflectionTestUtils.setField(writer, "enabled", true);
        ReflectionTestUtils.setField(writer, "localConfigLoader", null);
        DatasetManifest.ManifestMember member = DatasetManifest.ManifestMember.builder()
                .tsCode(tsCode)
                .datasetId("internal-dataset-id")
                .status(DatasetManifest.ManifestMember.STATUS_READY)
                .rowCount(1)
                .startDate(start)
                .endDate(end)
                .columns(columns)
                .build();

        String manifestId = writer.writeManifest(dataType, start, end, List.of(member), 1, columns);
        JsonNode manifest = new ObjectMapper().readTree(
                DatabaseFetchedPathStrategy.resolveManifestPath(manifestsRoot, manifestId)
                        .resolve("manifest.json").toFile());

        assertEquals(Files.size(csv), manifest.path("totalBytes").asLong());
        assertEquals(Files.size(csv), manifest.path("members").path(0).path("bytes").asLong());
        assertEquals("Int64", manifest.path("recommendedDtype").path("trade_date").asText());
        assertEquals("float64", manifest.path("recommendedDtype").path("amount").asText());
        assertEquals("complete", manifest.path("metadataStatus").asText());
    }
}
