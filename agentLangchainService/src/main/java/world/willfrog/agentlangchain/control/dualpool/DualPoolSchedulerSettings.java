package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

/**
 * 双池调度的参数从哪来、现在是多少。
 *
 * <p>取值分三层，前面的层有效就用前面的层：<b>热配置</b>（Nacos 的 {@code agent-llm}，泳道覆盖只影响该泳道）、
 * <b>环境属性</b>（部署时写在容器环境里）、<b>代码默认</b>。每个值都记下「最终用了什么、是从哪一层来的、
 * 前面有没有哪一层的值因为不合法被丢掉」——泳道里配错一个值不该悄悄变成别的数，也不该把调度停住，
 * 所以做法是「跳过它、记下原因、用下一层」，读数里能直接看到这件事。</p>
 *
 * <p>读这些值不查库、不走网络：热配置是内存里的快照，环境属性是内存里的表；调用方每一轮取一次即可，
 * 不必在构造时缓存（构造时缓存的值改不掉，那正是这套参数以前改不动的原因）。</p>
 *
 * <p>哪些能边跑边改、改了影响谁，逐个说清：<b>按回合读</b>的（恢复批次、扫描配额、提醒容量、启动翻页、
 * 退避初值与上限、成员结果接收的批次与退避）改完下一轮生效；<b>只影响新事的</b>（每回合新增节点上限、
 * 全局高低水位、每 Run 未完成上限、等待组成员上限）改完只作用于之后新建的节点或成员，已经在跑的那些
 * 不受影响；<b>创建时定死的</b>（新 Run 的调度器版本）只在创建 Run 那一刻读一次并写进数据库，之后这条
 * Run 永远按它自己的版本跑。还有一类只能在启动时生效（线程池大小、许可上限、队列容量、扫描间隔、
 * 租约时长、线程名前缀），它们照样读得出来，但标明「改了要重启」。</p>
 */
@Component
public class DualPoolSchedulerSettings {

    public static final String SOURCE_HOT_CONFIG = "hot_config";
    public static final String SOURCE_PROPERTY = "property";
    public static final String SOURCE_DEFAULT = "default";

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

    private static final String DUAL_POOL = "agent.langchain.dual-pool.";
    private static final String MEMBER_RECEIVER = "agent.langchain.wait-member.receiver.";

    /**
     * 一个生效值：值、来源、「不重启能不能改」，以及前面各层被丢掉的原因（没有就是 null）。
     */
    public record Setting(Object value, String source, boolean hotChangeable, String rejection) {

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

    private final AgentLlmLocalConfigLoader hotConfig;
    private final Environment environment;

    public DualPoolSchedulerSettings(AgentLlmLocalConfigLoader hotConfig, Environment environment) {
        this.hotConfig = hotConfig;
        this.environment = environment;
    }

    /**
     * 新 Run 用哪个调度器版本。
     *
     * <p>与其余参数不同，这里认不出的值**不往下一层落**：一个写错的版本名如果悄悄回落到旧版本，
     * 看到的现象是「配了新版本，跑的还是旧版本」，那比明确报错更难查。所以生效值仍然是「配置了什么
     * 就是什么」，认不出时由版本解析那一处对新 Run 失败关闭；读数里会写明这个值不被认识。</p>
     */
    public Setting newRunSchedulerVersion() {
        Setting resolved = resolveText(KEY_NEW_RUN_SCHEDULER_VERSION, true,
                AgentLlmProperties.Scheduler::getNewRunSchedulerVersion,
                SchedulerVersion.LEGACY.name());
        String raw = resolved.textValue();
        if (!knownVersion(raw)) {
            return new Setting(raw, resolved.source(), true,
                    "认不出的调度器版本，新 Run 会被拒绝：" + raw);
        }
        return resolved;
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
        return resolveInt(KEY_PER_TURN_NEW_NODE_LIMIT, true,
                AgentLlmProperties.Scheduler::getPerTurnNewNodeLimit,
                value -> value >= 1, 8, "必须是大于 0 的整数");
    }

