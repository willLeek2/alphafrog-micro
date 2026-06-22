package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.DatasetPersistedEvent.PersistedArtifactType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunDatasetCsvWriterTest {

    private AgentRunDatasetEntry datasetEntry(int number, String datasetId, String sortKey, String fromTsCode) {
        return AgentRunDatasetEntry.forDataset(number, datasetId,
                "/data/database_fetched/" + datasetId + "/" + sortKey,
                fromTsCode, sortKey);
    }

    private AgentRunDatasetEntry manifestEntry(int number, String manifestId, String sortKey,
                                               List<String> related) {
        return AgentRunDatasetEntry.forManifest(number, manifestId,
                "/data/manifests/v1/manifest-" + manifestId + "/" + sortKey,
                "UNCERTAIN", sortKey, related);
    }

    private AgentRunDatasetEntry manifestNone(int number, String manifestId, List<String> related) {
        return AgentRunDatasetEntry.forManifest(number, manifestId, "", "UNCERTAIN",
                "manifest.json", related);
    }

    @Test
    void pathsDatasetCsvShouldIncludeHeaderAndAllRows() {
        AgentRunDatasetSnapshot snap = new AgentRunDatasetSnapshot(
                List.of(
                        datasetEntry(1, "ds-a", "a.csv", "000300.SH"),
                        datasetEntry(2, "ds-b", "b.csv", "000300.SH#510300.SH")
                ),
                List.of());
        String csv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(snap);
        String[] lines = csv.split("\n");
        assertEquals("agent_run_dataset_id,dataset_file_path,from_ts_code", lines[0]);
        assertEquals("1,/__AF_INPUT__/ds-a/a.csv,000300.SH", lines[1]);
        // multi-ts-code 含 `#`，按 spec §4.1.2 不强制 quote，保持简单
        assertEquals("2,/__AF_INPUT__/ds-b/b.csv,000300.SH#510300.SH", lines[2]);
    }

    @Test
    void pathsDatasetCsvWithEmptySnapshotShouldOnlyEmitHeader() {
        AgentRunDatasetSnapshot snap = AgentRunDatasetSnapshot.empty();
        String csv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(snap);
        assertEquals("agent_run_dataset_id,dataset_file_path,from_ts_code\n", csv);
    }

    @Test
    void pathManifestCsvShouldJoinRelatedDatasetIdsWithHash() {
        AgentRunDatasetSnapshot snap = new AgentRunDatasetSnapshot(
                List.of(),
                List.of(
                        manifestEntry(1, "m-x", "manifest.json", List.of("ds-1", "ds-2", "ds-3")),
                        manifestEntry(2, "m-y", "manifest.json", List.of("ds-4"))
                )
        );
        String csv = AgentRunDatasetCsvWriter.writePathManifestCsv(snap);
        String[] lines = csv.split("\n");
        assertEquals("agent_run_manifest_id,manifest_file_path,related_dataset_ids", lines[0]);
        assertEquals("1,/__AF_INPUT__/m-x/manifest.json,ds-1#ds-2#ds-3", lines[1]);
        assertEquals("2,/__AF_INPUT__/m-y/manifest.json,ds-4", lines[2]);
    }

    @Test
    void pathManifestCsvWithNoneMarkerShouldEmitNoneLiteral() {
        AgentRunDatasetSnapshot snap = new AgentRunDatasetSnapshot(
                List.of(),
                List.of(manifestNone(1, "m-virtual", List.of("ds-1")))
        );
        String csv = AgentRunDatasetCsvWriter.writePathManifestCsv(snap);
        String[] lines = csv.split("\n");
        assertEquals("1,NONE,ds-1", lines[1]);
    }

    @Test
    void csvRowShouldQuoteFieldsContainingComma() {
        // dataset 描述中包含逗号的情况 (罕见但 spec §4 RFC 简化版)
        AgentRunDatasetEntry e = AgentRunDatasetEntry.forDataset(1, "ds-x",
                "/path", "000300.SH", "a, b.csv");
        AgentRunDatasetSnapshot snap = new AgentRunDatasetSnapshot(List.of(e), List.of());
        String csv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(snap);
        String[] lines = csv.split("\n");
        assertTrue(lines[1].contains("\"/__AF_INPUT__/ds-x/a, b.csv\""),
                "field with comma should be quoted; got: " + lines[1]);
    }

    @Test
    void csvRowShouldEscapeInternalDoubleQuotes() {
        AgentRunDatasetEntry e = AgentRunDatasetEntry.forDataset(1, "ds-x",
                "/path", "ts \"with quote\"", "a.csv");
        AgentRunDatasetSnapshot snap = new AgentRunDatasetSnapshot(List.of(e), List.of());
        String csv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(snap);
        assertTrue(csv.contains("\"ts \"\"with quote\"\"\""),
                "double quote inside quoted field should be doubled; got: " + csv);
    }

    @Test
    void sandboxInputPlaceholderShouldBeStableForPythonSubstitution() {
        // 任何写 CSV 的代码路径必须用同一个 placeholder；Python 端 replace 时才一致
        AgentRunDatasetSnapshot snap = new AgentRunDatasetSnapshot(
                List.of(datasetEntry(1, "ds-a", "a.csv", "000300.SH")),
                List.of(manifestEntry(1, "m-x", "manifest.json", List.of("ds-a"))));
        String dsCsv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(snap);
        String mfCsv = AgentRunDatasetCsvWriter.writePathManifestCsv(snap);
        assertTrue(dsCsv.contains(AgentRunDatasetCsvWriter.SANDBOX_INPUT_PLACEHOLDER));
        assertTrue(mfCsv.contains(AgentRunDatasetCsvWriter.SANDBOX_INPUT_PLACEHOLDER));
    }

    @Test
    void unrelatedEntryShouldNotAppearInOppositeCsv() {
        AgentRunDatasetSnapshot snap = new AgentRunDatasetSnapshot(
                List.of(datasetEntry(1, "ds-alpha", "alpha.csv", "000300.SH")),
                List.of(manifestEntry(1, "manifest-beta", "manifest.json", List.of("ds-alpha"))));
        String dsCsv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(snap);
        String mfCsv = AgentRunDatasetCsvWriter.writePathManifestCsv(snap);
        // dataset CSV 不应包含 manifest 文件路径
        assertFalse(dsCsv.contains("manifest-beta"));
        assertFalse(dsCsv.contains("manifest.json"));
        // manifest CSV 不应包含 dataset file path 字段
        assertFalse(mfCsv.contains("/__AF_INPUT__/ds-alpha/alpha.csv"));
    }

    @Test
    void entryTypesShouldBePreserved() {
        AgentRunDatasetEntry ds = datasetEntry(1, "ds-a", "a.csv", "ts");
        AgentRunDatasetEntry mf = manifestEntry(1, "m-x", "manifest.json", List.of("ds-a"));
        assertEquals(PersistedArtifactType.DATASET, ds.artifactType());
        assertEquals(PersistedArtifactType.MANIFEST, mf.artifactType());
        assertTrue(ds.isDataset());
        assertTrue(mf.isManifest());
    }
}
