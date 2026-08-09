package world.willfrog.agent.platform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * D04 统一存储路径门面（central storage-path facade，W5 task #105）。
 *
 * <p>单 writer：ccqwen（D04 shared-file 冻结，thread #af-v1p1:42cb47fb msg 321e6c19）。
 * 本类是 workspace / artifact / dataset / observability-debug 四个本地存储根的
 * 唯一配置解析点；consumer 一律经本门面读取，不再各自持有 {@code @Value} 根键。
 *
 * <h3>解析规则（每个根独立）</h3>
 * <ol>
 *   <li>新中心键 {@code agent.storage.*}（显式设置且非空白时生效）；</li>
 *   <li>旧键别名（一期兼容，见下）；</li>
 *   <li>代码默认值（与迁移前行为完全一致）。</li>
 * </ol>
 * 空白串视为未设置（yml 的 {@code ${ENV:}} 占位在 env 未设时解析为空串）。
 * 解析在构造期一次冻结：路径键无任何 Nacos 热更通道，根迁移只能走重启级发布，
 * 故 create-time freeze 即权威值。
 *
 * <h3>旧键别名对照</h3>
 * <ul>
 *   <li>{@link #KEY_WORKSPACE_ROOT} ← {@code agent.workspace.root}（原 WorkspacePathResolver 键）</li>
 *   <li>{@link #KEY_ARTIFACT_ROOT} ← {@code agent.persistent-artifact.root}（原 PersistentArtifactRegistry 键）与
 *       {@link #LEGACY_ARTIFACT_ROOT_SECONDARY}（原 AgentArtifactService 键，D22-5.1.3 第二别名；
 *       双旧键取值冲突且新键未设时启动 fail-closed，见 {@link #resolveArtifactRoot(Environment)}）</li>
 *   <li>{@link #KEY_DATASET_ROOT} ← {@code agent.tools.market-data.dataset.path}（现存 dataset 根键；
 *       WorkspaceManifestWriter 原为硬编码前缀 {@code /data/agent_datasets/}，无旧键）</li>
 *   <li>{@link #KEY_OBSERVABILITY_DEBUG_FILE} ← {@code agent.observability.debug-file.path}</li>
 * </ul>
 * 运维映射表与迁移注意：{@code agent-working-docs/d04-storage-key-mapping-v1.md}。
 *
 * <h3>可达性失败信号（§4.3）</h3>
 * <p>{@link #requireWritableRoot(Path, String)} 与 {@link #verifyDumpTarget(Path)}
 * 把「根不可达 / 目标越出配置根」转成显式异常（{@link StorageRootUnavailableException}
 * / {@link SecurityException}），不静默写错位置。
 *
 * <h3>非目标</h3>
 * <ul>
 *   <li>不接管 {@code agent.llm.prompt-base-dir}（D01 权威）；</li>
 *   <li>不实现对象存储适配器 / 跨机共享本体（D04 §4.4）；</li>
 *   <li>K3 {@code agent.artifact.storage.path} 自 D22-5.1.3 起已收敛为 artifactRoot 的第二
 *       legacy alias（{@link #LEGACY_ARTIFACT_ROOT_SECONDARY}）：新键未设而双旧键取值不同
 *       时启动 fail-closed（抛 {@link StorageRootUnavailableException}，消息只列键名不列值）。</li>
 * </ul>
 *
 * @author ccqwen
 */
@Component
public class AgentStoragePaths {

    private static final Logger log = LoggerFactory.getLogger(AgentStoragePaths.class);

    /** 新中心键（agent.storage 命名空间）。 */
    public static final String KEY_WORKSPACE_ROOT = "agent.storage.workspace-root";
    public static final String KEY_ARTIFACT_ROOT = "agent.storage.artifact-root";
    public static final String KEY_DATASET_ROOT = "agent.storage.dataset-root";
    public static final String KEY_OBSERVABILITY_DEBUG_FILE = "agent.storage.observability-debug-file";

    /** 旧键别名（一期兼容；新键未设时回退）。 */
    public static final String LEGACY_WORKSPACE_ROOT = "agent.workspace.root";
    public static final String LEGACY_ARTIFACT_ROOT = "agent.persistent-artifact.root";
    /**
     * artifactRoot 的第二 legacy alias（D22-5.1.3 K3 收敛）：原 AgentArtifactService 键。
     *
     * <p>仅在新键 {@link #KEY_ARTIFACT_ROOT} 未设置时参与回退；与
     * {@link #LEGACY_ARTIFACT_ROOT} 同时设置且取值不同时，启动期 fail-closed：
     * 抛 {@link StorageRootUnavailableException}，异常消息只列两个键名、绝不包含取值
     * （防止路径值泄漏进日志 / 异常）。
     */
    public static final String LEGACY_ARTIFACT_ROOT_SECONDARY = "agent.artifact.storage.path";
    public static final String LEGACY_DATASET_ROOT = "agent.tools.market-data.dataset.path";
    public static final String LEGACY_OBSERVABILITY_DEBUG_FILE = "agent.observability.debug-file.path";

    /** 默认值 = 迁移前各 consumer 的既有默认，保证零配置行为不变。 */
    public static final String DEFAULT_WORKSPACE_ROOT = "/data/agent_workspaces";
    public static final String DEFAULT_ARTIFACT_ROOT = "/data/agent_artifacts";
    public static final String DEFAULT_DATASET_ROOT = "/data/agent_datasets";
    public static final String DEFAULT_OBSERVABILITY_DEBUG_FILE = "/data/logs/agent-observability-debug.log";

    private final Path workspaceRoot;
    private final Path artifactRoot;
    private final Path datasetRoot;
    private final Path observabilityDebugFile;

    /**
     * Spring 入口：新键 → 旧键别名 → 默认值。
     *
     * <p>artifactRoot 独用双旧键解析链（D22-5.1.3 K3 收敛，冲突 fail-closed，
     * 见 {@link #resolveArtifactRoot(Environment)}）；其余三根保持单旧键行为不变。
     * 显式值构造器不经本解析链，不受冲突检查影响。
     */
    @Autowired
    public AgentStoragePaths(Environment environment) {
        this(
                resolveRoot(environment, KEY_WORKSPACE_ROOT, LEGACY_WORKSPACE_ROOT, DEFAULT_WORKSPACE_ROOT),
                resolveArtifactRoot(environment),
                resolveRoot(environment, KEY_DATASET_ROOT, LEGACY_DATASET_ROOT, DEFAULT_DATASET_ROOT),
                resolveRoot(environment, KEY_OBSERVABILITY_DEBUG_FILE, LEGACY_OBSERVABILITY_DEBUG_FILE,
                        DEFAULT_OBSERVABILITY_DEBUG_FILE)
        );
    }

    /**
     * 显式值入口（测试 / 非 Spring 装配）：四个根直接给定，均按绝对路径归一化。
     */
    public AgentStoragePaths(String workspaceRoot,
                             String artifactRoot,
                             String datasetRoot,
                             String observabilityDebugFile) {
        this.workspaceRoot = normalize(workspaceRoot, KEY_WORKSPACE_ROOT);
        this.artifactRoot = normalize(artifactRoot, KEY_ARTIFACT_ROOT);
        this.datasetRoot = normalize(datasetRoot, KEY_DATASET_ROOT);
        this.observabilityDebugFile = normalize(observabilityDebugFile, KEY_OBSERVABILITY_DEBUG_FILE);
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path artifactRoot() {
        return artifactRoot;
    }

    public Path datasetRoot() {
        return datasetRoot;
    }

    public Path observabilityDebugFile() {
        return observabilityDebugFile;
    }

    /**
     * 校验存储根可达且可写：存在则必须是可写目录；不存在则尝试创建。
     *
     * <p>失败时抛 {@link StorageRootUnavailableException}（含键名 + 绝对路径 + 原因）
     * 并记 ERROR 日志——这是 D04 §4.3 要求的明确失败信号，禁止静默降级。
     *
     * @param root     待校验的根目录（通常取自本门面 getter）
     * @param keyLabel 配置键名（用于错误信息与日志）
     * @return 校验通过的根目录
     */
    public Path requireWritableRoot(Path root, String keyLabel) {
        if (root == null) {
            throw new StorageRootUnavailableException(
                    "storage root is null for key " + keyLabel);
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            if (!Files.isDirectory(normalized)) {
                throw fail(root, keyLabel, "path exists but is not a directory", null);
            }
            if (!Files.isWritable(normalized)) {
                throw fail(root, keyLabel, "directory exists but is not writable", null);
            }
            return normalized;
        }
        try {
            Files.createDirectories(normalized);
        } catch (IOException e) {
            throw fail(root, keyLabel, "root missing and createDirectories failed "
                    + "(mount absent or permission denied)", e);
        }
        if (!Files.isWritable(normalized)) {
            throw fail(root, keyLabel, "root created but is not writable", null);
        }
        return normalized;
    }

    /**
     * dump 目标可达性 + 归属校验（§4.3 在 dump 入口的落点）。
     *
     * <p>两步：① workspace 根本身可达可写（挂载缺失会在这里显式失败，
     * 而不是静默落进容器本地目录）；② 目标目录必须位于配置的 workspace 根内，
     * 越界视为「写错位置」直接拒绝。
     *
     * @param dumpTarget 计划写入的 run 目录
     * @return 归一化后的目标目录
     */
    public Path verifyDumpTarget(Path dumpTarget) {
        if (dumpTarget == null) {
            throw new IllegalArgumentException("dumpTarget 不能为空");
        }
        requireWritableRoot(workspaceRoot, KEY_WORKSPACE_ROOT);
        Path normalized = dumpTarget.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new SecurityException("dump target escapes configured workspace root: target="
                    + normalized + ", " + KEY_WORKSPACE_ROOT + "=" + workspaceRoot);
        }
        return normalized;
    }

    private static String resolveRoot(Environment environment, String key, String legacyKey, String defaultValue) {
        String value = environment == null ? null : environment.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        String legacy = environment == null ? null : environment.getProperty(legacyKey);
        if (legacy != null && !legacy.isBlank()) {
            return legacy;
        }
        return defaultValue;
    }

    /**
     * artifactRoot 专用解析链（D22-5.1.3 K3 收敛）：新键 → 双旧键别名 → 默认值。
     *
     * <ol>
     *   <li>新键 {@link #KEY_ARTIFACT_ROOT} 非空白 → 直接短路返回，不读取也不比较任何旧键；</li>
     *   <li>否则读取两条旧键（{@link #LEGACY_ARTIFACT_ROOT} / {@link #LEGACY_ARTIFACT_ROOT_SECONDARY}）：
     *       均已设置且取值不同 → 启动期 fail-closed，抛 {@link StorageRootUnavailableException}
     *       （消息只列两个键名，绝不包含取值，防止路径值泄漏进日志 / 异常）；
     *       否则取任一非空白值（两者相等时即该值）；</li>
     *   <li>均未设置 → {@link #DEFAULT_ARTIFACT_ROOT}。</li>
     * </ol>
     */
    private static String resolveArtifactRoot(Environment environment) {
        String value = environment == null ? null : environment.getProperty(KEY_ARTIFACT_ROOT);
        if (value != null && !value.isBlank()) {
            return value;
        }
        String legacy1 = environment == null ? null : environment.getProperty(LEGACY_ARTIFACT_ROOT);
        String legacy2 = environment == null ? null : environment.getProperty(LEGACY_ARTIFACT_ROOT_SECONDARY);
        boolean hasLegacy1 = legacy1 != null && !legacy1.isBlank();
        boolean hasLegacy2 = legacy2 != null && !legacy2.isBlank();
        if (hasLegacy1 && hasLegacy2 && !legacy1.equals(legacy2)) {
            String message = "storage root unavailable: conflicting legacy aliases for artifact root, "
                    + LEGACY_ARTIFACT_ROOT + " and " + LEGACY_ARTIFACT_ROOT_SECONDARY
                    + " are both set with different values; unset one of them or set the canonical key "
                    + KEY_ARTIFACT_ROOT + " (configured values intentionally not disclosed)";
            log.error(message);
            throw new StorageRootUnavailableException(message);
        }
        if (hasLegacy1) {
            return legacy1;
        }
        if (hasLegacy2) {
            return legacy2;
        }
        return DEFAULT_ARTIFACT_ROOT;
    }

    private static Path normalize(String value, String keyLabel) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("storage path value is blank for key " + keyLabel);
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private static StorageRootUnavailableException fail(Path root, String keyLabel, String reason, Throwable cause) {
        Path normalized = root == null ? null : root.toAbsolutePath().normalize();
        String message = "storage root unavailable: key=" + keyLabel + ", path=" + normalized
                + ", reason=" + reason;
        log.error(message, cause);
        return cause == null
                ? new StorageRootUnavailableException(message)
                : new StorageRootUnavailableException(message, cause);
    }
}