    /** 每个 Run 同时存在的未完成工作项上限；只影响之后新建的工作项。 */
    public Setting perRunUnfinishedLimit() {
        return resolveInt(KEY_PER_RUN_UNFINISHED_LIMIT, true,
                AgentLlmProperties.Scheduler::getPerRunUnfinishedLimit,
                value -> value >= 1, 256, "必须是大于 0 的整数");
    }

    /** 全局未完成工作项的高水位：到了这里就暂停新增，只影响之后新建的节点。 */
    public Setting globalUnfinishedHighWatermark() {
        return resolveInt(KEY_GLOBAL_HIGH_WATERMARK, true,
                AgentLlmProperties.Scheduler::getGlobalUnfinishedHighWatermark,
                value -> value >= 0, 128, "不能是负数");
    }

    /**
     * 全局未完成工作项的低水位：暂停之后要降到这儿才恢复新增。
     *
     * <p>低水位不能高于高水位，这个组合校验跨两个参数：组合不成立时丢掉低水位这一层的值、往下一层取，
     * 取不到就用高水位本身（含义是「降到高水位才恢复」），并把原因写进读数。既不静默改成 0，也不让
     * 调度停下来。</p>
     */
    public Setting globalUnfinishedLowWatermark() {
        int high = globalUnfinishedHighWatermark().intValue();
        Setting resolved = resolveInt(KEY_GLOBAL_LOW_WATERMARK, true,
                AgentLlmProperties.Scheduler::getGlobalUnfinishedLowWatermark,
                value -> value >= 0, 96, "不能是负数");
        if (resolved.intValue() <= high) {
            return resolved;
        }
        String rejection = "低水位高于高水位（" + resolved.intValue() + " > " + high + "），已改用高水位";
        return new Setting(high, resolved.source(), true, rejection);
    }

    /** 一个等待组最多几个成员；只影响之后新建的等待组。 */
    public Setting waitGroupMaxMembers() {
        return resolveInt(KEY_WAIT_GROUP_MAX_MEMBERS, true,
                AgentLlmProperties.Scheduler::getWaitGroupMaxMembers,
                value -> value >= 1, 16, "必须是大于 0 的整数");
    }

    /** 恢复分发一轮最多处理几条通知；按回合读取，改完下一轮生效。 */
    public Setting recoveryBatchSize() {
        return resolveInt(KEY_RECOVERY_BATCH_SIZE, true,
                AgentLlmProperties.Scheduler::getRecoveryBatchSize,
                value -> value >= 1, 8, "必须是大于 0 的整数");
    }

    /** 每一轮至少留给数据库补扫的名额；按回合读取，且不会超过这一轮的总上限。 */
    public Setting recoveryScanQuota() {
        return resolveInt(KEY_RECOVERY_SCAN_QUOTA, true,
                AgentLlmProperties.Scheduler::getRecoveryScanQuota,
                value -> value >= 1, 4, "必须是大于 0 的整数");
    }

    /** 内存提醒最多压几条；按回合读取，改完影响之后的入队。 */
    public Setting recoveryWakeupCapacity() {
        return resolveInt(KEY_RECOVERY_WAKEUP_CAPACITY, true,
                AgentLlmProperties.Scheduler::getRecoveryWakeupCapacity,
                value -> value >= 1, 1024, "必须是大于 0 的整数");
    }

    /** 启动时最多翻几页；启动那一刻读一次。 */
    public Setting recoveryStartupPages() {
        return resolveInt(KEY_RECOVERY_STARTUP_PAGES, true,
                AgentLlmProperties.Scheduler::getRecoveryStartupPages,
                value -> value >= 1, 8, "必须是大于 0 的整数");
    }

    /** 恢复退避的初值。 */
    public Setting recoveryBackoffBaseMs() {
        return resolveLong(KEY_RECOVERY_BACKOFF_BASE_MS, true,
                AgentLlmProperties.Scheduler::getRecoveryBackoffBaseMs,
                value -> value >= 1L, 500L, "必须是大于 0 的毫秒数");
    }

