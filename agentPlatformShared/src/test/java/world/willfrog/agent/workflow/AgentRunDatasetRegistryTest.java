package world.willfrog.agent.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.DatasetPersistedEvent.PersistedArtifactType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunDatasetRegistryTest {

    private AgentRunDatasetRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentRunDatasetRegistry();
    }

    private DatasetPersistedEvent datasetEvent(String runId, String datasetId, String sortKey) {
        return new DatasetPersistedEvent(this, runId, datasetId,
                "/data/database_fetched/domestic_listed_asset/600000.SH/" + datasetId + "/" + sortKey,
                "600000.SH", sortKey);
    }

    private DatasetPersistedEvent multiTsDatasetEvent(String runId, String datasetId, String sortKey, String fromTsCode) {
        return new DatasetPersistedEvent(this, runId, datasetId,
                "/data/database_fetched/domestic_listed_asset/" + datasetId + "/" + sortKey,
                fromTsCode, sortKey);
    }

    private DatasetPersistedEvent manifestEvent(String runId, String manifestId, String sortKey, List<String> related) {
        return new DatasetPersistedEvent(this, runId, manifestId,
                "/data/manifests/v1/manifest-" + manifestId + "/manifest.json",
                "UNCERTAIN", related, sortKey);
    }

    @Test
    void datasetEventsShouldAssignMonotonicNumbersInArrivalOrder() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-c", "c.csv"));

        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        assertEquals(3, snap.datasets().size());
        assertEquals(1, snap.datasets().get(0).number());
        assertEquals(2, snap.datasets().get(1).number());
        assertEquals(3, snap.datasets().get(2).number());
        assertEquals("ds-a", snap.datasets().get(0).originalId());
        assertEquals("ds-b", snap.datasets().get(1).originalId());
        assertEquals("ds-c", snap.datasets().get(2).originalId());
    }

    /**
     * MF4 拍板：Q5 sortKey 字典序作为稳定全局序（不是事件到达顺序）。
     * 故意乱序到达 (b, c, a) → snapshot 编号必须按 sortKey 升序 (a=1, b=2, c=3)。
     */
    @Test
    void outOfOrderArrivalShouldAssignNumbersBySortKeyLexicographic() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-c", "c.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));

        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        assertEquals(3, snap.datasets().size());
        // 编号必须按 sortKey 升序：a.csv=1, b.csv=2, c.csv=3
        assertEquals(1, snap.datasets().get(0).number());
        assertEquals("a.csv", snap.datasets().get(0).sortKey());
        assertEquals("ds-a", snap.datasets().get(0).originalId());
        assertEquals(2, snap.datasets().get(1).number());
        assertEquals("b.csv", snap.datasets().get(1).sortKey());
        assertEquals("ds-b", snap.datasets().get(1).originalId());
        assertEquals(3, snap.datasets().get(2).number());
        assertEquals("c.csv", snap.datasets().get(2).sortKey());
        assertEquals("ds-c", snap.datasets().get(2).originalId());
    }

    /**
     * MF4 拍板：manifest 编号同样按 sortKey 字典序，与 dataset 编号空间独立（Q4）。
     */
    @Test
    void outOfOrderArrivalShouldAssignManifestNumbersBySortKeyLexicographic() {
        registry.onDatasetPersisted(manifestEvent("run-1", "m-z", "z.manifest.json", List.of()));
        registry.onDatasetPersisted(manifestEvent("run-1", "m-a", "a.manifest.json", List.of()));

        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        assertEquals(2, snap.manifests().size());
        assertEquals(1, snap.manifests().get(0).number());
        assertEquals("a.manifest.json", snap.manifests().get(0).sortKey());
        assertEquals(2, snap.manifests().get(1).number());
        assertEquals("z.manifest.json", snap.manifests().get(1).sortKey());
    }

    @Test
    void datasetAndManifestShouldHaveIndependentNumberSpaces() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        registry.onDatasetPersisted(manifestEvent("run-1", "m-x", "manifest.json", List.of("ds-a")));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        registry.onDatasetPersisted(manifestEvent("run-1", "m-y", "manifest2.json", List.of("ds-b")));

        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        assertEquals(2, snap.datasets().size());
        assertEquals(2, snap.manifests().size());
        assertEquals(1, snap.datasets().get(0).number());
        assertEquals(2, snap.datasets().get(1).number());
        assertEquals(1, snap.manifests().get(0).number());
        assertEquals(2, snap.manifests().get(1).number());
    }

    @Test
    void duplicateDatasetIdShouldReuseSameNumber() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        // 重发 ds-a（同 datasetId 不同 sortKey），应该复用 number=1
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));

        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        assertEquals(2, snap.datasets().size());
        assertEquals(1, snap.datasets().get(0).number());
        assertEquals("ds-a", snap.datasets().get(0).originalId());
        assertEquals(2, snap.datasets().get(1).number());
        assertEquals("ds-b", snap.datasets().get(1).originalId());
    }

    @Test
    void fromTsCodeShouldBePreservedFromEvent() {
        registry.onDatasetPersisted(multiTsDatasetEvent("run-1", "ds-a", "a.csv", "000300.SH#510300.SH"));
        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        assertEquals("000300.SH#510300.SH", snap.datasets().get(0).fromTsCode());
    }

    @Test
    void manifestRelatedDatasetIdsShouldBeImmutableInSnapshot() {
        // 260623 MF2: relatedDatasetIds 在 snapshot 时翻译成 run-level 编号；为保证翻译出 3 个 ref，
        // 先注册对应 3 个 dataset。
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-1", "1.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-2", "2.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-3", "3.csv"));
        registry.onDatasetPersisted(manifestEvent("run-1", "m-x", "manifest.json", List.of("ds-1", "ds-2", "ds-3")));
        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        AgentRunDatasetEntry m = snap.manifests().get(0);
        assertEquals(3, m.relatedDatasetIds().size());
        assertThrows(UnsupportedOperationException.class,
                () -> m.relatedDatasetIds().add("4"));
    }

    /**
     * MF2 拍板（spec §4.2.2）：manifest.related_dataset_ids 在 CSV 里必须是 agent run 级别
     * dataset 编号（不是 originalId），且必须用同 snapshot 的编号（按 sortKey 排序后）。
     *
     * <p>用例：先注册 dataset ds-a (sortKey a.csv) 和 ds-b (sortKey b.csv)，但事件到达顺序为
     * 「ds-b 先到 → ds-a 后到」。再注册 manifest 引用 [originalB, originalA]。Snapshot 编号
     * 应按 sortKey 升序：ds-a=1, ds-b=2；manifest.relatedDatasetIds 必须翻译成 [2, 1]，
     * 不是 [1, 2]（那会是到达顺序而非字典序）。
     */
    @Test
    void manifestRelatedDatasetIdsShouldTranslateToSnapshotNumbers() {
        // 乱序到达：b 先到、a 后到
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        // manifest 引用 [originalB, originalA]，按原始字符串顺序传入
        registry.onDatasetPersisted(manifestEvent("run-1", "m-1", "manifest.json",
                List.of("ds-b", "ds-a")));

        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        assertEquals(2, snap.datasets().size());
        // snapshot 按 sortKey 升序：a.csv=1, b.csv=2
        assertEquals(1, snap.datasets().get(0).number());
        assertEquals("ds-a", snap.datasets().get(0).originalId());
        assertEquals(2, snap.datasets().get(1).number());
        assertEquals("ds-b", snap.datasets().get(1).originalId());

        AgentRunDatasetEntry mf = snap.manifests().get(0);
        // 关键：manifest.relatedDatasetIds 必须翻译成 ["2", "1"]（按 originalB, originalA 的输入顺序），
        // 而不是保留原字符串 [ds-b, ds-a]
        assertEquals(List.of("2", "1"), mf.relatedDatasetIds(),
                "relatedDatasetIds 应翻译成同 snapshot 的 run-level 编号字符串（按输入顺序）");
    }

    /**
     * MF2 边界：manifest 引用的 originalId 在 snapshot 时如果不存在（dataset 还没注册），
     * 该引用必须被丢弃并 log warning，manifest 注册本身不失败。
     */
    @Test
    void manifestWithUnknownRelatedDatasetIdShouldDropRefAndKeepManifest() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        registry.onDatasetPersisted(manifestEvent("run-1", "m-1", "manifest.json",
                List.of("ds-a", "ds-unknown")));

        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        assertEquals(1, snap.manifests().size());
        AgentRunDatasetEntry mf = snap.manifests().get(0);
        // 已知 ref 翻译成 ["1"]，未知 ref 被丢弃
        assertEquals(List.of("1"), mf.relatedDatasetIds(),
                "未知 originalId 必须被丢弃，已知 originalId 翻译成 run-level 编号字符串");
    }

    /**
     * MF2 端到端：转译后的 relatedDatasetIds 通过 CsvWriter 输出 CSV 时呈现为编号 join（不是 originalId）。
     */
    @Test
    void csvShouldEmitTranslatedNumbersForRelatedDatasetIds() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        registry.onDatasetPersisted(manifestEvent("run-1", "m-1", "manifest.json",
                List.of("ds-b", "ds-a")));

        AgentRunDatasetSnapshot snap = registry.snapshot("run-1");
        String manifestCsv = AgentRunDatasetCsvWriter.writePathManifestCsv(snap);
        // manifest number=1, related 翻译成 ["2", "1"]（按输入顺序 originalB, originalA） → CSV "2#1"
        String[] lines = manifestCsv.split("\n");
        assertEquals(2, lines.length);
        // MF3: 4 列 (manifest_file_path, related_dataset_ids, source_path) — 末尾 source_path 是 datasetEvent 提供的 path
        assertTrue(lines[1].startsWith("1,/__AF_INPUT__/m-1/manifest.json,2#1,"),
                "MF3 schema: lines[1] = " + lines[1]);
    }

    @Test
    void listDatasetNumbersShouldReturnSortedLegalIds() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-c", "c.csv"));

        List<Integer> legal = registry.listDatasetNumbers("run-1");
        assertEquals(List.of(1, 2, 3), legal);
    }

    @Test
    void listDatasetNumbersForUnknownRunShouldReturnEmpty() {
        assertEquals(List.of(), registry.listDatasetNumbers("never-existed"));
    }

    @Test
    void findDatasetByNumberShouldReturnEntry() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        Optional<AgentRunDatasetEntry> found = registry.findDatasetByNumber("run-1", 1);
        assertTrue(found.isPresent());
        assertEquals("ds-a", found.get().originalId());
        assertEquals(PersistedArtifactType.DATASET, found.get().artifactType());
    }

    @Test
    void findDatasetByUnknownNumberShouldReturnEmpty() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        assertTrue(registry.findDatasetByNumber("run-1", 99).isEmpty());
    }

    @Test
    void resolveDatasetNumberShouldReturnAssignedNumber() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        assertEquals(Optional.of(1), registry.resolveDatasetNumber("run-1", "ds-a"));
        assertEquals(Optional.of(2), registry.resolveDatasetNumber("run-1", "ds-b"));
    }

    @Test
    void resetShouldClearPerRunState() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        assertTrue(registry.hasRunState("run-1"));
        registry.reset("run-1");
        assertFalse(registry.hasRunState("run-1"));
        assertEquals(0, registry.snapshot("run-1").datasets().size());
    }

    @Test
    void resetOnUnknownRunShouldBeNoOp() {
        // 不抛异常
        registry.reset("never-existed");
    }

    @Test
    void snapshotOnUnknownRunShouldReturnEmpty() {
        AgentRunDatasetSnapshot snap = registry.snapshot("never-existed");
        assertTrue(snap.isEmpty());
    }

    @Test
    void concurrentEventsFromSameRunShouldAssignUniqueNumbers() throws InterruptedException {
        String runId = "run-concurrent";
        int threads = 8;
        int eventsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger collisions = new AtomicInteger(0);

        try {
            for (int t = 0; t < threads; t++) {
                int threadIdx = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < eventsPerThread; i++) {
                            String datasetId = "ds-t" + threadIdx + "-i" + i;
                            registry.onDatasetPersisted(datasetEvent(runId, datasetId, datasetId + ".csv"));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads should finish");
        } finally {
            pool.shutdownNow();
        }

        AgentRunDatasetSnapshot snap = registry.snapshot(runId);
        int total = threads * eventsPerThread;
        assertEquals(total, snap.datasets().size());
        // 检查 number 都是唯一的 1..total
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (AgentRunDatasetEntry entry : snap.datasets()) {
            assertTrue(seen.add(entry.number()),
                    "Duplicate number assigned: " + entry.number() + " for " + entry.originalId());
        }
        assertEquals(total, seen.size());
    }

    @Test
    void concurrentEventsAcrossRunsShouldBeIsolated() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);

        try {
            for (int t = 0; t < 4; t++) {
                int runIdx = t;
                pool.submit(() -> {
                    try {
                        String runId = "run-iso-" + runIdx;
                        for (int i = 0; i < 10; i++) {
                            registry.onDatasetPersisted(
                                    datasetEvent(runId, "ds-" + i, "ds-" + i + ".csv"));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        for (int t = 0; t < 4; t++) {
            String runId = "run-iso-" + t;
            assertEquals(10, registry.snapshot(runId).datasets().size());
        }
    }

    @Test
    void eventWithBlankRunIdShouldBeIgnored() {
        // contract: Objects.requireNonNull(runId) → null runId 在 event 构造时就抛 NPE
        // 所以这里只测 blank runId ("") 应被 registry 忽略
        assertThrows(NullPointerException.class,
                () -> new DatasetPersistedEvent(this, null, "ds-a", "/p", "ts", "k"));
        // blank runId 走到 registry 内部被忽略
        registry.onDatasetPersisted(datasetEvent("   ", "ds-a", "a.csv"));
        assertFalse(registry.hasRunState("   "));
    }

    @Test
    void datasetEntryRecordShouldRejectNullFields() {
        assertThrows(NullPointerException.class, () ->
                AgentRunDatasetEntry.forDataset(1, null, "/path", "ts", "sortKey"));
        assertThrows(NullPointerException.class, () ->
                AgentRunDatasetEntry.forDataset(1, "ds", null, "ts", "sortKey"));
        assertThrows(IllegalArgumentException.class, () ->
                AgentRunDatasetEntry.forDataset(0, "ds", "/path", "ts", "sortKey"));
    }

    @Test
    void snapshotShouldReturnIndependentCopies() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        AgentRunDatasetSnapshot s1 = registry.snapshot("run-1");
        // 再加一个 event，s1 不受影响
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        AgentRunDatasetSnapshot s2 = registry.snapshot("run-1");
        assertEquals(1, s1.datasets().size());
        assertEquals(2, s2.datasets().size());
    }

    /**
     * MF3 lock-after-snapshot (visible-after-freeze)：
     * 先 snapshot 暴露 ds-a=1, ds-b=2；之后迟到 ds-c (sortKey "aa.csv"，字典序在 a.csv 和 b.csv 之间)，
     * 再次 snapshot，ds-a 和 ds-b 的 number 必须不变，ds-c 拿到下一个 number (= 3)。
     */
    @Test
    void snapshotShouldFreezeDatasetNumbersAfterFirstExposure() {
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-a", "a.csv"));
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-b", "b.csv"));
        AgentRunDatasetSnapshot snap1 = registry.snapshot("run-1");
        assertEquals(2, snap1.datasets().size());
        assertEquals(1, snap1.datasets().get(0).number());
        assertEquals("ds-a", snap1.datasets().get(0).originalId());
        assertEquals(2, snap1.datasets().get(1).number());
        assertEquals("ds-b", snap1.datasets().get(1).originalId());

        // 迟到 ds-c，sortKey "aa.csv" 字典序在 a.csv 和 b.csv 之间
        registry.onDatasetPersisted(datasetEvent("run-1", "ds-c", "aa.csv"));
        AgentRunDatasetSnapshot snap2 = registry.snapshot("run-1");

        // ds-a 和 ds-b 编号 frozen 不变，仍在原位置
        assertEquals(3, snap2.datasets().size());
        assertEquals(1, snap2.datasets().get(0).number());
        assertEquals("ds-a", snap2.datasets().get(0).originalId());
        assertEquals(2, snap2.datasets().get(1).number());
        assertEquals("ds-b", snap2.datasets().get(1).originalId());
        // late ds-c 追加到尾部，新 number = 3
        assertEquals(3, snap2.datasets().get(2).number());
        assertEquals("ds-c", snap2.datasets().get(2).originalId());
    }

    /**
     * MF3 lock-after-snapshot: manifest 编号也有同样的 freeze 语义，迟到 manifest 追加到尾部。
     */
    @Test
    void snapshotShouldFreezeManifestNumbersAfterFirstExposure() {
        registry.onDatasetPersisted(manifestEvent("run-1", "m-x", "x.manifest.json", List.of()));
        registry.onDatasetPersisted(manifestEvent("run-1", "m-z", "z.manifest.json", List.of()));
        AgentRunDatasetSnapshot snap1 = registry.snapshot("run-1");
        assertEquals(2, snap1.manifests().size());
        assertEquals(1, snap1.manifests().get(0).number());
        assertEquals("m-x", snap1.manifests().get(0).originalId());
        assertEquals(2, snap1.manifests().get(1).number());
        assertEquals("m-z", snap1.manifests().get(1).originalId());

        // 迟到 m-a，sortKey 字典序在最前
        registry.onDatasetPersisted(manifestEvent("run-1", "m-a", "a.manifest.json", List.of()));
        AgentRunDatasetSnapshot snap2 = registry.snapshot("run-1");

        // 原有 number frozen 不变
        assertEquals(3, snap2.manifests().size());
        assertEquals(1, snap2.manifests().get(0).number());
        assertEquals("m-x", snap2.manifests().get(0).originalId());
        assertEquals(2, snap2.manifests().get(1).number());
        assertEquals("m-z", snap2.manifests().get(1).originalId());
        // late m-a 追加到尾部
        assertEquals(3, snap2.manifests().get(2).number());
        assertEquals("m-a", snap2.manifests().get(2).originalId());
    }
}
