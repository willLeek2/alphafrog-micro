package world.willfrog.agent.platform.artifact;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Optional;

/**
 * 工具输出 rawRef 服务实现（260814 scheduler-03：本地磁盘后端）。
 *
 * <p>D22-5.1.3：注册/定位/读取均有显式上下文 overload（runId/userId 显式传入，
 * 不依赖 {@link AgentContext} 线程态）；旧入口为有界 delegate，从 AgentContext
 * 补齐上下文后转调同一套实现。所有读取/定位路径的归属校验一律走
 * {@link RunRawRefLocalStore#assertOwned}——注册时的 runId/userId 与调用方的
 * runId/userId 四个值都必须非空且两两相等，任一为空或不等即拒绝
 * （fail-closed），不保留任何宽容 seam。</p>
 *
 * <p>与旧实现的差异：内容不再经 {@link PersistentArtifactRegistry}
 * （Redis 元数据 + 12h 滑动过期 + Lua 认领），而是写当前 Run 生命周期的本地
 * 临时目录（同一机器重启后未终态 Run 仍可读；Run 终态或 TTL 到期后清理）。</p>
 */
@Service
@RequiredArgsConstructor
public class ToolOutputRefServiceImpl implements ToolOutputRefService {

    private static final int DEFAULT_MAX_LIMIT = 4000;
    private static final long DEFAULT_RAW_REF_TTL_HOURS = 12L;
    private static final int DEFAULT_KEYWORD_WINDOW_RADIUS = 200;
    private static final int DEFAULT_KEYWORD_WINDOW_MAX_HITS = 20;

    private final RunRawRefLocalStore localStore;
    private final Optional<AgentLlmLocalConfigLoader> localConfigLoader;

    @Override
    public PersistentArtifactRegistration registerRawOutput(String logicalId, String displayName, String content) {
        return registerRawOutput(AgentContext.getRunId(), AgentContext.getUserId(), logicalId, displayName, content);
    }

    @Override
    public PersistentArtifactRegistration registerRawOutput(String runId, String userId, String logicalId,
                                                            String displayName, String content) {
        String ref = localStore.register(runId, userId, displayName, content,
                resolveRawRefTtlHours() * 3600L, false);
        return PersistentArtifactRegistration.builder()
                .artifactId(ref)
                .locator(RawPayloadLocator.builder().path(ref).build())
                .build();
    }

    @Override
    public PersistentArtifactRegistration registerRawPrompt(String runId, String userId, String logicalId,
            String displayName, world.willfrog.alphafrogmicro.common.racing.prompt.PromptText content) {
        String ref = localStore.registerPrompt(runId, userId, displayName, content,
                resolveRawRefTtlHours() * 3600L, false);
        return PersistentArtifactRegistration.builder().artifactId(ref)
                .locator(RawPayloadLocator.builder().path(ref).build()).build();
    }

    @Override
    public PersistentArtifactRegistration rebindFromLocator(String logicalId, String displayName, RawPayloadLocator locator) {
        // 260814 scheduler-03 review fix：cache-hit rebind 必须保持 Run 隔离——
        // 只允许读当前 AgentContext 的 runId+userId 所拥有的 ref（同 Run 重启
        // 后仍可读）。不属于当前 Run、来源 Run 已终态清理、或 locator 无效时，
        // assertVisible 抛 IllegalArgumentException，由上层缓存服务视为缓存
        // 失效（删缓存回源），绝不跨 Run 复制 raw 内容。
        assertVisible(AgentContext.getRunId(), AgentContext.getUserId(), locator.getPath());
        ToolOutputReadResult original = localStore.readWithSources(AgentContext.getRunId(), AgentContext.getUserId(), locator.getPath());
        return original.getContentPrompt() == null ? registerRawOutput(logicalId, displayName, original.getContent())
                : registerRawPrompt(logicalId, displayName, original.getContentPrompt());
    }

    @Override
    public RawPayloadLocator locatorFor(String rawRef) {
        assertVisible(AgentContext.getRunId(), AgentContext.getUserId(), rawRef);
        return RawPayloadLocator.builder().path(rawRef).build();
    }

    @Override
    public RawPayloadLocator locatorFor(String runId, String userId, String rawRef) {
        assertVisible(runId, userId, rawRef);
        return RawPayloadLocator.builder().path(rawRef).build();
    }

    @Override
    public ToolOutputReadResult read(String rawRef, int offset, int limit, String keyword) {
        return doRead(AgentContext.getRunId(), AgentContext.getUserId(), rawRef, offset, limit, keyword);
    }

    @Override
    public ToolOutputReadResult read(String runId, String userId, String rawRef, int offset, int limit, String keyword) {
        return doRead(runId, userId, rawRef, offset, limit, keyword);
    }

    private ToolOutputReadResult doRead(String runId, String userId, String rawRef,
                                        int offset, int limit, String keyword) {
        assertVisible(runId, userId, rawRef);
        ToolOutputReadResult original = localStore.readWithSources(runId, userId, rawRef);
        String content = original.getContent();
        if (hasText(keyword)) {
            return readKeywordWindows(original, content, keyword, offset, limit);
        }
        int total = content.length();
        int safeOffset = Math.max(0, Math.min(offset, total));
        int cappedLimit = capLimit(limit);
        int end = Math.min(total, safeOffset + cappedLimit);
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
     * 014-opt-10：keyword 模式返回命中窗口（{@link KeywordWindowExtractor}）。
     * offset 是全文中开始查找命中的字符下标（续查传上次返回的 nextOffset）；
     * 窗口选好后不再做第二次截取，limit 只是本次返回文本的字符上限，装不下
     * 的整段留给下一次调用；totalLength 与 hitCount 都相对全文而言。
     */
    private ToolOutputReadResult readKeywordWindows(ToolOutputReadResult original, String content,
                                                    String keyword, int offset, int limit) {
        KeywordWindowExtractor.Result result = KeywordWindowExtractor.extract(
                content, keyword, Math.max(0, offset),
                resolveKeywordWindowRadius(), resolveKeywordWindowMaxHits(), capLimit(limit));
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

    /**
     * 归属校验（全部 read/locator 入口共用同一套严格语义）：注册时的
     * runId/userId 与调用方的 runId/userId 四个值都必须非空且两两相等，
     * 任一为空或不相等即抛 {@link IllegalArgumentException}（fail-closed）；
     * 不保留任何宽容 seam。
     */
    private void assertVisible(String runId, String userId, String rawRef) {
        localStore.assertOwned(runId, userId, rawRef);
    }

    private int capLimit(int requested) {
        int configured = localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getReread)
                .map(AgentLlmProperties.ToolReread::getMaxLimit)
                .filter(v -> v != null && v > 0)
                .orElse(DEFAULT_MAX_LIMIT);
        if (requested <= 0) {
            return configured;
        }
        return Math.min(requested, configured);
    }

    private long resolveRawRefTtlHours() {
        return localConfigLoader.flatMap(AgentLlmLocalConfigLoader::current)
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getRawRef)
                .map(AgentLlmProperties.ToolRawRef::getTtlHours)
                .filter(v -> v != null && v > 0)
                .map(Integer::longValue)
                .orElse(DEFAULT_RAW_REF_TTL_HOURS);
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
