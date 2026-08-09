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
 * Production PostgreSQL seam: proves ACCEPTED resumeState flows through the
 * real {@code AgentRunMapper.xml} MyBatis mapper against a live database.
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
    // ACCEPTED path: acceptResumeHandoff advances LAUNCHING → ACCEPTED
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

            // acceptResumeHandoff expects LAUNCHING, not ACCEPTED
            assertThat(mapper.acceptResumeHandoff(
                    "run-acc-1", accepted.toJson(),
                    "tok-1", 5L, "owner-1", 30L))
                    .as("acceptResumeHandoff: LAUNCHING→ACCEPTED must succeed")
                    .isEqualTo(1);

            // Verify DB state
            AgentRun run = mapper.findById("run-acc-1");
            assertThat(run.getStatus()).isEqualTo(AgentRunStatus.EXECUTING);
            ToolJobAnchor persisted = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
            assertThat(persisted.getResumeState()).isEqualTo("ACCEPTED");
            assertThat(persisted.isResultConsumed()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // ACCEPTED path: takeoverExpiredResumeLauncher
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

            // ACCEPTED + expired lease → takeover succeeds
            assertThat(mapper.takeoverExpiredResumeLauncher(
                    "run-acc-2", takeover.toJson(),
                    AgentRunStatus.EXECUTING,
                    "tok-2", 7L, "owner-old",
                    "owner-new", 30L, 120L))
                    .as("takeoverExpiredResumeLauncher: ACCEPTED+expired must succeed")
                    .isEqualTo(1);

            // Verify new owner
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

            // ACCEPTED + unexpired lease → takeover fails
            assertThat(mapper.takeoverExpiredResumeLauncher(
                    "run-acc-2b", takeover.toJson(),
                    AgentRunStatus.EXECUTING,
                    "tok-2b", 5L, "owner-old",
                    "owner-new", 30L, 120L))
                    .as("takeoverExpiredResumeLauncher: unexpired lease must return 0")
                    .isZero();

            // DB state unchanged
            ToolJobAnchor persisted = ToolJobAnchor.fromJson(
                    mapper.findById("run-acc-2b").getToolJobAnchorJson());
            assertThat(persisted.getResumeToken()).isEqualTo("tok-2b");
        }
    }

    // ------------------------------------------------------------------
    // ACCEPTED path: heartbeatResumeLauncher
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

            // Wrong owner → rows=0
            assertThat(mapper.heartbeatResumeLauncher(
                    "run-acc-3b", "tok-3b", 3L, "owner-wrong", 30L))
                    .as("heartbeatResumeLauncher: wrong owner must return 0")
                    .isZero();

            // Wrong version → rows=0
            assertThat(mapper.heartbeatResumeLauncher(
                    "run-acc-3b", "tok-3b", 99L, "owner-3b", 30L))
                    .as("heartbeatResumeLauncher: wrong version must return 0")
                    .isZero();
        }
    }

    // ------------------------------------------------------------------
    // ACCEPTED path: updateResumedTerminal
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
    // ACCEPTED path: clearAcceptedResumeHandoff
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

            // Anchor cleared to empty JSON
            assertThat(mapper.findById("run-acc-5").getToolJobAnchorJson())
                    .isEqualTo("{}");
        }
    }

    // ------------------------------------------------------------------
    // Legacy normalization: fromJson upgrades old data
    // ------------------------------------------------------------------

    @Test
    void legacyLaunchingWithResultConsumedTrueNormalizedToAccepted() {
        // Simulate old JSON written before D12: LAUNCHING + resultConsumed=true
        String legacyJson = "{\"schemaVersion\":1," +
                "\"operationId\":\"legacy-1:tc-1:1\"," +
                "\"resumeState\":\"LAUNCHING\"," +
                "\"resumeToken\":\"legacy-tok\"," +
                "\"resumeLeaseVersion\":5," +
                "\"resumeLauncherOwnerId\":\"owner-legacy\"," +
                "\"resumeLauncherLeaseUntil\":\"" + Instant.now().plusSeconds(30) + "\"," +
                "\"resultConsumed\":true}";

        // fromJson normalizes LAUNCHING+true → ACCEPTED
        ToolJobAnchor normalized = ToolJobAnchor.fromJson(legacyJson);
        assertThat(normalized.getResumeState())
                .as("legacy LAUNCHING+resultConsumed=true must normalize to ACCEPTED")
                .isEqualTo("ACCEPTED");
        assertThat(normalized.isResultConsumed()).isTrue();

        // The normalized anchor can be used in acceptResumeHandoff (proving mapper compatibility)
        insertRun("run-legacy-1", AgentRunStatus.RECEIVED, normalized.toJson());
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            assertThat(mapper.findById("run-legacy-1").getToolJobAnchorJson())
                    .contains("\"resumeState\":\"ACCEPTED\"")
                    .contains("\"resultConsumed\":true");
        }
    }

    @Test
    void contradictoryReadyWithResultConsumedTrueThrows() {
        // READY + resultConsumed=true is contradictory — must fail closed
        String contradictoryJson = "{\"schemaVersion\":1," +
                "\"operationId\":\"bad-1:tc-1:1\"," +
                "\"resumeState\":\"READY\"," +
                "\"resultConsumed\":true}";

        assertThatThrownBy(() -> ToolJobAnchor.fromJson(contradictoryJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contradictory");
    }

    // ------------------------------------------------------------------
    // LAUNCHING regression guard: existing flows still work
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
    // Fence: acceptResumeHandoff rejects non-LAUNCHING
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
    // helpers
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
