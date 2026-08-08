package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Loads Nacos-written JSON over application defaults, then clamps every value to code ceilings. */
@Component
@Slf4j
public class FinanceRecordChannelConfigLoader {

    private final ObjectMapper objectMapper;
    private final FinanceRecordChannelProperties defaults;
    private volatile Snapshot current;
    private volatile String loadedPath = "";
    private volatile long loadedLastModified = Long.MIN_VALUE;

    public FinanceRecordChannelConfigLoader(
            ObjectMapper objectMapper,
            FinanceRecordChannelProperties defaults) {
        this.objectMapper = objectMapper;
        this.defaults = defaults;
        this.current = sanitize(null, "application-defaults");
    }

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${agent.finance-record-channel.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    public Snapshot current() {
        return current;
    }

    public String frozenSnapshotJson() {
        try {
            Snapshot snapshot = current;
            MapPayload payload = new MapPayload();
            payload.effectiveFinanceRecordConfig = new EffectiveConfig(snapshot.limits());
            payload.targetEnvironment = snapshot.targetEnvironment();
            payload.sourceRevision = snapshot.sourceRevision();
            payload.limitsClamped = snapshot.limitsClamped();
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_CONFIG_SNAPSHOT_FAILED",
                    "Unable to serialize frozen finance record config", exception);
        }
    }

    public FinanceRecordChannelLimits parseFrozenLimits(String json) {
        return parseFrozenSnapshot(json).limits();
    }

