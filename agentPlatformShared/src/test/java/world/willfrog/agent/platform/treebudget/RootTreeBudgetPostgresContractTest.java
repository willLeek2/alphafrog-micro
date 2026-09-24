package world.willfrog.agent.platform.treebudget;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import world.willfrog.agent.platform.mapper.RootTreeBudgetMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 只在提供隔离 PostgreSQL 连接串时执行的真实事务与并发合同。 */
class RootTreeBudgetPostgresContractTest {
    private SqlSessionFactory sessions;

    @Test
    void reservationIsAtomicIdempotentAndReleasable() throws Exception {
        String dsn = System.getenv("AF_STAGE5A_PG_DSN");
        assumeTrue(dsn != null && !dsn.isBlank(), "未提供 AF_STAGE5A_PG_DSN：根树额度真库合同尚未验证");
        String schema = "tree_budget_" + UUID.randomUUID().toString().replace("-", "");
        PGSimpleDataSource admin = source(dsn, null);
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            PGSimpleDataSource scoped = source(dsn, schema);
            installSchema(scoped);
            sessions = sessions(scoped);
            try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO alphafrog_agent_run(id) VALUES ('root-a'), ('root-b')");
            }

            assertThat((Object) tx(store -> store.reserve("root-a", "llm-1", RootTreeBudgetStore.Kind.LLM_CALL, 1)))
                    .isEqualTo(RootTreeBudgetStore.State.RESERVED);
            assertThat((Object) tx(store -> store.reserve("root-a", "llm-1", RootTreeBudgetStore.Kind.LLM_CALL, 1)))
                    .isEqualTo(RootTreeBudgetStore.State.RESERVED);
            assertThat((Object) tx(store -> store.reserve("root-a", "llm-2", RootTreeBudgetStore.Kind.LLM_CALL, 1)))
                    .isEqualTo(RootTreeBudgetStore.State.REJECTED);
            assertThat((Object) tx(store -> store.confirm("llm-1"))).isEqualTo(RootTreeBudgetStore.State.CONFIRMED);
            assertThat((Object) tx(store -> store.confirm("llm-1"))).isEqualTo(RootTreeBudgetStore.State.CONFIRMED);
            assertThatThrownBy(() -> tx(store -> store.release("llm-1")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> tx(store -> store.reserve("root-b", "llm-1", RootTreeBudgetStore.Kind.LLM_CALL, 1)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat((Object) tx(store -> store.reserve("root-a", "node-1", RootTreeBudgetStore.Kind.ACTIVE_NODE, 1)))
                    .isEqualTo(RootTreeBudgetStore.State.RESERVED);
            assertThat((Object) tx(store -> store.confirm("node-1"))).isEqualTo(RootTreeBudgetStore.State.CONFIRMED);
            assertThat((Object) tx(store -> store.release("node-1"))).isEqualTo(RootTreeBudgetStore.State.RELEASED);
            assertThat((Object) tx(store -> store.release("node-1"))).isEqualTo(RootTreeBudgetStore.State.RELEASED);
            assertThat((Object) tx(store -> store.reserve("root-a", "wait-1", RootTreeBudgetStore.Kind.EXTERNAL_WAIT, 1)))
                    .isEqualTo(RootTreeBudgetStore.State.RESERVED);
            assertThat((Object) tx(store -> store.release("wait-1"))).isEqualTo(RootTreeBudgetStore.State.RELEASED);
            assertThat((Object) tx(store -> store.snapshot("root-a")))
                    .isEqualTo(new RootTreeBudgetStore.Snapshot(1, 0, 0, 0));
            assertThatThrownBy(() -> tx(store -> store.reserve("missing", "bad-1", RootTreeBudgetStore.Kind.TOOL_CALL, 1)))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> tx(store -> store.reserve("root-a", "bad-2", RootTreeBudgetStore.Kind.TOOL_CALL, 0)))
                    .isInstanceOf(IllegalArgumentException.class);

            // 两个子 Run 同时争同一根树最后一个额度，只能有一个被接纳。
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                var first = pool.submit(() -> {
                    start.await();
                    return tx(store -> store.reserve("root-a", "tool-child-a", RootTreeBudgetStore.Kind.TOOL_CALL, 1));
                });
                var second = pool.submit(() -> {
                    start.await();
                    return tx(store -> store.reserve("root-a", "tool-child-b", RootTreeBudgetStore.Kind.TOOL_CALL, 1));
                });
                start.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS) == RootTreeBudgetStore.State.RESERVED
                        ^ second.get(10, TimeUnit.SECONDS) == RootTreeBudgetStore.State.RESERVED).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(tx(store -> store.snapshot("root-a")).toolCalls()).isEqualTo(1);
        } finally {
            try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private <T> T tx(Function<RootTreeBudgetStore, T> action) {
        try (SqlSession session = sessions.openSession(false)) {
            RootTreeBudgetStore store = new MybatisRootTreeBudgetStore(session.getMapper(RootTreeBudgetMapper.class));
            T result = action.apply(store);
            session.commit();
            return result;
        }
    }

    private static PGSimpleDataSource source(String dsn, String schema) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(dsn.startsWith("jdbc:") ? dsn : "jdbc:" + dsn);
        source.setUser(System.getenv("AF_STAGE5A_PG_USER"));
        source.setPassword(System.getenv("AF_STAGE5A_PG_PASSWORD"));
        if (schema != null) source.setCurrentSchema(schema);
        return source;
    }

    private static void installSchema(PGSimpleDataSource source) throws Exception {
        String migration = Files.readString(Path.of("../migrate/migrations/upgrades/v1.5/019_agent_run_tree_budget.sql"));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE alphafrog_agent_run(id VARCHAR(64) PRIMARY KEY)");
            for (String sql : migration.split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    private static SqlSessionFactory sessions(PGSimpleDataSource source) throws Exception {
        Configuration config = new Configuration(new Environment("tree-budget-pg", new JdbcTransactionFactory(), source));
        String resource = "mapper/RootTreeBudgetMapper.xml";
        try (InputStream xml = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(config);
    }
}
