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
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchLlmLocalConfigLoader {

    private static final String FILE_PREFIX = "file:";

    private final ObjectMapper objectMapper;

    @Value("${agent.search-llm.config-file:}")
    private String configFile;

    private volatile SearchLlmProperties localConfig;
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private volatile String loadedPromptPath = "";
    private volatile long loadedPromptLastModified = Long.MIN_VALUE;
    private final Object reloadLock = new Object();

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
            clearIfPresent();
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                clearIfPresent();
                if (force) {
                    log.info("search llm local config file not found, skip: {}", path);
                }
                return;
            }
            try {
                long configModified = Files.getLastModifiedTime(path).toMillis();
                boolean unchanged = path.toString().equals(loadedConfigPath)
                        && configModified == loadedConfigLastModified
                        && !promptFileChanged();
                if (!force && unchanged) {
                    return;
                }
                SearchLlmProperties parsed;
                try (var in = Files.newInputStream(path)) {
                    parsed = objectMapper.readValue(in, SearchLlmProperties.class);
                }
                resolvePromptFile(parsed, path.getParent());
                this.localConfig = parsed;
                this.loadedConfigPath = path.toString();
                this.loadedConfigLastModified = configModified;
                log.info("Loaded search llm local config from {}", path);
            } catch (Exception e) {
                log.error("Failed to load search llm local config from {}", path, e);
            }
        }
    }

    private void resolvePromptFile(SearchLlmProperties parsed, Path baseDir) {
        if (parsed == null || parsed.getPrompts() == null) {
            loadedPromptPath = "";
            loadedPromptLastModified = Long.MIN_VALUE;
            return;
        }
        String prompt = parsed.getPrompts().getMarketNewsQueryTemplate();
        if (prompt == null || prompt.isBlank() || !prompt.trim().startsWith(FILE_PREFIX)) {
            loadedPromptPath = "";
            loadedPromptLastModified = Long.MIN_VALUE;
            return;
        }
        String pathRef = prompt.trim().substring(FILE_PREFIX.length()).trim();
        Path promptPath = Paths.get(pathRef);
        if (!promptPath.isAbsolute() && baseDir != null) {
            promptPath = baseDir.resolve(pathRef);
        }
        promptPath = promptPath.toAbsolutePath().normalize();
        try {
            parsed.getPrompts().setMarketNewsQueryTemplate(Files.readString(promptPath, StandardCharsets.UTF_8));
            loadedPromptPath = promptPath.toString();
            loadedPromptLastModified = Files.getLastModifiedTime(promptPath).toMillis();
        } catch (Exception e) {
            parsed.getPrompts().setMarketNewsQueryTemplate("");
            loadedPromptPath = "";
            loadedPromptLastModified = Long.MIN_VALUE;
            log.error("Failed to load search prompt file: {}", promptPath, e);
        }
    }

    private boolean promptFileChanged() {
        if (loadedPromptPath == null || loadedPromptPath.isBlank()) {
            return false;
        }
        try {
            Path path = Paths.get(loadedPromptPath);
            if (!Files.exists(path)) {
                return true;
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            return modified != loadedPromptLastModified;
        } catch (Exception e) {
            return true;
        }
    }

    private void clearIfPresent() {
        synchronized (reloadLock) {
            if (localConfig != null) {
                localConfig = null;
                loadedConfigPath = "";
                loadedConfigLastModified = Long.MIN_VALUE;
                loadedPromptPath = "";
                loadedPromptLastModified = Long.MIN_VALUE;
            }
        }
    }
}