    /**
     * Restores the complete immutable snapshot stored on a run/tool-job anchor.
     * Terminal processing must not combine frozen limits with a later live target-environment config.
     */
    public Snapshot parseFrozenSnapshot(String json) {
        try {
            MapPayload payload = objectMapper.readValue(json, MapPayload.class);
            if (payload.effectiveFinanceRecordConfig == null) {
                throw new IllegalArgumentException("effectiveFinanceRecordConfig is required");
            }
            FinanceRecordChannelLimits limits = payload.effectiveFinanceRecordConfig.toLimits();
            FinanceEnvironmentFact targetEnvironment = payload.targetEnvironment;
            if (!limits.targetEnvironmentId().isBlank() && targetEnvironment == null) {
                throw new IllegalArgumentException(
                        "targetEnvironment is required when targetEnvironmentId is configured");
            }
            if (targetEnvironment != null
                    && !limits.targetEnvironmentId().equals(targetEnvironment.environmentId())) {
                throw new IllegalArgumentException(
                        "targetEnvironment.environmentId must match targetEnvironmentId");
            }
            return new Snapshot(
                    limits,
                    targetEnvironment,
                    trim(payload.sourceRevision),
                    Boolean.TRUE.equals(payload.limitsClamped));
        } catch (Exception exception) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_CONFIG_SNAPSHOT_INVALID",
                    "Unable to parse frozen finance record config", exception);
        }
    }

    void reloadIfNeeded(boolean force) {
        String configured = trim(defaults.getConfigFile());
        if (configured.isEmpty()) {
            current = sanitize(null, "application-defaults");
            return;
        }
        Path path = Paths.get(configured).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            log.warn("Finance record config file not found; keeping last valid snapshot: {}", path);
            return;
        }
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (!force && path.toString().equals(loadedPath) && modified == loadedLastModified) {
                return;
            }
            byte[] bytes = Files.readAllBytes(path);
            DynamicConfig dynamic = objectMapper.readValue(bytes, DynamicConfig.class);
            validateDynamic(dynamic);
            current = sanitize(dynamic, "sha256:" + FinanceRecordDecoder.sha256Hex(bytes));
            loadedPath = path.toString();
            loadedLastModified = modified;
            log.info("Loaded finance record channel config from {} (enabled={}, limitsClamped={})",
                    path, current.limits().enabled(), current.limitsClamped());
        } catch (Exception exception) {
            log.error("Failed to load finance record channel config; keeping last valid snapshot: {}", path, exception);
        }
    }

    private Snapshot sanitize(DynamicConfig dynamic, String sourceRevision) {
        boolean enabled = dynamic != null && dynamic.enabled != null
                ? dynamic.enabled : defaults.isEnabled();
        Clamp count = clamp(firstPositive(dynamic == null ? null : dynamic.recordCountMax,
                defaults.getRecordCountMax()), FinanceRecordChannelProperties.HARD_RECORD_COUNT_MAX);
        Clamp record = clamp(firstPositive(dynamic == null ? null : dynamic.recordMaxBytes,
                defaults.getRecordMaxBytes()), FinanceRecordChannelProperties.HARD_RECORD_MAX_BYTES);
        Clamp channel = clamp(firstPositive(dynamic == null ? null : dynamic.recordChannelMaxBytes,
                defaults.getRecordChannelMaxBytes()), FinanceRecordChannelProperties.HARD_RECORD_CHANNEL_MAX_BYTES);
        Clamp stdout = clamp(firstPositive(dynamic == null ? null : dynamic.stdoutMaxBytes,
                defaults.getStdoutMaxBytes()), FinanceRecordChannelProperties.HARD_STDOUT_MAX_BYTES);
        Clamp stderr = clamp(firstPositive(dynamic == null ? null : dynamic.stderrMaxBytes,
                defaults.getStderrMaxBytes()), FinanceRecordChannelProperties.HARD_STDERR_MAX_BYTES);

        FinanceRecordChannelProperties.TargetEnvironment sourceEnvironment = dynamic != null
                && dynamic.targetEnvironment != null
                ? dynamic.targetEnvironment : defaults.getTargetEnvironment();
        FinanceEnvironmentFact environment = toFact(sourceEnvironment);
        FinanceRecordChannelLimits limits = new FinanceRecordChannelLimits(
                enabled, count.value, record.value, channel.value, stdout.value, stderr.value,
                environment == null ? "" : environment.environmentId());
        boolean clamped = count.clamped || record.clamped || channel.clamped
                || stdout.clamped || stderr.clamped;
        return new Snapshot(limits, environment, sourceRevision, clamped);
    }

    /**
     * Dynamic configuration is an atomic last-known-good document. Supplying one invalid
     * limit or a partial target environment must reject the whole revision instead of mixing
     * that revision with application defaults and presenting the hybrid as a valid snapshot.
     */
    private static void validateDynamic(DynamicConfig dynamic) {
        if (dynamic == null) {
            throw new IllegalArgumentException("dynamic finance record config is required");
        }
        requirePositiveWhenPresent(dynamic.recordCountMax, "recordCountMax");
        requirePositiveWhenPresent(dynamic.recordMaxBytes, "recordMaxBytes");
        requirePositiveWhenPresent(dynamic.recordChannelMaxBytes, "recordChannelMaxBytes");
        requirePositiveWhenPresent(dynamic.stdoutMaxBytes, "stdoutMaxBytes");
        requirePositiveWhenPresent(dynamic.stderrMaxBytes, "stderrMaxBytes");

        FinanceRecordChannelProperties.TargetEnvironment environment = dynamic.targetEnvironment;
        if (environment == null) {
            return;
        }
        requireNonBlank(environment.getEnvironmentId(), "targetEnvironment.environmentId");
        requireNonBlank(environment.getImageDigest(), "targetEnvironment.imageDigest");
        requireNonBlank(environment.getLibrarySetDigest(), "targetEnvironment.librarySetDigest");
        List<FinanceRecordChannelProperties.PackageApi> packages = environment.getPackageApis();
        if (packages == null || packages.isEmpty()) {
            throw new IllegalArgumentException("targetEnvironment.packageApis is required");
        }
        boolean financePackageFound = false;
        for (FinanceRecordChannelProperties.PackageApi item : packages) {
            if (item == null) {
                throw new IllegalArgumentException("targetEnvironment.packageApis contains null");
            }
            requireNonBlank(item.getName(), "targetEnvironment.packageApis.name");
            requireNonBlank(item.getVersion(), "targetEnvironment.packageApis.version");
            requireNonBlank(item.getApiVersion(), "targetEnvironment.packageApis.apiVersion");
            if ("alphafrog_finance".equals(trim(item.getName()))) {
                financePackageFound = true;
            }
        }
        if (!financePackageFound) {
            throw new IllegalArgumentException(
                    "targetEnvironment.packageApis must contain alphafrog_finance");
        }
    }

    private static void requirePositiveWhenPresent(Integer value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (trim(value).isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private static FinanceEnvironmentFact toFact(
            FinanceRecordChannelProperties.TargetEnvironment source) {
        if (source == null || trim(source.getEnvironmentId()).isEmpty()) {
            return null;
        }
        List<FinanceEnvironmentFact.PackageApi> packageApis = new ArrayList<>();
        if (source.getPackageApis() != null) {
            for (FinanceRecordChannelProperties.PackageApi item : source.getPackageApis()) {
                if (item != null) {
                    packageApis.add(new FinanceEnvironmentFact.PackageApi(
                            item.getName(), item.getVersion(), item.getApiVersion()));
                }
            }
        }
        return new FinanceEnvironmentFact(
                source.getEnvironmentId(), source.getImageDigest(), source.getLibrarySetDigest(),
                packageApis, true);
    }

    private static int firstPositive(Integer override, int fallback) {
        return override != null && override > 0 ? override : Math.max(1, fallback);
    }

    private static Clamp clamp(int value, int hardLimit) {
        return new Clamp(Math.min(value, hardLimit), value > hardLimit);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record Snapshot(
            FinanceRecordChannelLimits limits,
            FinanceEnvironmentFact targetEnvironment,
            String sourceRevision,
            boolean limitsClamped) {
    }

    private record Clamp(int value, boolean clamped) {}

    static class DynamicConfig {
        public Boolean enabled;
        public Integer recordCountMax;
        public Integer recordMaxBytes;
        public Integer recordChannelMaxBytes;
        public Integer stdoutMaxBytes;
        public Integer stderrMaxBytes;
        public FinanceRecordChannelProperties.TargetEnvironment targetEnvironment;
    }

    static class MapPayload {
        public EffectiveConfig effectiveFinanceRecordConfig;
        public FinanceEnvironmentFact targetEnvironment;
        public String sourceRevision;
        public Boolean limitsClamped;
    }

    static class EffectiveConfig {
        public Boolean enabled;
        public Integer recordCountMax;
        public Integer recordMaxBytes;
        public Integer recordChannelMaxBytes;
        public Integer stdoutMaxBytes;
        public Integer stderrMaxBytes;
        public String targetEnvironmentId;

        EffectiveConfig() {}

        EffectiveConfig(FinanceRecordChannelLimits limits) {
            enabled = limits.enabled();
            recordCountMax = limits.recordCountMax();
            recordMaxBytes = limits.recordMaxBytes();
            recordChannelMaxBytes = limits.recordChannelMaxBytes();
            stdoutMaxBytes = limits.stdoutMaxBytes();
            stderrMaxBytes = limits.stderrMaxBytes();
            targetEnvironmentId = limits.targetEnvironmentId();
        }

        FinanceRecordChannelLimits toLimits() {
            return new FinanceRecordChannelLimits(
                    Boolean.TRUE.equals(enabled), required(recordCountMax, "recordCountMax"),
                    required(recordMaxBytes, "recordMaxBytes"),
                    required(recordChannelMaxBytes, "recordChannelMaxBytes"),
                    required(stdoutMaxBytes, "stdoutMaxBytes"),
                    required(stderrMaxBytes, "stderrMaxBytes"), targetEnvironmentId);
        }

        private static int required(Integer value, String name) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
