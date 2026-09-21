package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

/**
 * 双池调度的参数从哪来、现在是多少。
 *
 * <p>取值分三层，前面的层有效就用前面的层：<b>热配置</b>（Nacos 的 {@code agent-llm}，泳道覆盖只影响该泳道）、
 * <b>环境属性</b>（部署时写在容器环境里）、<b>代码默认</b>。每个值都记下「最终用了什么、是从哪一层来的、
 * 前面有没有哪一层的值因为不合法被丢掉」——泳道里配错一个值不该悄悄变成别的数，也不该把调度停住，
 * 所以做法是「跳过它、记下原因、用下一层」，读数里能直接看到这件事，而且每一层被丢掉的原因都留着，
 * 后一层的问题不会把前一层的原因盖掉。</p>
 *
 * <p>一次解析只取一份热配置快照（{@link Resolver}）：高低水位、退避初值与上限这种成对的参数不会跨两个
 * 热配置版本拼接。一个调度回合与一次健康读数各自解析一份 {@link RoundSettings}，两者都不半途重读。</p>
 *
 * <p>哪些能边跑边改、改了影响谁，逐个说清：<b>按回合读</b>的（恢复批次、扫描配额、提醒容量、退避初值
 * 与上限、成员结果接收的批次与退避）改完下一轮生效；<b>只影响新事的</b>（每回合新增节点上限、全局高低
 * 水位、每 Run 未完成上限、等待组成员上限）改完只作用于之后新建的节点或成员，已经在跑的那些不受影响；
 * <b>创建时定死的</b>（新 Run 的调度器版本）只在创建 Run 那一刻读一次并写进数据库，之后这条 Run 永远按
 * 它自己的版本跑。最后一类只能在启动时生效（线程池大小、许可上限、队列容量、扫描间隔、租约时长、
 * 线程名前缀、启动补扫页数），改了要重启：读数里报的是各组件构造完之后真正在用的值，不是后来从属性源
 * 读回来的请求值（见 {@link FrozenEffectiveSettings}）。</p>
 */
@Component
public class DualPoolSchedulerSettings {

    public static final String SOURCE_HOT_CONFIG = "hot_config";
    public static final String SOURCE_PROPERTY = "property";
    public static final String SOURCE_DEFAULT = "default";
    /** 三层都拿不出一个成立的组合时用的保守回退：值来自别的参数，来源如实标成这一种。 */
    public static final String SOURCE_FALLBACK = "fallback";

    public static final String KEY_NEW_RUN_SCHEDULER_VERSION = "agent.langchain.dual-pool.new-run-scheduler-version";
    public static final String KEY_PER_TURN_NEW_NODE_LIMIT = "agent.langchain.dual-pool.per-turn-new-node-limit";
    public static final String KEY_PER_RUN_UNFINISHED_LIMIT = "agent.langchain.dual-pool.per-run-unfinished-limit";
    public static final String KEY_GLOBAL_HIGH_WATERMARK = "agent.langchain.dual-pool.global-unfinished-high-watermark";
    public static final String KEY_GLOBAL_LOW_WATERMARK = "agent.langchain.dual-pool.global-unfinished-low-watermark";
    public static final String KEY_WAIT_GROUP_MAX_MEMBERS = "agent.langchain.dual-pool.wait-group.max-members";
    public static final String KEY_RECOVERY_BATCH_SIZE = "agent.langchain.dual-pool.recovery.batch-size";
    public static final String KEY_RECOVERY_SCAN_QUOTA = "agent.langchain.dual-pool.recovery.scan-quota";
    public static final String KEY_RECOVERY_WAKEUP_CAPACITY = "agent.langchain.dual-pool.recovery.wakeup-capacity";
    public static final String KEY_RECOVERY_STARTUP_PAGES = "agent.langchain.dual-pool.recovery.startup-pages";
    public static final String KEY_RECOVERY_BACKOFF_BASE_MS = "agent.langchain.dual-pool.recovery.backoff-base-ms";
    public static final String KEY_RECOVERY_BACKOFF_MAX_MS = "agent.langchain.dual-pool.recovery.backoff-max-ms";
    public static final String KEY_MEMBER_RECEIVER_BATCH_SIZE = "agent.langchain.wait-member.receiver.batch-size";
    public static final String KEY_MEMBER_RECEIVER_BACKOFF_BASE_MS = "agent.langchain.wait-member.receiver.backoff-base-ms";
    public static final String KEY_MEMBER_RECEIVER_BACKOFF_MAX_MS = "agent.langchain.wait-member.receiver.backoff-max-ms";
    public static final String KEY_MEMBER_RECEIVER_MAX_BACKOFF_STEP = "agent.langchain.wait-member.receiver.max-backoff-step";

