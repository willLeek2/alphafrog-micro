package world.willfrog.externalinfo.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.config.ConfigLoadStateReporter;
import world.willfrog.alphafrogmicro.common.config.nacos.NacosLocalCachePaths;
import world.willfrog.alphafrogmicro.common.config.nacos.NacosLocalConfigWrittenEvent;
import world.willfrog.alphafrogmicro.common.utils.PlaceholderResolver;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * RAG Embedding 本地配置热加载器。
 *
 * <p>读取 {@code alphafrog.rag.embedding.config-file} 指向的 JSON 文件，
 * 定期检测文件修改时间，文件变更后自动重新加载，无需重启服务。</p>
 *
 * <p>配置文件示例：{@code externalInfoService/config/rag-embedding.local.example.json}</p>
 */
@Component
@Slf4j
public class RagEmbeddingLocalConfigLoader implements ApplicationListener<NacosLocalConfigWrittenEvent> {

    @Value("${alphafrog.rag.embedding.config-file:}")
    private String configFile;

    @Value("${spring.application.name:external-info-service}")
    private String serviceName;

    @Value("${spring.application.instance-id:${HOSTNAME:unknown}}")
    private String instanceId;

    @Value("${AF_LANE_TRAFFIC_SCOPE_ID:}")
    private String laneTrafficScopeId;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;
    private volatile RagEmbeddingProperties localConfig;
    private final Object reloadLock = new Object();
    private volatile String loadedConfigPath = "";
    private long loadedConfigLastModified = -1;
    private volatile byte[] loadedConfigBytes = new byte[0];
    /**
     * Nacos 缓存文件是 camelCase。解析整份文件时用这份 mapper，不跟应用里可能被改成
     * SNAKE_CASE 的 ObjectMapper 走同一套命名。
     */
    private volatile ObjectMapper fileMapper;

    public RagEmbeddingLocalConfigLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${alphafrog.rag.embedding.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    @Override
    public void onApplicationEvent(NacosLocalConfigWrittenEvent event) {
        applyNacosWrittenFile(event.getTargetFile());
    }

    /**
     * Nacos 已经把生效内容写进 {@code targetFile}。路径对得上才重读，避免泳道吃到主环境那份未加前缀的缓存。
     */
    public void applyNacosWrittenFile(String targetFile) {
        if (targetFile == null || targetFile.isBlank()) {
            return;
        }
        String file = resolvedConfigFile();
        if (file.isEmpty()) {
            return;
        }
        Path configured = Paths.get(file).toAbsolutePath().normalize();
        Path written = Paths.get(targetFile).toAbsolutePath().normalize();
        if (!configured.equals(written)) {
            return;
        }
        reloadIfNeeded(true);
    }

    /** 获取当前已加载的本地配置，未加载时返回 empty。 */
    public Optional<RagEmbeddingProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private String resolvedConfigFile() {
        return NacosLocalCachePaths.isolate(configFile == null ? "" : configFile.trim(), laneTrafficScopeId);
    }

    private void reloadIfNeeded(boolean force) {
        String file = resolvedConfigFile();
        if (file == null || file.isBlank()) {
            if (force) {
                log.info("[RagEmbeddingLocalConfigLoader] config-file 未配置，跳过本地配置加载");
            }
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            if (force) {
                log.info("[RagEmbeddingLocalConfigLoader] 配置文件不存在，跳过: {}", path);
            }
            return;
        }
        try {
            long currentModified = Files.getLastModifiedTime(path).toMillis();
            if (!force && currentModified == loadedConfigLastModified) {
                reportState(loadedConfigBytes);
                return;
            }
            synchronized (reloadLock) {
                if (!force && currentModified == loadedConfigLastModified) {
                    reportState(loadedConfigBytes);
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] bytes = in.readAllBytes();
                    RagEmbeddingProperties parsed = fileMapper().readValue(bytes, RagEmbeddingProperties.class);
                    PlaceholderResolver.resolve(parsed);
                    this.localConfig = parsed;
                    this.loadedConfigPath = path.toString();
                    this.loadedConfigLastModified = currentModified;
                    this.loadedConfigBytes = bytes;
                    reportState(bytes);
                    log.info("[RagEmbeddingLocalConfigLoader] 已加载 embedding 配置: {} model={} dimensions={}",
                            path,
                            localConfig.getModel().isBlank() ? "(默认)" : localConfig.getModel(),
                            localConfig.getDimensions() == 0 ? "(默认)" : localConfig.getDimensions());
                }
            }
        } catch (Exception e) {
            log.error("[RagEmbeddingLocalConfigLoader] 加载失败: {} - {}", file, e.getMessage());
        }
    }

    private void reportState(byte[] contentBytes) {
        ConfigLoadStateReporter.report(redisTemplate, serviceName, instanceId,
                "rag-embedding.json", loadedConfigPath, contentBytes);
    }

    private ObjectMapper fileMapper() {
        ObjectMapper cached = fileMapper;
        if (cached != null) {
            return cached;
        }
        synchronized (reloadLock) {
            if (fileMapper == null) {
                ObjectMapper copy = objectMapper.copy();
                copy.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
                fileMapper = copy;
            }
            return fileMapper;
        }
    }
}
