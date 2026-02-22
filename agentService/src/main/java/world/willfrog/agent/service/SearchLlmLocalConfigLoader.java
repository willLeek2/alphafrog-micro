package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.SearchLlmProperties;

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

    private static final String FILE_PREFIX = "file:";
    private static final String FILE_PREFIX_ALT = "file://";
    private static final String FILE_PREFIX_AT = "@file:";

    private final ObjectMapper objectMapper;

    @Value("${agent.search.config-file:}")
    private String configFile;

    private volatile SearchLlmProperties localConfig;
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private volatile Map<String, Long> loadedPromptFileModifiedTimes = new LinkedHashMap<>();
    private final Object reloadLock = new Object();

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${agent.search.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    public Optional<SearchLlmProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private void reloadIfNeeded(boolean force) {
        String file = configFile == null ? "" : configFile.trim();
        if (file.isEmpty()) {
            clearIfPresent();
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                clearIfPresent();
                return;
            }
            try {
                long currentModified = Files.getLastModifiedTime(path).toMillis();
                boolean unchanged = path.toString().equals(loadedConfigPath) && currentModified == loadedConfigLastModified;
                if (!force && unchanged && !promptFilesChanged()) {
                    return;
                }
                SearchLlmProperties parsed = objectMapper.readValue(Files.newInputStream(path), SearchLlmProperties.class);
                Map<String, Long> promptFileTimes = resolvePromptFiles(parsed, path.getParent());
                this.localConfig = parsed;
                this.loadedConfigPath = path.toString();
                this.loadedConfigLastModified = currentModified;
                this.loadedPromptFileModifiedTimes = promptFileTimes;
                log.info("Loaded search llm local config: {}", path);
            } catch (Exception e) {
                log.error("Failed to load search llm local config: {}", path, e);
            }
        }
    }

    private void clearIfPresent() {
        synchronized (reloadLock) {
            this.localConfig = null;
            this.loadedConfigPath = "";
            this.loadedConfigLastModified = Long.MIN_VALUE;
            this.loadedPromptFileModifiedTimes = new LinkedHashMap<>();
        }
    }

    private Map<String, Long> resolvePromptFiles(SearchLlmProperties config, Path baseDir) {
        Map<String, Long> fileTimes = new LinkedHashMap<>();
        if (config == null || config.getPrompts() == null) {
            return fileTimes;
        }
        config.getPrompts().setMarketNewsUserTemplate(resolvePromptText(config.getPrompts().getMarketNewsUserTemplate(), baseDir, fileTimes));
        return fileTimes;
    }

    private String resolvePromptText(String value, Path baseDir, Map<String, Long> fileTimes) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String filePathRef = stripFilePrefix(value.trim());
        if (filePathRef == null) {
            return value;
        }
        try {
            Path filePath = Paths.get(filePathRef);
            if (!filePath.isAbsolute()) {
                filePath = baseDir.resolve(filePathRef).toAbsolutePath().normalize();
            }
            fileTimes.put(filePath.toString(), Files.getLastModifiedTime(filePath).toMillis());
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to read search prompt file: {}", filePathRef, e);
            return "";
        }
    }

    private boolean promptFilesChanged() {
        for (Map.Entry<String, Long> entry : loadedPromptFileModifiedTimes.entrySet()) {
            try {
                Path path = Paths.get(entry.getKey());
                if (!Files.exists(path)) {
                    return true;
                }
                if (Files.getLastModifiedTime(path).toMillis() != entry.getValue()) {
                    return true;
                }
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    private String stripFilePrefix(String raw) {
        if (raw.startsWith(FILE_PREFIX_ALT)) {
            return raw.substring(FILE_PREFIX_ALT.length()).trim();
        }
        if (raw.startsWith(FILE_PREFIX)) {
            return raw.substring(FILE_PREFIX.length()).trim();
        }
        if (raw.startsWith(FILE_PREFIX_AT)) {
            return raw.substring(FILE_PREFIX_AT.length()).trim();
        }
        return null;
    }
}
