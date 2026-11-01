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
