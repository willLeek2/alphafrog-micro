package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.workflow.DatasetPersistedEvent;

import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetRegistryReusableEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private ApplicationEventPublisher eventPublisher;
    private DatasetRegistry registry;

    @TempDir
    Path tempDir;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        registry = new DatasetRegistry(redisTemplate, eventPublisher);
        ReflectionTestUtils.setField(registry, "enabled", true);
        ReflectionTestUtils.setField(registry, "ttlSeconds", 604800L);
        ReflectionTestUtils.setField(registry, "allowRangeReuse", true);
        AgentContext.setRunId("run-reuse");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void exactReusableHitPublishesRunScopedDatasetEvent() throws Exception {
        List<String> columns = List.of("trade_date", "close");
        DatasetRegistry.DatasetMeta meta = reusableMeta(
                "stock_daily", "000001.SZ", "20240101", "20240131", columns, "ds-exact");
        String queryKey = queryKey("stock_daily", "000001.SZ", "20240101", "20240131", columns);
        meta.setQueryKey(queryKey);

        when(valueOps.get(metaKey(queryKey))).thenReturn(MAPPER.writeValueAsString(meta));

        Optional<DatasetRegistry.DatasetMeta> found = registry.findReusable(
                "stock_daily", "000001.SZ", "20240101", "20240131", columns);

        assertTrue(found.isPresent());
        assertEquals("ds-exact", found.get().getDatasetId());
        DatasetPersistedEvent event = captureDatasetEvent();
        assertEquals("run-reuse", event.getRunId());
        assertEquals("ds-exact", event.getDatasetId());
        assertEquals(meta.getTsCode(), event.getFromTsCode());
        assertEquals(Path.of(meta.getPath()).resolve(meta.getDataFileName()).toAbsolutePath().toString(),
                event.getPersistedPath());
        assertEquals(meta.getDataFileName(), event.getSortKey());
    }

    @Test
    void rangeReusableHitPublishesRunScopedDatasetEvent() throws Exception {
        List<String> columns = List.of("trade_date", "close");
        String targetQueryKey = queryKey("stock_daily", "000001.SZ", "20240110", "20240120", columns);
        String candidateQueryKey = queryKey("stock_daily", "000001.SZ", "20240101", "20240131", columns);
        DatasetRegistry.DatasetMeta meta = reusableMeta(
                "stock_daily", "000001.SZ", "20240101", "20240131", columns, "ds-range");
        meta.setQueryKey(candidateQueryKey);

        when(valueOps.get(metaKey(targetQueryKey))).thenReturn(null);
        when(setOps.members(indexKey("stock_daily", "000001.SZ"))).thenReturn(Set.of(candidateQueryKey));
        when(valueOps.get(metaKey(candidateQueryKey))).thenReturn(MAPPER.writeValueAsString(meta));

        Optional<DatasetRegistry.DatasetMeta> found = registry.findReusable(
                "stock_daily", "000001.SZ", "20240110", "20240120", columns);

        assertTrue(found.isPresent());
        assertEquals("ds-range", found.get().getDatasetId());
        DatasetPersistedEvent event = captureDatasetEvent();
        assertEquals("run-reuse", event.getRunId());
        assertEquals("ds-range", event.getDatasetId());
        assertEquals(Path.of(meta.getPath()).resolve(meta.getDataFileName()).toAbsolutePath().toString(),
                event.getPersistedPath());
    }

    @Test
    void reusableManifestHitPublishesMemberDatasetAndManifestEvents() throws Exception {
        List<String> columns = List.of("trade_date", "close");
        DatasetRegistry.DatasetMeta dsMeta = reusableMeta(
                "stock_daily", "000001.SZ", "20240101", "20240131", columns, "ds-member");
        String dsQueryKey = queryKey("stock_daily", "000001.SZ", "20240101", "20240131", columns);
        dsMeta.setQueryKey(dsQueryKey);

        DatasetRegistry.ManifestMeta manifestMeta = reusableManifestMeta(
                "stock_daily", "20240101", "20240131", List.of("000001.SZ"), columns, "manifest-reuse");
        String manifestQueryKey = manifestQueryKey("stock_daily", "20240101", "20240131",
                List.of("000001.SZ"), columns);
        manifestMeta.setQueryKey(manifestQueryKey);

        when(valueOps.get(manifestMetaKey(manifestQueryKey))).thenReturn(MAPPER.writeValueAsString(manifestMeta));
        when(valueOps.get(metaKey(dsQueryKey))).thenReturn(MAPPER.writeValueAsString(dsMeta));

        Optional<DatasetRegistry.ManifestMeta> found = registry.findReusableManifest(
                "stock_daily", "20240101", "20240131", List.of("000001.SZ"), columns);

        assertTrue(found.isPresent());
        List<DatasetPersistedEvent> events = captureDatasetEvents(2);
        DatasetPersistedEvent datasetEvent = events.get(0);
        DatasetPersistedEvent manifestEvent = events.get(1);

        assertEquals("run-reuse", datasetEvent.getRunId());
        assertEquals("ds-member", datasetEvent.getDatasetId());
        assertEquals("000001.SZ", datasetEvent.getFromTsCode());

        assertEquals("run-reuse", manifestEvent.getRunId());
        assertEquals("manifest-reuse", manifestEvent.getManifestId());
        assertEquals(List.of("ds-member"), manifestEvent.getRelatedDatasetIds());
        assertEquals("000001.SZ", manifestEvent.getFromTsCode());
    }

    @Test
    void reusableManifestRebindsRelatedIdsToCurrentBatch() throws Exception {
        List<String> columns = List.of("trade_date", "close");
        DatasetRegistry.ManifestMeta manifestMeta = reusableManifestMeta(
                "stock_daily", "20240101", "20240131", List.of("000001.SZ"), columns,
                "manifest-reuse-old", "ds-from-previous-run", "20240101", "20240131");
        String queryKey = manifestQueryKey("stock_daily", "20240101", "20240131",
                List.of("000001.SZ"), columns);
        manifestMeta.setQueryKey(queryKey);
        when(valueOps.get(manifestMetaKey(queryKey))).thenReturn(MAPPER.writeValueAsString(manifestMeta));

        Optional<DatasetRegistry.ManifestMeta> found = registry.findReusableManifest(
                "stock_daily", "20240101", "20240131", List.of("000001.SZ"), columns,
                List.of("ds-from-current-run"));

        assertTrue(found.isPresent());
        DatasetPersistedEvent event = captureDatasetEvent();
        assertEquals("manifest-reuse-old", event.getManifestId());
        assertEquals(List.of("ds-from-current-run"), event.getRelatedDatasetIds());
    }

    @Test
    void manifestMemberRangeMismatchFallsBackToDatasetIdIndex() throws Exception {
        List<String> columns = List.of("trade_date", "close");
        DatasetRegistry.DatasetMeta dsMeta = reusableMeta(
                "stock_daily", "000001.SZ", "20240101", "20240131", columns, "ds-range-member");
        String dsQueryKey = queryKey("stock_daily", "000001.SZ", "20240101", "20240131", columns);
        dsMeta.setQueryKey(dsQueryKey);

        DatasetRegistry.ManifestMeta manifestMeta = reusableManifestMeta(
                "stock_daily", "20240110", "20240120", List.of("000001.SZ"), columns,
                "manifest-range-reuse", "ds-range-member", "20240110", "20240120");
        String manifestQueryKey = manifestQueryKey("stock_daily", "20240110", "20240120",
                List.of("000001.SZ"), columns);
        manifestMeta.setQueryKey(manifestQueryKey);

        String requestedMemberQueryKey = queryKey(
                "stock_daily", "000001.SZ", "20240110", "20240120", columns);
        when(valueOps.get(manifestMetaKey(manifestQueryKey))).thenReturn(MAPPER.writeValueAsString(manifestMeta));
        when(valueOps.get(metaKey(requestedMemberQueryKey))).thenReturn(null);
        when(setOps.members(indexKey("stock_daily", "000001.SZ"))).thenReturn(Set.of(dsQueryKey));
        when(valueOps.get(metaKey(dsQueryKey))).thenReturn(MAPPER.writeValueAsString(dsMeta));

        Optional<DatasetRegistry.ManifestMeta> found = registry.findReusableManifest(
                "stock_daily", "20240110", "20240120", List.of("000001.SZ"), columns);

        assertTrue(found.isPresent());
        List<DatasetPersistedEvent> events = captureDatasetEvents(2);
        DatasetPersistedEvent datasetEvent = events.get(0);
        DatasetPersistedEvent manifestEvent = events.get(1);

        assertEquals("ds-range-member", datasetEvent.getDatasetId());
        assertEquals(Path.of(dsMeta.getPath()).resolve(dsMeta.getDataFileName()).toAbsolutePath().toString(),
                datasetEvent.getPersistedPath());
        assertEquals("manifest-range-reuse", manifestEvent.getManifestId());
        assertEquals(List.of("ds-range-member"), manifestEvent.getRelatedDatasetIds());
    }

    private DatasetRegistry.DatasetMeta reusableMeta(String type, String tsCode, String startDate, String endDate,
                                                     List<String> columns, String datasetId) throws Exception {
        Path dir = tempDir.resolve(datasetId);
        Files.createDirectories(dir);
        String dataFileName = tsCode + ".csv";
        Files.writeString(dir.resolve(dataFileName), "trade_date,close\n20240101,1.0\n");
        long now = Instant.now().toEpochMilli();
        return DatasetRegistry.DatasetMeta.builder()
                .datasetId(datasetId)
                .type(type)
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .columns(columns)
                .columnsSignature(String.join(",", columns))
                .rowCount(1)
                .path(dir.toString())
                .format("csv")
                .dataFileName(dataFileName)
                .createdAt(now)
                .lastAccessAt(now)
                .hitCount(1)
                .ttlSeconds(604800L)
                .expireAt(now + 604800000L)
                .build();
    }

    private DatasetRegistry.ManifestMeta reusableManifestMeta(String dataType, String startDate, String endDate,
                                                             List<String> tsCodes, List<String> columns,
                                                             String manifestId) throws Exception {
        return reusableManifestMeta(dataType, startDate, endDate, tsCodes, columns, manifestId,
                "ds-member", startDate, endDate);
    }

    private DatasetRegistry.ManifestMeta reusableManifestMeta(String dataType, String startDate, String endDate,
                                                             List<String> tsCodes, List<String> columns,
                                                             String manifestId, String datasetId,
                                                             String memberStartDate, String memberEndDate)
            throws Exception {
        Path dir = tempDir.resolve(manifestId);
        Files.createDirectories(dir);
        List<DatasetManifest.ManifestMember> members = new ArrayList<>();
        for (String tsCode : tsCodes) {
            members.add(DatasetManifest.ManifestMember.builder()
                    .tsCode(tsCode)
                    .datasetId(datasetId)
                    .status(DatasetManifest.ManifestMember.STATUS_READY)
                    .rowCount(1)
                    .startDate(memberStartDate)
                    .endDate(memberEndDate)
                    .columns(columns)
                    .build());
        }
        DatasetManifest manifest = DatasetManifest.builder()
                .manifestId(manifestId)
                .kind(DatasetManifest.KIND)
                .dataType(dataType)
                .startDate(startDate)
                .endDate(endDate)
                .memberCount(members.size())
                .readyCount(members.size())
                .failedCount(0)
                .totalRowCount(members.size())
                .columns(columns)
                .columnsSignature(String.join(",", columns))
                .members(members)
                .createdAt(Instant.now().toEpochMilli())
                .build();
        MAPPER.writeValue(dir.resolve("manifest.json").toFile(), manifest);
        MAPPER.writeValue(dir.resolve("meta.json").toFile(), manifest);
        long now = Instant.now().toEpochMilli();
        return DatasetRegistry.ManifestMeta.builder()
                .manifestId(manifestId)
                .dataType(dataType)
                .startDate(startDate)
                .endDate(endDate)
                .columns(columns)
                .columnsSignature(String.join(",", columns))
                .memberCount(members.size())
                .readyCount(members.size())
                .failedCount(0)
                .totalRowCount(members.size())
                .path(dir.toString())
                .createdAt(now)
                .lastAccessAt(now)
                .hitCount(1)
                .ttlSeconds(604800L)
                .expireAt(now + 604800000L)
                .build();
    }

    private DatasetPersistedEvent captureDatasetEvent() {
        org.mockito.ArgumentCaptor<ApplicationEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return (DatasetPersistedEvent) captor.getValue();
    }

    private List<DatasetPersistedEvent> captureDatasetEvents(int count) {
        org.mockito.ArgumentCaptor<ApplicationEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, times(count)).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .map(event -> (DatasetPersistedEvent) event)
                .toList();
    }

    private String queryKey(String type, String tsCode, String startDate, String endDate, List<String> columns) {
        return ReflectionTestUtils.invokeMethod(registry, "buildQueryKey",
                type, tsCode, startDate, endDate, columns);
    }

    private String manifestQueryKey(String dataType, String startDate, String endDate,
                                    List<String> tsCodes, List<String> columns) {
        return ReflectionTestUtils.invokeMethod(registry, "buildManifestQueryKey",
                dataType, startDate, endDate, tsCodes, columns);
    }

    private static String metaKey(String queryKey) {
        return "dataset:meta:" + queryKey;
    }

    private static String manifestMetaKey(String queryKey) {
        return "manifest:meta:" + queryKey;
    }

    private static String indexKey(String type, String tsCode) {
        return "dataset:index:" + DatabaseFetchedPathStrategy.resolveTopic(type) + ":" + tsCode;
    }
}
