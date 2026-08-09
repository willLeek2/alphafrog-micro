package world.willfrog.agent.platform.artifact;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工具输出 rawRef 服务实现。
 *
 * <p>D22-5.1.3：注册/定位/读取均有显式上下文 overload（runId/userId 显式传入，
 * 不依赖 {@link AgentContext} 线程态），归属走
 * {@link PersistentArtifactRegistry#matchesOwnerStrict}（四值非空且相等，fail-closed）；
 * 旧入口保留为有界兼容 delegate，从 AgentContext 补齐上下文，归属走
 * {@link PersistentArtifactRegistry#matchesOwnerLenient}（宽容语义，仅 legacy seam，
 * user API 不可达）。</p>
 */
@Service
@RequiredArgsConstructor
public class ToolOutputRefServiceImpl implements ToolOutputRefService {

    private static final int DEFAULT_MAX_LIMIT = 4000;
    private static final long DEFAULT_RAW_REF_TTL_HOURS = 12L;

    private final PersistentArtifactRegistry artifactRegistry;
    private final Optional<AgentLlmLocalConfigLoader> localConfigLoader;

    @Override
    public PersistentArtifactRegistration registerRawOutput(String logicalId, String displayName, String content) {
        return registerRawOutput(AgentContext.getRunId(), AgentContext.getUserId(), logicalId, displayName, content);
    }

    @Override
    public PersistentArtifactRegistration registerRawOutput(String runId, String userId, String logicalId,
                                                            String displayName, String content) {
        return artifactRegistry.registerExplicit(runId, userId, "raw-ref", logicalId, displayName,
                content, resolveRawRefTtlHours());
    }

    @Override
    public PersistentArtifactRegistration rebindFromLocator(String logicalId, String displayName, RawPayloadLocator locator) {
        String content = artifactRegistry.readLocator(locator);
        return registerRawOutput(logicalId, displayName, content);
    }

    @Override
    public RawPayloadLocator locatorFor(String rawRef) {
        assertVisible(AgentContext.getRunId(), AgentContext.getUserId(), rawRef, false);
        return artifactRegistry.locatorFor(rawRef);
    }

    @Override
    public RawPayloadLocator locatorFor(String runId, String userId, String rawRef) {
        assertVisible(runId, userId, rawRef, true);
        return artifactRegistry.locatorFor(rawRef);
    }

    @Override
    public ToolOutputReadResult read(String rawRef, int offset, int limit, String keyword) {
        return doRead(AgentContext.getRunId(), AgentContext.getUserId(), rawRef, offset, limit, keyword, false);
    }

    @Override
    public ToolOutputReadResult read(String runId, String userId, String rawRef, int offset, int limit, String keyword) {
        return doRead(runId, userId, rawRef, offset, limit, keyword, true);
    }

    private ToolOutputReadResult doRead(String runId, String userId, String rawRef,
                                        int offset, int limit, String keyword, boolean strict) {
        assertVisible(runId, userId, rawRef, strict);
        String content = artifactRegistry.readContent(rawRef);
        String source = filterByKeyword(content, keyword);
        int total = source.length();
        int safeOffset = Math.max(0, Math.min(offset, total));
        int cappedLimit = capLimit(limit);
        int end = Math.min(total, safeOffset + cappedLimit);
        return ToolOutputReadResult.builder()
                .content(source.substring(safeOffset, end))
                .hasMore(end < total)
                .nextOffset(end)
                .totalLength(total)
                .build();
    }

    /**
     * D22 MUST-FIX ③：显式上下文入口（runId/userId 显式传入）走严格归属校验——四值
     * 非空且相等，任一侧空值 fail-closed；legacy AgentContext 入口保留宽容语义兼容
     * 历史无上下文制品，该 seam 对 user API 不可达。
     */
    private void assertVisible(String runId, String userId, String rawRef, boolean strict) {
        PersistentArtifactMeta meta = artifactRegistry.find(rawRef)
                .orElseThrow(() -> new IllegalArgumentException("rawRef not found: " + rawRef));
        boolean owner = strict
                ? PersistentArtifactRegistry.matchesOwnerStrict(meta, runId, userId)
                : PersistentArtifactRegistry.matchesOwnerLenient(meta, runId, userId);
        if (!owner) {
            throw new IllegalArgumentException("rawRef does not belong to current run/user");
        }
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

    private String filterByKeyword(String content, String keyword) {
        if (!hasText(keyword) || content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        return Arrays.stream(content.split("\\R", -1))
                .filter(line -> line.contains(keyword))
                .collect(Collectors.joining("\n"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
