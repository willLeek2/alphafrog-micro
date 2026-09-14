package world.willfrog.alphafrogmicro.common.gray;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 灰度规则文件的进程内热加载存储。
 *
 * <p>每次刷新都会先完成整份 JSON Schema 与语义校验，再用一次原子引用替换整个不可变快照。
 * 解析或校验失败时继续使用最近一次成功快照；在第一次成功加载之前则保持没有规则、没有版本号的
 * 空快照。业务代码不应直接读取文件，而应通过 {@link GrayDecider} 判断。</p>
 */
@Slf4j
public final class GrayRuleStore implements AutoCloseable {

    static final String SCHEMA_RESOURCE = "/META-INF/alphafrog/gray/gray-rules.schema.json";
    private static final String MISSING_FINGERPRINT = "missing";
    private static final String READ_ERROR_FINGERPRINT = "read-error";

    private final ObjectMapper objectMapper;
    private final JsonSchema schema;
    private final Path rulesFile;
    private final long refreshIntervalMillis;
    private final String serviceName;
    private final String instanceId;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object reloadLock = new Object();

    private volatile ScheduledExecutorService scheduler;
    private volatile String lastObservedFingerprint = "";
    private volatile String lastLoadedContentDigest = "";
    private volatile String lastLoggedRuleVersion = "";

    public GrayRuleStore(
            ObjectMapper objectMapper,
            Path rulesFile,
            long refreshIntervalMillis,
            String serviceName,
            String instanceId) {
        this.objectMapper = objectMapper;
        this.schema = loadSchema();
        this.rulesFile = rulesFile.toAbsolutePath().normalize();
        if (refreshIntervalMillis <= 0) {
            throw new IllegalArgumentException("alphafrog.gray.refresh-interval-ms must be positive");
        }
        this.refreshIntervalMillis = refreshIntervalMillis;
        this.serviceName = displayValue(serviceName);
        this.instanceId = displayValue(instanceId);
    }

    /** 启动时立即尝试加载一次，随后按固定间隔轮询文件内容。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        reload(false);
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alphafrog-gray-rules");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = created;
        created.scheduleWithFixedDelay(
                this::refreshSafely,
                refreshIntervalMillis,
                refreshIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 立即重新读取并校验当前文件，即使文件字节与上一次观察相同也会执行。
     *
     * @return 本次是否成功装入了一份完整合法文档
     */
    public boolean refreshNow() {
        return reload(true);
    }

    public Optional<GrayRuleDefinition> find(String ruleId) {
        if (ruleId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get().rules().get(ruleId));
    }

    public Optional<String> currentRuleVersion() {
        return Optional.ofNullable(snapshot.get().ruleVersion());
    }

    Path rulesFile() {
        return rulesFile;
    }

    Snapshot currentSnapshot() {
        return snapshot.get();
    }

    private void refreshSafely() {
        try {
            reload(false);
        } catch (RuntimeException exception) {
            log.error("Unexpected gray rule refresh failure; retaining the last valid snapshot", exception);
        }
    }

    private boolean reload(boolean force) {
        synchronized (reloadLock) {
            if (!Files.isRegularFile(rulesFile)) {
                if (force || !MISSING_FINGERPRINT.equals(lastObservedFingerprint)) {
                    lastObservedFingerprint = MISSING_FINGERPRINT;
                    if (snapshot.get().ruleVersion() == null) {
                        log.info("Gray rule file is not available yet; using the empty snapshot: {}", rulesFile);
                    } else {
                        log.error("Gray rule file disappeared; retaining ruleVersion={}: {}",
                                snapshot.get().ruleVersion(), rulesFile);
                    }
                }
                return false;
            }

            final byte[] content;
            try {
                content = Files.readAllBytes(rulesFile);
            } catch (IOException exception) {
                if (force || !READ_ERROR_FINGERPRINT.equals(lastObservedFingerprint)) {
                    lastObservedFingerprint = READ_ERROR_FINGERPRINT;
                    log.error("Unable to read gray rule file; retaining the last valid snapshot: {}", rulesFile);
                }
                return false;
            }

            String contentDigest = sha256Hex(content);
            if (!force && contentDigest.equals(lastObservedFingerprint)) {
                return false;
            }
            lastObservedFingerprint = contentDigest;

            try {
                Snapshot replacement = parseAndValidate(content);
                Snapshot previous = snapshot.getAndSet(replacement);
                reportSuccessfulLoad(previous, replacement, contentDigest);
                return true;
            } catch (InvalidGrayRuleDocumentException exception) {
                log.error("Rejected gray rule document; retaining the last valid snapshot: reason={}, file={}",
                        exception.reason(), rulesFile);
                return false;
            }
        }
    }