    /** 启动时定死的那些参数：只在启动时读一次，组件构造完之后就固定了。 */
    public static final String KEY_BUSINESS_ADMISSION_LIMIT = "agent.langchain.dual-pool.business-admission-limit";
    public static final String KEY_HINT_DRAIN_INTERVAL_MS = "agent.langchain.dual-pool.hint-drain-interval-ms";
    public static final String KEY_COORDINATION_DEFER_RETRY_MS = "agent.langchain.dual-pool.coordination-defer-retry-ms";
    public static final String KEY_HINT_QUEUE_FULL_RETRY_MS = "agent.langchain.dual-pool.hint-queue-full-retry-ms";
    public static final String KEY_RUN_WORKER_CORE_POOL_SIZE = "agent.langchain.dual-pool.run-worker.core-pool-size";
    public static final String KEY_RUN_WORKER_MAX_POOL_SIZE = "agent.langchain.dual-pool.run-worker.max-pool-size";
    public static final String KEY_RUN_WORKER_KEEP_ALIVE_SECONDS = "agent.langchain.dual-pool.run-worker.keep-alive-seconds";
    public static final String KEY_RUN_WORKER_THREAD_NAME_PREFIX = "agent.langchain.dual-pool.run-worker.thread-name-prefix";
    public static final String KEY_RUN_WORKER_HINT_CAPACITY = "agent.langchain.dual-pool.run-worker.hint-capacity";
    public static final String KEY_RUN_WORKER_SUBMIT_BUDGET = "agent.langchain.dual-pool.run-worker.submit-budget";
    public static final String KEY_RUN_WORKER_PERMIT_LIMIT = "agent.langchain.dual-pool.run-worker.permit-limit";
    public static final String KEY_NODE_WORKER_CORE_POOL_SIZE = "agent.langchain.dual-pool.node-worker.core-pool-size";
    public static final String KEY_NODE_WORKER_MAX_POOL_SIZE = "agent.langchain.dual-pool.node-worker.max-pool-size";
    public static final String KEY_NODE_WORKER_KEEP_ALIVE_SECONDS = "agent.langchain.dual-pool.node-worker.keep-alive-seconds";
    public static final String KEY_NODE_WORKER_THREAD_NAME_PREFIX = "agent.langchain.dual-pool.node-worker.thread-name-prefix";
    public static final String KEY_NODE_WORKER_HINT_CAPACITY = "agent.langchain.dual-pool.node-worker.hint-capacity";
    public static final String KEY_NODE_WORKER_SUBMIT_BUDGET = "agent.langchain.dual-pool.node-worker.submit-budget";
    public static final String KEY_NODE_WORKER_PERMIT_LIMIT = "agent.langchain.dual-pool.node-worker.permit-limit";
    public static final String KEY_NODE_WORKER_CLAIM_LEASE_SECONDS = "agent.langchain.dual-pool.node-worker.claim-lease-seconds";
    public static final String KEY_SCAN_INTERVAL_MS = "agent.langchain.dual-pool.scan.interval-ms";
    public static final String KEY_RECOVERY_SCAN_INTERVAL_MS = "agent.langchain.dual-pool.recovery.scan-interval-ms";
    public static final String KEY_SCAN_BATCH_SIZE = "agent.langchain.dual-pool.scan.batch-size";
    public static final String KEY_SERVICE_LEASE_TTL_SECONDS = "agent.langchain.dual-pool.service-lease-ttl-seconds";
    public static final String KEY_SERVICE_LEASE_RENEW_INTERVAL_MS = "agent.langchain.dual-pool.service-lease-renew-interval-ms";
    public static final String KEY_SERVICE_LEASE_OWNED_LIMIT = "agent.langchain.dual-pool.service-lease-owned-limit";
    public static final String KEY_WAIT_GROUP_MAX_MEMBER_RESULT_CHARS = "agent.langchain.dual-pool.wait-group.max-member-result-chars";
    public static final String KEY_WAIT_GROUP_MEMBER_POLL_DELAY_MS = "agent.langchain.dual-pool.wait-group.member-poll-delay-ms";
    public static final String KEY_MEMBER_RECEIVER_POLL_INTERVAL_MS = "agent.langchain.wait-member.receiver.poll-interval-ms";

    /** 演练开关：只在版本来自代码默认时把它推进双池，显式配置的版本不被它改写。 */
    public static final String KEY_HALT_DRILL = "agent.tool-job.fault-injection.allow-process-halt";

    private static final String DUAL_POOL = "agent.langchain.dual-pool.";
    private static final String MEMBER_RECEIVER = "agent.langchain.wait-member.receiver.";

