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
 *   <li>{@link #listByRunId}：run 级有界索引（ZSET），不生成新 ID；普通读取会清理幽灵
 *       成员，严格诊断读取可关闭该清理。</li>
 * </ul>
 *
 * <h3>幂等抢占协议（单一赢家不变量）</h3>
 * <p>候选 file + meta 先备好，再经单条 Lua 脚本（{@link #ATOMIC_CLAIM_SCRIPT}）原子提交：
 * 脚本内依次做「查身份是否已有赢家（有赢家则当场校验并修复其 run 列表成员资格、
 * 按赢家 meta 键自身剩余 TTL 刷新索引 TTL）→ 窗口轮转有界清理幽灵成员 → ZCARD
 * 容量检查 → 抬 seq 至当前最大 score → HSET 身份 + ZADD run 列表（score = 单调
 * 序号）+ 索引键 TTL 刷新（新建获 TTL、既有只延长不缩短、既有永久保持永久）」，
 * 要么全部生效、要么全部不生效。
 * 因此：输家只有在脚本返回 EXISTS 时才采纳赢家，而 EXISTS 意味着赢家的身份项与
 * 列表项已在同一次脚本执行中原子落盘且列表成员资格已被校验/修复——输家不可能在
 * 赢家列表提交前拿到 ID，不可能拿到幽灵 ID，也不可能采纳一个用户列表里看不见的
 * 赢家；容量不足时脚本返回 FULL 且不写任何索引，Java 侧回滚候选（meta + 文件）
 * 并外抛可见失败。若查无赢家 meta（清理竞态恰好删掉），输家用 Lua 值条件 HDEL
 * 原子清陈旧字段后以新候选重试（有界 {@value #MAX_CLAIM_ATTEMPTS} 次，仍不结算
 * 则显式失败）。同一身份任意时刻至多一份 meta / 一个文件 / 一条 run 索引项。</p>
 *
 * <h3>run 级有界索引（硬上限 + 窗口轮转幽灵自愈，ZSET 实现）</h3>
 * <p>run 列表是 ZSET：成员 = artifactId，score = 每 run 一把单调递增序号
 * （{@code run-seq:{runId}}，脚本内 INCRBY 发号，绝不重复、绝不回退——序号只表达
 * 「被检查的先后顺序」，不依赖任何时间语义）。认领（幂等路径）与加入（非幂等路径）
 * 都是单条 Lua 脚本内的原子操作：脚本先做窗口轮转的有界幽灵清理——
 * {@code ZRANGE list 0 budget-1} 取出当前得分最低的至多 {@value #GHOST_PURGE_BUDGET}
 * 个成员（ZRANGE 带 LIMIT 是构造性硬上限：单次脚本执行检查的成员数不可能超过
 * budget，这是 Redis 命令语义本身而不是提示），逐个 EXISTS 其 meta 键，meta 已不
 * 存在的幽灵成员当场 ZREM；发号前脚本先把 seq 原子抬到至少当前 ZSET 最大 score
 * （floorSeqToTopScore），窗口内的活成员随后用 INCRBY 新发的序号重新打分、移到
 * 所有未检查成员之后（严格大于任何未检查成员的得分——即使 seq 键单键丢失也不降级）
 * ——轮转状态就编码在 score 排序本身，不存在任何独立游标键，也就没有「游标键被短
 * TTL 候选覆盖/过期」这类漂移；再 ZCARD 容量检查，未满才写入——不存在多命令检查-
 * 加入窗口，索引绝不超
 * cap。进展保证分两档如实表述：①成员集合固定、无并发注册/删除、且窗口内活成员
 * 一律移到未检查成员之后时，窗口每次严格前进，至多 ceil(成员总数 /
 * {@value #GHOST_PURGE_BUDGET}) 次索引写入后所有幽灵必然被清完；②有并发写入/
 * 删除时只保证「每次执行至多检查 budget 个成员（硬预算）+ 已检查的活成员严格后移
 * （持续进展）」，不承诺圈数上界。{@link #listByRunId} 普通读取也会顺手移除遇到的
 * 幽灵；严格诊断读取只过滤不移除。注册失败可见，禁止 silent meta-only 成功。cap<=0 视为配置错误，
 * fail-closed。</p>
 *
 * <h3>统一滑动过期协议（meta 与索引 TTL 零漂移，单一归一化点）</h3>
 * <p>生效时长的唯一归一化点是 {@link #effectiveTtlHours}：
 * {@code ttlHours>0 取 ttlHours，否则取 defaultTtlHours，再 clamp 到至少 1}。
 * meta.expiresAtMillis、meta.ttlHours、meta 键 SET TTL、以及所有脚本的 TTL 秒数
 * ARGV 全部由这一个值派生，不存在第二处各自归一化导致的漂移。meta、身份 hash、
 * run 列表 ZSET、run 序号四类键按同一滑动过期协议管理：写路径（认领 CLAIMED /
 * 加入 ADDED）由 Lua 脚本在原子提交内做 TTL 刷新——脚本内判定每把索引键是否「本次
 * 新建」：本次新建的键获得有限 TTL（防止永久键泄漏）；既有键只延长不
 * 缩短（短 TTL 绝不覆盖长 TTL）；既有永久键（TTL = -1）保持永久，绝不缩短为有限值；
 * 缺失键（-2）跳过。「本次新建」的判定时机（v7）：认领/加入脚本在幽灵清理窗口完成
 * 之后、首次可能重建列表的 ZADD 之前即时重读键是否存在——清理可能 ZREM 掉最后一个
 * 成员使真实 Redis 当场自动删除空 ZSET 键，若沿用脚本入口快照，重建键会被误判为
 * 既有键而漏掉 TTL；重建键一律按本次新建处理。touch 路径没有任何删除操作，入口
 * 判定即精确。EXISTS 修复路径的刷新时长不取输家传入的 ARGV，而是取赢家 meta
 * 键自身的剩余 TTL（{@code TTL} 命令读回），杜绝「短 TTL 输家把赢家索引 TTL 改短」；
 * 读路径由 {@link #touch} 的单条原子 Lua 完成「身份冲突只读预检（先于一切写入，
 * 冲突时返回 2 且零副作用）→ 重写 meta（同时更新 lastAccessAtMillis 与
 * expiresAtMillis）→ 丢失身份项在严格赢家身份下 HSETNX 补建 → 发号前抬 seq 至当前
 * ZSET 最大 score → 成员 score 同步 / 丢失成员 ZADD NX 补回 → 四类键 TTL 刷新
 * （新建获 TTL、既有只延长、永久保持）」，返回状态码，Java 侧原样外抛、绝不吞异常
 * 报成功。因此任何读取都会让索引 TTL 不小于 meta 新 TTL，不存在「索引
 * 先于 meta 过期 → list 丢条目 → 同一幂等身份被第二次 CLAIMED」的漂移窗口。</p>
 *
 * <h3>过期清理（Lua 原子判定，读回当前 expiresAtMillis）</h3>
 * <p>cleanup 不再用 Java 预读的 expiresAtMillis 直接删：每个候选 meta 键执行
 * {@link #CLEANUP_META_SCRIPT}，脚本在 Redis 单线程内读回该键当前 JSON、解析
 * expiresAtMillis、仅当其为数字且 <= now 才 DEL——判定与删除原子。Java 预读 meta
 * 只为拿到文件路径；若预读与脚本判定之间 touch 刚把 expiresAtMillis 改到未来，
 * 脚本读到的是新值 → 返回 0 → 不删。touch 与 cleanup 都是单条脚本，Redis 单线程
 * 串行化二者，不存在 touch-then-cleanup 的 TOCTOU 窗口。</p>
 *
 * <h3>Redis 结构</h3>
 * <ul>
 *   <li>{@code agent:persistent-artifact:{artifactId}} — meta JSON，TTL = 生效时长；</li>
 *   <li>{@code agent:persistent-artifact:run-list:{runId}} — ZSET，run 的 artifactId
 *       索引（score = run 序号），硬上限 {@code agent.persistent-artifact.run-list-cap}
 *       （默认 1000）；</li>
 *   <li>{@code agent:persistent-artifact:run-identity:{runId}} — hash，幂等身份
 *       field={@link #identityField} collision-free 编码 → artifactId；</li>
 *   <li>{@code agent:persistent-artifact:run-seq:{runId}} — 字符串计数器，脚本内
 *       INCRBY 发号（窗口轮转重打分与新成员入列共用一把序号），TTL 随索引键同步
 *       滑动。每次发号前脚本先把 seq 原子抬到至少当前 ZSET 最大 score（ZREVRANGE
 *       0 0 WITHSCORES 有界读末尾 1 项）再 INCRBY——序号键即使因 Redis 重启/逐出
 *       单键丢失，新发号也严格接在当前最高分之后：不丢数据、不报错、排序不降级，
 *       硬预算与「已检查活成员严格移到未检查成员之后」的持续推进同样成立。</li>
 * </ul>
 * 注意：后三类键与 meta 共享 {@link #META_PREFIX} 前缀，cleanup 的 SCAN 会命中它们，
 * 循环内按前缀显式跳过（它们不是 meta JSON）。
 *
 * <h3>归属校验</h3>
 * <p>所有用户/工具可达的读取与定位入口一律走 {@link #matchesOwnerStrict}（meta 与调用方
 * 的 runId/userId 四值全部非空且相等，任一空值 fail-closed）——无论旧 AgentContext
 * 入口还是显式上下文入口，不存在宽容 seam（matchesOwnerLenient 已删除）。短格式
 * raw_ref（{@code raw_ref_001}）读取一律经 {@link #readContentStrict} 携带 runId+userId
 * 显式校验：同 runId 下 userId 错误或空白同样拒绝。</p>
 *
 * <h3>读取入口（TOCTOU 强化 + 有界流式读取）</h3>
 * <p>{@link #readArtifactBytes} / {@link #readWithinArtifactRoot} / {@link #readContent} /
 * {@link #readContentStrict} / {@link #readLocator}：读取前 realpath containment 复检
 * （中间目录的 symlink 也会被解析，
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

    /** run 级 artifactId 索引（ZSET：成员 = artifactId，score = run 序号）。与 meta 共享前缀，cleanup SCAN 时按前缀跳过。 */
    private static final String RUN_LIST_KEY_PREFIX = META_PREFIX + "run-list:";

    /** 幂等身份索引（hash：field 为 collision-free 长度前缀编码 → artifactId）。同上，SCAN 跳过。 */
    private static final String RUN_IDENTITY_KEY_PREFIX = META_PREFIX + "run-identity:";

    /**
     * run 序号计数器键（字符串，脚本内 INCRBY 发号）。窗口轮转给活成员重打分、新成员
     * 入列，都从这把序号取值——每次发号前脚本先把 seq 原子抬到至少当前 ZSET 最大
     * score（floorSeqToTopScore）再 INCRBY，序号严格单调递增，保证「已检查的活成员
     * 严格落在所有未检查成员之后」。与 meta 共享前缀，cleanup SCAN 时按前缀显式跳过。
     * 键自身 TTL 随索引键同步滑动（脚本内同款 TTL 刷新：新建获 TTL、既有只延长不缩短、
     * 既有永久保持永久），不会比索引键活得更久；键丢失（重启/逐出）时发号自动抬到
     * 当前最大 score 之后继续，排序语义不变，不丢数据、不降级。
     */
    private static final String RUN_SEQ_KEY_PREFIX = META_PREFIX + "run-seq:";

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

    /** 幽灵自愈的有界预算：每次索引写入前的窗口轮转清理最多检查多少个"缺 meta 的 ZSET 成员"（ZRANGE LIMIT 构造性硬上限）。 */
    private static final int GHOST_PURGE_BUDGET = 128;

    /**
     * 幂等认领原子提交脚本（Lua，单条脚本内要么全做、要么全不做）。
     *
     * <p>KEYS[1]=身份 hash 键，KEYS[2]=run 列表 ZSET 键，KEYS[3]=run 序号计数器键；
     * ARGV[1]=身份 field，ARGV[2]=候选 artifactId，ARGV[3]=容量上限，
     * ARGV[4]=幽灵清理预算，ARGV[5]=meta 键前缀，ARGV[6]=索引键 TTL（秒，由 Java 侧
     * 唯一归一化点 {@link #effectiveTtlHours} 派生）。步骤：
     * ①身份已有赢家：赢家 meta 仍在 → 校验并修复赢家在 run 列表中的成员资格
     *   （ZSCORE 缺失即先抬 seq 至当前最大 score 再以新发序号 ZADD 补回，杜绝"输家
     *   采纳一个用户列表里看不见的赢家"），并按赢家 meta 键自身的剩余 TTL（TTL 命令
     *   读回，绝不取输家 ARGV[6]）对三类索引键做 TTL 刷新（本次新建获 TTL、既有只
     *   延长不缩短、既有永久保持永久），随后返回
     *   EXISTS:赢家ID（不写任何新索引项）；赢家 meta 已缺失（陈旧悬挂）→ 同样返回
     *   EXISTS:赢家ID，由 Java 侧按既有协议值条件 HDEL 清除陈旧字段后重试；
     * ②有界幽灵清理（窗口轮转协议）：ZRANGE 取当前得分最低的至多 ARGV[4] 个成员
     *   （LIMIT 是构造性硬上限），逐个 EXISTS 其 meta 键，幽灵当场 ZREM；发号前先把
     *   seq 原子抬到至少当前 ZSET 最大 score（floorSeqToTopScore，ZREVRANGE 0 0
     *   WITHSCORES 有界读末尾 1 项），窗口内活成员再用 INCRBY 新发的连续序号重新打分、
     *   整体移到所有未检查成员之后（严格大于任何未检查成员的得分——即使 seq 键单键
     *   丢失也不降级）。轮转状态编码在 score 排序本身，无独立游标键。成员集合固定且
     *   无并发写入时，至多 ceil(成员总数 / ARGV[4]) 次执行清完所有幽灵；有并发写入时
     *   只保证每次执行的硬预算与持续进展；
     * ③ZCARD 容量检查：已满 → 返回 FULL（不写任何东西）；
     * ④HSET 身份 + 抬 seq 后 INCRBY 发号 + ZADD run 列表 + 三类索引键 TTL 刷新
     *   （本次新建键获得有限 TTL；既有键只延长不缩短、既有永久键保持永久；与 meta 同
     *   滑动过期协议对齐，见 {@link #touch}），返回 CLAIMED。「本次新建」判定（v7）：
     *   CLAIMED 路径在幽灵清理完成后即时重读 list/seq 是否存在，不沿用脚本入口快照——
     *   清理可能 ZREM 掉最后一个成员使真实 Redis 当场自动删除空 ZSET 键，随后的 ZADD
     *   属于重建，该键必须按本次新建处理获得有限 TTL，杜绝永久键泄漏；EXISTS 分支
     *   没有任何成员删除操作，入口快照在那里仍然精确。</p>
     *
     * <p>由此得到的不变量：输家观察到 EXISTS 时，赢家的身份项与列表项必然已在同一次
     * 脚本执行中原子落盘（输家不可能提前返回、不可能拿到幽灵 ID），且赢家必然在 run
     * 列表中可见（成员资格丢失时脚本当场修复，修复发号同样先抬 seq 到当前最大 score）；
     * FULL 路径从不写索引（容量失败的注册不留任何痕迹）；EXISTS 路径的索引 TTL 刷新
     * 时长只来自赢家 meta 键自身剩余 TTL，短 TTL 输家不可能把赢家索引 TTL 改短。</p>
     */
    private static final RedisScript<String> ATOMIC_CLAIM_SCRIPT = new DefaultRedisScript<>(
            "local function extendOnly(key, ttl, createdHere) "
                    + "  local t = redis.call('ttl', key) "
                    + "  if t == -2 then return end "
                    + "  if createdHere == 1 then redis.call('expire', key, ttl) return end "
                    + "  if t >= 0 and t < ttl then redis.call('expire', key, ttl) end "
                    + "end "
                    + "local function floorSeqToTopScore(listKey, seqKey) "
                    + "  local top = redis.call('zrevrange', listKey, 0, 0, 'WITHSCORES') "
                    + "  if top[2] then "
                    + "    local topScore = tonumber(top[2]) "
                    + "    local raw = redis.call('get', seqKey) "
                    + "    local cur = 0 "
                    + "    if raw then cur = tonumber(raw) or 0 end "
                    + "    if topScore > cur then redis.call('incrby', seqKey, topScore - cur) end "
                    + "  end "
                    + "end "
                    + "local identityExisted = redis.call('exists', KEYS[1]) "
                    + "local listExisted = redis.call('exists', KEYS[2]) "
                    + "local seqExisted = redis.call('exists', KEYS[3]) "
                    + "local existing = redis.call('hget', KEYS[1], ARGV[1]) "
                    + "if existing then "
                    + "  if redis.call('exists', ARGV[5] .. existing) == 1 then "
                    + "    if redis.call('zscore', KEYS[2], existing) == false then "
                    + "      floorSeqToTopScore(KEYS[2], KEYS[3]) "
                    + "      local repairSeq = redis.call('incrby', KEYS[3], 1) "
                    + "      redis.call('zadd', KEYS[2], repairSeq, existing) "
                    + "    end "
                    + "    local winnerTtl = redis.call('ttl', ARGV[5] .. existing) "
                    + "    if winnerTtl > 0 then "
                    + "      extendOnly(KEYS[1], winnerTtl, 1 - identityExisted) "
                    + "      extendOnly(KEYS[2], winnerTtl, 1 - listExisted) "
                    + "      extendOnly(KEYS[3], winnerTtl, 1 - seqExisted) "
                    + "    end "
                    + "  end "
                    + "  return 'EXISTS:' .. existing "
                    + "end "
                    + "local budget = tonumber(ARGV[4]) "
                    + "if budget > 0 then "
                    + "  local window = redis.call('zrange', KEYS[2], 0, budget - 1) "
                    + "  local live = {} "
                    + "  for _, m in ipairs(window) do "
                    + "    if redis.call('exists', ARGV[5] .. m) == 0 then "
                    + "      redis.call('zrem', KEYS[2], m) "
                    + "    else "
                    + "      table.insert(live, m) "
                    + "    end "
                    + "  end "
                    + "  if #live > 0 then "
                    + "    floorSeqToTopScore(KEYS[2], KEYS[3]) "
                    + "    local base = redis.call('incrby', KEYS[3], #live) "
                    + "    for i, m in ipairs(live) do "
                    + "      redis.call('zadd', KEYS[2], base - #live + i, m) "
                    + "    end "
                    + "  end "
                    + "end "
                    + "listExisted = redis.call('exists', KEYS[2]) "
                    + "seqExisted = redis.call('exists', KEYS[3]) "
                    + "if redis.call('zcard', KEYS[2]) >= tonumber(ARGV[3]) then return 'FULL' end "
                    + "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]) "
                    + "floorSeqToTopScore(KEYS[2], KEYS[3]) "
                    + "local claimSeq = redis.call('incrby', KEYS[3], 1) "
                    + "redis.call('zadd', KEYS[2], claimSeq, ARGV[2]) "
                    + "local ttl2 = tonumber(ARGV[6]) "
                    + "if ttl2 > 0 then "
                    + "  extendOnly(KEYS[1], ttl2, 1 - identityExisted) "
                    + "  extendOnly(KEYS[2], ttl2, 1 - listExisted) "
                    + "  extendOnly(KEYS[3], ttl2, 1 - seqExisted) "
                    + "end "
                    + "return 'CLAIMED'",
            String.class);

    /**
     * 非幂等 run 列表加入脚本（Lua，原子：窗口轮转幽灵清理 → ZCARD 容量检查 →
     * 抬 seq 至当前最大 score → INCRBY 发号 + ZADD → TTL 刷新）。
     *
     * <p>KEYS[1]=run 列表 ZSET 键，KEYS[2]=run 序号计数器键；ARGV[1]=容量上限，
     * ARGV[2]=幽灵清理预算，ARGV[3]=meta 键前缀，ARGV[4]=artifactId，
     * ARGV[5]=索引键 TTL（秒，同样只由 {@link #effectiveTtlHours} 派生）。幽灵清理与
     * {@link #ATOMIC_CLAIM_SCRIPT} 同款窗口轮转协议（ZRANGE LIMIT 硬预算、活成员严格
     * 后移、无游标键）。每次发号（轮转重打分与新成员入列）前先把 seq 原子抬到至少当前
     * ZSET 最大 score（floorSeqToTopScore，ZREVRANGE 0 0 WITHSCORES 有界读末尾 1 项）
     * 再 INCRBY——即使 seq 键单键丢失，幸存者与新成员也严格落在所有未检查成员之后，
     * 持续推进不降级。TTL 刷新区分「本次新建键获得有限 TTL」与「既有键只延长不缩短、
     * 既有永久键保持永久」；「本次新建」判定（v7）在幽灵清理完成后即时重读 list/seq
     * 是否存在，不沿用脚本入口快照——清理可能 ZREM 掉最后一个成员使真实 Redis 当场
     * 自动删除空 ZSET 键，随后的 ZADD 属于重建，该键必须按本次新建处理获得有限 TTL，
     * 杜绝永久键泄漏。已满返回 FULL（不写），否则写入返回 ADDED。非幂等路径的
     * artifactId 每次全新生成，不存在"已是成员"情形。</p>
     */
    private static final RedisScript<String> RUN_LIST_ADD_SCRIPT = new DefaultRedisScript<>(
            "local function extendOnly(key, ttl, createdHere) "
                    + "  local t = redis.call('ttl', key) "
                    + "  if t == -2 then return end "
                    + "  if createdHere == 1 then redis.call('expire', key, ttl) return end "
                    + "  if t >= 0 and t < ttl then redis.call('expire', key, ttl) end "
                    + "end "
                    + "local function floorSeqToTopScore(listKey, seqKey) "
                    + "  local top = redis.call('zrevrange', listKey, 0, 0, 'WITHSCORES') "
                    + "  if top[2] then "
                    + "    local topScore = tonumber(top[2]) "
                    + "    local raw = redis.call('get', seqKey) "
                    + "    local cur = 0 "
                    + "    if raw then cur = tonumber(raw) or 0 end "
                    + "    if topScore > cur then redis.call('incrby', seqKey, topScore - cur) end "
                    + "  end "
                    + "end "
                    + "local budget = tonumber(ARGV[2]) "
                    + "if budget > 0 then "
                    + "  local window = redis.call('zrange', KEYS[1], 0, budget - 1) "
                    + "  local live = {} "
                    + "  for _, m in ipairs(window) do "
                    + "    if redis.call('exists', ARGV[3] .. m) == 0 then "
                    + "      redis.call('zrem', KEYS[1], m) "
                    + "    else "
                    + "      table.insert(live, m) "
                    + "    end "
                    + "  end "
                    + "  if #live > 0 then "
                    + "    floorSeqToTopScore(KEYS[1], KEYS[2]) "
                    + "    local base = redis.call('incrby', KEYS[2], #live) "
                    + "    for i, m in ipairs(live) do "
                    + "      redis.call('zadd', KEYS[1], base - #live + i, m) "
                    + "    end "
                    + "  end "
                    + "end "
                    + "local listExisted = redis.call('exists', KEYS[1]) "
                    + "local seqExisted = redis.call('exists', KEYS[2]) "
                    + "if redis.call('zcard', KEYS[1]) >= tonumber(ARGV[1]) then return 'FULL' end "
                    + "floorSeqToTopScore(KEYS[1], KEYS[2]) "
                    + "local seq = redis.call('incrby', KEYS[2], 1) "
                    + "redis.call('zadd', KEYS[1], seq, ARGV[4]) "
                    + "local ttl = tonumber(ARGV[5]) "
                    + "if ttl > 0 then "
                    + "  extendOnly(KEYS[1], ttl, 1 - listExisted) "
                    + "  extendOnly(KEYS[2], ttl, 1 - seqExisted) "
                    + "end "
                    + "return 'ADDED'",
            String.class);

    /**
     * 读取 touch 原子脚本（Lua，单条脚本内完成身份冲突只读预检 + meta 重写 + 成员/身份
     * 修复 + 四类键滑动过期，返回状态码）。
     *
     * <p>KEYS[1]=meta 键，KEYS[2]=run 列表 ZSET 键，KEYS[3]=身份 hash 键，
     * KEYS[4]=run 序号计数器键；ARGV[1]=新 meta JSON（Java 侧已把 lastAccessAtMillis
     * 与 expiresAtMillis 一并更新到本次滑动值），ARGV[2]=TTL 秒数（{@link
     * #effectiveTtlHours} 派生），ARGV[3]=身份 field（非幂等制品传空串 → 跳过身份步），
     * ARGV[4]=artifactId。步骤：
     * ①meta 键不存在 → 返回 0（制品已在 find 与 touch 之间过期/删除，读取必须失败，
     *   绝不复活）；
     * ②身份冲突预检（仅幂等制品；只读，先于本脚本一切可见写入）：槽位被其他 artifactId
     *   占用 → 立即返回 2。返回 2 路径不执行任何写入，严格零副作用——入侵者的 meta
     *   原文与 TTL、run 列表 score、seq 值全部原样，失效制品绝不可能被失败读取反复续命；
     * ③记录列表/身份/序号三类索引键在本次调用之前是否已存在（供步骤⑦区分「既有键」
     *   与「本次新建键」）；
     * ④SET 新 meta + EXPIRE 满额滑动（meta 的 TTL 每次读取重置为完整生效时长）；
     * ⑤身份步（仅幂等制品）：槽位为空 → HSETNX 以本 artifactId 补建（②预检通过后 Lua
     *   单线程保证槽位不可能被他人抢走，HSETNX 必然成功）；槽位值 == 本 artifactId →
     *   通过。非幂等制品（ARGV[3]=''）整步跳过——它们本就没有身份项，绝不允许顺手创建；
     * ⑥发号前先把 seq 原子抬到至少当前 ZSET 最大 score（floorSeqToTopScore，
     *   ZREVRANGE 0 0 WITHSCORES 有界读末尾 1 项），再 INCRBY 发号 + 成员 score 同步：
     *   成员已在 ZSET → 以新发序号重新打分、移回真正队尾；成员缺失 → ZADD NX 以新序号
     *   补回。即使 seq 键单键丢失，touch 后成员也严格落在所有其他成员之后；
     * ⑦ZSET/身份/序号三类键 TTL 刷新：本次调用新建的键获得有限 TTL（防止永久键泄漏）；
     *   既有键只延长不缩短；既有永久键（-1）保持永久，绝不缩短为有限值；缺失（-2）跳过。
     * 返回 1。</p>
     *
     * <p>状态码合同：0 = meta 已消失；1 = 成功；2 = 身份槽位被其他 artifactId 占用
     * （零副作用：预检在任何写入之前）。Java 侧对 0/2/null 一律外抛异常，绝不吞掉报成功
     * （见 {@link #touch}）。</p>
     */
    private static final RedisScript<Long> TOUCH_SCRIPT = new DefaultRedisScript<>(
            "local function extendOnly(key, ttl, createdHere) "
                    + "  local t = redis.call('ttl', key) "
                    + "  if t == -2 then return end "
                    + "  if createdHere == 1 then redis.call('expire', key, ttl) return end "
                    + "  if t >= 0 and t < ttl then redis.call('expire', key, ttl) end "
                    + "end "
                    + "local function floorSeqToTopScore(listKey, seqKey) "
                    + "  local top = redis.call('zrevrange', listKey, 0, 0, 'WITHSCORES') "
                    + "  if top[2] then "
                    + "    local topScore = tonumber(top[2]) "
                    + "    local raw = redis.call('get', seqKey) "
                    + "    local cur = 0 "
                    + "    if raw then cur = tonumber(raw) or 0 end "
                    + "    if topScore > cur then redis.call('incrby', seqKey, topScore - cur) end "
                    + "  end "
                    + "end "
                    + "if redis.call('exists', KEYS[1]) == 0 then return 0 end "
                    + "if ARGV[3] ~= '' then "
                    + "  local holder = redis.call('hget', KEYS[3], ARGV[3]) "
                    + "  if holder ~= false and holder ~= ARGV[4] then return 2 end "
                    + "end "
                    + "local listExisted = redis.call('exists', KEYS[2]) "
                    + "local identityExisted = redis.call('exists', KEYS[3]) "
                    + "local seqExisted = redis.call('exists', KEYS[4]) "
                    + "local ttl = tonumber(ARGV[2]) "
                    + "redis.call('set', KEYS[1], ARGV[1]) "
                    + "if ttl > 0 then redis.call('expire', KEYS[1], ttl) end "
                    + "if ARGV[3] ~= '' then "
                    + "  redis.call('hsetnx', KEYS[3], ARGV[3], ARGV[4]) "
                    + "end "
                    + "floorSeqToTopScore(KEYS[2], KEYS[4]) "
                    + "local seq = redis.call('incrby', KEYS[4], 1) "
                    + "if redis.call('zscore', KEYS[2], ARGV[4]) == false then "
                    + "  redis.call('zadd', KEYS[2], 'NX', seq, ARGV[4]) "
                    + "else "
                    + "  redis.call('zadd', KEYS[2], seq, ARGV[4]) "
                    + "end "
                    + "if ttl > 0 then "
                    + "  extendOnly(KEYS[2], ttl, 1 - listExisted) "
                    + "  extendOnly(KEYS[3], ttl, 1 - identityExisted) "
                    + "  extendOnly(KEYS[4], ttl, 1 - seqExisted) "
                    + "end "
                    + "return 1",
            Long.class);

    /**
     * 过期清理原子判定脚本（Lua）：在 Redis 单线程内读回 meta 键当前 JSON，仅当
     * expiresAtMillis 是数字且 <= ARGV[1]（now 毫秒）时 DEL 并返回 1；键已不存在
     * 返回 0；JSON 损坏/非对象返回 -1（Java 记日志跳过，绝不盲删）；expiresAtMillis
     * 缺失/null/非数字返回 0（保守保留——永不过期语义与历史 Java 判空逻辑一致）。
     *
     * <p>KEYS[1]=meta 键；ARGV[1]=now 毫秒。判定与删除同脚本原子：若 touch 在 Java
     * 预读与本脚本之间把 expiresAtMillis 改到未来，脚本读到的是新值 → 不删。touch
     * 与本脚本都是单条脚本，Redis 单线程串行化二者，touch-then-cleanup 无 TOCTOU
     * 窗口。索引项与文件由 Java 在脚本返回 1 之后删除（索引残项是幽灵，写入侧窗口
     * 轮转与读取侧顺手清理都会收掉）。</p>
     */
    private static final RedisScript<Long> CLEANUP_META_SCRIPT = new DefaultRedisScript<>(
            "local raw = redis.call('get', KEYS[1]) "
                    + "if not raw then return 0 end "
                    + "local ok, meta = pcall(function() return cjson.decode(raw) end) "
                    + "if not ok or type(meta) ~= 'table' then return -1 end "
                    + "local exp = meta['expiresAtMillis'] "
                    + "if type(exp) ~= 'number' then return 0 end "
                    + "if exp <= tonumber(ARGV[1]) then return redis.call('del', KEYS[1]) end "
                    + "return 0",
            Long.class);

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
     * run 级制品列表：读 run 索引 ZSET（按 score 升序取全部成员）→ 逐条取 meta →
     * 过滤已过期/缺失项。
     *
     * <p>只读，不生成新 artifactId；重复调用结果一致（meta 缺失项自动滤掉）。
     * 返回按创建时间升序、artifactId 次序的列表。</p>
     *
     * <p>幽灵自愈（普通读取侧）：meta 键已不存在的 ZSET 成员（幽灵，典型成因是 meta 的
     * Redis TTL 先到期）在遍历时顺手 ZREM 移除，避免其永久占用容量配额、让 ZCARD
     * 虚高导致后续注册持续被误判超限。需要严格无副作用的诊断读取应调用
     * {@link #listByRunId(String, boolean)} 并传 {@code false}。</p>
     */
    public List<PersistentArtifactMeta> listByRunId(String runId) {
        return listByRunId(runId, true);
    }

    /**
     * run 级制品列表，可显式控制是否清理幽灵索引。传 {@code false} 时仍会过滤缺失
     * meta，但绝不执行 ZREM，供管理员诊断采集保持 Redis 严格只读。
     */
    public List<PersistentArtifactMeta> listByRunId(String runId, boolean removeGhostEntries) {
        if (!hasText(runId)) {
            return List.of();
        }
        String listKey = runListKey(runId);
        Set<String> artifactIds = redisTemplate.opsForZSet().range(listKey, 0, -1);
        if (artifactIds == null || artifactIds.isEmpty()) {
            return List.of();
        }
        List<PersistentArtifactMeta> metas = new ArrayList<>(artifactIds.size());
        for (String artifactId : artifactIds) {
            Optional<PersistentArtifactMeta> meta = find(artifactId);
            if (meta.isEmpty()) {
                if (removeGhostEntries) {
                    // 普通读取顺手自愈；严格诊断读取只过滤，不修改 Redis。
                    try {
                        redisTemplate.opsForZSet().remove(listKey, artifactId);
                    } catch (Exception e) {
                        log.warn("Failed to remove ghost run index entry: runId={} artifactId={} err={}",
                                runId, artifactId, e.getMessage());
                    }
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

    /**
     * 内容读取（不带归属校验的底层管线）：调用方必须自行完成严格归属校验
     * （{@link #matchesOwnerStrict}）后才可使用。所有用户/工具可达的读取一律走
     * {@link #readContentStrict}；仅内部已校验归属的门面（如 AgentArtifactService
     * 的 user API 先经 assertVisible 等价校验）允许直接调用本方法。
     */
    public String readContent(String artifactId) {
        PersistentArtifactMeta meta = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        return readContentOfMeta(meta);
    }

    /**
     * 严格归属校验的内容读取入口：先用 {@link #matchesOwnerStrict} 校验四值
     * （调用方与 meta 的 runId/userId 全部非空且相等，任一空值或不一致一律拒绝，
     * fail-closed），再走与 {@link #readContent} 完全相同的读取管线。
     *
     * <p>短格式 raw_ref（如 {@code raw_ref_001}，经 run 级索引解析到 artifactId）的
     * 读取链路必须经由本方法：只有 runId 正确不足以放行，userId 错误或缺失同样拒绝，
     * 同 runId 下其他用户的短格式引用无法读取内容。</p>
     *
     * @param artifactId 待读取制品
     * @param runId      调用方 run 上下文（必须与 meta.runId 严格相等）
     * @param userId     调用方 user 上下文（必须与 meta.userId 严格相等）
     */
    public String readContentStrict(String artifactId, String runId, String userId) {
        PersistentArtifactMeta meta = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        if (!matchesOwnerStrict(meta, runId, userId)) {
            throw new IllegalArgumentException("Artifact does not belong to current run/user: " + artifactId);
        }
        return readContentOfMeta(meta);
    }

    /** 共享读取管线：external 拒绝 → 路径存在检查 → touch → realpath 复检 + no-follow 读 + 哈希校验。 */
    private String readContentOfMeta(PersistentArtifactMeta meta) {
        if (Boolean.TRUE.equals(meta.getExternal())) {
            throw new IllegalArgumentException(
                    "External artifact has no registry-owned content: " + meta.getArtifactId());
        }
        if (!hasText(meta.getPath())) {
            throw new IllegalArgumentException("Artifact path missing: " + meta.getArtifactId());
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
        return readArtifactBytes(artifactId, maxBytes, true);
    }

    /**
     * 权威字节读取入口的诊断变体。
     *
     * <p>{@code refreshRetention=false} 只关闭访问时间和过期时间续期；路径约束、no-follow
     * 打开、大小上限和内容哈希校验全部保留。管理员只读诊断用它读取分片，避免 GET 修改
     * meta、run 索引、identity、seq 及其 Redis TTL。</p>
     *
     * @param maxBytes 读取字节上限；<=0 表示不限制
     * @param refreshRetention 是否刷新制品访问时间、过期时间和相关索引 TTL
     */
    public byte[] readArtifactBytes(String artifactId, long maxBytes, boolean refreshRetention) {
        PersistentArtifactMeta meta = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        if (!hasText(meta.getPath())) {
            throw new IllegalArgumentException("Artifact path missing: " + artifactId);
        }
        if (refreshRetention) {
            touch(meta);
        }
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
                // run 索引 / 幂等身份键 / run 序号键与 meta 共享前缀，不是 meta JSON，显式跳过。
                if (key.startsWith(RUN_LIST_KEY_PREFIX) || key.startsWith(RUN_IDENTITY_KEY_PREFIX)
                        || key.startsWith(RUN_SEQ_KEY_PREFIX)) {
                    continue;
                }
                // 预读只为拿文件路径；「是否过期」的权威判定在 Lua 脚本内读回当前值做，
                // 判定与 DEL 原子——预读之后 touch 若把 expiresAtMillis 改到未来，脚本不删。
                String json = redisTemplate.opsForValue().get(key);
                if (!hasText(json)) {
                    continue;
                }
                PersistentArtifactMeta meta;
                try {
                    meta = objectMapper.readValue(json, PersistentArtifactMeta.class);
                } catch (Exception e) {
                    log.warn("Failed to cleanup artifact meta {}", key, e);
                    continue;
                }
                Long verdict;
                try {
                    verdict = redisTemplate.execute(CLEANUP_META_SCRIPT, List.of(key), String.valueOf(now));
                } catch (Exception e) {
                    log.warn("Failed to evaluate artifact meta expiry {}", key, e);
                    continue;
                }
                if (verdict == null || verdict == 0L) {
                    continue;
                }
                if (verdict < 0L) {
                    log.warn("Artifact meta {} is malformed; cleanup left it untouched", key);
                    continue;
                }
                // 脚本已原子 DEL meta；这里收掉文件与索引痕迹（索引残项属幽灵，写入侧
                // 窗口轮转与读取侧顺手清理亦会收掉，双保险）。
                deleteFileAndIndices(meta);
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
        // 生效时长唯一归一化点：之后 meta.expiresAtMillis / meta.ttlHours / meta 键 TTL /
        // 脚本 TTL ARGV 全部由该值派生，不允许第二处各自归一化（第五轮 MUST-FIX ④）。
        long effectiveTtl = effectiveTtlHours(ttlHours);
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
                    (long) bytes.length, effectiveTtl, false, true, runId, userId, idempotent);
            try {
                // 候选 meta 先落盘再原子 claim：身份字段恒指向 meta 已写的制品
                save(meta);
                if (!idempotent) {
                    addToRunList(runId, artifactId, effectiveTtl);
                    return registration(meta);
                }
                CommitOutcome outcome = commitCandidate(runId, field, meta, effectiveTtl);
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
        // 生效时长唯一归一化点（同内容路径，第五轮 MUST-FIX ④）。
        long effectiveTtl = effectiveTtlHours(ttlHours);
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
                    size, effectiveTtl, true, cleanupPath, runId, userId, idempotent);
            try {
                save(meta);
                if (!idempotent) {
                    addToRunList(runId, artifactId, effectiveTtl);
                    return registration(meta);
                }
                CommitOutcome outcome = commitCandidate(runId, field, meta, effectiveTtl);
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
     * <p>整个「身份已有赢家？→ 窗口轮转幽灵清理 → 容量检查 → 抬 seq 至当前最大
     * score → 写身份 + 发号写 run 列表 + 索引键 TTL 刷新」由单条 Lua 脚本
     * （{@link #ATOMIC_CLAIM_SCRIPT}）一次执行完成，不再有任何多命令窗口：</p>
     * <ul>
     *   <li>CLAIMED → 赢家：meta 已落盘，身份与列表项在同一次脚本执行中原子可见，
     *       三类索引键的 TTL 也在同一次脚本执行中按统一滑动过期协议刷新（本次新建获
     *       TTL、既有只延长不缩短、既有永久保持永久，与 {@link #touch} 对齐），Java
     *       侧不再补做。</li>
     *   <li>FULL → 容量超限：脚本没写任何索引，直接抛可见失败，由调用方 catch 回滚
     *       候选（meta + 文件），禁止 silent meta-only 成功。FULL 路径从不写身份，
     *       因此容量失败的注册不会给任何后来者留下幽灵身份。</li>
     *   <li>EXISTS:赢家ID → 输家：先回滚候选（零残留）再采纳。输家只有在脚本报告
     *       EXISTS 时才可能拿到赢家 ID，而 EXISTS 意味着赢家的身份项与列表项已经
     *       原子落盘，且脚本已当场校验并修复赢家的 run 列表成员资格（ZSCORE 缺失
     *       即以新发序号 ZADD 补回）——输家不可能在赢家列表提交前返回，不可能返回
     *       幽灵 ID，也不可能采纳一个用户列表里看不见的赢家。EXISTS 路径的索引 TTL
     *       刷新时长取赢家 meta 键自身剩余 TTL（绝不取输家传入的 TTL），短 TTL 输家
     *       改不短赢家的索引。赢家 meta 已被清理（身份字段成为陈旧悬挂）时用值条件
     *       HDEL 原子清除并返回 retry=true——身份恒指向 meta 已落盘制品，清陈旧不
     *       伤及任何活制品。</li>
     * </ul>
     */
    private CommitOutcome commitCandidate(String runId, String field, PersistentArtifactMeta candidateMeta,
                                          long ttlHours) {
        String candidateArtifactId = candidateMeta.getArtifactId();
        String result = executeAtomicClaim(runId, field, candidateArtifactId, ttlHours);
        if ("CLAIMED".equals(result)) {
            // 索引键 TTL 已在同一次脚本执行中只延长不缩短刷新（统一滑动过期协议），Java 侧不补做
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
     *
     * <p>KEYS = [身份 hash 键, run 列表 ZSET 键, run 序号计数器键]；ARGV = [身份 field,
     * 候选 artifactId, 容量上限, 幽灵清理预算, meta 键前缀, 索引键 TTL 秒数]。
     * 入参 ttlHours 已是 {@link #effectiveTtlHours} 归一化后的生效时长（唯一归一化点，
     * 此处只做单位换算）；脚本内在 CLAIMED 路径对三类索引键做 TTL 刷新（本次新建获
     * TTL、既有只延长不缩短、既有永久保持永久），EXISTS 修复路径则按赢家 meta 键
     * 自身剩余 TTL 同款刷新（统一滑动过期协议，见 {@link #ATOMIC_CLAIM_SCRIPT} 与
     * {@link #touch}）。</p>
     */
    private String executeAtomicClaim(String runId, String field, String artifactId, long ttlHours) {
        if (maxRunListEntries <= 0) {
            throw new IllegalStateException(
                    "Run artifact index capacity must be positive: cap=" + maxRunListEntries);
        }
        String result = redisTemplate.execute(ATOMIC_CLAIM_SCRIPT,
                List.of(runIdentityKey(runId), runListKey(runId), runSeqKey(runId)),
                field, artifactId, String.valueOf(maxRunListEntries),
                String.valueOf(GHOST_PURGE_BUDGET), META_PREFIX,
                String.valueOf(TimeUnit.HOURS.toSeconds(ttlHours)));
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
     * 内原子完成「窗口轮转幽灵清理 → ZCARD 容量检查 → 抬 seq 至当前 ZSET 最大 score
     * → INCRBY 发号 + ZADD → 列表键与序号键 TTL 刷新（本次新建获 TTL、既有只延长不
     * 缩短、既有永久保持永久）」——超限时脚本不写任何东西并返回 FULL，Java 侧抛
     * 可见失败，禁止 silent meta-only 成功；不存在多命令检查-加入窗口。cap<=0 视为
     * 配置错误，fail-closed。KEYS = [run 列表 ZSET 键, run 序号计数器键]；入参
     * ttlHours 已是 {@link #effectiveTtlHours} 归一化后的生效时长（唯一归一化点），
     * ARGV 末位为换算后的 TTL 秒数（统一滑动过期协议，与 meta 同滑动）。
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
        String result = redisTemplate.execute(RUN_LIST_ADD_SCRIPT,
                List.of(listKey, runSeqKey(runId)),
                String.valueOf(maxRunListEntries), String.valueOf(GHOST_PURGE_BUDGET),
                META_PREFIX, artifactId,
                String.valueOf(TimeUnit.HOURS.toSeconds(ttlHours)));
        if (!"ADDED".equals(result)) {
            throw new IllegalStateException(
                    "Run artifact index capacity exceeded: runId=" + runId + " cap=" + maxRunListEntries);
        }
        // 列表键与序号键 TTL 已在同一次脚本执行中只延长不缩短刷新（统一滑动过期协议），Java 侧不补做
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

    /**
     * 构建 meta。入参 effectiveTtlHours 必须已经过 {@link #effectiveTtlHours} 归一化
     * （>0，且已应用默认值）——本方法不再做第二处归一化，expiresAtMillis 与 ttlHours
     * 直接由它派生，保证与 meta 键 TTL、脚本 TTL ARGV 零漂移（第五轮 MUST-FIX ④）。
     */
    private PersistentArtifactMeta buildMeta(String artifactId,
                                             String artifactType,
                                             String logicalId,
                                             String displayName,
                                             Path path,
                                             String contentHash,
                                             Long sizeBytes,
                                             long effectiveTtlHours,
                                             boolean external,
                                             boolean cleanupPath,
                                             String runId,
                                             String userId,
                                             boolean idempotent) {
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
                .expiresAtMillis(now + TimeUnit.HOURS.toMillis(effectiveTtlHours))
                .ttlHours(effectiveTtlHours)
                .external(external)
                .cleanupPath(cleanupPath)
                .idempotent(idempotent ? Boolean.TRUE : Boolean.FALSE)
                .build();
    }

    /**
     * 生效时长唯一归一化点（第五轮 MUST-FIX ④）：ttlHours>0 取 ttlHours，否则取
     * defaultTtlHours，再 clamp 到至少 1 小时。meta.expiresAtMillis / meta.ttlHours /
     * meta 键 SET TTL / 所有脚本 TTL ARGV 全部且只从这里派生——修复前 buildMeta 与
     * 脚本各自归一化（后者只 max(1) 不补默认值），同一制品会出现 meta 12h 而索引 1h
     * 的漂移，索引先过期后同一幂等身份可被第二次 CLAIMED。
     */
    private long effectiveTtlHours(long ttlHours) {
        long ttl = ttlHours > 0 ? ttlHours : defaultTtlHours;
        return Math.max(1L, ttl);
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

    /**
     * 读取 touch（单条原子 Lua，{@link #TOUCH_SCRIPT}，状态码合同，异常原样外抛）：
     * 在同一次脚本执行内完成「身份冲突只读预检（先于一切可见写入；冲突直接返回 2，
     * 零副作用）→ meta 重写（同时更新 lastAccessAtMillis 与 expiresAtMillis，第五轮
     * MUST-FIX ⑤——expiresAtMillis 不再停在注册时的旧值，cleanup 的 Lua 判定读到的
     * 就是本次滑动后的新值）→ 幂等制品的身份步（槽位空 HSETNX 补建 / 本 artifactId
     * 通过）→ 发号前抬 seq 至当前 ZSET 最大 score → 成员 ZSET score 同步（缺失以
     * ZADD NX 补回）→ ZSET/身份/序号三类键 TTL 刷新（本次新建获 TTL、既有只延长不
     * 缩短、既有永久保持永久）」。meta 键自身的 TTL 每次读取满额滑动（重置为完整
     * 生效时长），索引键 TTL 因此恒不小于 meta 新 TTL——统一滑动过期协议的读侧一半，
     * 杜绝「索引先于 meta 过期 → list 丢条目 → 同一幂等身份被第二次 CLAIMED」的漂移
     * 窗口。
     *
     * <p>失败语义（绝不吞异常报成功）：脚本返回 0 = meta 已在 find 与 touch 之间消失
     * （制品过期/删除）→ 抛 {@link IllegalArgumentException}，读取失败；返回 2 = 身份
     * 槽位被其他 artifactId 占用（预检在任何写入之前：meta 原文与 TTL、run 列表
     * score、seq 值全部原样，失效制品绝不可能被失败读取续命）→ 抛
     * {@link IllegalStateException}；脚本异常（Redis 故障等）原样上抛。无 run 上下文的
     * 历史制品没有索引键，只做 meta 满额滑动。</p>
     */
    private void touch(PersistentArtifactMeta meta) {
        long now = System.currentTimeMillis();
        long ttlHours = effectiveTtlHours(meta.getTtlHours() == null ? 0L : meta.getTtlHours());
        meta.setLastAccessAtMillis(now);
        meta.setExpiresAtMillis(now + TimeUnit.HOURS.toMillis(ttlHours));
        String runId = meta.getRunId();
        if (!hasText(runId)) {
            save(meta);
            return;
        }
        String field = Boolean.TRUE.equals(meta.getIdempotent())
                ? identityField(meta.getArtifactType(), meta.getLogicalId(),
                        Boolean.TRUE.equals(meta.getExternal()) ? meta.getPath() : null)
                : "";
        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize artifact meta for touch " + meta.getArtifactId(), e);
        }
        Long status = redisTemplate.execute(TOUCH_SCRIPT,
                List.of(key(meta.getArtifactId()), runListKey(runId), runIdentityKey(runId), runSeqKey(runId)),
                metaJson, String.valueOf(TimeUnit.HOURS.toSeconds(ttlHours)), field, meta.getArtifactId());
        if (status == null || status == 0L) {
            throw new IllegalArgumentException("Artifact not found: " + meta.getArtifactId());
        }
        if (status == 2L) {
            throw new IllegalStateException(
                    "Artifact identity slot occupied by another winner: " + meta.getArtifactId());
        }
        if (status != 1L) {
            throw new IllegalStateException(
                    "Unexpected touch status " + status + " for artifact " + meta.getArtifactId());
        }
    }

    /**
     * cleanup 脚本（{@link #CLEANUP_META_SCRIPT}）返回 1 之后的收尾：meta 键已被脚本
     * 原子 DEL，这里只收文件与索引痕迹。文件删除规则与原 deleteMetaAndFile 完全一致：
     * external 制品仅在 cleanupPath=true 且路径是 symlink 时删路径（绝不触碰底层文件），
     * 内容制品只删 artifactRoot 内的自有文件。
     */
    private void deleteFileAndIndices(PersistentArtifactMeta meta) {
        if (meta == null || !hasText(meta.getArtifactId())) {
            return;
        }
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
            redisTemplate.opsForZSet().remove(runListKey(runId), meta.getArtifactId());
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

    private static String runSeqKey(String runId) {
        return RUN_SEQ_KEY_PREFIX + runId;
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
