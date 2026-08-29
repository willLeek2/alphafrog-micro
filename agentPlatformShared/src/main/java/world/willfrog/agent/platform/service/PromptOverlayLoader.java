package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.config.ConfigLoadStateReporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prompt 运行时覆盖层加载器。
 *
 * <p>Nacos 推送的 {@code agent-prompt-overlay.json} 通过此组件生效：
 * 配置中心把覆盖文档写到本地文件（{@code agent.llm.prompt-overlay-file} 指定路径），
 * 本组件轮询文件变化，解析后交给 {@link PromptAuthority#applyOverlay} 叠加成生效版本，
 * 全程不需要重启服务。机制与 agent-llm 本地配置加载器一致。</p>
 *
 * <h2>覆盖文档格式（formatVersion 1）</h2>
 * <pre>{@code
 * {
 *   "formatVersion": 1,
 *   "baseBundleDigest": "sha256:...",     // 可选，编辑时基于的默认版本指纹，仅作溯源
 *   "prompts": { "<字段名>": "<完整正文>" },          // 可选，字段名必须在权威词表内
 *   "toolDescriptions": { "<工具名>": "<完整正文>" }   // 可选，工具名必须已在索引登记
 * }
 * }</pre>
 * <p>版本指纹沿用权威体系的整体摘要：叠加后重算，与默认版本指纹可直接比对；
 * Nacos 自身的版本号只是传输元数据。文档缺失、为空或任一加载校验不过时，
 * 保持当前生效版本不变；文件被删除视为覆盖撤下，回落 classpath 默认版本。</p>
 *
 * @see PromptAuthority 权威默认版本与覆盖叠加
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PromptOverlayLoader {

    static final String DATA_ID = "agent-prompt-overlay.json";
    private static final int SUPPORTED_FORMAT_VERSION = 1;

    private final ObjectMapper objectMapper;

    @Value("${agent.llm.prompt-overlay-file:}")
    private String overlayFile;

    @Value("${spring.application.name:agent-platform}")
    private String serviceName;

    @Value("${spring.application.instance-id:${HOSTNAME:unknown}}")
    private String instanceId;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private final Object reloadLock = new Object();
    private volatile String loadedPath = "";
    private volatile long loadedLastModified = Long.MIN_VALUE;
    private volatile byte[] loadedBytes = new byte[0];
    /** 上一次解析尝试（无论成败）针对的文件状态：文件没变就不重复解析，坏文档不会每个轮询周期刷一次拒绝日志。 */
    private volatile String attemptedPath = "";
    private volatile long attemptedLastModified = Long.MIN_VALUE;
    private final AtomicLong overlayReloadFailureCount = new AtomicLong();
    private volatile OverlayState state = OverlayState.defaultVersion();

    /** 当前覆盖生效状态，供审计与运维查询；默认版本状态表示覆盖未生效。 */
    public record OverlayState(
            boolean applied,
            String effectiveBundleDigest,
            String baseBundleDigest,
            int promptEntries,
            int toolDescriptionEntries,
            Instant appliedAt) {
        static OverlayState defaultVersion() {
            return new OverlayState(false, "", "", 0, 0, null);
        }
    }

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${agent.llm.prompt-overlay-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    public OverlayState current() {
        return state;
    }

    long overlayReloadFailureCount() {
        return overlayReloadFailureCount.get();
    }

    private void reloadIfNeeded(boolean force) {
        String file = overlayFile == null ? "" : overlayFile.trim();
        if (file.isEmpty()) {
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                boolean wasApplied = state.applied();
                PromptAuthority.shared().clearOverlay();
                loadedPath = "";
                loadedLastModified = Long.MIN_VALUE;
                loadedBytes = new byte[0];
                attemptedPath = "";
                attemptedLastModified = Long.MIN_VALUE;
                state = OverlayState.defaultVersion();
                if (wasApplied) {
                    log.warn("Prompt overlay file disappeared; fell back to default prompts: {}", path);
                }
                return;
            }
            try {
                long currentModified = Files.getLastModifiedTime(path).toMillis();
                boolean unchanged = path.toString().equals(loadedPath) && currentModified == loadedLastModified;
                boolean alreadyAttempted = path.toString().equals(attemptedPath) && currentModified == attemptedLastModified;
                if (!force && (unchanged || alreadyAttempted)) {
                    return;
                }
                attemptedPath = path.toString();
                attemptedLastModified = currentModified;
                byte[] bytes = Files.readAllBytes(path);
                OverlayDocument document = parse(bytes);
                PromptAuthority authority = PromptAuthority.shared();
                warnOnBaseDrift(document.baseBundleDigest, authority);
                authority.applyOverlay(document.prompts(), document.toolDescriptions());
                loadedPath = path.toString();
                loadedLastModified = currentModified;
                loadedBytes = bytes;
                state = new OverlayState(
                        !document.prompts().isEmpty() || !document.toolDescriptions().isEmpty(),
                        authority.bundleDigest(),
                        authority.baseBundleDigest(),
                        document.prompts().size(),
                        document.toolDescriptions().size(),
                        Instant.now());
                reportState(bytes);
                if (state.applied()) {
                    log.info("Applied prompt overlay from {}: promptEntries={} toolDescriptionEntries={} effectiveDigest={}",
                            path, document.prompts().size(), document.toolDescriptions().size(),
                            state.effectiveBundleDigest());
                } else {
                    log.info("Prompt overlay document has no entries; effective version stays default: {}", path);
                }
            } catch (PromptConfigurationException e) {
                markReloadFailure(e.reason());
                log.error("Rejected prompt overlay from {}; retaining current effective prompts: {}",
                        path, e.getMessage());
            } catch (IOException e) {
                markReloadFailure("overlay_read_failed");
                log.error("Failed to read prompt overlay from {}", path, e);
            }
        }
    }

    private OverlayDocument parse(byte[] bytes) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PromptConfigurationException("overlay_parse_failed", "覆盖文档不是合法 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new PromptConfigurationException("overlay_parse_failed", "覆盖文档必须是 JSON 对象");
        }
        if (root.path("formatVersion").asInt(-1) != SUPPORTED_FORMAT_VERSION) {
            throw new PromptConfigurationException(
                    "overlay_format_version_unsupported",
                    "覆盖文档 formatVersion 必须是 " + SUPPORTED_FORMAT_VERSION);
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!"formatVersion".equals(field) && !"baseBundleDigest".equals(field)
                    && !"prompts".equals(field) && !"toolDescriptions".equals(field)) {
                throw new PromptConfigurationException("overlay_unknown_field", "覆盖文档含未知顶层字段: " + field);
            }
        });
        JsonNode baseDigestNode = root.path("baseBundleDigest");
        String baseBundleDigest = baseDigestNode.isTextual() ? baseDigestNode.asText() : null;
        return new OverlayDocument(baseBundleDigest, readEntries(root, "prompts"), readEntries(root, "toolDescriptions"));
    }

    private Map<String, String> readEntries(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new PromptConfigurationException("overlay_parse_failed", "覆盖文档的 " + field + " 必须是对象");
        }
        Map<String, String> entries = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                throw new PromptConfigurationException(
                        "overlay_parse_failed", "覆盖文档 " + field + "." + entry.getKey() + " 的值必须是字符串");
            }
            entries.put(entry.getKey(), value.asText());
        });
        return entries;
    }

    private void warnOnBaseDrift(String baseBundleDigest, PromptAuthority authority) {
        if (baseBundleDigest == null || baseBundleDigest.isBlank()) {
            return;
        }
        if (!baseBundleDigest.equals(authority.baseBundleDigest())) {
            log.warn("Prompt overlay was authored against a different default bundle: overlayBase={} currentBase={}",
                    baseBundleDigest, authority.baseBundleDigest());
        }
    }

    private void markReloadFailure(String reason) {
        overlayReloadFailureCount.incrementAndGet();
        if (meterRegistry != null) {
            Counter.builder("agent.prompt.overlay.reload.failures")
                    .description("被拒绝的 Prompt 覆盖刷新次数")
                    .tag("reason", reason == null || reason.isBlank() ? "unknown" : reason)
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void reportState(byte[] contentBytes) {
        ConfigLoadStateReporter.report(redisTemplate, serviceName, instanceId,
                DATA_ID, loadedPath, contentBytes);
    }

    /** 解析后的覆盖文档：两组条目加可选的溯源指纹。 */
    private record OverlayDocument(
            String baseBundleDigest,
            Map<String, String> prompts,
            Map<String, String> toolDescriptions) {

        OverlayDocument(Map<String, String> prompts, Map<String, String> toolDescriptions) {
            this(null, prompts, toolDescriptions);
        }
    }
}