    /**
     * 一个生效值：值、来源、「不重启能不能改」，以及各层被丢掉的原因（没有就是 null）。
     *
     * @param value       最终生效的值。启动冻结类参数这里是组件真正在用的那个数
     * @param requested   请求值：属性源上写着的那个数。没有「请求值与生效值」这一层的参数是 null
     * @param inUseBy     启动冻结类参数：每个消费者此刻在用的值（谁在用 → 值）。别的参数是 null
     */
    public record Setting(Object value, String source, boolean hotChangeable, String rejection,
                          Object requested, Map<String, Object> inUseBy) {

        /** 只有一个值可说的那些参数：请求值与生效值本来就该是同一个数。 */
        public Setting(Object value, String source, boolean hotChangeable, String rejection) {
            this(value, source, hotChangeable, rejection, null, null);
        }

        public int intValue() {
            return ((Number) value).intValue();
        }

        public long longValue() {
            return ((Number) value).longValue();
        }

        public String textValue() {
            return value == null ? null : value.toString();
        }
    }

    /**
     * 一个回合（或一次读数）用的一整套参数：一次解析、一个不变的结果。
     *
     * <p>调用方只取一次，整回合都从这一份里读。成对的参数（高低水位、退避初值与上限）就是这么保证
     * 不跨热配置版本的：它们在同一次解析里读同一份热配置快照。</p>
     */
    public record RoundSettings(
            Setting perTurnNewNodeLimit,
            Setting perRunUnfinishedLimit,
            Setting globalUnfinishedHighWatermark,
            Setting globalUnfinishedLowWatermark,
            Setting waitGroupMaxMembers,
            Setting recoveryBatchSize,
            Setting recoveryScanQuota,
            Setting recoveryWakeupCapacity,
            Setting recoveryStartupPages,
            Setting recoveryBackoffBaseMs,
            Setting recoveryBackoffMaxMs,
            Setting memberReceiverBatchSize,
            Setting memberReceiverBackoffBaseMs,
            Setting memberReceiverBackoffMaxMs,
            Setting memberReceiverMaxBackoffStep) {
    }

    private final AgentLlmLocalConfigLoader hotConfig;
    private final Environment environment;
    private final FrozenEffectiveSettings frozenEffective;

    /** 生产装配：由 Spring 注入。 */
    @Autowired
    public DualPoolSchedulerSettings(AgentLlmLocalConfigLoader hotConfig, Environment environment,
                                     FrozenEffectiveSettings frozenEffective) {
        this.hotConfig = hotConfig;
        this.environment = environment;
        this.frozenEffective = frozenEffective;
    }

    /** 窄用例用：没有组件登记生效值时，启动冻结类参数只能报请求值。 */
    public DualPoolSchedulerSettings(AgentLlmLocalConfigLoader hotConfig, Environment environment) {
        this(hotConfig, environment, new FrozenEffectiveSettings());
    }

    /**
     * 新 Run 用哪个调度器版本：读到的配置值，加上演练开关之后的实际生效值。
     *
     * <p>与其余参数不同，这里认不出的值**不往下一层落**：一个写错的版本名如果悄悄回落到旧版本，
     * 看到的现象是「配了新版本，跑的还是旧版本」，那比明确报错更难查。所以生效值仍然是「配置了什么
     * 就是什么」，认不出时由版本解析那一处对新 Run 失败关闭；读数里会写明这个值不被认识。</p>
     *
     * <p>进程终止演练开关只覆盖**代码默认值**：只有一层都没配、停在内置的 LEGACY 上时，它才把新 Run
     * 推进双池 V1。热配置或环境属性里显式写下的版本（包括显式写 LEGACY）是操作人的选择，不被它改写——
     * 否则泳道没法用配置切回旧入口，读数里显示的版本也对不上新 Run 真正落库的那个。</p>
     */
    public Setting newRunSchedulerVersion() {
        Resolver resolver = resolver();
        Setting configured = resolver.text(KEY_NEW_RUN_SCHEDULER_VERSION,
                AgentLlmProperties.Scheduler::getNewRunSchedulerVersion, SchedulerVersion.LEGACY.name());
        String raw = configured.textValue();
        if (!knownVersion(raw)) {
            return new Setting(raw, configured.source(), true,
                    "认不出的调度器版本，新 Run 会被拒绝：" + raw);
        }
        if (!SchedulerVersion.LEGACY.name().equals(raw)
                || !SOURCE_DEFAULT.equals(configured.source())
                || !haltDrillOn()) {
            return configured;
        }
        return new Setting(SchedulerVersion.DUAL_POOL_V1.name(), configured.source(), true,
                "没有配置新 Run 的调度器版本，进程终止演练开关把默认值改成了 "
                        + SchedulerVersion.DUAL_POOL_V1.name(),
                raw, null);
    }

    /** 进程终止演练开关：只有隔离的长工具验收泳道会显式打开它。 */
    private boolean haltDrillOn() {
        return environment.getProperty(KEY_HALT_DRILL, Boolean.class, false);
    }