    /** 恢复退避的上限；小于初值时丢掉这一层的值，用初值并且把原因写进读数。 */
    public Setting recoveryBackoffMaxMs() {
        long base = recoveryBackoffBaseMs().longValue();
        Setting resolved = resolveLong(KEY_RECOVERY_BACKOFF_MAX_MS, true,
                AgentLlmProperties.Scheduler::getRecoveryBackoffMaxMs,
                value -> value >= base, 5000L, "必须不小于退避初值");
        if (resolved.longValue() >= base) {
            return resolved;
        }
        return new Setting(base, resolved.source(), true,
                "退避上限小于初值（" + resolved.longValue() + " < " + base + "），已改用初值");
    }

    /** 成员结果接收一轮最多取几条；按回合读取。 */
    public Setting memberReceiverBatchSize() {
        return resolveInt(KEY_MEMBER_RECEIVER_BATCH_SIZE, true,
                AgentLlmProperties.Scheduler::getMemberReceiverBatchSize,
                value -> value >= 1, 8, "必须是大于 0 的整数");
    }

    public Setting memberReceiverBackoffBaseMs() {
        return resolveLong(KEY_MEMBER_RECEIVER_BACKOFF_BASE_MS, true,
                AgentLlmProperties.Scheduler::getMemberReceiverBackoffBaseMs,
                value -> value >= 1L, 1000L, "必须是大于 0 的毫秒数");
    }

    /** 成员结果接收的退避上限；小于初值时同上面那条处理。 */
    public Setting memberReceiverBackoffMaxMs() {
        long base = memberReceiverBackoffBaseMs().longValue();
        Setting resolved = resolveLong(KEY_MEMBER_RECEIVER_BACKOFF_MAX_MS, true,
                AgentLlmProperties.Scheduler::getMemberReceiverBackoffMaxMs,
                value -> value >= base, 15000L, "必须不小于退避初值");
        if (resolved.longValue() >= base) {
            return resolved;
        }
        return new Setting(base, resolved.source(), true,
                "退避上限小于初值（" + resolved.longValue() + " < " + base + "），已改用初值");
    }

    /** 成员结果接收的退避最多翻几步。 */
    public Setting memberReceiverMaxBackoffStep() {
        return resolveInt(KEY_MEMBER_RECEIVER_MAX_BACKOFF_STEP, true,
                AgentLlmProperties.Scheduler::getMemberReceiverMaxBackoffStep,
                value -> value >= 1, 6, "必须是大于 0 的整数");
    }

    // ===== 取值 =====

    private Setting resolveText(String propertyKey, boolean hotChangeable,
                                Function<AgentLlmProperties.Scheduler, String> hotField,
                                String fallback) {
        String hot = hotScheduler(hotField);
        if (hot != null && !hot.isBlank()) {
            return new Setting(hot.trim(), SOURCE_HOT_CONFIG, hotChangeable, null);
        }
        String configured = property(propertyKey);
        if (configured != null && !configured.isBlank()) {
            return new Setting(configured.trim(), SOURCE_PROPERTY, hotChangeable, null);
        }
        return new Setting(fallback, SOURCE_DEFAULT, hotChangeable, null);
    }

    private Setting resolveInt(String propertyKey, boolean hotChangeable,
                               Function<AgentLlmProperties.Scheduler, Integer> hotField,
                               IntPredicate check, int fallback, String requirement) {
        String rejection = null;
        Integer hot = hotScheduler(hotField);
        if (hot != null) {
            if (check.test(hot)) {
                return new Setting(hot, SOURCE_HOT_CONFIG, hotChangeable, null);
            }
            rejection = "热配置的值不合法（" + requirement + "）：" + hot;
        }
        String configured = property(propertyKey);
        if (configured != null && !configured.isBlank()) {
            Integer parsed = parseInteger(configured);
            if (parsed != null && check.test(parsed)) {
                return new Setting(parsed, SOURCE_PROPERTY, hotChangeable, rejection);
            }
            rejection = "环境属性的值不合法（" + requirement + "）：" + configured;
        }
        return new Setting(fallback, SOURCE_DEFAULT, hotChangeable, rejection);
    }

