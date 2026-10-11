package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentLlmHotConfigNotSyncedException;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

/**
 * 调度器版本的唯一解析入口。
 *
 * <p>新 Run 的开关只在创建时读取一次并写进数据库。已经创建的 Run 只相信记录自身的
 * {@code schedulerVersion}，不会因热配置变化在执行中途切换调度器。未知值直接失败关闭。</p>
 *
 * <p>「配置写的是什么、实际生效的是什么」由设置解析组件一起算出来（热配置 → 环境属性 → 代码默认，
 * 以及进程终止演练开关对默认值的覆盖），这里只负责认出这个版本名并把认不出的当场拒掉。演练开关
 * 只覆盖代码默认值：显式配置的版本（包括显式写的 LEGACY）是操作人的选择，不被别的开关改写。</p>
 *
 * <p>泳道进程（容器里有 {@code AF_LANE_TRAFFIC_SCOPE_ID}）再加一条：新 Run 的版本必须来自
 * agent-llm 热配置。环境属性里的 LEGACY 默认值不能在覆盖尚未进入内存快照时冻进库。</p>
 */
@Component
public class SchedulerVersionPolicy {

    public static final String LEGACY = SchedulerVersion.LEGACY.name();
    public static final String DUAL_POOL_V1 = SchedulerVersion.DUAL_POOL_V1.name();
    public static final String DUAL_POOL_V2 = SchedulerVersion.DUAL_POOL_V2.name();

    private final DualPoolSchedulerSettings settings;

    public SchedulerVersionPolicy(DualPoolSchedulerSettings settings) {
        this.settings = settings;
    }

    public String versionForNewRun() {
        // Nacos 打开时，本地种子文件和环境属性里的 LEGACY 都不能抢在覆盖生效之前冻结版本。
        if (!settings.hotConfigIsAuthoritative()) {
            throw new AgentLlmHotConfigNotSyncedException(
                    "agent-llm Nacos 尚未写入本地缓存，拒绝用种子文件或环境默认值冻结新 Run 的调度器版本");
        }
        // 版本从这里读一次：热配置（泳道覆盖只影响该泳道）→ 环境属性 → 代码默认，演练开关也在这里
        // 生效。已经创建的 Run 只认自己记录里的版本，热配置怎么改都不会影响它们。
        DualPoolSchedulerSettings.Setting version = settings.newRunSchedulerVersion();
        if (settings.laneProcess()
                && !DualPoolSchedulerSettings.SOURCE_HOT_CONFIG.equals(version.source())) {
            throw new AgentLlmHotConfigNotSyncedException(
                    "泳道进程的新 Run 调度器版本必须来自 agent-llm 热配置，当前来源是 "
                            + version.source() + " 值 " + version.textValue());
        }
        String effectiveVersion = requireKnown(version.textValue());
        // 认不出的值在这里失败关闭（既不是旧路径，也不是任何一个双池版本），不悄悄回落到旧版本。
        // 三个已知版本都可以给新 Run 用：完整 DAG 那一版的执行链已经接通（分段执行、等待组、
        // 结果接收、恢复分发与启动恢复都在），但要不要用由配置决定——没配置时仍是旧路径。
        return effectiveVersion;
    }

    public String versionOf(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("agent_run_required");
        }
        // 正式存量由迁移显式回填且列为 NOT NULL；空值意味着查询漏列或数据损坏，必须失败关闭。
        return requireKnown(run.getSchedulerVersion());
    }

    public String requireKnown(String raw) {
        return SchedulerVersion.fromWire(raw).name();
    }

    public boolean isLegacy(AgentRun run) {
        return LEGACY.equals(versionOf(run));
    }

    /**
     * 这个 Run 是不是双池骨架（DUAL_POOL_V1）。
     *
     * <p>注意它不等于「属于双池家族」：完整 DAG 的 DUAL_POOL_V2 也走双池执行层，但它不用 Run 级单工具锚点。
     * 判断「能不能按单工具锚点恢复」「要不要写 Run 级锚点」时用这个方法；判断「走不走双池入口」时用
     * {@link #isDualPoolFamily(AgentRun)}。</p>
     */
    public boolean isDualPool(AgentRun run) {
        return DUAL_POOL_V1.equals(versionOf(run));
    }

    /** 这个 Run 是不是走双池执行层（DUAL_POOL_V1 或 DUAL_POOL_V2）。 */
    public boolean isDualPoolFamily(AgentRun run) {
        return isDualPoolFamily(versionOf(run));
    }

    /**
     * 按版本名判断是不是走双池执行层。
     *
     * <p>创建路径上还没有 Run 行可读，只能拿版本名判；两个入口共用同一份判断，不许各写一套。</p>
     */
    public boolean isDualPoolFamily(String versionName) {
        return SchedulerVersion.fromWire(versionName).isDualPoolFamily();
    }

    /** 这个 Run 的等待事实是不是存在等待组里（只有 DUAL_POOL_V2 成立）。 */
    public boolean usesWaitGroups(AgentRun run) {
        return SchedulerVersion.fromWire(versionOf(run)).usesWaitGroups();
    }
}