    /** 这个版本名是不是已知的四个取值之一；只做判断，不抛异常（读数要能把错的也读出来）。 */
    private static boolean knownVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String trimmed = raw.strip();
        for (SchedulerVersion version : SchedulerVersion.values()) {
            if (version.name().equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** 一个协调回合最多新增几个节点；只影响之后新建的节点。 */
    public Setting perTurnNewNodeLimit() {
        return round().perTurnNewNodeLimit();
    }

    /** 每个 Run 同时存在的未完成工作项上限；只影响之后新建的工作项。 */
    public Setting perRunUnfinishedLimit() {
        return round().perRunUnfinishedLimit();
    }

    /** 全局未完成工作项的高水位：到了这里就暂停新增，只影响之后新建的节点。 */
    public Setting globalUnfinishedHighWatermark() {
        return round().globalUnfinishedHighWatermark();
    }

    /** 全局未完成工作项的低水位：暂停之后要降到这儿才恢复新增。 */
    public Setting globalUnfinishedLowWatermark() {
        return round().globalUnfinishedLowWatermark();
    }

    /** 一个等待组最多几个成员；只影响之后新建的等待组。 */
    public Setting waitGroupMaxMembers() {
        return round().waitGroupMaxMembers();
    }

    /** 恢复分发一轮最多处理几条通知；按回合读取，改完下一轮生效。 */
    public Setting recoveryBatchSize() {
        return round().recoveryBatchSize();
    }

    /** 每一轮至少留给数据库补扫的名额；按回合读取，且不会超过这一轮的总上限。 */
    public Setting recoveryScanQuota() {
        return round().recoveryScanQuota();
    }

    /** 内存提醒最多压几条；按回合读取，改完影响之后的入队。 */
    public Setting recoveryWakeupCapacity() {
        return round().recoveryWakeupCapacity();
    }

    /** 启动时最多翻几页；只在启动补扫那一刻读一次，改了要重启。 */
    public Setting recoveryStartupPages() {
        return round().recoveryStartupPages();
    }

    /** 恢复退避的初值。 */
    public Setting recoveryBackoffBaseMs() {
        return round().recoveryBackoffBaseMs();
    }

    /** 恢复退避的上限。 */
    public Setting recoveryBackoffMaxMs() {
        return round().recoveryBackoffMaxMs();
    }

    /** 成员结果接收一轮最多取几条；按回合读取。 */
    public Setting memberReceiverBatchSize() {
        return round().memberReceiverBatchSize();
    }

    public Setting memberReceiverBackoffBaseMs() {
        return round().memberReceiverBackoffBaseMs();
    }

    /** 成员结果接收的退避上限。 */
    public Setting memberReceiverBackoffMaxMs() {
        return round().memberReceiverBackoffMaxMs();
    }

    /** 成员结果接收的退避最多翻几步。 */
    public Setting memberReceiverMaxBackoffStep() {
        return round().memberReceiverMaxBackoffStep();
    }

    /**
     * 一个回合用的整套参数：这里解析一次，这一回合都用它。
     *
     * <p>成对的参数在这一份里一起算出来，所以不会出现「高水位来自新版本、低水位来自旧版本」这种
     * 谁都没配过的组合。</p>
     */
    public RoundSettings round() {
        Resolver resolver = resolver();
        Setting high = resolver.integer(KEY_GLOBAL_HIGH_WATERMARK,
                AgentLlmProperties.Scheduler::getGlobalUnfinishedHighWatermark,
                value -> value >= 0, 128, "不能是负数", true);
        Setting base = resolver.longValue(KEY_RECOVERY_BACKOFF_BASE_MS,
                AgentLlmProperties.Scheduler::getRecoveryBackoffBaseMs,
                value -> value >= 1L, 500L, "必须是大于 0 的毫秒数", true);
        Setting memberBase = resolver.longValue(KEY_MEMBER_RECEIVER_BACKOFF_BASE_MS,
                AgentLlmProperties.Scheduler::getMemberReceiverBackoffBaseMs,
                value -> value >= 1L, 1000L, "必须是大于 0 的毫秒数", true);
        return new RoundSettings(
                resolver.integer(KEY_PER_TURN_NEW_NODE_LIMIT,
                        AgentLlmProperties.Scheduler::getPerTurnNewNodeLimit,
                        value -> value >= 1, 8, "必须是大于 0 的整数", true),
                resolver.integer(KEY_PER_RUN_UNFINISHED_LIMIT,
                        AgentLlmProperties.Scheduler::getPerRunUnfinishedLimit,
                        value -> value >= 1, 256, "必须是大于 0 的整数", true),
                high,
                lowWatermark(resolver, high),
                resolver.integer(KEY_WAIT_GROUP_MAX_MEMBERS,
                        AgentLlmProperties.Scheduler::getWaitGroupMaxMembers,
                        value -> value >= 1, 16, "必须是大于 0 的整数", true),
                resolver.integer(KEY_RECOVERY_BATCH_SIZE,
                        AgentLlmProperties.Scheduler::getRecoveryBatchSize,
                        value -> value >= 1, 8, "必须是大于 0 的整数", true),
                resolver.integer(KEY_RECOVERY_SCAN_QUOTA,
                        AgentLlmProperties.Scheduler::getRecoveryScanQuota,
                        value -> value >= 1, 4, "必须是大于 0 的整数", true),
                resolver.integer(KEY_RECOVERY_WAKEUP_CAPACITY,
                        AgentLlmProperties.Scheduler::getRecoveryWakeupCapacity,
                        value -> value >= 1, 1024, "必须是大于 0 的整数", true),
                // 启动补扫只在应用起来那一刻读一次：运行期改它不会触发新的补扫，所以标成要重启。
                resolver.integer(KEY_RECOVERY_STARTUP_PAGES,
                        AgentLlmProperties.Scheduler::getRecoveryStartupPages,
                        value -> value >= 1, 8, "必须是大于 0 的整数", false),
                base,
                ceiling(resolver, KEY_RECOVERY_BACKOFF_MAX_MS,
                        AgentLlmProperties.Scheduler::getRecoveryBackoffMaxMs, base, 5000L),
                resolver.integer(KEY_MEMBER_RECEIVER_BATCH_SIZE,
                        AgentLlmProperties.Scheduler::getMemberReceiverBatchSize,
                        value -> value >= 1, 8, "必须是大于 0 的整数", true),
                memberBase,
                ceiling(resolver, KEY_MEMBER_RECEIVER_BACKOFF_MAX_MS,
                        AgentLlmProperties.Scheduler::getMemberReceiverBackoffMaxMs, memberBase, 15000L),
                resolver.integer(KEY_MEMBER_RECEIVER_MAX_BACKOFF_STEP,
                        AgentLlmProperties.Scheduler::getMemberReceiverMaxBackoffStep,
                        value -> value >= 1, 6, "必须是大于 0 的整数", true));
    }

    /**
     * 低水位逐层找：某一层的值不高于高水位，这一层才算成立。
     *
     * <p>组合不成立时**跳到下一层**，而不是把这一层的值钳到高水位、来源标签还留着这一层：那样读数
     * 会报出一个这一层根本没有的值，操作人按它判断「改生效了」会判断错。三层都不成立时才回落到高
     * 水位本身（含义是「降到高水位才恢复」），来源如实标成保守回退，并把每一层为什么不成立都写出来。</p>
     */
    private Setting lowWatermark(Resolver resolver, Setting high) {
        List<String> rejections = new ArrayList<>();
        int ceiling = high.intValue();
        Integer hot = resolver.hotValue(AgentLlmProperties.Scheduler::getGlobalUnfinishedLowWatermark);
        if (hot != null) {
            if (hot >= 0 && hot <= ceiling) {
                return new Setting(hot, SOURCE_HOT_CONFIG, true, join(rejections));
            }
            rejections.add("热配置的低水位不合法（" + lowRequirement(ceiling) + "）：" + hot);
        }
        String configured = property(KEY_GLOBAL_LOW_WATERMARK);
        if (configured != null && !configured.isBlank()) {
            Integer parsed = parseInteger(configured);
            if (parsed != null && parsed >= 0 && parsed <= ceiling) {
                return new Setting(parsed, SOURCE_PROPERTY, true, join(rejections));
            }
            rejections.add("环境属性的低水位不合法（" + lowRequirement(ceiling) + "）：" + configured);
        }
        int fallback = 96;
        if (fallback <= ceiling) {
            return new Setting(fallback, SOURCE_DEFAULT, true, join(rejections));
        }
        rejections.add("三层都没有成立的组合（代码默认的低水位 " + fallback + " 高于高水位 " + ceiling + "）");
        return new Setting(ceiling, SOURCE_FALLBACK, true, join(rejections));
    }

    private static String lowRequirement(int ceiling) {
        return "不能是负数、也不能高于高水位 " + ceiling;
    }

    /**
     * 上限逐层找：某一层的值不小于初值，这一层才算成立；没有一层成立就用初值兜底。
     *
     * <p>回落到初值时来源标成保守回退，不标成那个被丢掉的值所在的层——初值不是那一层给出的。</p>
     */
    private Setting ceiling(Resolver resolver, String key,
                            Function<AgentLlmProperties.Scheduler, Long> hotField,
                            Setting floor, long fallback) {
        List<String> rejections = new ArrayList<>();
        long floorValue = floor.longValue();
        Long hot = resolver.hotValue(hotField);
        if (hot != null) {
            if (hot >= 1L && hot >= floorValue) {
                return new Setting(hot, SOURCE_HOT_CONFIG, true, join(rejections));
            }
            rejections.add("热配置的值不合法（必须不小于退避初值 " + floorValue + "）：" + hot);
        }
        String configured = property(key);
        if (configured != null && !configured.isBlank()) {
            Long parsed = parseLong(configured);
            if (parsed != null && parsed >= 1L && parsed >= floorValue) {
                return new Setting(parsed, SOURCE_PROPERTY, true, join(rejections));
            }
            rejections.add("环境属性的值不合法（必须不小于退避初值 " + floorValue + "）：" + configured);
        }
        if (fallback >= floorValue) {
            return new Setting(fallback, SOURCE_DEFAULT, true, join(rejections));
        }
        rejections.add("三层都没有成立的上限（代码默认的 " + fallback
                + " 小于退避初值 " + floorValue + "，初值来自 " + floor.source() + "）");
        return new Setting(floorValue, SOURCE_FALLBACK, true, join(rejections));
    }

    private static String join(List<String> rejections) {
        return rejections.isEmpty() ? null : String.join("；", rejections);
    }

    /**
     * 全部参数的当前值：键就是 Spring 属性名，值是「值 + 来源 + 改了要不要重启 + 被丢掉的原因 + 请求值
     * 与消费者在用的值」。
     *
     * <p>键用属性名而不是另起一套名字，是为了让这份读数能与 yml、环境变量、{@code @Value} 逐一对照，
     * 换个名字对读者等于多一层翻译。这份读数必须覆盖全部双池参数：有测试按源码里出现的属性名逐条
     * 核对，漏掉一个就会失败。</p>
     */
    public Map<String, Setting> snapshot() {
        Resolver resolver = resolver();
        RoundSettings round = round();
        Setting version = newRunSchedulerVersion();
        Map<String, Setting> snapshot = new LinkedHashMap<>();
        snapshot.put(KEY_NEW_RUN_SCHEDULER_VERSION, version);
        snapshot.put(KEY_PER_TURN_NEW_NODE_LIMIT, round.perTurnNewNodeLimit());
        snapshot.put(KEY_PER_RUN_UNFINISHED_LIMIT, round.perRunUnfinishedLimit());
        snapshot.put(KEY_GLOBAL_HIGH_WATERMARK, round.globalUnfinishedHighWatermark());
        snapshot.put(KEY_GLOBAL_LOW_WATERMARK, round.globalUnfinishedLowWatermark());
        snapshot.put(KEY_WAIT_GROUP_MAX_MEMBERS, round.waitGroupMaxMembers());
        snapshot.put(KEY_RECOVERY_BATCH_SIZE, round.recoveryBatchSize());
        snapshot.put(KEY_RECOVERY_SCAN_QUOTA, round.recoveryScanQuota());
        snapshot.put(KEY_RECOVERY_WAKEUP_CAPACITY, round.recoveryWakeupCapacity());
        snapshot.put(KEY_RECOVERY_STARTUP_PAGES, round.recoveryStartupPages());
        snapshot.put(KEY_RECOVERY_BACKOFF_BASE_MS, round.recoveryBackoffBaseMs());
        snapshot.put(KEY_RECOVERY_BACKOFF_MAX_MS, round.recoveryBackoffMaxMs());
        snapshot.put(KEY_MEMBER_RECEIVER_BATCH_SIZE, round.memberReceiverBatchSize());
        snapshot.put(KEY_MEMBER_RECEIVER_BACKOFF_BASE_MS, round.memberReceiverBackoffBaseMs());
        snapshot.put(KEY_MEMBER_RECEIVER_BACKOFF_MAX_MS, round.memberReceiverBackoffMaxMs());
        snapshot.put(KEY_MEMBER_RECEIVER_MAX_BACKOFF_STEP, round.memberReceiverMaxBackoffStep());

        // 以下这些只能在启动时生效：写进线程池、许可台账、队列与定时任务之后就不再变。
        // 报的是各组件构造完之后真正在用的值（登记见 FrozenEffectiveSettings），请求值单独留一份：
        // 属性源在启动之后变过、或者组件做了归一化时，两个数不一样，读数要能看出来。
        snapshot.put(KEY_BUSINESS_ADMISSION_LIMIT, frozen(resolver, KEY_BUSINESS_ADMISSION_LIMIT, 100));
        snapshot.put(KEY_HINT_DRAIN_INTERVAL_MS, frozen(resolver, KEY_HINT_DRAIN_INTERVAL_MS, 50));
        snapshot.put(KEY_COORDINATION_DEFER_RETRY_MS, frozen(resolver, KEY_COORDINATION_DEFER_RETRY_MS, 1000));
        snapshot.put(KEY_HINT_QUEUE_FULL_RETRY_MS, frozen(resolver, KEY_HINT_QUEUE_FULL_RETRY_MS, 5000));
        snapshot.put(KEY_RUN_WORKER_CORE_POOL_SIZE, frozen(resolver, KEY_RUN_WORKER_CORE_POOL_SIZE, 2));
        snapshot.put(KEY_RUN_WORKER_MAX_POOL_SIZE, frozen(resolver, KEY_RUN_WORKER_MAX_POOL_SIZE, 2));
        snapshot.put(KEY_RUN_WORKER_KEEP_ALIVE_SECONDS, frozen(resolver, KEY_RUN_WORKER_KEEP_ALIVE_SECONDS, 60));
        snapshot.put(KEY_RUN_WORKER_THREAD_NAME_PREFIX,
                frozen(resolver, KEY_RUN_WORKER_THREAD_NAME_PREFIX, "agent-run-coordination-"));
        snapshot.put(KEY_RUN_WORKER_HINT_CAPACITY, frozen(resolver, KEY_RUN_WORKER_HINT_CAPACITY, 256));
        snapshot.put(KEY_RUN_WORKER_SUBMIT_BUDGET, frozen(resolver, KEY_RUN_WORKER_SUBMIT_BUDGET, 32));
        snapshot.put(KEY_RUN_WORKER_PERMIT_LIMIT, frozen(resolver, KEY_RUN_WORKER_PERMIT_LIMIT, 2));
        snapshot.put(KEY_NODE_WORKER_CORE_POOL_SIZE, frozen(resolver, KEY_NODE_WORKER_CORE_POOL_SIZE, 4));
        snapshot.put(KEY_NODE_WORKER_MAX_POOL_SIZE, frozen(resolver, KEY_NODE_WORKER_MAX_POOL_SIZE, 4));
        snapshot.put(KEY_NODE_WORKER_KEEP_ALIVE_SECONDS, frozen(resolver, KEY_NODE_WORKER_KEEP_ALIVE_SECONDS, 60));
        snapshot.put(KEY_NODE_WORKER_THREAD_NAME_PREFIX,
                frozen(resolver, KEY_NODE_WORKER_THREAD_NAME_PREFIX, "agent-node-"));
        snapshot.put(KEY_NODE_WORKER_HINT_CAPACITY, frozen(resolver, KEY_NODE_WORKER_HINT_CAPACITY, 1024));
        snapshot.put(KEY_NODE_WORKER_SUBMIT_BUDGET, frozen(resolver, KEY_NODE_WORKER_SUBMIT_BUDGET, 64));
        snapshot.put(KEY_NODE_WORKER_PERMIT_LIMIT, frozen(resolver, KEY_NODE_WORKER_PERMIT_LIMIT, 4));
        snapshot.put(KEY_NODE_WORKER_CLAIM_LEASE_SECONDS, frozen(resolver, KEY_NODE_WORKER_CLAIM_LEASE_SECONDS, 300));
        snapshot.put(KEY_SCAN_INTERVAL_MS, frozen(resolver, KEY_SCAN_INTERVAL_MS, 1000));
        snapshot.put(KEY_RECOVERY_SCAN_INTERVAL_MS, frozen(resolver, KEY_RECOVERY_SCAN_INTERVAL_MS, 1000));
        snapshot.put(KEY_SCAN_BATCH_SIZE, frozen(resolver, KEY_SCAN_BATCH_SIZE, 100));
        snapshot.put(KEY_SERVICE_LEASE_TTL_SECONDS, frozen(resolver, KEY_SERVICE_LEASE_TTL_SECONDS, 120));
        snapshot.put(KEY_SERVICE_LEASE_RENEW_INTERVAL_MS,
                frozen(resolver, KEY_SERVICE_LEASE_RENEW_INTERVAL_MS, 40000));
        snapshot.put(KEY_SERVICE_LEASE_OWNED_LIMIT, frozen(resolver, KEY_SERVICE_LEASE_OWNED_LIMIT, 512));
        snapshot.put(KEY_WAIT_GROUP_MAX_MEMBER_RESULT_CHARS,
                frozen(resolver, KEY_WAIT_GROUP_MAX_MEMBER_RESULT_CHARS, 1048576));
        snapshot.put(KEY_WAIT_GROUP_MEMBER_POLL_DELAY_MS,
                frozen(resolver, KEY_WAIT_GROUP_MEMBER_POLL_DELAY_MS, 2000));
        snapshot.put(KEY_MEMBER_RECEIVER_POLL_INTERVAL_MS, frozen(resolver, KEY_MEMBER_RECEIVER_POLL_INTERVAL_MS, 1000));
        return snapshot;
    }

    /**
     * 启动冻结类参数：值取消费者登记的那一份，请求值另外附上。
     *
     * <p>只有一个消费者时值就是一个数；同一个参数被几个消费者读、用的数还不一样时（租约时长这种），
     * 值是一张「谁在用 → 用多少」的表，不替它们挑一个。</p>
     */
    private Setting frozen(Resolver resolver, String key, Object fallback) {
        Setting requested = resolver.tolerateAnything(key, fallback);
        Map<String, Object> inUseBy = frozenEffective.inUseBy(key);
        if (inUseBy.isEmpty()) {
            // 没有组件登记：这一项要么不在生产路径上，要么登记漏了。报请求值并写明它只是请求值，
            // 别让它看起来像是「在用的值」。
            return new Setting(requested.value(), requested.source(), false,
                    append(requested.rejection(), "没有组件登记它在用的值，这一行报的是请求值"), null, null);
        }
        Object effective = inUseBy.size() == 1 ? inUseBy.values().iterator().next() : inUseBy;
        String rejection = requested.value() != null && !requested.value().equals(effective)
                ? append(requested.rejection(), "请求值与实际生效值不同（请求 " + requested.value()
                        + "，实际 " + effective + "）：启动时归一化的结果，或者属性源在启动之后变过")
                : requested.rejection();
        return new Setting(effective, requested.source(), false, rejection, requested.value(), inUseBy);
    }

    private static String append(String existing, String extra) {
        return existing == null || existing.isBlank() ? extra : existing + "；" + extra;
    }

    private Resolver resolver() {
        return new Resolver(hotConfig == null ? Optional.empty() : hotConfig.current());
    }

    private String property(String key) {
        return environment.getProperty(key);
    }

    private static Integer parseInteger(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Long parseLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * 一次解析：拿着同一条热配置快照按三层取值，这一份解析里所有参数看到的是同一个版本的热配置。
     */
    private final class Resolver {

        private final Optional<AgentLlmProperties> hot;

        private Resolver(Optional<AgentLlmProperties> hot) {
            this.hot = hot;
        }

        /** 热配置里那一段；这一层没配或者读不出来时返回 null，调用方按「没配」处理。 */
        <T> T hotValue(Function<AgentLlmProperties.Scheduler, T> field) {
            return hot.map(AgentLlmProperties::getRuntime)
                    .map(AgentLlmProperties.Runtime::getScheduler)
                    .map(field)
                    .orElse(null);
        }

        Setting text(String key, Function<AgentLlmProperties.Scheduler, String> hotField, String fallback) {
            String hotText = hotValue(hotField);
            if (hotText != null && !hotText.isBlank()) {
                return new Setting(hotText.trim(), SOURCE_HOT_CONFIG, true, null);
            }
            String configured = property(key);
            if (configured != null && !configured.isBlank()) {
                return new Setting(configured.trim(), SOURCE_PROPERTY, true, null);
            }
            return new Setting(fallback, SOURCE_DEFAULT, true, null);
        }

        Setting integer(String key, Function<AgentLlmProperties.Scheduler, Integer> hotField,
                        IntPredicate check, int fallback, String requirement, boolean hotChangeable) {
            List<String> rejections = new ArrayList<>();
            Integer hotNumber = hotValue(hotField);
            if (hotNumber != null) {
                if (check.test(hotNumber)) {
                    return new Setting(hotNumber, SOURCE_HOT_CONFIG, hotChangeable, join(rejections));
                }
                rejections.add("热配置的值不合法（" + requirement + "）：" + hotNumber);
            }
            String configured = property(key);
            if (configured != null && !configured.isBlank()) {
                Integer parsed = parseInteger(configured);
                if (parsed != null && check.test(parsed)) {
                    return new Setting(parsed, SOURCE_PROPERTY, hotChangeable, join(rejections));
                }
                rejections.add("环境属性的值不合法（" + requirement + "）：" + configured);
            }
            return new Setting(fallback, SOURCE_DEFAULT, hotChangeable, join(rejections));
        }

        Setting longValue(String key, Function<AgentLlmProperties.Scheduler, Long> hotField,
                          LongPredicate check, long fallback, String requirement, boolean hotChangeable) {
            List<String> rejections = new ArrayList<>();
            Long hotNumber = hotValue(hotField);
            if (hotNumber != null) {
                if (check.test(hotNumber)) {
                    return new Setting(hotNumber, SOURCE_HOT_CONFIG, hotChangeable, join(rejections));
                }
                rejections.add("热配置的值不合法（" + requirement + "）：" + hotNumber);
            }
            String configured = property(key);
            if (configured != null && !configured.isBlank()) {
                Long parsed = parseLong(configured);
                if (parsed != null && check.test(parsed)) {
                    return new Setting(parsed, SOURCE_PROPERTY, hotChangeable, join(rejections));
                }
                rejections.add("环境属性的值不合法（" + requirement + "）：" + configured);
            }
            return new Setting(fallback, SOURCE_DEFAULT, hotChangeable, join(rejections));
        }

        /** 启动冻结类参数的请求值：值照读，解析不了就用默认值并把原因写进读数。 */
        Setting tolerateAnything(String key, Object fallback) {
            String configured = property(key);
            if (configured == null) {
                return new Setting(fallback, SOURCE_DEFAULT, false, null);
            }
            if (fallback instanceof Integer) {
                Integer parsed = parseInteger(configured);
                return parsed == null
                        ? new Setting(fallback, SOURCE_DEFAULT, false, "环境属性的值不是整数：" + configured)
                        : new Setting(parsed, SOURCE_PROPERTY, false, null);
            }
            if (fallback instanceof Long) {
                Long parsed = parseLong(configured);
                return parsed == null
                        ? new Setting(fallback, SOURCE_DEFAULT, false, "环境属性的值不是整数：" + configured)
                        : new Setting(parsed, SOURCE_PROPERTY, false, null);
            }
            return new Setting(configured, SOURCE_PROPERTY, false, null);
        }
    }

}
