package world.willfrog.agent.platform.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 持久制品唯一权威注册表（D22-5.1.3 起）。
 *
 * <p>职责：artifact 的注册、元数据（Redis）、文件落盘、run 级有界索引、TTL 清理与
 * 路径哈希校验。{@link world.willfrog.agent.platform.service.AgentArtifactService}
 * 降级为 user API 门面 + 历史兼容适配器后，所有制品（rawRef / external / 脚本 / 数据集）
 * 的注册与 list/load 权威均在本网关。</p>
 *
 * <h3>D22-5.1.3：显式上下文入口</h3>
 * <ul>
 *   <li>{@link #registerExplicit} / {@link #registerExternalExplicit}：非幂等，runId/userId
 *       显式传入，不依赖 {@link AgentContext} 线程态；每次调用生成新 artifactId。</li>
 *   <li>{@link #registerIdempotent} / {@link #registerExternalIdempotent}：幂等，稳定身份
 *       (runId + collision-free 编码的 type/logicalId[/path]) 经单条 Redis Lua 脚本原子抢占；
 *       重复注册（重复 list、重启后 list、admin/normal 双 list）返回同一 artifactId，
 *       零重写、零重复项。</li>
 *   <li>{@link #listByRunId}：run 级有界索引（SET），只读，不生成新 ID。</li>
 * </ul>
 *
 * <h3>幂等抢占协议（单一赢家不变量）</h3>
 * <p>候选 file + meta 先备好，再经单条 Lua 脚本（{@link #ATOMIC_CLAIM_SCRIPT}）原子提交：
 * 脚本内依次做「查身份是否已有赢家 → 有界清理幽灵成员 → SCARD 容量检查 → HSET 身份 +
 * SADD run 列表」，五步要么全部生效、要么全部不生效。因此：输家只有在脚本返回
 * EXISTS 时才采纳赢家，而 EXISTS 意味着赢家的身份项与列表项已在同一次脚本执行中
 * 原子落盘——输家不可能在赢家列表提交前拿到 ID，也不可能拿到幽灵 ID；容量不足时
 * 脚本返回 FULL 且不写任何索引，Java 侧回滚候选（meta + 文件）并外抛可见失败。
 * 若查无赢家 meta（清理竞态恰好删掉），输家用 Lua 值条件 HDEL 原子清陈旧字段后
 * 以新候选重试（有界 {@value #MAX_CLAIM_ATTEMPTS} 次，仍不结算则显式失败）。
 * 同一身份任意时刻至多一份 meta / 一个文件 / 一条 run 索引项。</p>
 *
 * <h3>run 级有界索引（硬上限 + 幽灵自愈）</h3>
 * <p>认领（幂等路径）与加入（非幂等路径）都是单条 Lua 脚本内的原子操作：脚本先做
 * 有界幽灵清理（每次最多检查 {@value #GHOST_PURGE_BUDGET} 个成员，meta 键已不存在
 * 的成员当场移除），再 SCARD 容量检查，未满才写入——不存在多命令
 * SADD→SCARD→SREM 的检查-加入窗口，索引绝不超 cap。幽灵成员（meta 因 TTL 到期
 * 消失而 SET 成员残留）不会永久占用容量配额，{@link #listByRunId} 读取时也会顺手
 * 移除遇到的幽灵。注册失败可见，禁止 silent meta-only 成功。cap<=0 视为配置错误，
 * fail-closed。</p>
 *
 * <h3>Redis 结构</h3>
 * <ul>
 *   <li>{@code agent:persistent-artifact:{artifactId}} — meta JSON，TTL = ttlHours；</li>
 *   <li>{@code agent:persistent-artifact:run-list:{runId}} — SET，run 的 artifactId 索引，
 *       硬上限 {@code agent.persistent-artifact.run-list-cap}（默认 1000）；</li>
 *   <li>{@code agent:persistent-artifact:run-identity:{runId}} — hash，幂等身份
 *       field={@link #identityField} collision-free 编码 → artifactId。</li>
 * </ul>
 * 注意：后两类键与 meta 共享 {@link #META_PREFIX} 前缀，cleanup 的 SCAN 会命中它们，
 * 循环内按前缀显式跳过（它们不是 meta JSON）。
 *
 * <h3>归属校验</h3>
 * <p>所有用户/工具可达的读取与定位入口一律走 {@link #matchesOwnerStrict}（meta 与调用方
 * 的 runId/userId 四值全部非空且相等，任一空值 fail-closed）——无论旧 AgentContext
 * 入口还是显式上下文入口，不存在宽容 seam（matchesOwnerLenient 已删除）。</p>
 *
 * <h3>读取入口（TOCTOU 强化 + 有界流式读取）</h3>
 * <p>{@link #readArtifactBytes} / {@link #readWithinArtifactRoot} / {@link #readContent} /
 * {@link #readLocator}：读取前 realpath containment 复检（中间目录的 symlink 也会被解析，
 * 父目录被换成指向根外的链接同样拒绝）+ no-follow 打开 + 哈希校验（内容制品）。
 * 大小上限由两层构成：Files.size 快速失败预检查 + 权威有界流式读取
 * （{@link #readBounded}：至多读 maxBytes+1 字节，读到多余字节即拒）——即使文件在
 * 预检查与实读之间增大，内存最多分配 maxBytes+1 字节。注册后 symlink 换入 /
 * 内容替换在读取时 fail-closed。</p>
 *
 * <h3>D22-5.1.3：external 路径门槛</h3>
 * <p>external 制品只允许落在 D04 批准根内（artifactRoot 或 datasetRoot），
 * 规范化后做 containment 校验；已存在的路径额外解析真实路径，拒绝 symlink 逃逸。</p>
 *
 * <h3>兼容语义</h3>
 * <p>旧 {@link #register}/{@link #registerExternal} 入口保留为有界兼容 delegate：
 * runId/userId 从 {@link AgentContext} 线程态补齐后转调显式入口。清理（cleanup）
 * 在删除 meta + 文件的同时移除 run 索引与幂等身份字段（同删，不留悬挂引用；
 * 身份字段经值条件 HDEL 原子清除，不误伤并发新抢占）。</p>
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersistentArtifactRegistry {

    private static final String META_PREFIX = "agent:persistent-artifact:";

    /** run 级 artifactId 索引（SET）。与 meta 共享前缀，cleanup SCAN 时按前缀跳过。 */
    private static final String RUN_LIST_KEY_PREFIX = META_PREFIX + "run-list:";

    /** 幂等身份索引（hash：field 为 collision-free 长度前缀编码 → artifactId）。同上，SCAN 跳过。 */
    private static final String RUN_IDENTITY_KEY_PREFIX = META_PREFIX + "run-identity:";

    /** 幂等抢占最大尝试次数：遇到陈旧身份（赢家 meta 已被清理）值条件清除后有界重试，仍不结算则显式失败。 */
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    /**
     * 原子值条件 HDEL（Lua）：仅当 field 值仍等于期望 artifactId 时删除。
     * 清理与幂等抢占双方都用它清身份字段，杜绝 get-then-delete 窗口误删并发新抢占。
     */
    private static final RedisScript<Long> CONDITIONAL_HDEL_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('hget', KEYS[1], ARGV[1]) == ARGV[2] then "
                    + "return redis.call('hdel', KEYS[1], ARGV[1]) else return 0 end",
            Long.class);

    /** 幽灵自愈的有界预算：每次索引写入前最多检查多少个"缺 meta 的 SET 成员"并移除。 */
    private static final int GHOST_PURGE_BUDGET = 128;

    /**
     * 幂等认领原子提交脚本（Lua，单条脚本内要么全做、要么全不做）。
     *
     * <p>KEYS[1]=身份 hash 键，KEYS[2]=run 列表 SET 键；
     * ARGV[1]=身份 field，ARGV[2]=候选 artifactId，ARGV[3]=容量上限，
     * ARGV[4]=幽灵清理预算，ARGV[5]=meta 键前缀。步骤：
     * ①身份已有赢家 → 直接返回 EXISTS:赢家ID（不写任何东西）；
     * ②有界幽灵清理：最多检查 ARGV[4] 个 SET 成员，meta 键不存在者当场 SREM；
     * ③SCARD 容量检查：已满 → 返回 FULL（不写任何东西）；
     * ④HSET 身份 + SADD run 列表，返回 CLAIMED。</p>
     *
     * <p>由此得到的不变量：输家观察到 EXISTS 时，赢家的身份项与列表项必然已在同一次
     * 脚本执行中原子落盘（输家不可能提前返回、不可能拿到幽灵 ID）；FULL 路径从不写
     * 索引（容量失败的注册不留任何痕迹）。</p>
     */
    private static final RedisScript<String> ATOMIC_CLAIM_SCRIPT = new DefaultRedisScript<>(
            "local existing = redis.call('hget', KEYS[1], ARGV[1]) "
                    + "if existing then return 'EXISTS:' .. existing end "
                    + "local budget = tonumber(ARGV[4]) "
                    + "if budget > 0 then "
                    + "  local members = redis.call('smembers', KEYS[2]) "
                    + "  local checked = 0 "
                    + "  for _, m in ipairs(members) do "
                    + "    if checked >= budget then break end "
                    + "    checked = checked + 1 "
                    + "    if redis.call('exists', ARGV[5] .. m) == 0 then "
                    + "      redis.call('srem', KEYS[2], m) "
                    + "    end "
                    + "  end "
                    + "end "
                    + "if redis.call('scard', KEYS[2]) >= tonumber(ARGV[3]) then return 'FULL' end "
                    + "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]) "
                    + "redis.call('sadd', KEYS[2], ARGV[2]) "
                    + "return 'CLAIMED'",
            String.class);

    /**
     * 非幂等 run 列表加入脚本（Lua，原子：幽灵清理 → 容量检查 → SADD）。
     *
     * <p>KEYS[1]=run 列表 SET 键；ARGV[1]=容量上限，ARGV[2]=幽灵清理预算，
     * ARGV[3]=meta 键前缀，ARGV[4]=artifactId。已满返回 FULL（不写），否则写入返回 ADDED。
     * 非幂等路径的 artifactId 每次全新生成，不存在"已是成员"情形。</p>
     */
    private static final RedisScript<String> RUN_LIST_ADD_SCRIPT = new DefaultRedisScript<>(
            "local budget = tonumber(ARGV[2]) "
                    + "if budget > 0 then "
                    + "  local members = redis.call('smembers', KEYS[1]) "
                    + "  local checked = 0 "
                    + "  for _, m in ipairs(members) do "
                    + "    if checked >= budget then break end "
                    + "    checked = checked + 1 "
                    + "    if redis.call('exists', ARGV[3] .. m) == 0 then "
                    + "      redis.call('srem', KEYS[1], m) "
                    + "    end "
                    + "  end "
                    + "end "
                    + "if redis.call('scard', KEYS[1]) >= tonumber(ARGV[1]) then return 'FULL' end "
                    + "redis.call('sadd', KEYS[1], ARGV[4]) "
                    + "return 'ADDED'",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * D04：artifact 根经统一存储门面解析（新键 agent.storage.artifact-root，
     * 旧键别名 agent.persistent-artifact.root，默认 /data/agent_artifacts）。
     */
    private final AgentStoragePaths storagePaths;

    @Value("${agent.persistent-artifact.ttl-hours:12}")
    private long defaultTtlHours;

    @Value("${agent.persistent-artifact.cleanup-scan-count:500}")
    private int cleanupScanCount;

    /** run 级索引硬上限：超限的注册原子拒绝并回滚（可见失败，禁止 silent meta-only 成功）；cap<=0 fail-closed。 */
    @Value("${agent.persistent-artifact.run-list-cap:1000}")
    private int maxRunListEntries;

    // ===== 兼容入口（有界 delegate：AgentContext 补上下文后转显式入口） =====

    public PersistentArtifactRegistration register(String artifactType,
                                                   String logicalId,
                                                   String displayName,
                                                   String content) {
        return registerExplicit(AgentContext.getRunId(), AgentContext.getUserId(),
                artifactType, logicalId, displayName, content, defaultTtlHours);
    }

    public PersistentArtifactRegistration register(String artifactType,
                                                   String logicalId,
                                                   String displayName,
                                                   String content,
                                                   long ttlHours) {
        return registerExplicit(AgentContext.getRunId(), AgentContext.getUserId(),
                artifactType, logicalId, displayName, content, ttlHours);
    }

    public PersistentArtifactRegistration registerExternal(String artifactType,
                                                           String logicalId,
                                                           String displayName,
                                                           Path path,
                                                           long ttlHours) {
        return registerExternalExplicit(AgentContext.getRunId(), AgentContext.getUserId(),
                artifactType, logicalId, displayName, path, ttlHours, false);
    }

    public PersistentArtifactRegistration registerExternal(String artifactType,
                                                           String logicalId,
                                                           String displayName,
                                                           Path path,
                                                           long ttlHours,
                                                           boolean cleanupPath) {
        return registerExternalExplicit(AgentContext.getRunId(), AgentContext.getUserId(),
                artifactType, logicalId, displayName, path, ttlHours, cleanupPath);
    }

    // ===== D22-5.1.3：显式上下文入口 =====

    /**
     * 非幂等注册（显式上下文）：每次调用生成新 artifactId。
     *
     * <p>适用于每次调用本就产生新制品的桥接方（如 RunRawRefStore 的逐条 rawRef——
     * 其 logicalId 固定为 runId，绝不能走幂等路径，否则同 run 第二条即撞 ID）。</p>
     *
     * @param runId  显式 run 上下文（可为空：历史兼容语义，meta.runId 落 null，不进 run 索引）
     * @param userId 显式 user 上下文（可为空，同上）
     * @return 注册结果
     */
    public PersistentArtifactRegistration registerExplicit(String runId,
                                                             String userId,
                                                             String artifactType,
                                                             String logicalId,
                                                             String displayName,
                                                             String content,
                                                             long ttlHours) {
        return doRegisterContent(runId, userId, artifactType, logicalId, displayName, content, ttlHours, false);
    }

    /**
     * 幂等注册（显式上下文）：稳定身份 (runId|type|logicalId)，重复注册返回同一 artifactId。
     *
     * <p>经单条 Lua 脚本（{@link #ATOMIC_CLAIM_SCRIPT}）原子抢占身份字段并同步写入
     * run 列表：赢家写文件 + meta + 索引；输家直接返回赢家结果，零重写。事件派生制品的
     * lazy external registration 用 {@link #registerExternalIdempotent}。</p>
     *
     * @param runId 不得为空（幂等身份的组成部分）
     * @return 注册结果（重复注册时 meta 为既有制品）
     */
    public PersistentArtifactRegistration registerIdempotent(String runId,
                                                             String userId,
                                                             String artifactType,
                                                             String logicalId,
                                                             String displayName,
                                                             String content,
                                                             long ttlHours) {
        return doRegisterContent(runId, userId, artifactType, logicalId, displayName, content, ttlHours, true);
    }

    /**
     * 非幂等 external 注册（显式上下文）。path 必须位于 D04 批准根内。
     *
     * @param cleanupPath true 时清理阶段允许删除该路径（仅限 symlink，见 cleanup 逻辑）
     */
    public PersistentArtifactRegistration registerExternalExplicit(String runId,
                                                                   String userId,
                                                                   String artifactType,
                                                                   String logicalId,
                                                                   String displayName,
                                                                   Path path,
                                                                   long ttlHours,
                                                                   boolean cleanupPath) {
        return doRegisterExternal(runId, userId, artifactType, logicalId, displayName,
                path, ttlHours, cleanupPath, false);
    }

    /**
     * 幂等 external 注册（显式上下文）：稳定身份 (runId|type|logicalId|path)。
     *
     * <p>事件派生旧制品（脚本/数据集文件）的 lazy registration 走这里：不复制文件、
     * 不双写两棵树、重复 list / 重启后 list / 并发 list 均不产生第二 artifactId。
     * 幂等 external 固定 cleanupPath=false——引用制品的清理只删 meta 与索引，不动底层文件。</p>
     *
     * @param runId 不得为空（幂等身份的组成部分）
     */
    public PersistentArtifactRegistration registerExternalIdempotent(String runId,
                                                                     String userId,
                                                                     String artifactType,
                                                                     String logicalId,
                                                                     String displayName,
                                                                     Path path,
                                                                     long ttlHours) {
        return doRegisterExternal(runId, userId, artifactType, logicalId, displayName,
                path, ttlHours, false, true);
    }

    // ===== 读取 / 列表 =====

    /**
     * run 级制品列表：读 run 索引 SET → 逐条取 meta → 过滤已过期/缺失项。
     *
     * <p>只读，不生成新 artifactId；重复调用结果一致（meta 缺失项自动滤掉）。
     * 返回按创建时间升序、artifactId 次序的列表。</p>
     *
     * <p>幽灵自愈（读取侧）：meta 键已不存在的 SET 成员（幽灵，典型成因是 meta 的
     * Redis TTL 先到期）在遍历时顺手 SREM 移除，避免其永久占用容量配额、让 SCARD
     * 虚高导致后续注册持续被误判超限。</p>
     */
    public List<PersistentArtifactMeta> listByRunId(String runId) {
        if (!hasText(runId)) {
            return List.of();
        }
        String listKey = runListKey(runId);
        Set<String> artifactIds = redisTemplate.opsForSet().members(listKey);
        if (artifactIds == null || artifactIds.isEmpty()) {
            return List.of();
        }
        List<PersistentArtifactMeta> metas = new ArrayList<>(artifactIds.size());
        for (String artifactId : artifactIds) {
            Optional<PersistentArtifactMeta> meta = find(artifactId);
            if (meta.isEmpty()) {
                // 幽灵成员：meta 已过期/缺失，读取侧顺手移除（写入侧另有有界清理）
                try {
                    redisTemplate.opsForSet().remove(listKey, artifactId);
                } catch (Exception e) {
                    log.warn("Failed to remove ghost run index entry: runId={} artifactId={} err={}",
                            runId, artifactId, e.getMessage());
                }
                continue;
            }
            // 防御陈旧索引项：meta 的 runId 必须与请求 run 一致
            if (runId.equals(meta.get().getRunId())) {
                metas.add(meta.get());
            }
        }
        metas.sort(Comparator
                .comparing((PersistentArtifactMeta m) -> m.getCreatedAtMillis() == null ? 0L : m.getCreatedAtMillis())
                .thenComparing(m -> m.getArtifactId() == null ? "" : m.getArtifactId()));
        return metas;
    }

    /**
     * 严格归属校验（唯一归属校验，不存在宽容 seam）：meta 与调用方的 runId/userId
     * 四值全部非空且相等。
     *
     * <p>所有用户/工具可达的读取与定位路径——无论旧 AgentContext 入口还是显式上下文
     * 入口——一律走这里：任一侧空值一律拒绝（fail-closed），不允许空值放行。
     * 历史无上下文制品（meta 缺 runId/userId）经任何入口都拒绝读取。</p>
     */
    public static boolean matchesOwnerStrict(PersistentArtifactMeta meta, String runId, String userId) {
        if (meta == null) {
            return false;
        }
        return hasText(runId) && hasText(userId)
                && hasText(meta.getRunId()) && hasText(meta.getUserId())
                && meta.getRunId().equals(runId)
                && meta.getUserId().equals(userId);
    }

    public Optional<PersistentArtifactMeta> find(String artifactId) {
        if (!hasText(artifactId)) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(key(artifactId));
        if (!hasText(json)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, PersistentArtifactMeta.class));
        } catch (Exception e) {
            log.warn("Failed to parse artifact meta {}", artifactId, e);
            return Optional.empty();
        }
    }

    public RawPayloadLocator locatorFor(String artifactId) {
        PersistentArtifactMeta meta = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        touch(meta);
        return RawPayloadLocator.builder()
                .path(meta.getPath())
                .contentHash(meta.getContentHash())
                .build();
    }

    public String readContent(String artifactId) {
        PersistentArtifactMeta meta = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        if (Boolean.TRUE.equals(meta.getExternal())) {
            throw new IllegalArgumentException("External artifact has no registry-owned content: " + artifactId);
        }
        if (!hasText(meta.getPath())) {
            throw new IllegalArgumentException("Artifact path missing: " + artifactId);
        }
        touch(meta);
        Path real = verifyReadablePath(Path.of(meta.getPath()), false);
        return new String(readBytesChecked(real, meta.getContentHash(), -1L), StandardCharsets.UTF_8);
    }

    public String readLocator(RawPayloadLocator locator) {
        if (locator == null || !hasText(locator.getPath())) {
            throw new IllegalArgumentException("Raw payload locator path is required");
        }
        Path real = verifyReadablePath(Path.of(locator.getPath()), true);
        return new String(readBytesChecked(real, locator.getContentHash(), -1L), StandardCharsets.UTF_8);
    }

    /**
     * 权威字节读取入口（TOCTOU 强化）：读前 realpath containment 复检 + no-follow 打开
     * + 哈希校验（内容制品）+ touch。external 制品也走这里（user 门面下载不再直读 meta.path）。
     *
     * @param maxBytes 读取字节上限，超出抛 {@code IllegalStateException("artifact too large to download")}；
     *                 <=0 表示不限制
     */
    public byte[] readArtifactBytes(String artifactId, long maxBytes) {
        PersistentArtifactMeta meta = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        if (!hasText(meta.getPath())) {
            throw new IllegalArgumentException("Artifact path missing: " + artifactId);
        }
        touch(meta);
        Path real = verifyReadablePath(Path.of(meta.getPath()), Boolean.TRUE.equals(meta.getExternal()));
        return readBytesChecked(real, meta.getContentHash(), maxBytes);
    }

    /**
     * legacy 快照读取入口（Base64 只读回退用）：路径只允许位于 artifactRoot 内，
     * 同样走 TOCTOU 强化读取；无 meta，不 touch、不做哈希校验。
     */
    public byte[] readWithinArtifactRoot(Path path, long maxBytes) {
        if (path == null) {
            throw new IllegalArgumentException("Artifact path is required");
        }
        Path real = verifyReadablePath(path, false);
        return readBytesChecked(real, null, maxBytes);
    }

    @Scheduled(initialDelayString = "${agent.persistent-artifact.cleanup-initial-delay-ms:300000}",
            fixedDelayString = "${agent.persistent-artifact.cleanup-delay-ms:300000}")
    public void cleanupExpiredArtifacts() {
        long now = System.currentTimeMillis();
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(META_PREFIX + "*")
                .count(Math.max(1, cleanupScanCount))
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                // run 索引 / 幂等身份键与 meta 共享前缀，不是 meta JSON，显式跳过。
                if (key.startsWith(RUN_LIST_KEY_PREFIX) || key.startsWith(RUN_IDENTITY_KEY_PREFIX)) {
                    continue;
                }
                String json = redisTemplate.opsForValue().get(key);
                if (!hasText(json)) {
                    continue;
                }
                try {
                    PersistentArtifactMeta meta = objectMapper.readValue(json, PersistentArtifactMeta.class);
                    Long expiresAt = meta.getExpiresAtMillis();
                    if (expiresAt != null && expiresAt <= now) {
                        deleteMetaAndFile(meta);
                    }
                } catch (Exception e) {
                    log.warn("Failed to cleanup artifact meta {}", key, e);
                }
            }
        } catch (Exception e) {
            log.warn("Persistent artifact cleanup failed", e);
        }
    }

    // ===== 内部实现 =====

    private PersistentArtifactRegistration doRegisterContent(String runId,
                                                             String userId,
                                                             String artifactType,
                                                             String logicalId,
                                                             String displayName,
                                                             String content,
                                                             long ttlHours,
                                                             boolean idempotent) {
        String safeType = hasText(artifactType) ? artifactType.trim() : "artifact";
        if (idempotent) {
            requireRunIdForIdentity(runId);
        }
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        Path root = rootPath();
        // D04 §4.3：写入前校验 artifact 根可达（挂载缺失/权限不足 → 显式失败信号）。
        storagePaths.requireWritableRoot(root, AgentStoragePaths.KEY_ARTIFACT_ROOT);
        String field = idempotent ? identityField(safeType, logicalId, null) : null;
        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {
            String artifactId = newArtifactId(safeType);
            Path path = root.resolve(safeType).resolve(artifactId.replace(':', '_') + ".txt").normalize();
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException("Artifact path escapes root");
            }
            try {
                Files.createDirectories(path.getParent());
                Files.write(path, bytes);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write persistent artifact " + artifactId, e);
            }
            PersistentArtifactMeta meta = buildMeta(artifactId, safeType, logicalId, displayName, path, hash,
                    (long) bytes.length, ttlHours, false, true, runId, userId);
            try {
                // 候选 meta 先落盘再原子 claim：身份字段恒指向 meta 已写的制品
                save(meta);
                if (!idempotent) {
                    addToRunList(runId, artifactId, ttlHours);
                    return registration(meta);
                }
                CommitOutcome outcome = commitCandidate(runId, field, meta, ttlHours);
                if (outcome.registration() != null) {
                    return outcome.registration();
                }
                if (!outcome.retry()) {
                    break;
                }
                // 陈旧身份已值条件清除：下一轮以新候选重试
            } catch (RuntimeException e) {
                // 注册失败（索引超限 / Redis 故障等）：回滚候选，禁止 meta-only 残留
                rollbackCandidate(meta);
                throw e;
            }
        }
        throw new IllegalStateException("Idempotent artifact claim not settled after " + MAX_CLAIM_ATTEMPTS
                + " attempts: runId=" + runId + ", identity=" + field);
    }

    private PersistentArtifactRegistration doRegisterExternal(String runId,
                                                              String userId,
                                                              String artifactType,
                                                              String logicalId,
                                                              String displayName,
                                                              Path path,
                                                              long ttlHours,
                                                              boolean cleanupPath,
                                                              boolean idempotent) {
        if (path == null) {
            throw new IllegalArgumentException("External artifact path is required");
        }
        String safeType = hasText(artifactType) ? artifactType.trim() : "artifact";
        Path normalized = path.toAbsolutePath().normalize();
        // D22-5.1.3：external 路径只能落 D04 批准根内（artifactRoot / datasetRoot）。
        verifyExternalPath(normalized);
        if (idempotent) {
            requireRunIdForIdentity(runId);
        }
        String field = idempotent ? identityField(safeType, logicalId, normalized.toString()) : null;
        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {
            String artifactId = newArtifactId(safeType);
            Long size = null;
            try {
                if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    size = Files.size(normalized);
                }
            } catch (IOException e) {
                log.debug("External artifact size unavailable for {}: {}", normalized, e.getMessage());
            }
            PersistentArtifactMeta meta = buildMeta(artifactId, safeType, logicalId, displayName, normalized, null,
                    size, ttlHours, true, cleanupPath, runId, userId);
            try {
                save(meta);
                if (!idempotent) {
                    addToRunList(runId, artifactId, ttlHours);
                    return registration(meta);
                }
                CommitOutcome outcome = commitCandidate(runId, field, meta, ttlHours);
                if (outcome.registration() != null) {
                    return outcome.registration();
                }
                if (!outcome.retry()) {
                    break;
                }
            } catch (RuntimeException e) {
                rollbackCandidate(meta);
                throw e;
            }
        }
        throw new IllegalStateException("Idempotent artifact claim not settled after " + MAX_CLAIM_ATTEMPTS
                + " attempts: runId=" + runId + ", identity=" + field);
    }

    /** 抢占结算结果：registration 非空 = 已结算（候选胜出或采纳赢家）；retry = 陈旧身份已清，以新候选重试。 */
    private record CommitOutcome(PersistentArtifactRegistration registration, boolean retry) {
    }

    /**
     * 候选原子结算（候选 file + meta 必须已备好，{@value #MAX_CLAIM_ATTEMPTS} 次尝试协议）。
     *
     * <p>整个「身份已有赢家？→ 幽灵清理 → 容量检查 → 写身份 + 写 run 列表」由单条
     * Lua 脚本（{@link #ATOMIC_CLAIM_SCRIPT}）一次执行完成，不再有任何多命令窗口：</p>
     * <ul>
     *   <li>CLAIMED → 赢家：meta 已落盘，身份与列表项在同一次脚本执行中原子可见，
     *       之后只做尽力而为的 TTL 延长（失败不阻断）。</li>
     *   <li>FULL → 容量超限：脚本没写任何索引，直接抛可见失败，由调用方 catch 回滚
     *       候选（meta + 文件），禁止 silent meta-only 成功。FULL 路径从不写身份，
     *       因此容量失败的注册不会给任何后来者留下幽灵身份。</li>
     *   <li>EXISTS:赢家ID → 输家：先回滚候选（零残留）再采纳。输家只有在脚本报告
     *       EXISTS 时才可能拿到赢家 ID，而 EXISTS 意味着赢家的身份项与列表项已经
     *       原子落盘——输家不可能在赢家列表提交前返回，也不可能返回幽灵 ID。
     *       赢家 meta 已被清理（身份字段成为陈旧悬挂）时用值条件 HDEL 原子清除并
     *       返回 retry=true——身份恒指向 meta 已落盘制品，清陈旧不伤及任何活制品。</li>
     * </ul>
     */
    private CommitOutcome commitCandidate(String runId, String field, PersistentArtifactMeta candidateMeta,
                                          long ttlHours) {
        String candidateArtifactId = candidateMeta.getArtifactId();
        String result = executeAtomicClaim(runId, field, candidateArtifactId);
        if ("CLAIMED".equals(result)) {
            extendTtlIfNeeded(runIdentityKey(runId), ttlHours);
            extendTtlIfNeeded(runListKey(runId), ttlHours);
            return new CommitOutcome(registration(candidateMeta), false);
        }
        if ("FULL".equals(result)) {
            // 容量超限：脚本未写任何索引；外抛后由调用方 catch 回滚候选（可见失败）
            throw new IllegalStateException(
                    "Run artifact index capacity exceeded: runId=" + runId + " cap=" + maxRunListEntries);
        }
        if (result != null && result.startsWith("EXISTS:")) {
            // 输家：候选零残留，然后采纳赢家（赢家身份+列表已原子落盘，无幽灵窗口）
            rollbackCandidate(candidateMeta);
            String winnerId = result.substring("EXISTS:".length());
            Optional<PersistentArtifactMeta> winnerMeta = find(winnerId);
            if (winnerMeta.isPresent()) {
                return new CommitOutcome(registration(winnerMeta.get()), false);
            }
            // 赢家 meta 已被清理：原子值条件清陈旧字段，调用方以新候选重试
            removeIdentityIfMatches(runId, field, winnerId);
            return new CommitOutcome(null, true);
        }
        throw new IllegalStateException("Unexpected atomic claim result: runId=" + runId + " result=" + result);
    }

    /**
     * 执行幂等认领原子脚本。cap<=0 视为配置错误，在进脚本前 fail-closed。
     * 返回值：CLAIMED / FULL / EXISTS:{赢家ID}。
     */
    private String executeAtomicClaim(String runId, String field, String artifactId) {
        if (maxRunListEntries <= 0) {
            throw new IllegalStateException(
                    "Run artifact index capacity must be positive: cap=" + maxRunListEntries);
        }
        String result = redisTemplate.execute(ATOMIC_CLAIM_SCRIPT,
                List.of(runIdentityKey(runId), runListKey(runId)),
                field, artifactId, String.valueOf(maxRunListEntries),
                String.valueOf(GHOST_PURGE_BUDGET), META_PREFIX);
        return result;
    }

    /**
     * 原子值条件清除身份字段：仅当 field 仍指向 expectedArtifactId 时删除
     * （Lua {@link #CONDITIONAL_HDEL_SCRIPT}），防止 get-then-delete 窗口误删并发新抢占。
     */
    private void removeIdentityIfMatches(String runId, String field, String expectedArtifactId) {
        try {
            redisTemplate.execute(CONDITIONAL_HDEL_SCRIPT, List.of(runIdentityKey(runId)),
                    field, expectedArtifactId);
        } catch (Exception e) {
            log.warn("Failed to clear artifact identity conditionally: runId={} artifactId={} err={}",
                    runId, expectedArtifactId, e.getMessage());
        }
    }

    /**
     * 候选注册回滚：删 meta 与索引痕迹；内容制品删自有文件，external 制品绝不触碰底层路径
     * （注册失败不得删除调用方文件，即使 cleanupPath=true）。
     */
    private void rollbackCandidate(PersistentArtifactMeta meta) {
        if (meta == null || !hasText(meta.getArtifactId())) {
            return;
        }
        try {
            redisTemplate.delete(key(meta.getArtifactId()));
        } catch (Exception e) {
            log.warn("Failed to roll back artifact meta {}: {}", meta.getArtifactId(), e.getMessage());
        }
        removeFromIndices(meta);
        if (Boolean.TRUE.equals(meta.getExternal()) || !hasText(meta.getPath())) {
            return;
        }
        Path path = Path.of(meta.getPath()).toAbsolutePath().normalize();
        if (!path.startsWith(rootPath())) {
            return;
        }
        deletePath(path);
    }

    /**
     * run 级索引有界加入（非幂等路径）：单条 Lua 脚本（{@link #RUN_LIST_ADD_SCRIPT}）
     * 内原子完成「幽灵清理 → SCARD 容量检查 → SADD」——超限时脚本不写任何东西并返回
     * FULL，Java 侧抛可见失败，禁止 silent meta-only 成功；不存在旧实现
     * SADD→SCARD→SREM 的多命令检查-加入窗口。cap<=0 视为配置错误，fail-closed。
     */
    private void addToRunList(String runId, String artifactId, long ttlHours) {
        if (!hasText(runId)) {
            return;
        }
        if (maxRunListEntries <= 0) {
            throw new IllegalStateException(
                    "Run artifact index capacity must be positive: cap=" + maxRunListEntries);
        }
        String listKey = runListKey(runId);
        String result = redisTemplate.execute(RUN_LIST_ADD_SCRIPT, List.of(listKey),
                String.valueOf(maxRunListEntries), String.valueOf(GHOST_PURGE_BUDGET),
                META_PREFIX, artifactId);
        if (!"ADDED".equals(result)) {
            throw new IllegalStateException(
                    "Run artifact index capacity exceeded: runId=" + runId + " cap=" + maxRunListEntries);
        }
        extendTtlIfNeeded(listKey, ttlHours);
    }

    /** 仅在当前 TTL 不足时延长（索引键被同 run 多制品共享，不得用短 TTL 覆盖长 TTL）。 */
    private void extendTtlIfNeeded(String redisKey, long ttlHours) {
        long desired = Math.max(1L, ttlHours);
        try {
            Long current = redisTemplate.getExpire(redisKey, TimeUnit.HOURS);
            if (current == null || current < 0 || current < desired) {
                redisTemplate.expire(redisKey, desired, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            // TTL 维护失败不阻断注册：最坏结果是索引早于 meta 过期，list 侧自愈。
            log.warn("Failed to maintain TTL for {}: {}", redisKey, e.getMessage());
        }
    }

    /**
     * D22-5.1.3：external 路径门槛。
     *
     * <p>规范化路径必须位于 artifactRoot 或 datasetRoot 内（拒绝 traversal / 根逃逸）；
     * 已存在的路径额外解析真实路径（跟随 symlink），真实位置仍必须在批准根内。
     * 真实路径比较时批准根同样解析 symlink（如 macOS /var → /private/var），
     * 避免根自身处于链接路径下时误拒合法文件。</p>
     */
    private void verifyExternalPath(Path normalized) {
        Path artifactRoot = rootPath();
        Path datasetRoot = storagePaths.datasetRoot().toAbsolutePath().normalize();
        if (!normalized.startsWith(artifactRoot) && !normalized.startsWith(datasetRoot)) {
            throw new SecurityException("External artifact path outside approved storage roots: " + normalized);
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path real = normalized.toRealPath();
                Path realArtifactRoot = toRealPathIfPossible(artifactRoot);
                Path realDatasetRoot = toRealPathIfPossible(datasetRoot);
                if (!real.startsWith(realArtifactRoot) && !real.startsWith(realDatasetRoot)) {
                    throw new SecurityException(
                            "External artifact path resolves outside approved storage roots: " + normalized);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to resolve external artifact path: " + normalized, e);
            }
        }
    }

    /** 根目录存在时解析真实路径（跟随 symlink），否则原样返回；失败降级为原路径。 */
    private static Path toRealPathIfPossible(Path root) {
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return root.toRealPath();
            }
        } catch (IOException ignored) {
            // 解析失败时按规范化路径比较，保持保守不放宽。
        }
        return root;
    }

    private static void requireRunIdForIdentity(String runId) {
        if (!hasText(runId)) {
            throw new IllegalArgumentException("runId is required for idempotent artifact registration");
        }
    }

    /**
     * 幂等身份字段（collision-free 编码，registry 与 user 门面共用唯一实现）：
     * 每个段编码为 {@code 长度:值|}，任意不同 (type, logicalId[, path]) 组合的编码必不相同。
     * 例：{@code ("a|b","c")} → {@code 3:a|b|1:c|}；{@code ("a","b|c")} → {@code 1:a|3:b|c|}。
     * 内容制品两段；external 制品追加 normalizedPath 第三段。
     */
    public static String identityField(String artifactType, String logicalId, String externalPath) {
        StringBuilder sb = new StringBuilder();
        appendIdentitySegment(sb, artifactType);
        appendIdentitySegment(sb, logicalId);
        if (externalPath != null) {
            appendIdentitySegment(sb, externalPath);
        }
        return sb.toString();
    }

    private static void appendIdentitySegment(StringBuilder sb, String segment) {
        String value = segment == null ? "" : segment;
        sb.append(value.length()).append(':').append(value).append('|');
    }

    private static String newArtifactId(String safeType) {
        return safeType + ":" + UUID.randomUUID().toString().replace("-", "");
    }

    private PersistentArtifactMeta buildMeta(String artifactId,
                                             String artifactType,
                                             String logicalId,
                                             String displayName,
                                             Path path,
                                             String contentHash,
                                             Long sizeBytes,
                                             long ttlHours,
                                             boolean external,
                                             boolean cleanupPath,
                                             String runId,
                                             String userId) {
        long ttl = ttlHours > 0 ? ttlHours : defaultTtlHours;
        long now = System.currentTimeMillis();
        return PersistentArtifactMeta.builder()
                .artifactId(artifactId)
                .artifactType(artifactType)
                .runId(hasText(runId) ? runId : null)
                .userId(hasText(userId) ? userId : null)
                .logicalId(logicalId)
                .displayName(displayName)
                .path(path.toAbsolutePath().normalize().toString())
                .contentHash(contentHash)
                .sizeBytes(sizeBytes)
                .createdAtMillis(now)
                .lastAccessAtMillis(now)
                .expiresAtMillis(now + TimeUnit.HOURS.toMillis(ttl))
                .ttlHours(ttl)
                .external(external)
                .cleanupPath(cleanupPath)
                .build();
    }

    private void save(PersistentArtifactMeta meta) {
        try {
            redisTemplate.opsForValue().set(key(meta.getArtifactId()),
                    objectMapper.writeValueAsString(meta),
                    Math.max(1L, meta.getTtlHours()),
                    TimeUnit.HOURS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save persistent artifact meta " + meta.getArtifactId(), e);
        }
    }

    private void touch(PersistentArtifactMeta meta) {
        meta.setLastAccessAtMillis(System.currentTimeMillis());
        save(meta);
    }

    private void deleteMetaAndFile(PersistentArtifactMeta meta) {
        if (meta == null || !hasText(meta.getArtifactId())) {
            return;
        }
        redisTemplate.delete(key(meta.getArtifactId()));
        removeFromIndices(meta);
        if (!hasText(meta.getPath())) {
            return;
        }
        Path path = Path.of(meta.getPath()).toAbsolutePath().normalize();
        if (Boolean.TRUE.equals(meta.getExternal())) {
            if (Boolean.TRUE.equals(meta.getCleanupPath()) && Files.isSymbolicLink(path)) {
                deletePath(path);
            }
            return;
        }
        if (!path.startsWith(rootPath())) {
            log.warn("Skip artifact file delete outside root: {}", path);
            return;
        }
        deletePath(path);
    }

    /**
     * D22-5.1.3：过期清理同删 run 索引与幂等身份字段，不留悬挂引用。
     * 身份字段经值条件 HDEL 原子清除（仅在仍指向本 artifactId 时删除），
     * 避免误删并发新注册抢占的字段。
     */
    private void removeFromIndices(PersistentArtifactMeta meta) {
        String runId = meta.getRunId();
        if (!hasText(runId)) {
            return;
        }
        try {
            redisTemplate.opsForSet().remove(runListKey(runId), meta.getArtifactId());
        } catch (Exception e) {
            log.warn("Failed to remove artifact from run index: runId={} artifactId={} err={}",
                    runId, meta.getArtifactId(), e.getMessage());
        }
        removeIdentityIfMatches(runId, identityField(meta.getArtifactType(), meta.getLogicalId(),
                Boolean.TRUE.equals(meta.getExternal()) ? meta.getPath() : null), meta.getArtifactId());
    }

    private void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete artifact path {}", path, e);
        }
    }

    /**
     * 读取前路径复检（TOCTOU 强化）：规范化 containment（内容制品仅 artifactRoot；
     * external 另许 datasetRoot）→ realpath 解析（不存在即失败）→ 真实位置仍须位于
     * 批准根内（根自身亦解析 symlink）。任一步失败即 fail-closed。
     */
    private Path verifyReadablePath(Path path, boolean externalAllowed) {
        Path normalized = path.toAbsolutePath().normalize();
        Path artifactRoot = rootPath();
        Path datasetRoot = storagePaths.datasetRoot().toAbsolutePath().normalize();
        if (!normalized.startsWith(artifactRoot) && !(externalAllowed && normalized.startsWith(datasetRoot))) {
            throw new IllegalArgumentException("Artifact path escapes approved storage roots: " + normalized);
        }
        Path real;
        try {
            real = normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve artifact path: " + normalized, e);
        }
        Path realArtifactRoot = toRealPathIfPossible(artifactRoot);
        Path realDatasetRoot = toRealPathIfPossible(datasetRoot);
        if (!real.startsWith(realArtifactRoot) && !(externalAllowed && real.startsWith(realDatasetRoot))) {
            throw new SecurityException("Artifact path resolves outside approved storage roots: " + normalized);
        }
        // 复检通过后返回规范化原路径：随后按 no-follow 打开原路径，
        // 注册后把路径换成 symlink 的 TOCTOU 攻击会在打开时 fail-closed。
        return normalized;
    }

    /**
     * no-follow 打开原路径 + 大小上限（两层）+ 哈希校验（如有）；任何异常 fail-closed。
     *
     * <p>大小上限第一层是 Files.size 快速失败预检查；第二层（权威）是
     * {@link #readBounded} 有界流式读取——即使文件在预检查与实读之间被增大
     * （TOCTOU），也至多读 maxBytes+1 字节后拒绝，绝不会把任意大文件整个读入内存。</p>
     */
    private byte[] readBytesChecked(Path openPath, String expectedHash, long maxBytes) {
        try {
            long size = Files.size(openPath);
            if (maxBytes > 0 && size > maxBytes) {
                throw new IllegalStateException("artifact too large to download");
            }
            byte[] bytes = readBounded(openPath, maxBytes);
            if (hasText(expectedHash) && !expectedHash.equals(sha256(bytes))) {
                throw new IllegalStateException("Raw payload hash mismatch");
            }
            return bytes;
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read artifact " + openPath, e);
        }
    }

    /**
     * 有界流式读取（大小上限的权威执行点）：从流中至多读 maxBytes+1 字节，一旦读到
     * 第 maxBytes+1 个字节立即拒绝。因此无论预检查（Files.size）是否已被绕过——
     * 例如文件在预检查之后、实读之前增大——内存中最多只分配 maxBytes+1 字节。
     * maxBytes<=0 表示不限制。包私有 + static，便于单元测试直接钉住该合同。
     */
    static byte[] readBounded(Path path, long maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            if (maxBytes <= 0) {
                return in.readAllBytes();
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long remaining = maxBytes + 1;
            long total = 0;
            int read;
            while (remaining > 0 && (read = in.read(chunk, 0, (int) Math.min(chunk.length, remaining))) > 0) {
                buffer.write(chunk, 0, read);
                total += read;
                remaining -= read;
            }
            if (total > maxBytes) {
                throw new IllegalStateException("artifact too large to download");
            }
            return buffer.toByteArray();
        }
    }

    private static PersistentArtifactRegistration registration(PersistentArtifactMeta meta) {
        return PersistentArtifactRegistration.builder()
                .artifactId(meta.getArtifactId())
                .meta(meta)
                .locator(RawPayloadLocator.builder()
                        .path(meta.getPath())
                        .contentHash(meta.getContentHash())
                        .build())
                .build();
    }

    private Path rootPath() {
        return storagePaths.artifactRoot().toAbsolutePath().normalize();
    }

    private String key(String artifactId) {
        return META_PREFIX + artifactId;
    }

    private static String runListKey(String runId) {
        return RUN_LIST_KEY_PREFIX + runId;
    }

    private static String runIdentityKey(String runId) {
        return RUN_IDENTITY_KEY_PREFIX + runId;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