    private Setting resolveLong(String propertyKey, boolean hotChangeable,
                                Function<AgentLlmProperties.Scheduler, Long> hotField,
                                LongPredicate check, long fallback, String requirement) {
        String rejection = null;
        Long hot = hotScheduler(hotField);
        if (hot != null) {
            if (check.test(hot)) {
                return new Setting(hot, SOURCE_HOT_CONFIG, hotChangeable, null);
            }
            rejection = "热配置的值不合法（" + requirement + "）：" + hot;
        }
        String configured = property(propertyKey);
        if (configured != null && !configured.isBlank()) {
            Long parsed = parseLong(configured);
            if (parsed != null && check.test(parsed)) {
                return new Setting(parsed, SOURCE_PROPERTY, hotChangeable, rejection);
            }
            rejection = "环境属性的值不合法（" + requirement + "）：" + configured;
        }
        return new Setting(fallback, SOURCE_DEFAULT, hotChangeable, rejection);
    }

    /** 冻结类参数：值照读，标明改了要重启；解析不了就用默认值并把原因写进读数。 */
    private Setting frozen(String key, Object fallback) {
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

    /** 取热配置里那一段；配置读不出来或者没有这一段时返回 null，调用方按「没配」处理。 */
    private <T> T hotScheduler(Function<AgentLlmProperties.Scheduler, T> field) {
        if (hotConfig == null) {
            return null;
        }
        return hotConfig.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getScheduler)
                .map(field)
                .orElse(null);
    }

