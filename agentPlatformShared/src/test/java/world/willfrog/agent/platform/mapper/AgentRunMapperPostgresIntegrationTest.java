package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;

import javax.sql.DataSource;
import java.io.InputStream;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the production mapper contract against PostgreSQL's JSONB and NOT NULL semantics.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentRunMapperPostgresIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpMapper() throws Exception {
        DataSource dataSource = dataSource();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE alphafrog_agent_run (
                        id VARCHAR(64) PRIMARY KEY,
                        user_id VARCHAR(64),
                        status VARCHAR(32),
                        current_step INT DEFAULT 0,
                        max_steps INT DEFAULT 20,
                        plan_json JSONB NOT NULL DEFAULT '{}',
                        snapshot_json JSONB NOT NULL DEFAULT '{}',
                        last_error TEXT,
                        ttl_expires_at TIMESTAMPTZ,
                        started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                        completed_at TIMESTAMPTZ,
                        ext JSONB NOT NULL DEFAULT '{}',
                        tool_job_anchor_json JSONB NOT NULL DEFAULT '{}'
                    )
                    """);
        }

        Configuration configuration = new Configuration(
                new Environment("postgres-test", new JdbcTransactionFactory(), dataSource)
        );
        String mapperResource = "mapper/AgentRunMapper.xml";
        try (InputStream mapperXml = Resources.getResourceAsStream(mapperResource)) {
            new XMLMapperBuilder(
                    mapperXml,
                    configuration,
                    mapperResource,
                    configuration.getSqlFragments()
            ).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void insertNullToolJobAnchorPersistsEmptyObjectInsteadOfSqlNull() {
        AgentRun run = new AgentRun();
        run.setId("null-anchor-run");
        run.setUserId("user-1");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setCurrentStep(0);
        run.setMaxSteps(12);
        run.setPlanJson("{}");
        run.setSnapshotJson("{}");
        run.setTtlExpiresAt(OffsetDateTime.now().plusMinutes(30));
        run.setExt("{}");
        run.setToolJobAnchorJson(null);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            assertThat(mapper.insert(run)).isEqualTo(1);
            assertThat(mapper.findById(run.getId()).getToolJobAnchorJson()).isEqualTo("{}");
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
