package world.willfrog.alphafrogmicro.common.config.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Nacos 配置桥接器。
 *
 * <p>订阅 Nacos Config 的指定 dataId，收到推送后三段式写本地文件，
 * 让各微服务现有的 *LocalConfigLoader 通过文件轮询自动热加载。</p>
 *
 * <p>dataId 解析按「泳道 → 主」候选链（见 {@link #candidateDataIds(Subscription)}）：
 * 容器设置了 AF_LANE_TRAFFIC_SCOPE_ID 时先查 "{scopeId}.{dataId}"（泳道覆盖），
 * 无内容再回落 "{dataId}"（主配置）；整链为空打 error 日志，本地加载器回落默认。
 * 候选链只做组内 data-id 回落：所有查询都固定用订阅自身的 group，
 * 绝不跨组回退（不回落到订阅组之外的任何组，也不在组内造别的组名）。</p>
 */
@Slf4j
@Component
public class NacosConfigBridge {

    @Value("${alphafrog.config.nacos.server-addr:127.0.0.1:8848}")
    private String serverAddr;

    @Value("${alphafrog.config.nacos.namespace:}")
    private String namespace;

    @Value("${alphafrog.config.nacos.data-id:}")
    private String dataId;

    @Value("${alphafrog.config.nacos.group:alphafrog-config}")
    private String group;

    @Value("${alphafrog.config.nacos.enabled:false}")
    private boolean enabled;

    @Value("${agent.flow.code-refine.config-file:}")
    private String configFilePath;

    /** Prompt 覆盖层的默认 Nacos dataId。这份配置被删掉时，本地文件也得一起拿掉，加载器才能回落到权威默认。 */
    private static final String PROMPT_OVERLAY_DATA_ID = "agent-prompt-overlay.json";

    /** 泳道隔离变量（由 Beta 控制器注入容器）：非空表示容器跑在某条泳道上，配置解析先查 "{scopeId}.{dataId}" 再回落主 dataId。 */
    private static final String LANE_SCOPE_PROPERTY = "AF_LANE_TRAFFIC_SCOPE_ID";

    private final ObjectMapper objectMapper;
    private final Environment environment;
    private ConfigService configService;
    private final List<Subscription> activeSubscriptions = new ArrayList<>();
    private final Map<String, String> lastWrittenContentBySubscription = new LinkedHashMap<>();

    @Autowired
    public NacosConfigBridge(ObjectProvider<ObjectMapper> objectMapperProvider, Environment environment) {
        this(objectMapperProvider.getIfAvailable(ObjectMapper::new), environment);
    }

    public NacosConfigBridge(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[NacosConfigBridge] 未启用，跳过初始化");
            return;
        }
        List<Subscription> subscriptions = resolveSubscriptions();
        if (subscriptions.isEmpty()) {
            log.warn("[NacosConfigBridge] 未配置有效订阅，跳过初始化");
            return;
        }

        try {
            Properties properties = new Properties();
            properties.put("serverAddr", serverAddr);
            if (namespace != null && !namespace.isBlank()) {
                properties.put("namespace", namespace);
            }
            this.configService = NacosFactory.createConfigService(properties);

            for (Subscription subscription : subscriptions) {
                subscribe(subscription);
            }
        } catch (NacosException e) {
            log.error("[NacosConfigBridge] Nacos 初始化失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (configService != null) {
            try {
                configService.shutDown();
            } catch (NacosException e) {
                log.warn("[NacosConfigBridge] 关闭 Nacos 客户端异常", e);
            }
        }
    }

    private List<Subscription> resolveSubscriptions() {
        List<Subscription> subscriptions = Binder.get(environment)
                .bind("alphafrog.config.nacos.subscriptions",
                        org.springframework.boot.context.properties.bind.Bindable.listOf(Subscription.class))
                .orElseGet(ArrayList::new);
        List<Subscription> valid = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            if (subscription == null || isBlank(subscription.getDataId()) || isBlank(subscription.getTargetFile())) {
                continue;
            }
            if (isBlank(subscription.getGroup())) {
                subscription.setGroup(group);
            }
            valid.add(subscription);
        }
        if (valid.isEmpty() && !isBlank(dataId) && !isBlank(configFilePath)) {
            Subscription legacy = new Subscription();
            legacy.setDataId(dataId);
            legacy.setGroup(group);
            legacy.setTargetFile(configFilePath);
            valid.add(legacy);
        }
        return valid;
    }

    private void subscribe(Subscription subscription) throws NacosException {
        String subscriptionGroup = isBlank(subscription.getGroup()) ? group : subscription.getGroup();
        List<String> candidates = candidateDataIds(subscription);
        // Nacos 客户端断连时 getConfig 会回退本地快照文件；候选链上每一条的快照都要清掉，
        // 残留的泳道快照可能遮蔽已在服务端删除的泳道配置。
        for (String candidate : candidates) {
            clearNacosLocalSnapshot(candidate, subscriptionGroup);
        }
        ResolvedConfig resolved = resolveConfigContent(subscription);
        applyResolvedConfig(subscription, resolved, "initial-load");

        for (String candidate : candidates) {
            addChainListener(subscription, candidate, subscriptionGroup);
        }
        synchronized (activeSubscriptions) {
            activeSubscriptions.add(subscription);
        }
        log.info("[NacosConfigBridge] 已订阅 Nacos 配置 server={} dataId={} 候选链={} group={} filePath={}",
                serverAddr, subscription.getDataId(), candidates, subscriptionGroup, subscription.getTargetFile());
    }

    /**
     * 对候选链上的单条 data-id 注册监听。
     *
     * <p>需求原文写「对链上实际生效的那条 data-id 注册 listener」；本实现是它的超集：
     * 链上每一条（主 dataId 恒有，设了 AF_LANE_TRAFFIC_SCOPE_ID 时再加泳道那条）都挂 listener，
     * 回调不直接信任推送内容，而是重新跑整链解析（{@link #resolveConfigContent}）后应用结果。
     * 取舍原因：「实际生效的那条」会随泳道覆盖的增删动态变化，只挂生效条就必须维护 listener
     * 换绑状态机（泳道删除时解绑旧条、回落时再绑新条、断连重启还要恢复）；两条全挂 + 事件到达时
     * 整链重解析，用一次多余的服务端查询换掉整个状态机，并天然覆盖两类事件——「泳道被删后
     * 回落主 Beta」和「泳道缺失期间主 Beta 变更」。代价是泳道命中期间主 Beta 的变更推送会多查
     * 一次泳道 data-id，重解析结果仍是泳道内容，行为正确。</p>
     */
    private void addChainListener(Subscription subscription, String candidateDataId, String subscriptionGroup)
            throws NacosException {
        configService.addListener(candidateDataId, subscriptionGroup, new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String config) {
                log.info("[NacosConfigBridge] 收到配置推送 dataId={} 逻辑dataId={}",
                        candidateDataId, subscription.getDataId());
                try {
                    ResolvedConfig latest = resolveConfigContent(subscription);
                    applyResolvedConfig(subscription, latest, "listener");
                } catch (Exception e) {
                    log.error("[NacosConfigBridge] 监听回调整链重解析失败 dataId={} 候选链={} group={}",
                            subscription.getDataId(), candidateDataIds(subscription), subscriptionGroup, e);
                }
            }
        });
    }

    /**
     * 候选 data-id 链（组内回落，绝不跨组）：
     * 设了 AF_LANE_TRAFFIC_SCOPE_ID 时 = ["{scopeId}.{dataId}", "{dataId}"]
     * （如 lane-demo.agent-llm.json → agent-llm.json）；未设时 = ["{dataId}"]。
     * 所有查询都用订阅自身的 group（默认 alphafrog-config，来自现有 @Value/订阅配置），
     * 绝不回落到订阅组之外的任何组（如生产 alphafrog-config），也不在组内造别的组名。
     */
    private List<String> candidateDataIds(Subscription subscription) {
        List<String> candidates = new ArrayList<>();
        String scopeId = laneScopeId();
        if (!scopeId.isBlank()) {
            candidates.add(scopeId + "." + subscription.getDataId());
        }
        candidates.add(subscription.getDataId());
        return candidates;
    }

    private String laneScopeId() {
        String scopeId = environment == null ? null : environment.getProperty(LANE_SCOPE_PROPERTY);
        return scopeId == null ? "" : scopeId.trim();
    }

    /**
     * 按候选顺序逐条 getConfig，第一条非 null 且非 blank 的内容生效；整链都空返回 content=null。
     * 方法内不吞异常：getConfig 抛出的 NacosException 原样上抛，由调用方按现有结构处理
     * （subscribe 直接上抛给 init 的 catch；refresh/listener 回调已有 try-catch）。
     */
    private ResolvedConfig resolveConfigContent(Subscription subscription) throws NacosException {
        String subscriptionGroup = isBlank(subscription.getGroup()) ? group : subscription.getGroup();
        for (String candidate : candidateDataIds(subscription)) {
            String content = configService.getConfig(candidate, subscriptionGroup, 5000);
            if (content != null && !content.isBlank()) {
                return new ResolvedConfig(content, candidate);
            }
        }
        return new ResolvedConfig(null, null);
    }

    /** resolveConfigContent 的结果：content 为最终生效内容（整链为空时为 null），effectiveDataId 为它来自候选链的哪一条。 */
    private record ResolvedConfig(String content, String effectiveDataId) {
        boolean hasContent() {
            return content != null && !content.isBlank();
        }
    }

    /**
     * 应用整链解析结果（初始加载 / listener 回调 / 定时刷新共用）：
     * 有内容走既有写入路径；整链为空时 prompt overlay 删本地文件（回落权威默认），
     * 其它 dataId 打 error 日志并保留本地文件不动，由各 *LocalConfigLoader 回落 classpath/env 默认。
     */
    private void applyResolvedConfig(Subscription subscription, ResolvedConfig resolved, String source) {
        if (resolved.hasContent()) {
            writeConfigToFileIfChanged(subscription, resolved.content(), resolved.effectiveDataId(), source);
            return;
        }
        if (shouldDeleteLocalOnBlank(subscription)) {
            // 启动时 Nacos 上已经没有 overlay，别把上次留下的本地文件继续当成覆盖层。
            removeLocalTargetIfPresent(subscription);
            return;
        }
        String subscriptionGroup = isBlank(subscription.getGroup()) ? group : subscription.getGroup();
        log.error("[NacosConfigBridge] 配置候选链全部为空 dataId={} 候选链={} group={} source={}，"
                        + "保留本地文件不动，等待候选链恢复或由本地加载器回落默认",
                subscription.getDataId(), candidateDataIds(subscription), subscriptionGroup, source);
    }

    /**
     * Periodic pull refresh for missed Nacos listener events or stale long-poll connections.
     */
    @Scheduled(fixedDelayString = "${alphafrog.config.nacos.refresh-interval-ms:30000}")
    public void refreshSubscriptions() {
        if (!enabled || configService == null || activeSubscriptions.isEmpty()) {
            return;
        }
        List<Subscription> subscriptionsSnapshot;
        synchronized (activeSubscriptions) {
            subscriptionsSnapshot = List.copyOf(activeSubscriptions);
        }
        for (Subscription subscription : subscriptionsSnapshot) {
            String subscriptionGroup = isBlank(subscription.getGroup()) ? group : subscription.getGroup();
            try {
                ResolvedConfig latest = resolveConfigContent(subscription);
                applyResolvedConfig(subscription, latest, "periodic-refresh");
            } catch (Exception e) {
                log.warn("[NacosConfigBridge] 定时刷新失败 dataId={} group={}",
                        subscription.getDataId(), subscriptionGroup, e);
            }
        }
    }

    private void writeConfigToFileIfChanged(Subscription subscription, String configContent, String effectiveDataId, String source) {
        String key = subscriptionKey(subscription);
        synchronized (lastWrittenContentBySubscription) {
            String lastWritten = lastWrittenContentBySubscription.get(key);
            if (configContent.equals(lastWritten) && fileContentEquals(subscription, configContent)) {
                return;
            }
            writeConfigToFile(subscription, configContent);
            if (fileContentEquals(subscription, configContent)) {
                lastWrittenContentBySubscription.put(key, configContent);
                log.info("[NacosConfigBridge] 配置同步完成 dataId={} effectiveDataId={} source={}",
                        subscription.getDataId(), effectiveDataId, source);
            }
        }
    }

    private boolean fileContentEquals(Subscription subscription, String configContent) {
        try {
            Path targetPath = Paths.get(subscription.getTargetFile()).toAbsolutePath().normalize();
            return Files.exists(targetPath)
                    && configContent.equals(Files.readString(targetPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    private String subscriptionKey(Subscription subscription) {
        String subscriptionGroup = isBlank(subscription.getGroup()) ? group : subscription.getGroup();
        return subscription.getDataId() + "\n" + subscriptionGroup + "\n" + subscription.getTargetFile();
    }

    /**
     * 只有 prompt overlay 这份订阅，空白内容才表示「配置被删了」。
     * 其它 dataId 继续忽略空白，避免误删 agent-llm 等本地文件。
     */
    private boolean shouldDeleteLocalOnBlank(Subscription subscription) {
        return subscription != null && PROMPT_OVERLAY_DATA_ID.equals(subscription.getDataId());
    }

    /**
     * 删掉本地目标文件并清掉上次写入缓存。
     * 文件不在了，PromptOverlayLoader 才会回落到权威默认。
     */
    private void removeLocalTargetIfPresent(Subscription subscription) {
        if (subscription == null || isBlank(subscription.getTargetFile())) {
            return;
        }
        Path targetPath = Paths.get(subscription.getTargetFile()).toAbsolutePath().normalize();
        synchronized (lastWrittenContentBySubscription) {
            try {
                boolean deleted = Files.deleteIfExists(targetPath);
                lastWrittenContentBySubscription.remove(subscriptionKey(subscription));
                if (deleted) {
                    log.info("[NacosConfigBridge] 配置已删除，本地文件已移除 dataId={} file={}",
                            subscription.getDataId(), targetPath);
                }
            } catch (IOException e) {
                log.warn("[NacosConfigBridge] 删除本地配置文件失败 dataId={} file={}",
                        subscription.getDataId(), targetPath, e);
            }
        }
    }

    private void clearNacosLocalSnapshot(String dataId, String subscriptionGroup) {
        if (isBlank(dataId)) {
            return;
        }
        String effectiveServerAddr = isBlank(serverAddr) ? "127.0.0.1:8848" : serverAddr;
        String fixedAddr = "fixed-" + effectiveServerAddr.replace(":", "_");
        String namespaceDir = isBlank(namespace) ? "nacos" : namespace;
        Path snapshotFile = Paths.get(System.getProperty("user.home"),
                "nacos", "config", fixedAddr, namespaceDir, "snapshot", subscriptionGroup, dataId);
        try {
            if (Files.deleteIfExists(snapshotFile)) {
                log.info("[NacosConfigBridge] 已清除本地快照: {}", snapshotFile);
            }
        } catch (IOException e) {
            log.warn("[NacosConfigBridge] 清除本地快照失败: {}", snapshotFile, e);
        }
    }

    /**
     * 三段式写文件：tmp → fsync → JSON 校验 → atomic move → 读回校验
     */
    private void writeConfigToFile(Subscription subscription, String configContent) {
        Path targetPath = Paths.get(subscription.getTargetFile()).toAbsolutePath().normalize();
        Path tmpPath = Paths.get(subscription.getTargetFile() + ".tmp");
        Path backupPath = Paths.get(subscription.getTargetFile() + ".backup." + System.currentTimeMillis());
        boolean movedToTarget = false;

        try {
            Files.createDirectories(targetPath.getParent());
            // 0. 若目标文件存在，先备份
            if (Files.exists(targetPath)) {
                Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // 1. 写临时文件
            Files.writeString(tmpPath, configContent, StandardCharsets.UTF_8);

            // 2. fsync（确保数据落盘）
            try (FileChannel channel = FileChannel.open(tmpPath,
                    StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            // 3. 移动前先校验临时文件，避免无效 JSON 覆盖可用配置。
            objectMapper.readTree(tmpPath.toFile());

            // 4. 原子移动到目标路径
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                movedToTarget = true;
            } catch (AtomicMoveNotSupportedException e) {
                log.warn("[NacosConfigBridge] 原子移动不支持，回退到普通移动: {}", targetPath);
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                movedToTarget = true;
            }

            // 5. 读回校验
            objectMapper.readTree(targetPath.toFile());

            log.info("[NacosConfigBridge] 配置已写入文件: {}", targetPath);
        } catch (IOException e) {
            log.error("[NacosConfigBridge] 写文件失败: {}", targetPath, e);
            // 尝试用备份还原
            if (Files.exists(backupPath)) {
                try {
                    Files.move(backupPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("[NacosConfigBridge] 已用备份还原: {}", targetPath);
                } catch (IOException restoreEx) {
                    log.error("[NacosConfigBridge] 备份还原也失败: {}", targetPath, restoreEx);
                }
            } else if (movedToTarget) {
                try {
                    Files.deleteIfExists(targetPath);
                    log.info("[NacosConfigBridge] 已删除校验失败的新配置文件: {}", targetPath);
                } catch (IOException deleteEx) {
                    log.error("[NacosConfigBridge] 删除校验失败的新配置文件也失败: {}", targetPath, deleteEx);
                }
            }
        } finally {
            // 清理临时文件
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class Subscription {
        private String dataId;
        private String group;
        private String targetFile;

        public String getDataId() {
            return dataId;
        }

        public void setDataId(String dataId) {
            this.dataId = dataId;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getTargetFile() {
            return targetFile;
        }

        public void setTargetFile(String targetFile) {
            this.targetFile = targetFile;
        }
    }
}
