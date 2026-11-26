package world.willfrog.agent.platform.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步工具任务表迁移与回退脚本的关键约束。
 *
 * <p>旧等待成员表里同时存着两种派发证明：沙箱作业的数字结构版本，与子智能体等待的
 * 字符串结构版本 {@code sub_agent_wait_v1}。迁移按整数强转会当场失败，按工具名跳过子智能体
 * 又会让在途任务失去推进者；回退按业务键覆盖失败则会把旧行留在过期状态。这三条都在这里钉住。</p>
 */
class AsyncToolTaskMigrationSqlTest {

    private static final String UPGRADE = MigrationStatements.read("026_agent_async_tool_task.sql");
    private static final String DOWNGRADE = readDowngrade();

    private static String readDowngrade() {
        Path upgrade = MigrationStatements.locate("026_agent_async_tool_task.sql");
        // 升级脚本在 migrate/migrations/upgrades/v1.5/，回退脚本在 migrate/rollbacks/v1.5/。
        Path downgrade = upgrade.getParent().getParent().getParent().getParent()
                .resolve("rollbacks/v1.5/026_agent_async_tool_task_down.sql");
        return MigrationStatements.read(downgrade);
    }

    @Test
    void upgradeCopiesEveryProofShapeWithoutGuessingTheVersion() {
        assertThat(UPGRADE).as("子智能体等待成员在第一步仍按等待成员推进，不能跳过")
                .doesNotContain("tool_name <> 'spawnSubAgent'");
        assertThat(UPGRADE).as("结构版本是字符串时按 1 处理，不能直接转整数")
                .contains("jsonb_typeof(m.dispatch_proof_json -> 'schemaVersion') = 'number'");
        assertThat(UPGRADE).as("派发证明整体搬进请求快照，不逐字段拆")
                .contains("COALESCE(m.dispatch_proof_json, '{}'::jsonb)");
    }

    @Test
    void rollbackRewritesUnfinishedTasksForBothExistingAndNewRows() {
        assertThat(DOWNGRADE).as("升级后新产生的任务没有旧行，必须允许新建")
                .contains("INSERT INTO alphafrog_agent_run_wait_member");
        assertThat(DOWNGRADE).as("已有旧行要覆盖成当前状态，而不是保持升级时的旧状态")
                .contains("ON CONFLICT (group_id, member_identity) DO UPDATE");
        assertThat(DOWNGRADE).as("快照里已有字符串结构版本时不能再拼一个数字版本")
                .contains("CASE WHEN t.request_json ->> 'schemaVersion' IS NULL");
    }
}
