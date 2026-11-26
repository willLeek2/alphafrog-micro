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
    void upgradeChecksItsOwnResultAndCarriesTheStopTail() {
        assertThat(UPGRADE).as("迁移要自己对账并在不一致时中止整份脚本")
                .contains("DO $migration_check$")
                .contains("RAISE EXCEPTION")
                .contains("migrated <> expected");
        assertThat(UPGRADE).as("停机尾巴要带上下一次尝试时刻与领取信息，且停机记录的时刻优先")
                .contains("next_poll_at = COALESCE(s.next_attempt_at, t.next_poll_at)")
                .contains("CASE WHEN s.state = 'CLAIMED' THEN s.claimed_by END");
        assertThat(UPGRADE).as("停机扫描要有自己的部分索引，取消后的行不在到期索引覆盖范围内")
                .contains("idx_agent_async_tool_task_stop_due");
        // 带 FROM 的 UPDATE 里，没写表名的列会在目标表与运行表之间一起寻找：
        // 两张表都有 updated_at，不写别名会报列引用歧义，整份脚本回滚。
        assertThat(UPGRADE).as("补充超时时刻的 UPDATE 要给目标表起别名并写全列名")
                .contains("UPDATE alphafrog_agent_async_tool_task t")
                .contains("GREATEST(t.updated_at, CURRENT_TIMESTAMP)")
                .doesNotContain("GREATEST(updated_at, CURRENT_TIMESTAMP)");
        assertThat(UPGRADE).as("任务行与所属组必须同 Run，旧等待成员表的这条外键要原样保留")
                .contains("CONSTRAINT agent_async_tool_task_group_fk")
                .contains("FOREIGN KEY (group_id, run_id) REFERENCES alphafrog_agent_run_wait_group(id, run_id)");
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

    @Test
    void rollbackIsAtomicAndMarksWhatItCannotWriteBack() {
        assertThat(DOWNGRADE).as("手工执行的脚本也要整份一个事务")
                .contains("BEGIN;")
                .contains("COMMIT;");
        assertThat(DOWNGRADE).as("回写不进去的任务要留原因码，不能静默丢")
                .contains("ROLLBACK_INCOMPLETE")
                .contains("missing_member_identity");
        assertThat(DOWNGRADE).as("核对查询要能直接执行，覆盖被丢弃的停机尾巴")
                .contains("SELECT t.job_id, t.run_id, t.tool_name, t.external_operation_id, t.stop_state");
        assertThat(DOWNGRADE).as("凑不齐回写键、被标为失败的行要有专门的信息查询，并写明它不属于必须为空")
                .contains("result_ref_json ->> 'exit_reason' = 'ROLLBACK_INCOMPLETE'")
                .contains("第四条（信息查询，不属于「必须为空」）")
                .contains("前三查询必须返回 0 行");
        assertThat(DOWNGRADE).as("第三条只列旧停机表里也没有记录的行，正常回退才能等到空结果")
                .contains("AND NOT EXISTS (")
                .contains("JOIN alphafrog_agent_run_wait_member_stop s ON s.wait_member_id = m.id");
        assertThat(DOWNGRADE).as("执行方式要写明 ON_ERROR_STOP，否则出错后退出码仍是 0")
                .contains("ON_ERROR_STOP=1")
                .contains("psql -v ON_ERROR_STOP=1 -f 026_agent_async_tool_task_down.sql")
                .contains("前三条核对查询都是空结果");
    }
}
