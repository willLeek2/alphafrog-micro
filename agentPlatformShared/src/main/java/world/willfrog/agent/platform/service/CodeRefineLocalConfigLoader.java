package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.CodeRefineProperties;
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
 * Code Refine 本地配置热加载器。
 *
 * <p>支持文件轮询热加载、Nacos 推送后的自动感知、配置状态 Redis 上报。</p>
 */
@Component
@Slf4j
public class CodeRefineLocalConfigLoader implements ApplicationListener<NacosLocalConfigWrittenEvent> {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final CodeRefineProperties properties;

    @Value("${spring.application.name:agent-platform}")
    private String serviceName;

    @Value("${spring.application.instance-id:${HOSTNAME:unknown}}")
    private String instanceId;

    @Value("${AF_LANE_TRAFFIC_SCOPE_ID:}")
    private String laneTrafficScopeId;

    private volatile CodeRefineProperties localConfig;
    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = -1;
    private volatile byte[] loadedConfigBytes = new byte[0];
    /**
     * Nacos 缓存文件是 camelCase。解析整份文件时用这份 mapper，不跟应用里可能被改成
     * SNAKE_CASE 的 ObjectMapper 走同一套命名。
     */
    private volatile ObjectMapper fileMapper;
    private final Object fileMapperLock = new Object();

    @Autowired
    public CodeRefineLocalConfigLoader(ObjectMapper objectMapper,
                                        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                        CodeRefineProperties properties) {
        this(objectMapper, redisTemplateProvider.getIfAvailable(), properties);
    }

    public CodeRefineLocalConfigLoader(ObjectMapper objectMapper,
                                       StringRedisTemplate redisTemplate,
                                       CodeRefineProperties properties) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${agent.flow.code-refine.config-refresh-interval-ms:10000}")
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

    private String resolvedConfigFile() {
        String configured = properties.getConfigFile() == null ? "" : properties.getConfigFile().trim();
        return NacosLocalCachePaths.isolate(configured, laneTrafficScopeId);
    }

    private void reloadIfNeeded(boolean force) {
        String file = resolvedConfigFile();
        if (file.isEmpty()) {
            if (force) {
                log.info("agent.flow.code-refine.config-file is empty, skip local code refine config loading");
            }
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            if (force) {
                log.info("Local code refine config file not found, skip: {}", path);
            }
            return;
        }

        try {
            long currentModified = Files.getLastModifiedTime(path).toMillis();
            String normalizedPath = path.toString();
            if (!force && normalizedPath.equals(loadedConfigPath) && currentModified == loadedConfigLastModified) {
                reportState(loadedConfigBytes);
                return;
            }

            try (InputStream in = Files.newInputStream(path)) {
                byte[] bytes = in.readAllBytes();
                CodeRefineProperties parsed = fileMapper().readValue(bytes, CodeRefineProperties.class);

                // 解析 ${ENV_VAR} 占位符
                PlaceholderResolver.resolve(parsed);

                this.localConfig = sanitize(parsed);
                this.loadedConfigPath = normalizedPath;
                this.loadedConfigLastModified = currentModified;
                this.loadedConfigBytes = bytes;

                log.info("Loaded local code refine config from {} (maxAttempts={})",
                        path, this.localConfig.getMaxAttempts());

                // 上报 Redis 状态
                reportState(bytes);
            }
        } catch (Exception e) {
            log.error("Failed to load local code refine config from {}", path, e);
        }
    }

    public Optional<CodeRefineProperties> current() {
        return Optional.ofNullable(localConfig);
    }

    private CodeRefineProperties sanitize(CodeRefineProperties input) {
        CodeRefineProperties cfg = input == null ? new CodeRefineProperties() : input;
        if (cfg.getMaxAttempts() <= 0) {
            cfg.setMaxAttempts(3);
        }
        return cfg;
    }

    private ObjectMapper fileMapper() {
        ObjectMapper cached = fileMapper;
        if (cached != null) {
            return cached;
        }
        synchronized (fileMapperLock) {
            if (fileMapper == null) {
                ObjectMapper copy = objectMapper.copy();
                copy.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
                fileMapper = copy;
            }
            return fileMapper;
        }
    }

    /**
     * 上报配置加载状态到 Redis，供 admin 查询副本生效情况。
     */
    private void reportState(byte[] contentBytes) {
        ConfigLoadStateReporter.report(redisTemplate, serviceName, instanceId,
                "code-refine.json", loadedConfigPath, contentBytes);
    }
}
