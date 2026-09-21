package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

/**
 * 调度器版本的唯一解析入口。
 *
 * <p>新 Run 的开关只在创建时读取一次并写进数据库。已经创建的 Run 只相信记录自身的
 * {@code schedulerVersion}，不会因热配置变化在执行中途切换调度器。未知值直接失败关闭。</p>
 */
@Component
public class SchedulerVersionPolicy {

    public static final String LEGACY = SchedulerVersion.LEGACY.name();
    public static final String DUAL_POOL_V1 = SchedulerVersion.DUAL_POOL_V1.name();
    public static final String DUAL_POOL_V2 = SchedulerVersion.DUAL_POOL_V2.name();

    private final Environment environment;
    private final DualPoolSchedulerSettings settings;

    public SchedulerVersionPolicy(Environment environment, DualPoolSchedulerSettings settings) {
        this.environment = environment;
        this.settings = settings;
    }

    public String versionForNewRun() {
        // 版本从这里读一次：热配置（泳道覆盖只影响该泳道）→ 环境属性 → 代码默认。已经创建的 Run
        // 只认自己记录里的版本，热配置怎么改都不会影响它们。
        String configuredVersion = requireKnown(settings.newRunSchedulerVersion().textValue());
        // 认不出的值在这里失败关闭（既不是旧路径，也不是任何一个双池版本），不悄悄回落到旧版本。
        // 三个已知版本都可以给新 Run 用：完整 DAG 那一版的执行链已经接通（分段执行、等待组、
        // 结果接收、恢复分发与启动恢复都在），但要不要用由配置决定——没配置时仍是旧路径。
        //
        // 版本判断放在演练开关之前：显式配置的版本必须先被检查，不能被另一个开关悄悄改掉。
        // 进程终止演练只对双 Worker 池的持久恢复链有意义。这个开关只由隔离的
        // 长工具验收泳道显式授权；它只改默认值：配置停在没有指定双池版本（LEGACY 或未配置）时才把新 Run
        // 推进双池，显式写下的版本不被它改写。避免旧串行链产出无效样本。
        if (LEGACY.equals(configuredVersion) && environment.getProperty(
                "agent.tool-job.fault-injection.allow-process-halt", Boolean.class, false)) {
            return DUAL_POOL_V1;
        }
        return configuredVersion;
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
