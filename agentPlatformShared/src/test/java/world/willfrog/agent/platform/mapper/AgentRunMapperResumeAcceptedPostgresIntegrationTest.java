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

    // ------------------------------------------------------------------
    // ACCEPTED 路径：acceptResumeHandoff 将 LAUNCHING 推进为 ACCEPTED
    // ------------------------------------------------------------------

    @Test
    void acceptResumeHandoffFromLaunchingCreatesAccepted() {
        Instant lease = Instant.now().plusSeconds(60);
        ToolJobAnchor launching = resumeAnchor("acc-1:tc-1:1", "LAUNCHING", false);
        launching.setResumeToken("tok-1");
        launching.setResumeLeaseVersion(5);
        launching.setResumeLauncherOwnerId("owner-1");
        launching.setResumeLauncherLeaseUntil(lease);
        launching.setResultConsumed(true);

        insertRun("run-acc-1", AgentRunStatus.RECEIVED, launching.toJson());

        ToolJobAnchor accepted = resumeAnchor("acc-1:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-1");
        accepted.setResumeLeaseVersion(5);
        accepted.setResumeLauncherOwnerId("owner-1");
        accepted.setResultConsumed(true);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            // acceptResumeHandoff 要求 DB 中是 LAUNCHING，不能已经是 ACCEPTED
            assertThat(mapper.acceptResumeHandoff(
                    "run-acc-1", accepted.toJson(),
                    "tok-1", 5L, "owner-1", 30L))
                    .as("acceptResumeHandoff: LAUNCHING→ACCEPTED must succeed")
                    .isEqualTo(1);

            // 验证 DB 状态
            AgentRun run = mapper.findById("run-acc-1");
            assertThat(run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
            ToolJobAnchor persisted = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
            assertThat(persisted.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(persisted.isResultConsumed()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // ACCEPTED 路径：takeoverExpiredResumeLauncher
    // ------------------------------------------------------------------

    @Test
    void takeoverExpiredAcceptedSucceeds() {
        Instant expiredLease = Instant.now().minusSeconds(10);
        ToolJobAnchor accepted = resumeAnchor("acc-2:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-2");
        accepted.setResumeLeaseVersion(7);
        accepted.setResumeLauncherOwnerId("owner-old");
        accepted.setResumeLauncherLeaseUntil(expiredLease);
        accepted.setResumeClaimedAt(Instant.now().minusSeconds(60));
        accepted.setResultConsumed(true);

        insertRun("run-acc-2", AgentRunStatus.EXECUTING, accepted.toJson());

        ToolJobAnchor takeover = resumeAnchor("acc-2:tc-1:1", "ACCEPTED", true);
        takeover.setResumeToken("tok-2-new");
        takeover.setResumeLeaseVersion(8);
        takeover.setResumeLauncherOwnerId("owner-new");
        takeover.setResultConsumed(true);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            // ACCEPTED + 过期 lease → takeover 成功
            assertThat(mapper.takeoverExpiredResumeLauncher(
                    "run-acc-2", takeover.toJson(),
                    AgentRunStatus.EXECUTING,
                    "tok-2", 7L, "owner-old",
                    "owner-new", 30L, 120L))
                    .as("takeoverExpiredResumeLauncher: ACCEPTED+expired must succeed")
                    .isEqualTo(1);

            // 验证新 owner
            ToolJobAnchor persisted = ToolJobAnchor.fromJson(
                    mapper.findById("run-acc-2").getToolJobAnchorJson());
            assertThat(persisted.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(persisted.getResumeToken()).isEqualTo("tok-2-new");
            assertThat(persisted.getResumeLeaseVersion()).isEqualTo(8);
            assertThat(persisted.getResumeLauncherOwnerId()).isEqualTo("owner-new");
        }
    }

    @Test
    void takeoverAcceptedWithUnexpiredLeaseFails() {
        Instant activeLease = Instant.now().plusSeconds(300);
        ToolJobAnchor accepted = resumeAnchor("acc-2b:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-2b");
        accepted.setResumeLeaseVersion(5);
        accepted.setResumeLauncherOwnerId("owner-old");
        accepted.setResumeLauncherLeaseUntil(activeLease);
        accepted.setResumeClaimedAt(Instant.now().minusSeconds(60));
        accepted.setResultConsumed(true);

        insertRun("run-acc-2b", AgentRunStatus.EXECUTING, accepted.toJson());

        ToolJobAnchor takeover = resumeAnchor("acc-2b:tc-1:1", "ACCEPTED", true);
        takeover.setResumeToken("tok-2b-new");
        takeover.setResumeLeaseVersion(6);
        takeover.setResumeLauncherOwnerId("owner-new");
        takeover.setResultConsumed(true);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            // ACCEPTED + 未过期 lease → takeover 失败
            assertThat(mapper.takeoverExpiredResumeLauncher(
                    "run-acc-2b", takeover.toJson(),
                    AgentRunStatus.EXECUTING,
                    "tok-2b", 5L, "owner-old",
                    "owner-new", 30L, 120L))
                    .as("takeoverExpiredResumeLauncher: unexpired lease must return 0")
                    .isZero();

            // DB 状态不变
            ToolJobAnchor persisted = ToolJobAnchor.fromJson(
                    mapper.findById("run-acc-2b").getToolJobAnchorJson());
            assertThat(persisted.getResumeToken()).isEqualTo("tok-2b");
        }
    }

    // ------------------------------------------------------------------
    // ACCEPTED 路径：heartbeatResumeLauncher
    // ------------------------------------------------------------------

    @Test
    void heartbeatAcceptedSucceeds() {
        Instant activeLease = Instant.now().plusSeconds(60);
        ToolJobAnchor accepted = resumeAnchor("acc-3:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-3");
        accepted.setResumeLeaseVersion(3);
        accepted.setResumeLauncherOwnerId("owner-3");
        accepted.setResumeLauncherLeaseUntil(activeLease);
        accepted.setResultConsumed(true);

        insertRun("run-acc-3", AgentRunStatus.EXECUTING, accepted.toJson());

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            assertThat(mapper.heartbeatResumeLauncher(
                    "run-acc-3", "tok-3", 3L, "owner-3", 30L))
                    .as("heartbeatResumeLauncher: ACCEPTED+active lease must succeed")
                    .isEqualTo(1);
        }
    }

    @Test
    void heartbeatAcceptedWithWrongOwnerFails() {
        Instant activeLease = Instant.now().plusSeconds(60);
        ToolJobAnchor accepted = resumeAnchor("acc-3b:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-3b");
        accepted.setResumeLeaseVersion(3);
        accepted.setResumeLauncherOwnerId("owner-3b");
        accepted.setResumeLauncherLeaseUntil(activeLease);
        accepted.setResultConsumed(true);

        insertRun("run-acc-3b", AgentRunStatus.EXECUTING, accepted.toJson());

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            // 错误 owner → rows=0
            assertThat(mapper.heartbeatResumeLauncher(
                    "run-acc-3b", "tok-3b", 3L, "owner-wrong", 30L))
                    .as("heartbeatResumeLauncher: wrong owner must return 0")
                    .isZero();

            // 错误 version → rows=0
            assertThat(mapper.heartbeatResumeLauncher(
                    "run-acc-3b", "tok-3b", 99L, "owner-3b", 30L))
                    .as("heartbeatResumeLauncher: wrong version must return 0")
                    .isZero();
        }
    }

    // ------------------------------------------------------------------
    // ACCEPTED 路径：updateResumedTerminal
    // ------------------------------------------------------------------

    @Test
    void updateResumedTerminalWithAcceptedSucceeds() {
        Instant activeLease = Instant.now().plusSeconds(60);
        ToolJobAnchor accepted = resumeAnchor("acc-4:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-4");
        accepted.setResumeLeaseVersion(4);
        accepted.setResumeLauncherOwnerId("owner-4");
        accepted.setResumeLauncherLeaseUntil(activeLease);
        accepted.setResultConsumed(true);

        insertRun("run-acc-4", AgentRunStatus.EXECUTING, accepted.toJson());

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            assertThat(mapper.updateResumedTerminal(
                    "run-acc-4", "user-1",
                    AgentRunStatus.COMPLETED,
                    "{\"steps\":[]}", "{\"snap\":1}",
                    true, null,
                    "tok-4", 4L, "owner-4"))
                    .as("updateResumedTerminal: ACCEPTED+consumed must succeed")
                    .isEqualTo(1);

            AgentRun run = mapper.findById("run-acc-4");
            assertThat(run.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
        }
    }

    @Test
    void updateResumedTerminalWithWrongVersionFails() {
        Instant activeLease = Instant.now().plusSeconds(60);
        ToolJobAnchor accepted = resumeAnchor("acc-4b:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-4b");
        accepted.setResumeLeaseVersion(4);
        accepted.setResumeLauncherOwnerId("owner-4b");
        accepted.setResumeLauncherLeaseUntil(activeLease);
        accepted.setResultConsumed(true);

        insertRun("run-acc-4b", AgentRunStatus.EXECUTING, accepted.toJson());

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            assertThat(mapper.updateResumedTerminal(
                    "run-acc-4b", "user-1",
                    AgentRunStatus.COMPLETED,
                    "{\"steps\":[]}", "{\"snap\":1}",
                    true, null,
                    "tok-4b", 99L, "owner-4b"))
                    .as("updateResumedTerminal: wrong version must return 0")
                    .isZero();
        }
    }

    // ------------------------------------------------------------------
    // ACCEPTED 路径：clearAcceptedResumeHandoff
    // ------------------------------------------------------------------

    @Test
    void clearAcceptedResumeHandoffWithAcceptedSucceeds() {
        ToolJobAnchor accepted = resumeAnchor("acc-5:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-5");
        accepted.setResumeLeaseVersion(5);
        accepted.setResumeLauncherOwnerId("owner-5");
        accepted.setResultConsumed(true);

        insertRun("run-acc-5", AgentRunStatus.COMPLETED, accepted.toJson());

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            assertThat(mapper.clearAcceptedResumeHandoff(
                    "run-acc-5", "tok-5", 5L, "owner-5"))
                    .as("clearAcceptedResumeHandoff: ACCEPTED+COMPLETED must succeed")
                    .isEqualTo(1);

            // Anchor 已清空为 {}
            assertThat(mapper.findById("run-acc-5").getToolJobAnchorJson())
                    .isEqualTo("{}");
        }
    }

    // ------------------------------------------------------------------
    // Legacy 兼容：原始 JSON 直接入 PG，验证 production mapper 处理
    // ------------------------------------------------------------------

    @Test
    void legacyLaunchingWithConsumedTrueTakeoverSucceeds() {
        // 直接插入 D12 之前的原始 JSON（LAUNCHING + resultConsumed=true），
        // 不经过 fromJson 归一化，验证 production mapper SQL 能正确处理。
        Instant expiredLease = Instant.now().minusSeconds(10);
        String legacyJson = "{\"schemaVersion\":1," +
                "\"operationId\":\"legacy-1:tc-1:1\"," +
                "\"resumeState\":\"LAUNCHING\"," +
                "\"resumeToken\":\"legacy-tok\"," +
                "\"resumeLeaseVersion\":5," +
                "\"resumeLauncherOwnerId\":\"owner-legacy\"," +
                "\"resumeLauncherLeaseUntil\":\"" + expiredLease + "\"," +
                "\"resumeClaimedAt\":\"" + Instant.now().minusSeconds(60) + "\"," +
                "\"resultConsumed\":true}";

        insertRun("run-legacy-1", AgentRunStatus.RECEIVED, legacyJson);

        // 构造 takeover anchor：token/version 递增，owner 更换
        ToolJobAnchor takeover = resumeAnchor("legacy-1:tc-1:1", "ACCEPTED", true);
        takeover.setResumeToken("legacy-tok-new");
        takeover.setResumeLeaseVersion(6);
        takeover.setResumeLauncherOwnerId("owner-new");
        takeover.setResultConsumed(true);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            // takeover 成功：SQL 匹配 LAUNCHING（原始 JSON 中的值）
            assertThat(mapper.takeoverExpiredResumeLauncher(
                    "run-legacy-1", takeover.toJson(),
                    AgentRunStatus.RECEIVED,
                    "legacy-tok", 5L, "owner-legacy",
                    "owner-new", 30L, 120L))
                    .as("takeover of legacy LAUNCHING+true JSON must succeed")
                    .isEqualTo(1);

            // DB 中的 JSON 已更新为新 token/version/owner
            ToolJobAnchor persisted = ToolJobAnchor.fromJson(
                    mapper.findById("run-legacy-1").getToolJobAnchorJson());
            assertThat(persisted.getResumeToken()).isEqualTo("legacy-tok-new");
            assertThat(persisted.getResumeLeaseVersion()).isEqualTo(6);
            assertThat(persisted.getResumeLauncherOwnerId()).isEqualTo("owner-new");
            // fromJson 归一化：LAUNCHING+true → ACCEPTED
            assertThat(persisted.getResumeState()).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void contradictoryReadyWithConsumedTrueIsRejectedByMappers() {
        // 直接插入矛盾的 READY + resultConsumed=true JSON
        String contradictoryJson = "{\"schemaVersion\":1," +
                "\"operationId\":\"bad-1:tc-1:1\"," +
                "\"resumeState\":\"READY\"," +
                "\"resumeToken\":\"bad-tok\"," +
                "\"resumeLeaseVersion\":1," +
                "\"resultConsumed\":true}";

        insertRun("run-bad-1", AgentRunStatus.RECEIVED, contradictoryJson);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            // claimResumeLauncher 拒绝：READY + resultConsumed=true 不能 claim
            ToolJobAnchor claimAnchor = resumeAnchor("bad-1:tc-1:1", "LAUNCHING", false);
            claimAnchor.setResumeToken("bad-tok-new");
            claimAnchor.setResumeLeaseVersion(2);
            assertThat(mapper.claimResumeLauncher(
                    "run-bad-1", claimAnchor.toJson(),
                    AgentRunStatus.RECEIVED, AgentRunStatus.RECEIVED,
                    "bad-tok", 1L, "owner-x", 30L))
                    .as("claimResumeLauncher: READY+true must return 0")
                    .isZero();

            // listResumeReadyAnchors 不包含矛盾行
            assertThat(mapper.listResumeReadyAnchors(20).stream()
                    .map(world.willfrog.agent.platform.entity.AgentRun::getId))
                    .as("listResumeReadyAnchors must exclude READY+true")
                    .doesNotContain("run-bad-1");

            // DB 不变
            assertThat(mapper.findById("run-bad-1").getToolJobAnchorJson())
                    .contains("\"resumeState\":\"READY\"")
                    .contains("\"resultConsumed\":true");
        }

        // fromJson 仍然 fail-closed（Java 层防御）
        assertThatThrownBy(() -> ToolJobAnchor.fromJson(contradictoryJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contradictory");
    }

    // ------------------------------------------------------------------
    // LAUNCHING 回归守卫：已有流程仍正常
    // ------------------------------------------------------------------

    @Test
    void takeoverExpiredLaunchingStillWorks() {
        Instant expiredLease = Instant.now().minusSeconds(10);
        ToolJobAnchor launching = resumeAnchor("launch-1:tc-1:1", "LAUNCHING", false);
        launching.setResumeToken("tok-launch");
        launching.setResumeLeaseVersion(3);
        launching.setResumeLauncherOwnerId("owner-old");
        launching.setResumeLauncherLeaseUntil(expiredLease);
        launching.setResumeClaimedAt(Instant.now().minusSeconds(60));

        insertRun("run-launch-1", AgentRunStatus.RECEIVED, launching.toJson());

        ToolJobAnchor takeover = resumeAnchor("launch-1:tc-1:1", "LAUNCHING", false);
        takeover.setResumeToken("tok-launch-new");
        takeover.setResumeLeaseVersion(4);
        takeover.setResumeLauncherOwnerId("owner-new");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            assertThat(mapper.takeoverExpiredResumeLauncher(
                    "run-launch-1", takeover.toJson(),
                    AgentRunStatus.RECEIVED,
                    "tok-launch", 3L, "owner-old",
                    "owner-new", 30L, 120L))
                    .as("takeoverExpiredResumeLauncher: LAUNCHING+expired must still succeed")
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // 栅栏：acceptResumeHandoff 拒绝非 LAUNCHING 状态
    // ------------------------------------------------------------------

    @Test
    void acceptResumeHandoffRejectsAlreadyAccepted() {
        Instant lease = Instant.now().plusSeconds(60);
        // DB has ACCEPTED (not LAUNCHING)
        ToolJobAnchor accepted = resumeAnchor("fence-1:tc-1:1", "ACCEPTED", true);
        accepted.setResumeToken("tok-fence");
        accepted.setResumeLeaseVersion(1);
        accepted.setResumeLauncherOwnerId("owner-fence");
        accepted.setResumeLauncherLeaseUntil(lease);
        accepted.setResultConsumed(true);

        insertRun("run-fence-1", AgentRunStatus.EXECUTING, accepted.toJson());

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);

            // acceptResumeHandoff requires LAUNCHING — ACCEPTED must fail
            assertThat(mapper.acceptResumeHandoff(
                    "run-fence-1", accepted.toJson(),
                    "tok-fence", 1L, "owner-fence", 30L))
                    .as("acceptResumeHandoff: already ACCEPTED must return 0")
                    .isZero();
        }
    }

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