    private Snapshot parseAndValidate(byte[] content) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (IOException exception) {
            throw invalid("JSON_SYNTAX", exception);
        }
        if (root == null || root.isMissingNode()) {
            throw invalid("JSON_EMPTY", null);
        }

        Set<ValidationMessage> schemaErrors = schema.validate(root);
        if (!schemaErrors.isEmpty()) {
            throw invalid("SCHEMA_VALIDATION_" + schemaErrors.size(), null);
        }

        final GrayRuleDocument document;
        try {
            document = objectMapper.treeToValue(root, GrayRuleDocument.class);
        } catch (Exception exception) {
            throw invalid("DOCUMENT_MAPPING", exception);
        }

        if (!hasText(document.ruleVersion())) {
            throw invalid("BLANK_RULE_VERSION", null);
        }

        Map<String, GrayRuleDefinition> rules = new LinkedHashMap<>();
        for (GrayRuleDefinition rule : document.rules()) {
            if (!hasText(rule.getOwner())) {
                throw invalid("BLANK_OWNER", null);
            }
            try {
                OffsetDateTime.parse(rule.getExpiresAt(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException exception) {
                throw invalid("INVALID_EXPIRES_AT", exception);
            }
            if (rules.putIfAbsent(rule.getRuleId(), rule) != null) {
                throw invalid("DUPLICATE_RULE_ID", null);
            }
        }

        return new Snapshot(
                document.ruleVersion(),
                document.bucketSalt(),
                Collections.unmodifiableMap(new LinkedHashMap<>(rules)));
    }

    private void reportSuccessfulLoad(Snapshot previous, Snapshot replacement, String contentDigest) {
        if (!replacement.ruleVersion().equals(lastLoggedRuleVersion)) {
            log.info("Loaded gray rule version={} service={} instance={} rules={} source={}",
                    replacement.ruleVersion(), serviceName, instanceId, replacement.rules().size(), rulesFile);
            lastLoggedRuleVersion = replacement.ruleVersion();
        } else if (!contentDigest.equals(lastLoadedContentDigest)
                && previous.ruleVersion() != null
                && previous.ruleVersion().equals(replacement.ruleVersion())) {
            log.warn("Gray rule content changed without a new ruleVersion; service={} instance={} source={}",
                    serviceName, instanceId, rulesFile);
        }
        lastLoadedContentDigest = contentDigest;
    }

    private JsonSchema loadSchema() {
        try (InputStream input = GrayRuleStore.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Gray rule schema is missing from the common artifact");
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load gray rule schema", exception);
        }
    }

    private String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private InvalidGrayRuleDocumentException invalid(String reason, Throwable cause) {
        return new InvalidGrayRuleDocumentException(reason, cause);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String displayValue(String value) {
        return hasText(value) ? value : "unknown";
    }

    @Override
    public void close() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
        started.set(false);
    }

    record Snapshot(String ruleVersion, String bucketSalt, Map<String, GrayRuleDefinition> rules) {
        static Snapshot empty() {
            return new Snapshot(null, null, Map.of());
        }
    }

    private record GrayRuleDocument(String ruleVersion, String bucketSalt, List<GrayRuleDefinition> rules) {
        @JsonCreator
        private GrayRuleDocument(
                @JsonProperty("ruleVersion") String ruleVersion,
                @JsonProperty("bucketSalt") String bucketSalt,
                @JsonProperty("rules") List<GrayRuleDefinition> rules) {
            this.ruleVersion = ruleVersion;
            this.bucketSalt = bucketSalt;
            this.rules = List.copyOf(rules);
        }
    }

    private static final class InvalidGrayRuleDocumentException extends IllegalArgumentException {
        private final String reason;

        private InvalidGrayRuleDocumentException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }
}
