package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestWriterTest {

    @TempDir
    Path tempDir;

    private ManifestWriter writer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        writer = new ManifestWriter();
        ReflectionTestUtils.setField(writer, "datasetPath", tempDir.toString());
        ReflectionTestUtils.setField(writer, "enabled", true);
        objectMapper = new ObjectMapper();
    }

    @Test
    void writeManifest_shouldProduceStableManifestIdRegardlessOfInputOrder() {
        List<DatasetManifest.ManifestMember> orderA = new ArrayList<>();
        orderA.add(member("000001.SZ", "atomic-1", DatasetManifest.ManifestMember.STATUS_READY, 5));
        orderA.add(member("000002.SZ", "atomic-2", DatasetManifest.ManifestMember.STATUS_READY, 7));
        orderA.add(member("000300.SH", "atomic-3", DatasetManifest.ManifestMember.STATUS_READY, 3));

        List<DatasetManifest.ManifestMember> orderB = new ArrayList<>();
        orderB.add(member("000300.SH", "atomic-3", DatasetManifest.ManifestMember.STATUS_READY, 3));
        orderB.add(member("000001.SZ", "atomic-1", DatasetManifest.ManifestMember.STATUS_READY, 5));
        orderB.add(member("000002.SZ", "atomic-2", DatasetManifest.ManifestMember.STATUS_READY, 7));

        List<String> columns = List.of("trade_date", "close");

        String idA = writer.writeManifest("stock_daily", "20240101", "20240131", orderA, 15, columns);
        String idB = writer.writeManifest("stock_daily", "20240101", "20240131", orderB, 15, columns);

        assertEquals(idA, idB,
                "同一组资产不同输入顺序应产生相同 manifestId");
        assertTrue(idA.startsWith("manifest-stock_daily-20240101-20240131-"),
                "manifestId 命名空间应包含 dataType/起止日期：actual=" + idA);
    }

    @Test
    void writeManifest_shouldSortMembersByTsCode() throws Exception {
        List<DatasetManifest.ManifestMember> members = new ArrayList<>();
        members.add(member("000300.SH", "atomic-c", DatasetManifest.ManifestMember.STATUS_READY, 3));
        members.add(member("000001.SZ", "atomic-a", DatasetManifest.ManifestMember.STATUS_READY, 5));
        members.add(member("000002.SZ", "atomic-b", DatasetManifest.ManifestMember.STATUS_READY, 7));

        String manifestId = writer.writeManifest("stock_daily", "20240101", "20240131",
                members, 15, List.of("trade_date", "close"));

        File manifestFile = tempDir.resolve(manifestId).resolve(manifestId + ".manifest.json").toFile();
        assertTrue(manifestFile.exists(), "manifest.json 应已落盘");

        DatasetManifest loaded = objectMapper.readValue(manifestFile, DatasetManifest.class);
        assertEquals("000001.SZ", loaded.getMembers().get(0).getTsCode());
        assertEquals("000002.SZ", loaded.getMembers().get(1).getTsCode());
        assertEquals("000300.SH", loaded.getMembers().get(2).getTsCode());
    }

    @Test
    void writeManifest_shouldRecordPartialFailure() throws Exception {
        List<DatasetManifest.ManifestMember> members = new ArrayList<>();
        members.add(member("000001.SZ", "atomic-1", DatasetManifest.ManifestMember.STATUS_READY, 5));
        members.add(member("000002.SZ", null, DatasetManifest.ManifestMember.STATUS_FAILED, 0,
                "EMPTY_DATA", "no rows for 000002.SZ"));
        members.add(member("000300.SH", "atomic-3", DatasetManifest.ManifestMember.STATUS_READY, 3));

        String manifestId = writer.writeManifest("stock_daily", "20240101", "20240131",
                members, 8, List.of("trade_date", "close"));

        File manifestFile = tempDir.resolve(manifestId).resolve(manifestId + ".manifest.json").toFile();
        DatasetManifest loaded = objectMapper.readValue(manifestFile, DatasetManifest.class);

        assertEquals(3, loaded.getMemberCount());
        assertEquals(2, loaded.getReadyCount());
        assertEquals(1, loaded.getFailedCount());
        assertEquals(0, loaded.getBrokenCount());
        assertEquals(8, loaded.getTotalRowCount());

        DatasetManifest.ManifestMember failed = loaded.getMembers().get(1);
        assertEquals(DatasetManifest.ManifestMember.STATUS_FAILED, failed.getStatus());
        assertEquals("EMPTY_DATA", failed.getErrorCode());
        assertEquals("no rows for 000002.SZ", failed.getErrorMessage());
        assertNull(failed.getDatasetId());
    }

    @Test
    void writeManifest_shouldWriteBothJsonAndMeta() throws Exception {
        List<DatasetManifest.ManifestMember> members = List.of(
                member("000001.SZ", "atomic-1", DatasetManifest.ManifestMember.STATUS_READY, 5)
        );

        String manifestId = writer.writeManifest("stock_daily", "20240101", "20240131",
                members, 5, List.of("trade_date", "close"));

        File dir = tempDir.resolve(manifestId).toFile();
        assertTrue(dir.isDirectory());
        File manifestJson = new File(dir, manifestId + ".manifest.json");
        File metaJson = new File(dir, manifestId + ".meta.json");
        assertTrue(manifestJson.exists(), "manifest.json 应已落盘");
        assertTrue(metaJson.exists(), "meta.json 应已落盘");

        ManifestWriter.ManifestMeta meta = objectMapper.readValue(metaJson, ManifestWriter.ManifestMeta.class);
        assertEquals(manifestId, meta.getManifestId());
        assertEquals("stock_daily", meta.getDataType());
        assertEquals(1, meta.getMemberCount());
        assertEquals(1, meta.getReadyCount());
        assertEquals(0, meta.getFailedCount());
        assertEquals(5, meta.getTotalRowCount());
        assertNotNull(meta.getPath());
    }

    @Test
    void writeManifest_shouldReturnNullWhenDisabled() {
        ReflectionTestUtils.setField(writer, "enabled", false);
        String result = writer.writeManifest("stock_daily", "20240101", "20240131",
                List.of(member("000001.SZ", "atomic-1", DatasetManifest.ManifestMember.STATUS_READY, 1)),
                1, List.of("close"));
        assertNull(result);
    }

    @Test
    void writeManifest_shouldThrowOnMissingRequiredArgs() {
        try {
            writer.writeManifest(null, "20240101", "20240131", new ArrayList<>(), 0, new ArrayList<>());
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void writeManifest_shouldProduceDifferentIdsForDifferentColumns() {
        List<DatasetManifest.ManifestMember> members = List.of(
                member("000001.SZ", "atomic-1", DatasetManifest.ManifestMember.STATUS_READY, 1)
        );
        String id1 = writer.writeManifest("stock_daily", "20240101", "20240131", members, 1,
                List.of("trade_date", "close"));
        String id2 = writer.writeManifest("stock_daily", "20240101", "20240131", members, 1,
                List.of("trade_date", "close", "vol"));
        assertFalse(id1.equals(id2),
                "columns 集合不同应产生不同 manifestId");
    }

    @Test
    void buildQueryKeyForMembers_shouldMatchWriteManifest() throws Exception {
        String idFromWriter = writer.writeManifest("index_daily", "20240101", "20240131",
                List.of(member("000300.SH", "atomic-x", DatasetManifest.ManifestMember.STATUS_READY, 1)),
                1, List.of("close"));
        String queryKey = writer.buildQueryKeyForMembers("index_daily", "20240101", "20240131",
                List.of("000300.SH"), List.of("close"));
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(queryKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hashed) {
            sb.append(String.format("%02x", b));
        }
        String expectedHash8 = sb.toString().substring(0, 8);
        assertTrue(idFromWriter.endsWith(expectedHash8),
                "manifestId 末尾 8 位应等于 sha256(queryKey)[:8]");
    }

    private DatasetManifest.ManifestMember member(String tsCode, String datasetId, String status, int rowCount) {
        return DatasetManifest.ManifestMember.builder()
                .tsCode(tsCode)
                .datasetId(datasetId)
                .status(status)
                .rowCount(rowCount)
                .startDate("20240101")
                .endDate("20240131")
                .columns(List.of("trade_date", "close"))
                .build();
    }

    private DatasetManifest.ManifestMember member(String tsCode, String datasetId, String status, int rowCount,
                                                  String errorCode, String errorMessage) {
        return DatasetManifest.ManifestMember.builder()
                .tsCode(tsCode)
                .datasetId(datasetId)
                .status(status)
                .rowCount(rowCount)
                .startDate("20240101")
                .endDate("20240131")
                .columns(List.of("trade_date", "close"))
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
