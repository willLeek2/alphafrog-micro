package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.SearchLlmProperties;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchLlmLocalConfigLoader {

    private final ObjectMapper objectMapper;

    @Value("${agent.search-llm.config-file:}")
    private String configFile;

    private volatile SearchLlmProperties localConfig;
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private volatile Map<String, Long> loadedPromptFileModifiedTimes = new LinkedHashMap<>();
    private final Object reloadLock = new Object();

    private static final String FILE_PREFIX = "file:";
    private static final String FILE_PREFIX_ALT = "file://";
    private static final String FILE_PREFIX_AT = "@file:";

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${agent.search-llm.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    public Optional<SearchLlmProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private void reloadIfNeeded(boolean force) {
        String file = configFile == null ? "" : configFile.trim();
        if (file.isEmpty()) {
            if (force) {
                log.info("agent.search-llm.config-file is empty, skip search llm config loading");
            }
            clearLocalConfigIfPresent("agent.search-llm.config-file is empty");
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                if (force && this.localConfig == null) {
                    log.info("Search llm config file not found, skip: {}", path);
                }
                clearLocalConfigIfPresent("Search llm config file not found: " + path);
                return;
            }
            try {
                long currentModified = Files.getLastModifiedTime(path).toMillis();
                String normalizedPath = path.toString();
                boolean unchanged = normalizedPath.equals(loadedConfigPath) && currentModified == loadedConfigLastModified;
                if (!force && unchanged && !promptFilesChanged()) {
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    SearchLlmProperties parsed = objectMapper.readValue(in, SearchLlmProperties.class);
                    SearchLlmProperties sanitized = sanitize(parsed);
                    Map<String, Long> promptFiles = resolvePromptFiles(sanitized, path.getParent());
                    this.localConfig = sanitized;
                    this.loadedConfigPath = normalizedPath;
                    this.loadedConfigLastModified = currentModified;
                    this.loadedPromptFileModifiedTimes = promptFiles;
                    log.info("Loaded search llm config from {} (providers={})", path, sanitized.getProviders().size());
                }
            } catch (Exception e) {
                log.error("Failed to load search llm config from {}", path, e);
            }
        }
    }

    private boolean promptFilesChanged() {
        if (loadedPromptFileModifiedTimes == null || loadedPromptFileModifiedTimes.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Long> entry : loadedPromptFileModifiedTimes.entrySet()) {
            Path filePath = Paths.get(entry.getKey());
            if (!Files.exists(filePath)) {
                return true;
            }
            try {
                long lastModified = Files.getLastModifiedTime(filePath).toMillis();
                if (lastModified != entry.getValue()) {
                    return true;
                }
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    private void clearLocalConfigIfPresent(String reason) {
        synchronized (reloadLock) {
            if (this.localConfig != null) {
                this.localConfig = null;
                this.loadedConfigPath = "";
                this.loadedConfigLastModified = Long.MIN_VALUE;
                this.loadedPromptFileModifiedTimes = new LinkedHashMap<>();
                log.warn("Local search llm config cleared: {}", reason);
            }
        }
    }

    private Map<String, Long> resolvePromptFiles(SearchLlmProperties cfg, Path baseDir) {
        Map<String, Long> fileTimes = new LinkedHashMap<>();
        if (cfg == null || cfg.getPrompts() == null || baseDir == null) {
            return fileTimes;
        }
        SearchLlmProperties.Prompts prompts = cfg.getPrompts();
        prompts.setMarketNewsQuery(resolvePromptText(prompts.getMarketNewsQuery(), baseDir, fileTimes));
        prompts.setMarketNewsFallback(resolvePromptText(prompts.getMarketNewsFallback(), baseDir, fileTimes));
        return fileTimes;
    }

    private String resolvePromptText(String value, Path baseDir, Map<String, Long> fileTimes) {
        if (!hasText(value)) {
            return value;
        }
        String pathRef = stripFilePrefix(value.trim());
        if (pathRef == null) {
            return value;
        }
        Path filePath = resolveFilePath(pathRef, baseDir);
        if (filePath == null) {
            return "";
        }
        try {
            recordFileModifiedTime(filePath, fileTimes);
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to load prompt file: {}", filePath, e);
            return "";
        }
    }

    private void recordFileModifiedTime(Path filePath, Map<String, Long> fileTimes) {
        try {
            fileTimes.put(filePath.toString(), Files.getLastModifiedTime(filePath).toMillis());
        } catch (Exception e) {
            log.warn("Failed to record prompt file timestamp: {}", filePath, e);
        }
    }

    private Path resolveFilePath(String pathRef, Path baseDir) {
        if (pathRef == null) {
            return null;
        }
        Path filePath = pathRef.startsWith("/") ? Paths.get(pathRef) : baseDir.resolve(pathRef);
        return filePath.toAbsolutePath().normalize();
    }

    private String stripFilePrefix(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith(FILE_PREFIX_AT)) {
            return v.substring(FILE_PREFIX_AT.length());
        }
        if (v.startsWith(FILE_PREFIX_ALT)) {
            return v.substring(FILE_PREFIX_ALT.length());
        }
        if (v.startsWith(FILE_PREFIX)) {
            return v.substring(FILE_PREFIX.length());
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private SearchLlmProperties sanitize(SearchLlmProperties source) {
        SearchLlmProperties target = source == null ? new SearchLlmProperties() : source;
        if (target.getProviders() == null) {
            target.setProviders(new LinkedHashMap<>());
        } else {
            target.getProviders().forEach((k, v) -> {
                if (v == null) {
                    return;
                }
                if (v.getDefaultLanguages() == null) {
                    v.setDefaultLanguages(new java.util.ArrayList<>());
                }
                if (v.getDefaultDomains() == null) {
                    v.setDefaultDomains(new java.util.ArrayList<>());
                }
            });
        }
        if (target.getPrompts() == null) {
            target.setPrompts(new SearchLlmProperties.Prompts());
        }
        return target;
    }
}
