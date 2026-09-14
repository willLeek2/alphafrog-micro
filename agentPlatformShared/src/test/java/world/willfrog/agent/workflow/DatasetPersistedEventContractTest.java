package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract fixture test — frozen interface between sub-task 01 (storage) and 02 (run-level ID).
 */
class DatasetPersistedEventContractTest {

    @Test
    void datasetEventShouldHaveCorrectFields() {
        DatasetPersistedEvent event = new DatasetPersistedEvent(
                this, "run-abc", "ds-xyz",
                "/data/database_fetched/domestic_listed_asset/600000.SH/ABC123/600000.SH.csv",
                "600000.SH", "600000.SH.csv"
        );

        assertEquals("run-abc", event.getRunId());
        assertEquals(DatasetPersistedEvent.PersistedArtifactType.DATASET, event.getArtifactType());
        assertEquals("ds-xyz", event.getDatasetId());
        assertNull(event.getManifestId(), "dataset event 的 manifestId 应为 null");
        assertTrue(event.getPersistedPath().contains("database_fetched"));
        assertEquals("600000.SH", event.getFromTsCode());
        assertTrue(event.getRelatedDatasetIds().isEmpty());
        assertEquals("600000.SH.csv", event.getSortKey());
    }

    @Test
    void manifestEventShouldHaveRelatedDatasetIds() {
        DatasetPersistedEvent event = new DatasetPersistedEvent(
                this, "run-abc", "m-xyz",
                "/data/manifests/v1/manifest-m-xyz/manifest.json",
                "UNCERTAIN",
                List.of("ds-1", "ds-2", "ds-3"),
                "manifest.json"
        );

        assertEquals(DatasetPersistedEvent.PersistedArtifactType.MANIFEST, event.getArtifactType());
        assertEquals("m-xyz", event.getManifestId());
        assertNull(event.getDatasetId(), "manifest event 的 datasetId 应为 null");
        assertEquals(3, event.getRelatedDatasetIds().size());
        assertTrue(event.getRelatedDatasetIds().contains("ds-1"));
    }

    @Test
    void fromTsCodeShouldDefaultToUncertainWhenNull() {
        DatasetPersistedEvent event = new DatasetPersistedEvent(
                this, "run-1", "ds-1", "/path/ds-1.csv", null, "ds-1.csv"
        );
        assertEquals("UNCERTAIN", event.getFromTsCode());
    }

    @Test
    void fromTsCodeShouldDefaultToUncertainWhenBlank() {
        DatasetPersistedEvent event = new DatasetPersistedEvent(
                this, "run-1", "ds-1", "/path/ds-1.csv", "  ", "ds-1.csv"
        );
        assertEquals("UNCERTAIN", event.getFromTsCode());
    }

    @Test
    void multiTsCodeShouldUseHashSeparator() {
        DatasetPersistedEvent event = new DatasetPersistedEvent(
                this, "run-1", "ds-1", "/path/ds-1.csv",
                "000300.SH#510300.SH", "ds-1.csv"
        );
        assertTrue(event.getFromTsCode().contains("#"));
    }

    @Test
    void relatedDatasetIdsShouldBeImmutable() {
        DatasetPersistedEvent event = new DatasetPersistedEvent(
                this, "run-1", "m-1", "/path/m-1.json",
                "UNCERTAIN", List.of("ds-1"), "manifest.json"
        );
        assertThrows(UnsupportedOperationException.class,
                () -> event.getRelatedDatasetIds().add("ds-3"));
    }

    @Test
    void sortKeyShouldBeFilename() {
        DatasetPersistedEvent event = new DatasetPersistedEvent(
                this, "run-1", "ds-1",
                "/data/database_fetched/domestic_index/000300.SH/DEF456/000300.SH.csv",
                "000300.SH", "000300.SH.csv"
        );
        assertEquals("000300.SH.csv", event.getSortKey());
    }

    @Test
    void nullConstructorArgsShouldThrow() {
        assertThrows(NullPointerException.class, () ->
                new DatasetPersistedEvent(this, null, "ds", "/p", "UNCERTAIN", "k"));
        assertThrows(NullPointerException.class, () ->
                new DatasetPersistedEvent(this, "run", "ds", null, "UNCERTAIN", "k"));
        assertThrows(NullPointerException.class, () ->
                new DatasetPersistedEvent(this, "run", "ds", "/p", "UNCERTAIN", null));
    }
}
