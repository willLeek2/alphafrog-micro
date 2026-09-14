package world.willfrog.agent.platform.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RunRawRefLocalStore 存储层机械性语义测试（260814 scheduler-03）。
 *
 * <p>覆盖面：内容文件落盘位置、不可猜测引用格式、同机重启后引用仍可读（plan §6.3）、
 * 顺序短 ID 重启续号、终态清理（目录+索引条目+序号重置）、三项上限（单条字节数、
 * 每 run 条数、每 run 总字节数）、TTL 到期驱逐（文件一并删除）、启动清扫（过期条目
 * + 无引用残留目录）、symlink 目录逃逸防御、严格归属校验、索引持久化。</p>
 *
 * <p>TTL 场景不 sleep：通过同包反射把内存索引里的 Entry 换成 createdAt 在过去的新
 * record（Entry 是包内可见的隐式 static record），模拟内容时间流逝。</p>
 */
class RunRawRefLocalStoreTest {

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("raw-ref");
    }

    private RunRawRefLocalStore newStore(long maxEntryBytes, int maxEntriesPerRun, long maxTotalBytesPerRun) {
        return new RunRawRefLocalStore(root.toString(), maxEntryBytes, maxEntriesPerRun, maxTotalBytesPerRun);
    }

    private RunRawRefLocalStore newStore() {
        return newStore(8_388_608L, 512, 536_870_912L);
    }

    @Test
    void register_shouldWriteContentFileUnderRunDir() throws Exception {
        RunRawRefLocalStore store = newStore();
        String ref = store.register("run_1", "user_1", "test", "hello", 3600, true);

        Path file = root.resolve("run_1").resolve(ref + ".txt");
        assertTrue(Files.isRegularFile(file));
        assertEquals("hello", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(root.resolve(RunRawRefLocalStore.INDEX_FILE_NAME)));
    }

    @Test
    void uuidStyleRefs_shouldBeUnguessableAndDistinct() {
        RunRawRefLocalStore store = newStore();
        String a = store.register("run_u", "user_1", "test", "a", 3600, false);
        String b = store.register("run_u", "user_1", "test", "b", 3600, false);

        assertTrue(a.startsWith("raw_") && !a.startsWith("raw_ref_"), "ref: " + a);
        assertFalse(a.equals(b));
    }

    @Test
    void uuidRef_shouldSurviveRestartOnSameMachine() {
        // plan §6.3：同一台机器上服务进程重启后，未终态 Run 的 rawRef 仍可读。
        // 全局 index.json 在每次注册时原子重写，新实例首次访问时惰性加载。
        RunRawRefLocalStore first = newStore();
        String ref = first.register("run_restart", "user_1", "test", "kept across restart", 3600, false);

        RunRawRefLocalStore restarted = newStore();
        assertEquals("kept across restart", restarted.read("run_restart", "user_1", ref));
    }

    @Test
    void shortIdSequence_shouldContinueAfterRestart() {
        RunRawRefLocalStore first = newStore();
        first.register("run_seq", "user_1", "test", "1", 3600, true);
        first.register("run_seq", "user_1", "test", "2", 3600, true);

        RunRawRefLocalStore restarted = newStore();
        assertEquals("raw_ref_003", restarted.register("run_seq", "user_1", "test", "3", 3600, true));
    }

    @Test
    void cleanupRun_shouldDeleteRunDirEntriesAndResetSequence() {
        RunRawRefLocalStore store = newStore();
        String ref1 = store.register("run_clean", "user_1", "test", "one", 3600, true);
        store.register("run_clean", "user_1", "test", "two", 3600, true);

        store.cleanupRun("run_clean");

        assertFalse(Files.exists(root.resolve("run_clean")), "run 目录应被整体删除");
        assertFalse(store.belongsToRun("run_clean", ref1));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("run_clean", "user_1", ref1));
        // 序号随 run 清理重置：新注册回到 001
        assertEquals("raw_ref_001",
                store.register("run_clean", "user_1", "test", "three", 3600, true));
    }

    @Test
    void cleanupRun_shouldNotTouchOtherRuns() {
        RunRawRefLocalStore store = newStore();
        String kept = store.register("run_keep", "user_1", "test", "keep", 3600, true);
        store.register("run_del", "user_1", "test", "drop", 3600, true);

        store.cleanupRun("run_del");

        assertEquals("keep", store.read("run_keep", "user_1", kept));
        assertTrue(store.belongsToRun("run_keep", kept));
    }

    @Test
    void register_shouldRejectContentAboveMaxEntryBytes() {
        RunRawRefLocalStore store = newStore(10L, 512, 536_870_912L);
        assertThrows(IllegalArgumentException.class,
                () -> store.register("run_cap", "user_1", "test", "12345678901", 3600, true));
        // 恰好等于上限的 10 字节可以注册
        assertEquals("raw_ref_001",
                store.register("run_cap", "user_1", "test", "1234567890", 3600, true));
    }

    @Test
    void register_shouldRejectTooManyEntriesPerRun() {
        RunRawRefLocalStore store = newStore(8_388_608L, 2, 536_870_912L);
        store.register("run_count", "user_1", "test", "a", 3600, true);
        store.register("run_count", "user_1", "test", "b", 3600, true);
        assertThrows(IllegalArgumentException.class,
                () -> store.register("run_count", "user_1", "test", "c", 3600, true));
        // 上限按 run 独立计算：别的 run 不受影响
        assertEquals("raw_ref_001",
                store.register("run_other", "user_1", "test", "c", 3600, true));
    }

    @Test
    void register_shouldRejectRunAboveTotalBytes() {
        RunRawRefLocalStore store = newStore(8_388_608L, 512, 10L);
        store.register("run_bytes", "user_1", "test", "abcdef", 3600, true);
        assertThrows(IllegalArgumentException.class,
                () -> store.register("run_bytes", "user_1", "test", "ghijkl", 3600, true));
        assertEquals("abcdef", store.read("run_bytes", "user_1", "raw_ref_001"));
    }

    @Test
    void expiredEntry_shouldBeEvictedWithFileOnRead() {
        RunRawRefLocalStore store = newStore();
        // ttl=1 秒注册后把条目老化 10 秒，模拟内容时间流逝而不 sleep
        String ref = store.register("run_ttl", "user_1", "test", "stale", 1, true);
        Path file = root.resolve("run_ttl").resolve(ref + ".txt");
        ageEntryToPast(store, ref);

        assertThrows(IllegalArgumentException.class,
                () -> store.read("run_ttl", "user_1", ref));
        assertFalse(Files.exists(file), "过期条目的内容文件应随驱逐一并删除");
        assertFalse(store.belongsToRun("run_ttl", ref));
    }

    @Test
    void startupSweep_shouldDropStaleDirsButKeepReferencedRuns() throws Exception {
        RunRawRefLocalStore first = newStore();
        String ref = first.register("run_live", "user_1", "test", "live", 3600, true);
        // 手工制造一个无引用的残留目录（例如上次进程崩溃留下的孤儿）
        Files.createDirectories(root.resolve("stale-run"));

        RunRawRefLocalStore restarted = newStore();
        restarted.sweepAtStartup();

        assertFalse(Files.exists(root.resolve("stale-run")), "无引用残留目录应被启动清扫删除");
        assertTrue(Files.exists(root.resolve("run_live")), "有引用 run 目录应保留");
        assertEquals("live", restarted.read("run_live", "user_1", ref));
    }

    @Test
    void read_shouldRejectSymlinkEscapeOutsideRunDir() throws Exception {
        RunRawRefLocalStore store = newStore();
        String ref = store.register("run_link", "user_1", "test", "safe", 3600, true);
        Path file = root.resolve("run_link").resolve(ref + ".txt");
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "escaped");

        Files.delete(file);
        Files.createSymbolicLink(file, outside.toAbsolutePath());

        assertThrows(IllegalArgumentException.class,
                () -> store.read("run_link", "user_1", ref));
    }

    @Test
    void read_shouldRequireStrictOwnership() {
        RunRawRefLocalStore store = newStore();
        String ref = store.register("run_own", "user_own", "test", "secret", 3600, true);

        assertThrows(IllegalArgumentException.class, () -> store.read(" ", "user_own", ref));
        assertThrows(IllegalArgumentException.class, () -> store.read("run_own", null, ref));
        assertThrows(IllegalArgumentException.class, () -> store.read("run_own", "user_other", ref));
        assertThrows(IllegalArgumentException.class, () -> store.read("run_other", "user_own", ref));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("run_own", "user_own", "raw_ref_999"));
        assertEquals("secret", store.read("run_own", "user_own", ref));
    }

    @Test
    void register_shouldRejectBlankInputs() {
        RunRawRefLocalStore store = newStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.register(" ", "user_1", "d", "payload", 3600, true));
        assertThrows(IllegalArgumentException.class,
                () -> store.register("run_1", " ", "d", "payload", 3600, true));
        assertThrows(IllegalArgumentException.class,
                () -> store.register("run_1", "user_1", "d", null, 3600, true));
        assertThrows(IllegalArgumentException.class,
                () -> store.register("run_1", "user_1", "d", "payload", 0, true));
    }

    @Test
    void register_shouldRejectTraversalRunId() {
        // reviewer MUST-FIX：runId 直接拼进 rootDir 路径，任何分隔符/../绝对
        // 路径都必须在注册源头拒绝，且绝不能产生 root 之外的目录。
        RunRawRefLocalStore store = newStore();
        String absolute = tempDir.resolve("evil-abs").toString();
        String[] bad = {"../evil", "a/b", "a\\b", ".", "..", absolute};
        for (String runId : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.register(runId, "user_1", "d", "payload", 3600, true),
                    "runId 应被拒绝: " + runId);
        }
        assertFalse(Files.exists(tempDir.resolve("evil")), "root 外不得产生 evil 目录");
        assertFalse(Files.exists(tempDir.resolve("evil-abs")), "绝对路径 runId 不得落盘");
    }

    @Test
    void cleanupRun_shouldRejectTraversalRunIdAndTouchNothingOutside() throws Exception {
        // reviewer MUST-FIX：cleanupRun 是递归删除，穿越 runId 绝不能碰 root
        // 之外的文件。
        RunRawRefLocalStore store = newStore();
        Path outsideDir = Files.createDirectories(tempDir.resolve("evil"));
        Path outsideFile = Files.writeString(outsideDir.resolve("keep.txt"), "keep");

        assertThrows(IllegalArgumentException.class, () -> store.cleanupRun("../evil"));
        assertThrows(IllegalArgumentException.class, () -> store.cleanupRun("a/b"));
        assertThrows(IllegalArgumentException.class,
                () -> store.cleanupRun(tempDir.resolve("evil").toString()));

        assertTrue(Files.exists(outsideFile), "root 之外的文件必须原样保留");
    }

    @Test
    void read_shouldRejectTraversalRunId() {
        RunRawRefLocalStore store = newStore();
        String ref = store.register("run_ok", "user_1", "d", "payload", 3600, true);
        assertThrows(IllegalArgumentException.class,
                () -> store.read("../evil", "user_1", ref));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("a/b", "user_1", ref));
        assertEquals("payload", store.read("run_ok", "user_1", ref));
    }

    @Test
    void indexPathField_shouldBeIgnoredInFavorOfDerivedPath() throws Exception {
        // reviewer MUST-FIX：索引加载不得信任 index.json 里的任意绝对路径——
        // path 字段指向 root 之外时被整体忽略，一律按 root/runId/ref.txt 重新
        // 推导；内容落在推导路径上才可读，外面的路径绝不被触碰。
        Path index = root.resolve(RunRawRefLocalStore.INDEX_FILE_NAME);
        Files.createDirectories(root);
        Path outsideFile = Files.writeString(tempDir.resolve("outside.txt"), "outside-content");
        Files.writeString(index, """
                [{"ref":"raw_ref_001","runId":"run_x","userId":"user_1",
                  "displayName":"d","path":"%s","bytes":15,
                  "createdAtMillis":%d,"ttlSeconds":3600}]
                """.formatted(
                        outsideFile.toString().replace("\\", "\\\\"),
                        System.currentTimeMillis()));
        Path derivedFile = root.resolve("run_x").resolve("raw_ref_001.txt");
        Files.createDirectories(derivedFile.getParent());
        Files.writeString(derivedFile, "derived-content");

        RunRawRefLocalStore restarted = newStore();
        restarted.sweepAtStartup();

        assertEquals("derived-content", restarted.read("run_x", "user_1", "raw_ref_001"),
                "必须按推导路径读取，而不是索引里的 path 字段");
        assertEquals("outside-content", Files.readString(outsideFile), "root 之外的文件不得被改动");
    }

    @Test
    void indexEntryWithTraversalRunId_shouldFailFastOnLoad() throws Exception {
        Path index = root.resolve(RunRawRefLocalStore.INDEX_FILE_NAME);
        Files.createDirectories(root);
        Files.writeString(index, """
                [{"ref":"raw_ref_001","runId":"../evil","userId":"user_1",
                  "displayName":"d","path":"x","bytes":5,
                  "createdAtMillis":%d,"ttlSeconds":3600}]
                """.formatted(System.currentTimeMillis()));

        RunRawRefLocalStore restarted = newStore();
        assertThrows(IllegalStateException.class, restarted::sweepAtStartup);
    }

    @Test
    void corruptIndex_shouldFailFastAndKeepDirectories() throws Exception {
        // reviewer MUST-FIX：索引损坏时绝不能按空索引继续然后把全部内容目录
        // 当孤儿删掉——fail-fast 且现有目录原样保留。
        Path index = root.resolve(RunRawRefLocalStore.INDEX_FILE_NAME);
        Files.createDirectories(root);
        Files.writeString(index, "{definitely not valid json");
        Path dataDir = Files.createDirectories(root.resolve("run_data"));
        Path dataFile = Files.writeString(dataDir.resolve("raw_ref_001.txt"), "data");

        RunRawRefLocalStore restarted = newStore();
        assertThrows(IllegalStateException.class, restarted::sweepAtStartup);
        assertTrue(Files.exists(dataFile), "损坏索引时绝不允许清扫删除数据目录");
    }

    @Test
    void register_shouldRollbackWhenIndexPersistFails() throws Exception {
        // reviewer MUST-FIX：索引落盘失败时注册必须显式失败并回滚（内存条目 +
        // 内容文件），绝不能返回一个重启即丢失的"可用"引用。
        RunRawRefLocalStore store = newStore();
        // 把 index.json 的位置占成一个目录，使原子 rename 必然失败
        Files.createDirectories(root);
        Files.createDirectory(root.resolve(RunRawRefLocalStore.INDEX_FILE_NAME));

        assertThrows(IllegalStateException.class,
                () -> store.register("run_fail", "user_1", "d", "payload", 3600, true));
        assertFalse(store.belongsToRun("run_fail", "raw_ref_001"), "失败注册不得留内存条目");
        assertFalse(Files.exists(root.resolve("run_fail").resolve("raw_ref_001.txt")),
                "失败注册不得留内容文件");
    }

    @Test
    void register_shouldRejectSymlinkedRunDir() throws Exception {
        // reviewer 追加要求：字符串校验挡不住 root/<合法runId> 本身是指向
        // root 之外的符号链接——注册必须拒绝跟随链接写入。
        RunRawRefLocalStore store = newStore();
        Files.createDirectories(root);
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(root.resolve("run_link"), outside.toAbsolutePath());

        assertThrows(IllegalArgumentException.class,
                () -> store.register("run_link", "user_1", "d", "payload", 3600, true));
        assertEquals("keep", Files.readString(outsideFile), "root 之外的内容不得被写入或改动");
    }

    @Test
    void cleanupRun_shouldDeleteOnlySymlinkNotTarget() throws Exception {
        // reviewer 追加要求：递归删除绝不跟随符号链接——只删链接本身，
        // 链接目标（root 之外）的内容原样保留。
        RunRawRefLocalStore store = newStore();
        Files.createDirectories(root);
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(root.resolve("run_link"), outside.toAbsolutePath());

        store.cleanupRun("run_link");

        assertFalse(Files.exists(root.resolve("run_link")), "链接本身应被删除");
        assertTrue(Files.exists(outsideFile), "链接目标内容必须原样保留");
        assertEquals("keep", Files.readString(outsideFile));
    }

    @Test
    void startupSweep_shouldDeleteExpiredEntryFileButKeepLiveSibling() {
        // reviewer 追加要求：同一 Run 一个过期、一个仍有效时，启动清扫必须删掉
        // 过期条目的内容文件（不能只摘索引条目），且有效引用仍可读。
        RunRawRefLocalStore store = newStore();
        String live = store.register("run_mix", "user_1", "d", "live", 3600, true);
        String dead = store.register("run_mix", "user_1", "d", "dead", 1, true);
        Path deadFile = root.resolve("run_mix").resolve(dead + ".txt");
        ageEntryToPast(store, dead);

        store.sweepAtStartup();

        assertFalse(Files.exists(deadFile), "过期条目文件必须被启动清扫删除");
        assertEquals("live", store.read("run_mix", "user_1", live), "同 Run 有效引用必须仍可读");
        assertFalse(store.belongsToRun("run_mix", dead), "过期条目必须从索引摘除");
    }

    @Test
    void startupSweep_shouldDeleteOnlySymlinkNotTarget() throws Exception {
        RunRawRefLocalStore first = newStore();
        first.register("run_live", "user_1", "d", "live", 3600, true);
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(root.resolve("stale-link"), outside.toAbsolutePath());

        RunRawRefLocalStore restarted = newStore();
        restarted.sweepAtStartup();

        assertFalse(Files.exists(root.resolve("stale-link")), "无引用链接应被清扫（只删链接）");
        assertTrue(Files.exists(outsideFile), "链接目标内容必须原样保留");
    }

    @Test
    void indexFile_shouldBeLoadableJsonAfterRestart() throws Exception {
        RunRawRefLocalStore first = newStore();
        first.register("run_json", "user_1", "test", "content", 3600, true);

        String raw = Files.readString(root.resolve(RunRawRefLocalStore.INDEX_FILE_NAME));
        assertTrue(raw.contains("\"run_json\""), "index.json 应含 runId: " + raw);
        assertTrue(raw.contains("\"raw_ref_001\""), "index.json 应含 ref: " + raw);
    }

    @SuppressWarnings("unchecked")
    private void ageEntryToPast(RunRawRefLocalStore store, String ref) {
        Map<RunRawRefLocalStore.Key, RunRawRefLocalStore.Entry> entries =
                (Map<RunRawRefLocalStore.Key, RunRawRefLocalStore.Entry>)
                        ReflectionTestUtils.getField(store, "entries");
        RunRawRefLocalStore.Entry live = entries.values().stream()
                .filter(e -> ref.equals(e.ref()))
                .findFirst()
                .orElseThrow();
        RunRawRefLocalStore.Entry expired = new RunRawRefLocalStore.Entry(
                live.ref(), live.runId(), live.userId(), live.displayName(), live.path(),
                live.bytes(), System.currentTimeMillis() - 10_000L, live.ttlSeconds(), live.runDir());
        entries.put(new RunRawRefLocalStore.Key(live.runId(), live.ref()), expired);
    }
}
