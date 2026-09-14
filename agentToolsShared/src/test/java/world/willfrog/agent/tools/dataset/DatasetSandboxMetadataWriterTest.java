package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetSandboxMetadataWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldRenderVersionedPathFreeDatasetMetadata() throws Exception {
        Path csv = tempDir.resolve("000001.SZ.csv");
        Files.writeString(csv, "ts_code,trade_date,close\n000001.SZ,20240101,10.5\n");
        Files.writeString(tempDir.resolve("000001.SZ.meta.json"), """
                {
                  "rowCount": 1,
                  "columns": ["ts_code", "trade_date", "close"],
                  "recommendedUsecols": ["ts_code", "trade_date", "close"],
                  "recommendedDtype": {"trade_date": "Int64", "close": "float64"},
                  "readProfiles": {"price_volume": ["ts_code", "trade_date", "close"]}
                }
                """);
        AgentRunDatasetEntry entry = AgentRunDatasetEntry.forDataset(
                7, "internal-dataset-id", csv.toString(), "000001.SZ", csv.getFileName().toString());

        String json = new DatasetSandboxMetadataWriter().writeDatasetMetadata(
                new AgentRunDatasetSnapshot(List.of(entry), List.of()));
        JsonNode root = objectMapper.readTree(json);
        JsonNode metadata = root.path("datasets").path("7");

        assertEquals("agent_run_dataset_meta_v1", root.path("schema_version").asText());
        assertEquals(1, metadata.path("rowCount").asLong());
        assertEquals(Files.size(csv), metadata.path("bytes").asLong());
        assertEquals("complete", metadata.path("metadataStatus").asText());
        assertEquals("Int64", metadata.path("recommendedDtype").path("trade_date").asText());
        assertFalse(json.contains("internal-dataset-id"));
        assertFalse(json.contains(tempDir.toString()));
    }

    @Test
    void missingMetadataShouldUseStatAndHeaderWithoutInventingRowCount() throws Exception {
        Path csv = tempDir.resolve("partial.csv");
        Files.writeString(csv, "trade_date,close\n20240101,10.5\n");
        AgentRunDatasetEntry entry = AgentRunDatasetEntry.forDataset(
                1, "ds", csv.toString(), "UNCERTAIN", csv.getFileName().toString());

        JsonNode metadata = objectMapper.readTree(new DatasetSandboxMetadataWriter()
                        .writeDatasetMetadata(new AgentRunDatasetSnapshot(List.of(entry), List.of())))
                .path("datasets").path("1");

        assertTrue(metadata.path("rowCount").isNull());
        assertEquals(Files.size(csv), metadata.path("bytes").asLong());
        assertEquals(List.of("trade_date", "close"), objectMapper.convertValue(
                metadata.path("columns"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals("partial", metadata.path("metadataStatus").asText());
    }

    @Test
    void schemaHintsShouldKeepFinancialNumbersAtFloat64ByDefault() {
        DatasetSchemaHintResolver.SchemaHints hints = DatasetSchemaHintResolver.resolve(
                List.of("ts_code", "trade_date", "open", "close", "amount"));

        assertEquals("category", hints.recommendedDtype().get("ts_code"));
        assertEquals("Int64", hints.recommendedDtype().get("trade_date"));
        assertEquals("float64", hints.recommendedDtype().get("close"));
        assertTrue(hints.readProfiles().get("price_volume").contains("amount"));
    }

    @Test
    void shouldRenderManifestTotalsAndRunLevelMemberNumbers() throws Exception {
        Path first = tempDir.resolve("first.csv");
        Path second = tempDir.resolve("second.csv");
        Files.writeString(first, "trade_date,close\n20240101,10.0\n");
        Files.writeString(second, "trade_date,close\n20240102,11.0\n");
        Path manifest = tempDir.resolve("manifest.json");
        Files.writeString(manifest, """
                {
                  "totalRowCount": 2,
                  "columns": ["trade_date", "close"],
                  "recommendedUsecols": ["trade_date", "close"],
                  "recommendedDtype": {"trade_date": "Int64", "close": "float64"}
                }
                """);
        AgentRunDatasetEntry datasetOne = AgentRunDatasetEntry.forDataset(
                7, "internal-one", first.toString(), "000001.SZ", first.getFileName().toString());
        AgentRunDatasetEntry datasetTwo = AgentRunDatasetEntry.forDataset(
                9, "internal-two", second.toString(), "000002.SZ", second.getFileName().toString());
        AgentRunDatasetEntry manifestEntry = AgentRunDatasetEntry.forManifest(
                3, "internal-manifest", manifest.toString(), "MULTI", manifest.getFileName().toString(),
                List.of("9", "7"));

        String json = new DatasetSandboxMetadataWriter().writeManifestMetadata(
                new AgentRunDatasetSnapshot(List.of(datasetOne, datasetTwo), List.of(manifestEntry)));
        JsonNode root = objectMapper.readTree(json);
        JsonNode metadata = root.path("manifests").path("3");

        assertEquals("agent_run_manifest_meta_v1", root.path("schema_version").asText());
        assertEquals(2, metadata.path("totalRowCount").asLong());
        assertEquals(Files.size(manifest), metadata.path("totalBytes").asLong());
        assertEquals(List.of(9, 7), objectMapper.convertValue(
                metadata.path("memberNumbers"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class)));
        assertFalse(metadata.has("rowCount"));
        assertFalse(metadata.has("bytes"));
        assertFalse(json.contains("internal-one"));
        assertFalse(json.contains("internal-two"));
        assertFalse(json.contains("internal-manifest"));
        assertFalse(json.contains(tempDir.toString()));
    }
}
