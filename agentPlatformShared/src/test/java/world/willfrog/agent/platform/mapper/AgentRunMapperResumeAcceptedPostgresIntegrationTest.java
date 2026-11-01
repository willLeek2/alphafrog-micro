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
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;

import javax.sql.DataSource;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产 PostgreSQL 接缝：验证 ACCEPTED resumeState 通过真实的
 * {@code AgentRunMapper.xml} MyBatis mapper 在真实数据库上正确执行。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentRunMapperResumeAcceptedPostgresIntegrationTest {

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
                        deployment_id VARCHAR(64) NOT NULL,
                        deployment_generation_id VARCHAR(68) NOT NULL,
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
                        execution_checkpoint_json JSONB NOT NULL DEFAULT '{}',
                        restart_attempt INT NOT NULL DEFAULT 0,
                        tool_job_anchor_json JSONB NOT NULL DEFAULT '{}',
                        scheduler_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
                        plan_generation INT NOT NULL DEFAULT -1,
                        run_control_version BIGINT NOT NULL DEFAULT 0
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

    // ------------------------------------------------------------------
    // ACCEPTED 路径：acceptResumeHandoff 将 LAUNCHING 推进为 ACCEPTED
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // ACCEPTED 路径：takeoverExpiredResumeLauncher
    // ------------------------------------------------------------------



    // ------------------------------------------------------------------
    // ACCEPTED 路径：heartbeatResumeLauncher
    // ------------------------------------------------------------------



    // ------------------------------------------------------------------
    // ACCEPTED 路径：updateResumedTerminal
    // ------------------------------------------------------------------



    // ------------------------------------------------------------------
    // ACCEPTED 路径：clearAcceptedResumeHandoff
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // Legacy 兼容：原始 JSON 直接入 PG，验证 production mapper 处理
    // ------------------------------------------------------------------



    // ------------------------------------------------------------------
    // LAUNCHING 回归守卫：已有流程仍正常
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // 栅栏：acceptResumeHandoff 拒绝非 LAUNCHING 状态
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // 约束栅栏：矛盾的 status/state/consumed 组合被拒绝
    // ------------------------------------------------------------------





    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static ToolJobAnchor resumeAnchor(
            String operationId, String resumeState, boolean resultConsumed) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setTaskId("task-1");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setResumeState(resumeState);
        anchor.setResultConsumed(resultConsumed);
        return anchor;
    }

    private static void insertRun(
            String runId,
            AgentRunStatus status,
            String anchorJson) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-1");
        run.setDeploymentId("stable");
        run.setDeploymentGenerationId("gen-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        run.setStatus(status);
        run.setCurrentStep(0);
        run.setMaxSteps(12);
        run.setPlanJson("{}");
        run.setSnapshotJson("{}");
        run.setTtlExpiresAt(OffsetDateTime.now().plusMinutes(30));
        run.setExt("{}");
        run.setToolJobAnchorJson(anchorJson);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertThat(session.getMapper(AgentRunMapper.class).insert(run)).isEqualTo(1);
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
