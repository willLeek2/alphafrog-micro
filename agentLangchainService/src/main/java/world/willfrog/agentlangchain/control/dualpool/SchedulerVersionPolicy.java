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

    private final Environment environment;

    public SchedulerVersionPolicy(Environment environment) {
        this.environment = environment;
    }

    public String versionForNewRun() {
        String configuredVersion = requireKnown(environment.getProperty(
                "agent.langchain.dual-pool.new-run-scheduler-version", LEGACY));
        // 进程终止演练只对双 Worker 池的持久恢复链有意义。这个开关只由隔离的
        // 长工具验收泳道显式授权；授权后强制新 Run 进入双池，避免旧串行链产出无效样本。
        if (environment.getProperty(
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

    public boolean isDualPool(AgentRun run) {
        return DUAL_POOL_V1.equals(versionOf(run));
    }
}
