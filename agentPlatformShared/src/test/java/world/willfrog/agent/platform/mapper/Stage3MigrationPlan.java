package world.willfrog.agent.platform.mapper;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段三真库用例实际应用的迁移脚本计划。
 *
 * <p>真库用例的升级路径分三段：先走真实升级链到 025（init 建表脚本加上 upgrades 里排到 025
 * 的全部 SQL），再把 007…021 整份重跑两遍验证脚本可重复执行，最后把 026 异步任务表迁移整份
 * 执行一次（它带一次性搬运行与自校验块，不能重跑）。把这份计划集中一处，真库用例和不需要
 * 数据库的单测读的是同一份事实——手写清单漏掉 022–025 这类脚本时，单测会先红，不会等到真库上
 * 才报「列不存在」。</p>
 *
 * <p>排序规则以 {@code migrate/migrate.py} 为准：版本目录按目录名字典序
 * （{@code plan_current} 里的 {@code sorted(..., key=lambda d: d.name)}），目录内文件按文件名
 * 排序。migrate.py 还会执行 {@code .py} 配置检查脚本；本用例只执行 SQL（切句与执行器只认 SQL），
 * 所以覆盖范围只核对 {@code .sql}。</p>
 */
public final class Stage3MigrationPlan {

    /**
     * 真实升级链走到这一份（含）：链里同时有 024 的删除意图列与 025 的工作区保留两列，
     * 产品映射语句已经按这三列取数。
     */
    public static final String CHAIN_LAST_SCRIPT = "025_agent_run_workspace_retention.sql";

    /** 异步任务表迁移：整份执行一次，不参与上面的两遍重跑。 */
    public static final String ASYNC_TASK_SCRIPT = "026_agent_async_tool_task.sql";

    /**
     * 在升级链里已经跑过一遍、之后还要整份重跑两遍的脚本。
     *
     * <p>重跑本身量的是「脚本可以重复执行」；把它们列在这里而不是由真库用例手写，是为了让
     * 覆盖范围有一份可被单测核对的出处。</p>
     */
    private static final List<String> RERUN_TWICE_SCRIPTS = List.of(
            "007_agent_run_dag_wait_group.sql",
            "008_agent_run_wait_member_dispatch_proof.sql",
            "009_agent_run_recovery_consumed_by_check.sql",
            "010_agent_run_event_received_repair_index.sql",
            "011_agent_run_service_lease.sql",
            "012_agent_run_coordination_shared_candidate.sql",
            "013_agent_run_recovery_notification_close.sql",
            "014_agent_run_acceptance_fixture.sql",
            "015_agent_run_release_point.sql",
            "016_agent_run_acceptance_fixture_call.sql",
            "017_agent_run_acceptance_fixture_policy.sql",
            "018_agent_run_child_intent.sql",
            "019_agent_run_tree_budget.sql",
            "020_agent_run_wait_member_stop.sql",
            "021_agent_run_tree_activity_gate.sql");

    private Stage3MigrationPlan() {
    }

    /** 需要整份重跑两遍的脚本名，顺序固定。 */
    public static List<String> rerunTwiceScripts() {
        return RERUN_TWICE_SCRIPTS;
    }

    /** 真实升级链：init 建表脚本按文件名升序，之后按 migrate.py 的排序规则升到 025。 */
    public static List<Path> upgradeChain() {
        return MigrationStatements.upgradeChainUpTo(CHAIN_LAST_SCRIPT);
    }

    /** 升级链里属于 upgrades 的脚本名（去掉 init 建表脚本），供单测核对已应用范围没有缺口。 */
    public static List<String> upgradeChainScriptNames() {
        Set<Path> upgradePaths = new HashSet<>(MigrationScripts.pathsInOrder());
        return upgradeChain().stream()
                .filter(upgradePaths::contains)
                .map(path -> path.getFileName().toString())
                .toList();
    }
}
