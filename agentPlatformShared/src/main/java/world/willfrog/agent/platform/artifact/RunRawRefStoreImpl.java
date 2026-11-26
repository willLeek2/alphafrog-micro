package world.willfrog.agent.platform.artifact;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Optional;

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
    private static final int DEFAULT_KEYWORD_WINDOW_RADIUS = 200;
    private static final int DEFAULT_KEYWORD_WINDOW_MAX_HITS = 20;

    private final RunRawRefLocalStore localStore;
    private final Optional<AgentLlmLocalConfigLoader> localConfigLoader;

    @Override
    public String register(String runId, String userId, String displayName, String content, long ttlSeconds) {
        return localStore.register(runId, userId, displayName, content, ttlSeconds, true);
    }

    @Override
    public String registerPrompt(String runId, String userId, String displayName,
                                 world.willfrog.alphafrogmicro.common.racing.prompt.PromptText content, long ttlSeconds) {
        return localStore.registerPrompt(runId, userId, displayName, content, ttlSeconds, true);
    }

    @Override
    public String read(String runId, String userId, String shortId) {
        return localStore.read(runId, userId, shortId);
    }

    @Override
    public ToolOutputReadResult read(String runId, String userId, String shortId,
                                     int offset, int limit, String keyword) {
        ToolOutputReadResult original = localStore.readWithSources(runId, userId, shortId);
        String content = original.getContent();
        if (keyword != null && !keyword.isBlank()) {
            return readKeywordWindows(original, content, keyword, offset, limit);
        }
        int total = content.length();
        int safeOffset = Math.max(0, Math.min(offset, total));
        int capped = limit > 0 ? limit : DEFAULT_MAX_LIMIT;
        int end = Math.min(total, safeOffset + capped);
        return ToolOutputReadResult.builder()
                .content(content.substring(safeOffset, end))
                .contentPrompt(original.getContentPrompt() == null ? null
                        : new world.willfrog.alphafrogmicro.common.racing.prompt.PromptText(
                                content.substring(safeOffset, end), original.getContentPrompt().sources()))
                .hasMore(end < total)
                .nextOffset(end)
                .totalLength(total)
                .build();
    }

    /**
     * 014-opt-10：keyword 模式返回命中窗口（{@link KeywordWindowExtractor}），
     * 与 ToolOutputRefServiceImpl 同一口径：offset 是全文中开始查找命中的
     * 字符下标，窗口选好后不再做第二次截取，limit 只是本次返回文本的字符
     * 上限；窗口半径与命中数上限读取同一组配置（缺省 200 与 20）。
     */
    private ToolOutputReadResult readKeywordWindows(ToolOutputReadResult original, String content,
                                                    String keyword, int offset, int limit) {
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                content, keyword, Math.max(0, offset),
                resolveKeywordWindowRadius(), resolveKeywordWindowMaxHits(),
                limit > 0 ? limit : DEFAULT_MAX_LIMIT);
        String text;
        int hitCount;
        boolean hasMore;
        int nextOffset;
        if (result.hitCount() == 0) {
            text = KeywordWindowExtractor.zeroHitMessage(keyword, content.length());
            hitCount = 0;
            hasMore = false;
            nextOffset = content.length();
        } else {
            text = result.text();
            hitCount = result.hitCount();
            hasMore = result.hasMore();
            nextOffset = hasMore ? result.nextIndex() : content.length();
        }
        return ToolOutputReadResult.builder()
                .content(text)
                .contentPrompt(original.getContentPrompt() == null ? null
                        : new world.willfrog.alphafrogmicro.common.racing.prompt.PromptText(
                                text, original.getContentPrompt().sources()))
                .hasMore(hasMore)
                .nextOffset(nextOffset)
                .totalLength(content.length())
                .hitCount(hitCount)
                .build();
    }

    @Override
    public boolean belongsToRun(String runId, String shortId) {
        return localStore.belongsToRun(runId, shortId);
    }

    private int resolveKeywordWindowRadius() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getReread)
                .map(AgentLlmProperties.ToolReread::getKeywordWindowRadius)
                .filter(v -> v != null && v > 0)
                .orElse(DEFAULT_KEYWORD_WINDOW_RADIUS);
    }

    private int resolveKeywordWindowMaxHits() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getReread)
                .map(AgentLlmProperties.ToolReread::getKeywordWindowMaxHits)
                .filter(v -> v != null && v > 0)
                .orElse(DEFAULT_KEYWORD_WINDOW_MAX_HITS);
    }
}
