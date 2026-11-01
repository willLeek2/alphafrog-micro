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
