package world.willfrog.agent.platform.artifact;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Run-scoped short-ID mapping layer for rawRef, backed by the local-disk
 * {@link RunRawRefLocalStore} (260814 scheduler-03). The Redis hash mapping
 * and the {@link PersistentArtifactRegistry} content path are no longer used
 * by this store.
 *
 * <p>Agent-visible IDs follow the format {@code raw_ref_001}, {@code raw_ref_002}, etc.
 * Ownership（归属校验）：短 ID 只证明该 ref 在此 run 下注册过；内容读取
 * 一律经 {@link RunRawRefLocalStore#read(String, String, String)} 做严格
 * runId+userId 归属校验——调用方的 runId 与 userId 必须与注册时严格相等，
 * 任一空白或不一致 fail-closed 拒绝。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunRawRefStoreImpl implements RunRawRefStore {

    private static final String SHORT_ID_PREFIX = "raw_ref_";
    private static final int DEFAULT_MAX_LIMIT = 4000;

    private final RunRawRefLocalStore localStore;

    @Override
    public String register(String runId, String userId, String displayName, String content, long ttlSeconds) {
        return localStore.register(runId, userId, displayName, content, ttlSeconds, true);
    }

    @Override
    public String read(String runId, String userId, String shortId) {
        return localStore.read(runId, userId, shortId);
    }

    @Override
    public ToolOutputReadResult read(String runId, String userId, String shortId,
                                     int offset, int limit, String keyword) {
        String content = localStore.read(runId, userId, shortId);
        String source = filterByKeyword(content, keyword);
        int total = source.length();
        int safeOffset = Math.max(0, Math.min(offset, total));
        int capped = limit > 0 ? limit : DEFAULT_MAX_LIMIT;
        int end = Math.min(total, safeOffset + capped);
        return ToolOutputReadResult.builder()
                .content(source.substring(safeOffset, end))
                .hasMore(end < total)
                .nextOffset(end)
                .totalLength(total)
                .build();
    }

    @Override
    public boolean belongsToRun(String runId, String shortId) {
        return localStore.belongsToRun(runId, shortId);
    }

    private String filterByKeyword(String content, String keyword) {
        if (keyword == null || keyword.isBlank() || content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        return Arrays.stream(content.split("\\R", -1))
                .filter(line -> line.contains(keyword))
                .collect(Collectors.joining("\n"));
    }
}
