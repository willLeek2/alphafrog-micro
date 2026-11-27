package world.willfrog.agent.platform.mapper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移脚本切句工具的自检：真库用例靠它把升级链与阶段脚本逐条执行，切错了会在真库上表现出
 * 很难查的失败。
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

    /**
     * 真库用例的升级链必须一路走到工作区保留那一份，不能停在 006 之后手写补几份。
     *
     * <p>缺 022–025 时，产品映射语句会在真库上报「列不存在」；这类缺口在单测里就该红，
     * 不该等到有数据库的环境才暴露。断言的是顺序与端点：022、023、024、025 按顺序出现，
     * 且 026 仍由用例单独执行、不在链里。</p>
     */
    @Test
    void stage3UpgradeChainRunsThroughWorkspaceRetentionAndStopsBeforeAsyncTask() {
        List<String> chain = Stage3MigrationPlan.upgradeChainScriptNames();
        assertThat(chain).containsSubsequence(
                "022_finance_indicators.sql",
                "023_agent_python_risk_decision.sql",
                "024_agent_run_deletion_intent.sql",
                "025_agent_run_workspace_retention.sql");
        assertThat(chain).endsWith(Stage3MigrationPlan.CHAIN_LAST_SCRIPT);
        assertThat(chain).as("026 带一次性搬运行，必须由用例单独整份执行一次")
                .doesNotContain(Stage3MigrationPlan.ASYNC_TASK_SCRIPT);
    }

    /**
     * 把「真库用例到底应用了哪些脚本」摊开核对：升级链（到 025）∪ 两遍重跑的 007…021 ∪ 026，
     * 必须覆盖 upgrades 目录里排序不超过 026 的全部 SQL，且清单里每个名字都真实存在。
     *
     * <p>不写成「等于目录里的全部文件」：以后新增 027 时，本用例还没有应用它，不该误报；
     * 要管的是「已应用范围内不能有缺口」。</p>
     */
    @Test
    void stage3PlanCoversEverySqlUpgradeThroughAsyncTaskWithoutGaps() {
        List<String> ordered = MigrationScripts.pathsInOrder().stream()
                .map(path -> path.getFileName().toString())
                .toList();
        int lastAppliedIndex = ordered.indexOf(Stage3MigrationPlan.ASYNC_TASK_SCRIPT);
        assertThat(lastAppliedIndex).as("026 必须在 upgrades 目录里").isNotNegative();
        List<String> mustApply = ordered.subList(0, lastAppliedIndex + 1);

        assertThat(mustApply)
                .as("计划里点名的脚本都要真实存在于 upgrades 目录")
                .containsAll(Stage3MigrationPlan.rerunTwiceScripts())
                .contains(Stage3MigrationPlan.CHAIN_LAST_SCRIPT,
                        Stage3MigrationPlan.ASYNC_TASK_SCRIPT);
        assertThat(Stage3MigrationPlan.rerunTwiceScripts())
                .as("两遍重跑的都是链里已经跑过、并且排在 025 之前的脚本")
                .allSatisfy(name -> assertThat(mustApply.indexOf(name))
                        .isLessThan(mustApply.indexOf(Stage3MigrationPlan.CHAIN_LAST_SCRIPT)));

        Set<String> applied = new LinkedHashSet<>(Stage3MigrationPlan.upgradeChainScriptNames());
        applied.addAll(Stage3MigrationPlan.rerunTwiceScripts());
        applied.add(Stage3MigrationPlan.ASYNC_TASK_SCRIPT);
        assertThat(applied)
                .as("已应用范围内不能有缺口：漏掉的脚本就是真库上「表/列不存在」的来源")
                .containsAll(mustApply);
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
    void semicolonsInsideDollarQuotesDoNotSplitStatements() {
        String function = "CREATE OR REPLACE FUNCTION f() RETURNS TRIGGER AS $$\n"
                + "BEGIN\n"
                + "  NEW.updated_at = CURRENT_TIMESTAMP;\n"
                + "  RETURN NEW;\n"
                + "END;\n"
                + "$$ language 'plpgsql';\n";
        assertThat(MigrationStatements.split(function + "SELECT 1;"))
                .as("函数体里的分号不算分界，整段函数一条语句")
                .hasSize(2)
                .first().asString().contains("END;\n$$ language 'plpgsql'");
        assertThat(MigrationStatements.split("SELECT 1;")).containsExactly("SELECT 1");
    }

    @Test
    void taggedDollarQuotesKeepTheirOwnDelimiter() {
        String script = "DO $tag$\nBEGIN\n  PERFORM 1;\nEND $tag$;\nSELECT 2;";
        List<String> statements = MigrationStatements.split(script);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).as("带标签的美元引用按自己的标签收尾")
                .contains("END $tag$");
        assertThat(statements.get(1)).isEqualTo("SELECT 2");
    }

    @Test
    void dollarQuoteInsideSingleQuotesIsJustText() {
        assertThat(MigrationStatements.split("SELECT '$$;' AS text;"))
                .as("单引号里的 $$ 不开启美元引用")
                .containsExactly("SELECT '$$;' AS text");
    }

    @Test
    void unterminatedDollarQuoteFailsClosed() {
        assertThatThrownBy(() -> MigrationStatements.split("DO $$ BEGIN PERFORM 1;"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("美元引用");
    }

    @Test
    void realV05ScriptWithFunctionBodiesSplitsWithoutDanglingDollarQuotes() {
        List<String> statements = MigrationStatements.split(
                MigrationStatements.read("001_tushare_interfaces.sql"));
        assertThat(statements).as("v0.5 那份脚本要能切开").isNotEmpty();
        assertThat(statements).allSatisfy(statement ->
                assertThat(statement.chars().filter(ch -> ch == '$').count() % 2)
                        .as("每条语句里的美元符号必须成对：%s", statement)
                        .isZero());
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