    /**
     * 全部参数的当前值：键就是 Spring 属性名，值是「值 + 来源 + 改了要不要重启 + 被丢掉的原因」。
     *
     * <p>键用属性名而不是另起一套名字，是为了让这份读数能与 yml、环境变量、{@code @Value} 逐一对照，
     * 换个名字对读者等于多一层翻译。这份读数必须覆盖全部双池参数：有测试按源码里出现的属性名逐条
     * 核对，漏掉一个就会失败。</p>
     */
    public Map<String, Setting> snapshot() {
        Map<String, Setting> snapshot = new LinkedHashMap<>();
        snapshot.put(KEY_NEW_RUN_SCHEDULER_VERSION, newRunSchedulerVersion());
        snapshot.put(KEY_PER_TURN_NEW_NODE_LIMIT, perTurnNewNodeLimit());
        snapshot.put(KEY_PER_RUN_UNFINISHED_LIMIT, perRunUnfinishedLimit());
        snapshot.put(KEY_GLOBAL_HIGH_WATERMARK, globalUnfinishedHighWatermark());
        snapshot.put(KEY_GLOBAL_LOW_WATERMARK, globalUnfinishedLowWatermark());
        snapshot.put(KEY_WAIT_GROUP_MAX_MEMBERS, waitGroupMaxMembers());
        snapshot.put(KEY_RECOVERY_BATCH_SIZE, recoveryBatchSize());
        snapshot.put(KEY_RECOVERY_SCAN_QUOTA, recoveryScanQuota());
        snapshot.put(KEY_RECOVERY_WAKEUP_CAPACITY, recoveryWakeupCapacity());
        snapshot.put(KEY_RECOVERY_STARTUP_PAGES, recoveryStartupPages());
        snapshot.put(KEY_RECOVERY_BACKOFF_BASE_MS, recoveryBackoffBaseMs());
        snapshot.put(KEY_RECOVERY_BACKOFF_MAX_MS, recoveryBackoffMaxMs());
        snapshot.put(KEY_MEMBER_RECEIVER_BATCH_SIZE, memberReceiverBatchSize());
        snapshot.put(KEY_MEMBER_RECEIVER_BACKOFF_BASE_MS, memberReceiverBackoffBaseMs());
        snapshot.put(KEY_MEMBER_RECEIVER_BACKOFF_MAX_MS, memberReceiverBackoffMaxMs());
        snapshot.put(KEY_MEMBER_RECEIVER_MAX_BACKOFF_STEP, memberReceiverMaxBackoffStep());

        // 以下这些只能在启动时生效：写进线程池、许可台账、队列与定时任务之后就不再变。
        // 照样读出来，但标明要重启——读数里少一样东西，比多一样东西更容易让人误判。
        snapshot.put(DUAL_POOL + "business-admission-limit", frozen(DUAL_POOL + "business-admission-limit", 100));
        snapshot.put(DUAL_POOL + "hint-drain-interval-ms", frozen(DUAL_POOL + "hint-drain-interval-ms", 50));
        snapshot.put(DUAL_POOL + "coordination-defer-retry-ms",
                frozen(DUAL_POOL + "coordination-defer-retry-ms", 1000));
        snapshot.put(DUAL_POOL + "hint-queue-full-retry-ms",
                frozen(DUAL_POOL + "hint-queue-full-retry-ms", 5000));
        snapshot.put(DUAL_POOL + "run-worker.core-pool-size", frozen(DUAL_POOL + "run-worker.core-pool-size", 2));
        snapshot.put(DUAL_POOL + "run-worker.max-pool-size", frozen(DUAL_POOL + "run-worker.max-pool-size", 2));
        snapshot.put(DUAL_POOL + "run-worker.keep-alive-seconds",
                frozen(DUAL_POOL + "run-worker.keep-alive-seconds", 60));
        snapshot.put(DUAL_POOL + "run-worker.thread-name-prefix",
                frozen(DUAL_POOL + "run-worker.thread-name-prefix", "agent-run-coordination-"));
        snapshot.put(DUAL_POOL + "run-worker.hint-capacity", frozen(DUAL_POOL + "run-worker.hint-capacity", 256));
        snapshot.put(DUAL_POOL + "run-worker.submit-budget", frozen(DUAL_POOL + "run-worker.submit-budget", 32));
        snapshot.put(DUAL_POOL + "run-worker.permit-limit", frozen(DUAL_POOL + "run-worker.permit-limit", 2));
        snapshot.put(DUAL_POOL + "node-worker.core-pool-size", frozen(DUAL_POOL + "node-worker.core-pool-size", 4));
        snapshot.put(DUAL_POOL + "node-worker.max-pool-size", frozen(DUAL_POOL + "node-worker.max-pool-size", 4));
        snapshot.put(DUAL_POOL + "node-worker.keep-alive-seconds",
                frozen(DUAL_POOL + "node-worker.keep-alive-seconds", 60));
        snapshot.put(DUAL_POOL + "node-worker.thread-name-prefix",
                frozen(DUAL_POOL + "node-worker.thread-name-prefix", "agent-node-"));
        snapshot.put(DUAL_POOL + "node-worker.hint-capacity", frozen(DUAL_POOL + "node-worker.hint-capacity", 1024));
        snapshot.put(DUAL_POOL + "node-worker.submit-budget", frozen(DUAL_POOL + "node-worker.submit-budget", 64));
        snapshot.put(DUAL_POOL + "node-worker.permit-limit", frozen(DUAL_POOL + "node-worker.permit-limit", 4));
        snapshot.put(DUAL_POOL + "node-worker.claim-lease-seconds",
                frozen(DUAL_POOL + "node-worker.claim-lease-seconds", 300));
        snapshot.put(DUAL_POOL + "scan.interval-ms", frozen(DUAL_POOL + "scan.interval-ms", 1000));
        snapshot.put(DUAL_POOL + "recovery.scan-interval-ms",
                frozen(DUAL_POOL + "recovery.scan-interval-ms", 1000));
        snapshot.put(DUAL_POOL + "scan.batch-size", frozen(DUAL_POOL + "scan.batch-size", 100));
        snapshot.put(DUAL_POOL + "service-lease-ttl-seconds",
                frozen(DUAL_POOL + "service-lease-ttl-seconds", 120));
        snapshot.put(DUAL_POOL + "service-lease-renew-interval-ms",
                frozen(DUAL_POOL + "service-lease-renew-interval-ms", 40000));
        snapshot.put(DUAL_POOL + "service-lease-owned-limit",
                frozen(DUAL_POOL + "service-lease-owned-limit", 512));
        snapshot.put(DUAL_POOL + "wait-group.max-member-result-chars",
                frozen(DUAL_POOL + "wait-group.max-member-result-chars", 1048576));
        snapshot.put(DUAL_POOL + "wait-group.member-poll-delay-ms",
                frozen(DUAL_POOL + "wait-group.member-poll-delay-ms", 2000));
        snapshot.put(MEMBER_RECEIVER + "poll-interval-ms", frozen(MEMBER_RECEIVER + "poll-interval-ms", 1000));
        return snapshot;
    }
}
