package world.willfrog.agent.platform.mapper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移脚本切句工具的自检：真库用例靠它把 007 逐条执行，切错了会在真库上表现出很难查的失败。
 *
 * <p>这里能覆盖的是切句规则本身；「这些语句在真库上能不能执行」由 {@code Stage3WaitContractPostgresTest}
 * 在给了外部连接串的环境里回答。</p>
 */
class MigrationStatementsTest {

    private static final String SCRIPT = "007_agent_run_dag_wait_group.sql";

    @Test
    void splitsTheStage3ScriptIntoExecutableStatements() {
        List<String> statements = MigrationStatements.split(MigrationStatements.read(SCRIPT));
        assertThat(statements).as("007 是一份多语句脚本").hasSizeGreaterThan(20);
        assertThat(statements).as("空语句不该流到执行端")
                .allSatisfy(statement -> assertThat(statement).isNotBlank());
        assertThat(statements).as("注释行要在切句时丢掉，否则执行端会报语法错")
                .allSatisfy(statement -> assertThat(statement).doesNotStartWith("--"));
        assertThat(statements.stream().filter(statement -> statement.contains("CREATE TABLE")).count())
                .as("六张新表各一条建表语句").isEqualTo(6);
    }

    @Test
    void keepsStatementsThatCarryTheStage3Contracts() {
        List<String> statements = MigrationStatements.split(MigrationStatements.read(SCRIPT));
        assertThat(statements.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS "
                + "alphafrog_agent_run_wait_group") && s.contains("next_segment_sequence = "
                + "segment_sequence + 1")))
                .as("等待组那条建表语句要整条留下，包括用来算下一段的等式").isTrue();
        assertThat(statements).anySatisfy(statement -> assertThat(statement)
                .contains("SET runnable_since = LEAST(next_visible_at, CURRENT_TIMESTAMP)"));
        assertThat(statements).anySatisfy(statement -> assertThat(statement)
                .contains("ON alphafrog_agent_run_wait_member(external_operation_id)"));
    }

    @Test
    void upgradeChainRunsFromTheInitScriptsUpToTheScriptBeforeStage3() {
        List<java.nio.file.Path> chain = MigrationStatements.upgradeChainUpTo(
                "006_agent_tool_job_test_control.sql");
        assertThat(chain).as("升级链要从建表脚本开始，不能只手工摆几张前置表").hasSizeGreaterThan(30);
        List<String> names = chain.stream().map(path -> path.getFileName().toString()).toList();
        assertThat(names.subList(0, MigrationStatements.initScripts().size()))
                .as("链的开头是 init 目录里的建表脚本，按文件名升序")
                .containsExactlyElementsOf(MigrationStatements.initScripts().stream()
                        .map(path -> path.getFileName().toString())
                        .toList());
        assertThat(names).endsWith("006_agent_tool_job_test_control.sql");
        assertThat(names).as("阶段三脚本要单独应用，不混在升级链里").doesNotContain(SCRIPT);
        assertThat(names).contains("004_agent_run_work_item.sql");
    }

    @Test
    void semicolonsInsideQuotesDoNotSplitStatements() {
        assertThat(MigrationStatements.split("SELECT 'a;b'; SELECT 1;"))
                .containsExactly("SELECT 'a;b'", "SELECT 1");
        assertThat(MigrationStatements.split("SELECT 'it''s ok';"))
                .as("连着两个单引号是转义写法，不算收尾")
                .containsExactly("SELECT 'it''s ok'");
    }

    @Test
    void unterminatedQuoteFailsClosed() {
        assertThatThrownBy(() -> MigrationStatements.split("SELECT 'oops; SELECT 1;"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("单引号");
    }

    @Test
    void missingScriptFailsClosed() {
        assertThatThrownBy(() -> MigrationStatements.locate("999_not_there.sql"))
                .isInstanceOf(IllegalStateException.class);
    }
}
