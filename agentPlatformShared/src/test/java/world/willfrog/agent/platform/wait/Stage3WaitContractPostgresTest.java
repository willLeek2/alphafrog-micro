package world.willfrog.agent.platform.wait;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import world.willfrog.agent.platform.mapper.MigrationStatements;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import javax.sql.DataSource;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段三数据合同与等待链在真实 PostgreSQL 上的验证。
 *
 * <p>只在给了外部连接串时才跑：环境变量 {@code AF_STAGE3_PG_DSN}（形如
 * {@code postgresql://user:pass@host:5432/db}，或直接给 {@code jdbc:postgresql://...} 再加
 * {@code AF_STAGE3_PG_USER}/{@code AF_STAGE3_PG_PASSWORD}）。本机禁止起 Docker，所以本地一律跳过；
 * 证据要在负责人授权的外部 PostgreSQL 上跑出来。</p>
 *
 * <p>做法：建一个临时 schema，把连接串的 {@code currentSchema} 指到它 → 按 004 的形状造两张前置表
 * （Run 与工作项）→ 把 007 脚本的语句整份执行两遍（第二遍要一样通过，证明脚本可重复执行）→ 逐条插反例
 * 确认被约束拒绝 → 再用真的 MyBatis 语句跑并发用例（成员只结束一次、最后成员只产生一次恢复资格、
 * 组只齐备一次、旧领取提交为零、同一条恢复资格只被消费一次）→ 收尾删掉整个 schema。</p>
 *
 * <p>全程只碰临时 schema：所有连接都通过 {@code currentSchema} 落在它里面，既有库表一行都不动；
 * 万一中途进程被杀，残留的 schema 名字都带 {@code stage3_contract_} 前缀，可以直接删掉。</p>
 */
class Stage3WaitContractPostgresTest {

    private static final String STAGE3_SCRIPT = "007_agent_run_dag_wait_group.sql";
    private static final String SCHEMA = "stage3_contract_" + UUID.randomUUID().toString().replace("-", "");
    private static final int CONCURRENT_THREADS = 4;

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    // ==================== 起停 ====================

    @BeforeAll
    static void setUp() throws Exception {
        String dsn = env("AF_STAGE3_PG_DSN");
        Assumptions.assumeTrue(dsn != null && !dsn.isBlank(),
                "未提供 AF_STAGE3_PG_DSN：真库合同验证要在负责人授权的 PostgreSQL 上执行，本机不跑");
        Target target = resolveTarget(dsn);
        try (Connection connection = open(target, null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        dataSource = open(target, SCHEMA);
        createPrerequisities();
        applyStage3ScriptTwice();
        sqlSessionFactory = buildSessionFactory(dataSource);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    // ==================== 脚本可重复执行 ====================

    @Test
    void stage3ScriptOnlyAddsStructures() throws Exception {
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_wait_group")).isZero();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_scheduler_round")).isEqualTo(2);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_scheduler_capacity_state")).isEqualTo(1);
    }

    // ==================== 反例约束 ====================

    @Test
    void runIdempotencyColumnsArePairedAndUniquePerUser() throws Exception {
        String base = "INSERT INTO alphafrog_agent_run (id, user_id, status, scheduler_version) VALUES ";
        execute(base + "('run-idem-1', 'user-idem', 'RECEIVED', 'LEGACY')");
        // 键与摘要必须成对：只给键不给摘要、只给摘要不给键都要被拒。
        expectRejected("UPDATE alphafrog_agent_run SET idempotency_key = 'k-1' WHERE id = 'run-idem-1'",
                "alphafrog_agent_run_idempotency_pair_check");
        expectRejected("UPDATE alphafrog_agent_run SET request_digest = 'd-1' WHERE id = 'run-idem-1'",
                "alphafrog_agent_run_idempotency_pair_check");
        execute("UPDATE alphafrog_agent_run SET idempotency_key = 'k-1', request_digest = 'd-1' "
                + "WHERE id = 'run-idem-1'");
        // 同一个用户下同一个键只能有一条。
        execute(base + "('run-idem-2', 'user-idem', 'RECEIVED', 'LEGACY')");
        expectRejected("UPDATE alphafrog_agent_run SET idempotency_key = 'k-1', request_digest = 'd-2' "
                + "WHERE id = 'run-idem-2'", "uq_agent_run_user_idempotency_key");
        // 另一个用户用同一个键是允许的：唯一范围按用户分组。
        execute(base + "('run-idem-3', 'user-other', 'RECEIVED', 'LEGACY')");
        execute("UPDATE alphafrog_agent_run SET idempotency_key = 'k-1', request_digest = 'd-3' "
                + "WHERE id = 'run-idem-3'");
    }

    @Test
    void coordinationRejectsNodeDispatchReasonAndOldVersions() throws Exception {
        createRun("run-coord", 0, 0L);
        expectRejected("INSERT INTO alphafrog_agent_run_coordination "
                + "(run_id, scheduler_version, defer_reason) "
                + "VALUES ('run-coord', 'DUAL_POOL_V2', 'HINT_QUEUE_FULL')",
                "alphafrog_agent_run_coordination_defer_reason_check");
        expectRejected("INSERT INTO alphafrog_agent_run_coordination (run_id, scheduler_version) "
                + "VALUES ('run-coord', 'DUAL_POOL_V1')",
                "alphafrog_agent_run_coordination_scheduler_version_check");
        execute("INSERT INTO alphafrog_agent_run_coordination (run_id, scheduler_version, defer_reason) "
                + "VALUES ('run-coord', 'DUAL_POOL_V2', 'PER_ROUND_NEW_NODE_LIMIT')");
    }

    @Test
    void workItemDispatchDeferReasonOnlyAcceptsKnownReasons() throws Exception {
        createRun("run-dispatch", 0, 0L);
        createSegment("run-dispatch", 0, "node-1", 0, 0, 0, "worker-1", 1L, 0L, "EXECUTING");
        expectRejected("UPDATE alphafrog_agent_run_work_item SET dispatch_defer_reason = 'NOT_A_REASON' "
                + "WHERE run_id = 'run-dispatch'",
                "alphafrog_agent_run_work_item_dispatch_defer_reason_check");
        execute("UPDATE alphafrog_agent_run_work_item SET dispatch_defer_reason = 'HINT_QUEUE_FULL' "
                + "WHERE run_id = 'run-dispatch'");
    }

    @Test
    void waitGroupIsBoundToItsSegmentsAndOnlyBelongsToTheNewVersion() throws Exception {
        String runId = "run-group";
        createRun(runId, 4, 0L);
        createSegment(runId, 4, "node-1", 0, 0, 1, "worker-1", 3L, 0L, "EXECUTING");
        createSegment(runId, 4, "node-1", 0, 1, 0, null, 3L, 0L, "WAITING");

        String insert = "INSERT INTO alphafrog_agent_run_wait_group "
                + "(run_id, plan_generation, node_id, node_attempt, segment_sequence, model_turn, "
                + "scheduler_version, next_segment_sequence, state, expected_members, completed_members, ready_at) "
                + "VALUES ('" + runId + "', 4, 'node-1', 0, 0, 0, '%s', %d, '%s', 2, %d, %s)";

        expectRejected(String.format(insert, "DUAL_POOL_V1", 1, "WAITING", 0, "NULL"),
                "alphafrog_agent_run_wait_group_scheduler_version_check");
        // 下一段只能是当前段加一。
        expectRejected(String.format(insert, "DUAL_POOL_V2", 2, "WAITING", 0, "NULL"),
                "alphafrog_agent_run_wait_group_counter_check");
        // 下一段必须是一行真实工作项：给它一个没有对应行的分段序号（0+1 存在，所以换一个身份不存在的前置段）。
        execute("INSERT INTO alphafrog_agent_run_work_item "
                + "(run_id, plan_generation, node_id, node_attempt, segment_sequence, state, "
                + "context_version, run_control_version, scheduler_version) "
                + "VALUES ('" + runId + "', 4, 'node-2', 0, 0, 'EXECUTING', 3, 0, 'DUAL_POOL_V2')");
        execute("INSERT INTO alphafrog_agent_run_work_item "
                + "(run_id, plan_generation, node_id, node_attempt, segment_sequence, state, "
                + "context_version, run_control_version, scheduler_version) "
                + "VALUES ('" + runId + "', 4, 'node-2', 0, 1, 'WAITING', 3, 0, 'DUAL_POOL_V2')");
        execute("DELETE FROM alphafrog_agent_run_work_item "
                + "WHERE run_id = '" + runId + "' AND node_id = 'node-2' AND segment_sequence = 1");
        expectRejected("INSERT INTO alphafrog_agent_run_wait_group "
                + "(run_id, plan_generation, node_id, node_attempt, segment_sequence, model_turn, "
                + "scheduler_version, next_segment_sequence, state, expected_members) "
                + "VALUES ('" + runId + "', 4, 'node-2', 0, 0, 0, 'DUAL_POOL_V2', 1, 'WAITING', 2)",
                "alphafrog_agent_run_wait_group_next_segment_fk");
        // 齐备与已交接都必须留下完整性事实。
        expectRejected(String.format(insert, "DUAL_POOL_V2", 1, "READY", 1, "CURRENT_TIMESTAMP"),
                "alphafrog_agent_run_wait_group_ready_check");
        expectRejected(String.format(insert, "DUAL_POOL_V2", 1, "RESUMED", 2, "NULL"),
                "alphafrog_agent_run_wait_group_ready_check");
        expectRejected("INSERT INTO alphafrog_agent_run_wait_group "
                + "(run_id, plan_generation, node_id, node_attempt, segment_sequence, model_turn, "
                + "scheduler_version, next_segment_sequence, state, expected_members) "
                + "VALUES ('" + runId + "', 4, 'node-1', 0, 0, 0, 'DUAL_POOL_V2', 1, 'WAITING', 500)",
                "alphafrog_agent_run_wait_group_member_bound_check");
    }

    @Test
    void memberAndNotificationRunIdMustMatchTheirGroup() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-members", 2, 0L);
        long groupId = fixture.groupId();
        String otherRun = "run-other";
        createRun(otherRun, 0, 0L);

        String memberInsert = "INSERT INTO alphafrog_agent_run_wait_member "
                + "(group_id, run_id, member_seq, member_identity, tool_name, state) VALUES ";
        expectRejected(memberInsert + "(" + groupId + ", '" + otherRun + "', 99, "
                + "'foreign-member', 'tool', 'PENDING')",
                "alphafrog_agent_run_wait_member_group_fk");
        // 终态与结束时间要一一对应，两个方向都要管住。
        expectRejected(memberInsert + "(" + groupId + ", '" + fixture.runId() + "', 99, "
                + "'done-no-time', 'tool', 'SUCCEEDED')",
                "alphafrog_agent_run_wait_member_finished_check");
        expectRejected("INSERT INTO alphafrog_agent_run_wait_member "
                + "(group_id, run_id, member_seq, member_identity, tool_name, state, finished_at) "
                + "VALUES (" + groupId + ", '" + fixture.runId() + "', 99, 'time-no-done', 'tool', "
                + "'RUNNING', CURRENT_TIMESTAMP)",
                "alphafrog_agent_run_wait_member_finished_check");
        // 通知的 run_id 也要等于组的 run_id。
        expectRejected("INSERT INTO alphafrog_agent_run_recovery_notification "
                + "(group_id, run_id, recovery_generation) VALUES (" + groupId + ", '" + otherRun + "', 9)",
                "alphafrog_agent_run_recovery_notification_group_fk");
        expectRejected("INSERT INTO alphafrog_agent_run_recovery_notification "
                + "(group_id, run_id, recovery_generation, state) "
                + "VALUES (" + groupId + ", '" + fixture.runId() + "', 9, 'CONSUMED')",
                "alphafrog_agent_run_recovery_notification_consumed_check");
        expectRejected("INSERT INTO alphafrog_agent_run_recovery_notification "
                + "(group_id, run_id, recovery_generation, state, consumed_at) "
                + "VALUES (" + groupId + ", '" + fixture.runId() + "', 9, 'WAITING', CURRENT_TIMESTAMP)",
                "alphafrog_agent_run_recovery_notification_consumed_check");
    }

    @Test
    void externalOperationIdentityIsUniqueAcrossRuns() throws Exception {
        GroupFixture first = suspendSimpleGroup("run-op-a", 2, 0L);
        GroupFixture second = suspendSimpleGroup("run-op-b", 2, 0L);
        execute("UPDATE alphafrog_agent_run_wait_member SET external_operation_id = 'op-shared' "
                + "WHERE group_id = " + first.groupId() + " AND member_seq = 0");
        // 另一个 Run 的成员用同一个外部作业身份必须被拒：范围是全局，不是 Run 内。
        expectRejected("INSERT INTO alphafrog_agent_run_wait_member "
                + "(group_id, run_id, member_seq, member_identity, tool_name, external_operation_id, state) "
                + "VALUES (" + second.groupId() + ", '" + second.runId() + "', 99, 'op-member', 'tool', "
                + "'op-shared', 'PENDING')",
                "uq_agent_run_wait_member_operation");
    }

    @Test
    void capacityStateKeepsPauseFlagAndTimePaired() throws Exception {
        expectRejected("INSERT INTO alphafrog_agent_scheduler_capacity_state "
                + "(scope_key, unfinished_count, add_paused, paused_since, high_watermark, low_watermark) "
                + "VALUES ('PROBE-PAUSED-NO-TIME', 0, TRUE, NULL, 128, 96)",
                "alphafrog_agent_scheduler_capacity_state_paused_check");
        expectRejected("INSERT INTO alphafrog_agent_scheduler_capacity_state "
                + "(scope_key, unfinished_count, add_paused, paused_since, high_watermark, low_watermark) "
                + "VALUES ('PROBE-TIME-NO-PAUSE', 0, FALSE, CURRENT_TIMESTAMP, 128, 96)",
                "alphafrog_agent_scheduler_capacity_state_paused_check");
        expectRejected("INSERT INTO alphafrog_agent_scheduler_capacity_state "
                + "(scope_key, unfinished_count, add_paused, paused_since, high_watermark, low_watermark) "
                + "VALUES ('PROBE-BAD-WATERMARK', 0, FALSE, NULL, 90, 96)",
                "alphafrog_agent_scheduler_capacity_state_counter_check");
    }

    // ==================== 并发用例 ====================

    @Test
    void memberFinishesOnlyOnceUnderConcurrency() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-once", 2, 0L);
        List<MemberCompletionResult> results = runConcurrently(CONCURRENT_THREADS, ignored -> completeMember(
                fixture.groupId(), "call-a", WaitMemberState.SUCCEEDED, fixture.runControlVersion()));
        assertThat(results.stream().filter(MemberCompletionResult::applied).count())
                .as("同一个成员被四个线程同时报结束，只有一次算数")
                .isEqualTo(1);
        assertThat(results).as("没写进去的那几次要如实返回没写进去")
                .allSatisfy(result -> assertThat(result.memberState()).isNotNull());
        assertThat(groupCompletedMembers(fixture.groupId()))
                .as("结束计数不能重复加")
                .isEqualTo(1);
        assertThat(memberCount(fixture.groupId(), "call-a", "SUCCEEDED")).isEqualTo(1);
    }

    @Test
    void lastMemberProducesExactlyOneRecoveryEntitlement() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-ready", 2, 0L);
        assertThat(completeMember(fixture.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                fixture.runControlVersion()).applied()).isTrue();
        List<MemberCompletionResult> results = runConcurrently(CONCURRENT_THREADS, ignored -> completeMember(
                fixture.groupId(), "call-b", WaitMemberState.FAILED, fixture.runControlVersion()));
        assertThat(results.stream().filter(MemberCompletionResult::applied).count())
                .as("最后一个成员也只有一次算数")
                .isEqualTo(1);
        assertThat(results.stream().filter(MemberCompletionResult::groupBecameReady).count())
                .as("恢复资格只产生一次")
                .isEqualTo(1);
        assertThat(notificationCount(fixture.groupId()))
                .as("库里只能有一条恢复通知")
                .isEqualTo(1);
        WaitGroup group = groupById(fixture.groupId());
        assertThat(group.getState()).isEqualTo(WaitGroupState.READY.name());
        assertThat(group.getCompletedMembers()).isEqualTo(2);
        assertThat(group.getRecoveryGeneration()).isEqualTo(1);
    }

    @Test
    void twoMembersFinishingTogetherMakeTheGroupReadyOnce() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-race", 2, 0L);
        List<MemberCompletionResult> results = runConcurrently(2, index -> completeMember(
                fixture.groupId(), index == 0 ? "call-a" : "call-b",
                WaitMemberState.SUCCEEDED, fixture.runControlVersion()));
        assertThat(results.stream().filter(MemberCompletionResult::groupBecameReady).count())
                .as("两个成员同时结束，组也只能齐备一次")
                .isEqualTo(1);
        assertThat(notificationCount(fixture.groupId())).isEqualTo(1);
        WaitGroup group = groupById(fixture.groupId());
        assertThat(group.getState()).isEqualTo(WaitGroupState.READY.name());
        assertThat(group.getCompletedMembers()).isEqualTo(group.getExpectedMembers());
    }

    @Test
    void staleClaimSubmissionWritesNothing() throws Exception {
        String runId = "run-stale";
        createRun(runId, 0, 0L);
        createSegment(runId, 0, "node-1", 0, 0, 0, null, 1L, 0L, "RUNNABLE");
        NodeWorkItemIdentity identity = new NodeWorkItemIdentity(runId, 0, "node-1", 0, 0);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NodeWorkItemMapper mapper = session.getMapper(NodeWorkItemMapper.class);
            assertThat(mapper.claim(runId, 0, "node-1", 0, 0, "DUAL_POOL_V2", 1L, 0L,
                    "worker-1", java.time.OffsetDateTime.now().plusMinutes(1)))
                    .as("第一次领取成功").isEqualTo(1);
            assertThat(mapper.claim(runId, 0, "node-1", 0, 0, "DUAL_POOL_V2", 1L, 0L,
                    "worker-2", java.time.OffsetDateTime.now().plusMinutes(1)))
                    .as("重复领取加不到行").isNull();
            assertThat(mapper.startExecution(runId, 0, "node-1", 0, 0, 1, "worker-1")).isEqualTo(1);
            assertThat(mapper.handOverClaim(runId, 0, "node-1", 0, 0, 1, "worker-2",
                    java.time.OffsetDateTime.now().plusMinutes(1)))
                    .as("显式转交把代际推到 2").isEqualTo(2);
            assertThat(mapper.commitSegmentResult(runId, 0, "node-1", 0, 0, 1L, 0L, 1, "{}"))
                    .as("旧领取者拿着旧代际提交，一行都写不进去").isZero();
            assertThat(mapper.commitSegmentResult(runId, 0, "node-1", 0, 0, 1L, 0L, 2, "{\"ok\":1}"))
                    .as("新领取者提交成功").isEqualTo(1);
        }
    }

    @Test
    void recoveryEntitlementIsConsumedOnlyOnce() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-consume", 1, 0L);
        MemberCompletionResult completion = completeMember(fixture.groupId(), "call-a",
                WaitMemberState.SUCCEEDED, fixture.runControlVersion());
        assertThat(completion.notificationId()).isNotNull();
        long notificationId = completion.notificationId();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            RecoveryConsumptionResult wrongVersion = store.consumeRecovery(notificationId, "dispatcher-1",
                    fixture.runControlVersion() + 7);
            assertThat(wrongVersion.consumed())
                    .as("控制版本不符时通知取不走，下一段也不会被放行").isFalse();
            RecoveryConsumptionResult first = store.consumeRecovery(notificationId, "dispatcher-1",
                    fixture.runControlVersion());
            assertThat(first.consumed()).isTrue();
            assertThat(first.promoted()).isTrue();
            RecoveryConsumptionResult second = store.consumeRecovery(notificationId, "dispatcher-2",
                    fixture.runControlVersion());
            assertThat(second.consumed())
                    .as("同一代际的恢复资格只能被取走一次").isFalse();
            assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_work_item "
                    + "WHERE run_id = '" + fixture.runId() + "' AND state = 'RESUMABLE'"))
                    .as("下一段只被放行一次").isEqualTo(1);
        }
    }

    // ==================== 语句与工具 ====================

    private static void createPrerequisities() throws Exception {
        execute("""
                CREATE TABLE alphafrog_agent_run (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64),
                    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
                    scheduler_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
                    plan_generation INT NOT NULL DEFAULT -1,
                    run_control_version BIGINT NOT NULL DEFAULT 0,
                    CONSTRAINT alphafrog_agent_run_scheduler_version_check
                        CHECK (scheduler_version IN ('LEGACY', 'DUAL_POOL_V1'))
                )
                """);
        execute("""
                CREATE TABLE alphafrog_agent_run_work_item (
                    id BIGSERIAL PRIMARY KEY,
                    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
                    plan_generation INT NOT NULL,
                    node_id VARCHAR(256) NOT NULL,
                    node_attempt INT NOT NULL DEFAULT 0,
                    segment_sequence INT NOT NULL DEFAULT 0,
                    state VARCHAR(32) NOT NULL DEFAULT 'RUNNABLE',
                    context_version BIGINT NOT NULL,
                    run_control_version BIGINT NOT NULL,
                    claim_epoch INT NOT NULL DEFAULT 0,
                    scheduler_version VARCHAR(32) NOT NULL DEFAULT 'DUAL_POOL_V1',
                    claimed_by VARCHAR(128),
                    lease_expires_at TIMESTAMPTZ,
                    next_visible_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT alphafrog_agent_run_work_item_identity_key
                        UNIQUE (run_id, plan_generation, node_id, node_attempt, segment_sequence),
                    CONSTRAINT alphafrog_agent_run_work_item_scheduler_version_check
                        CHECK (scheduler_version IN ('LEGACY', 'DUAL_POOL_V1'))
                )
                """);
    }

    /** 整份执行两遍：第二遍必须一样通过，证明脚本可以重复执行。 */
    private static void applyStage3ScriptTwice() throws Exception {
        List<String> statements = MigrationStatements.split(MigrationStatements.read(STAGE3_SCRIPT));
        assertThat(statements).as("脚本要能被切成可执行语句").isNotEmpty();
        for (int round = 1; round <= 2; round++) {
            for (String statement : statements) {
                try {
                    execute(statement);
                } catch (SQLException e) {
                    throw new IllegalStateException("第 " + round + " 遍执行脚本失败：" + statement, e);
                }
            }
        }
    }

    private static SqlSessionFactory buildSessionFactory(DataSource source) throws Exception {
        Configuration configuration = new Configuration(
                new Environment("stage3-postgres", new JdbcTransactionFactory(), source));
        for (String resource : List.of("mapper/WaitGroupMapper.xml", "mapper/NodeWorkItemMapper.xml")) {
            try (InputStream xml = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void createRun(String runId, int planGeneration, long runControlVersion) throws Exception {
        execute("INSERT INTO alphafrog_agent_run (id, user_id, status, scheduler_version, "
                + "plan_generation, run_control_version) VALUES ('" + runId + "', 'user-1', "
                + "'EXECUTING', 'DUAL_POOL_V2', " + planGeneration + ", " + runControlVersion + ")");
    }

    private static void createSegment(String runId, int planGeneration, String nodeId, int nodeAttempt,
                                      int segmentSequence, int claimEpoch, String claimedBy,
                                      long contextVersion, long runControlVersion, String state)
            throws Exception {
        execute("INSERT INTO alphafrog_agent_run_work_item (run_id, plan_generation, node_id, "
                + "node_attempt, segment_sequence, state, context_version, run_control_version, "
                + "claim_epoch, claimed_by, scheduler_version) VALUES ('" + runId + "', "
                + planGeneration + ", '" + nodeId + "', " + nodeAttempt + ", " + segmentSequence + ", '"
                + state + "', " + contextVersion + ", " + runControlVersion + ", " + claimEpoch + ", "
                + (claimedBy == null ? "NULL" : "'" + claimedBy + "'") + ", 'DUAL_POOL_V2')");
    }

    private record GroupFixture(String runId, long groupId, long runControlVersion) {
    }

    /** 造一个「两个成员、都在等」的等待组，用它跑成员结束相关的用例。 */
    private static GroupFixture suspendSimpleGroup(String runId, int memberCount, long runControlVersion)
            throws Exception {
        createRun(runId, 0, runControlVersion);
        createSegment(runId, 0, "node-1", 0, 0, 3, "worker-1", 2L, runControlVersion, "EXECUTING");
        List<WaitMemberDraft> members = new ArrayList<>();
        for (int index = 0; index < memberCount; index++) {
            members.add(new WaitMemberDraft(index, "call-" + (char) ('a' + index), "executePython",
                    null));
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            WaitSuspensionResult result = store.suspendSegment(new WaitSuspensionRequest(
                    new NodeWorkItemIdentity(runId, 0, "node-1", 0, 0),
                    new NodeWorkItemVersions(2L, runControlVersion, 3),
                    "worker-1", 0, SchedulerVersion.DUAL_POOL_V2, members,
                    "{\"waitSuspension\":true}", "{\"checkpoint\":\"c-1\"}"));
            assertThat(result.suspended()).as("等待组要真的建起来：" + result).isTrue();
            assertThat(result.groupId()).isNotNull();
            return new GroupFixture(runId, result.groupId(), runControlVersion);
        }
    }

    private static MemberCompletionResult completeMember(long groupId, String memberIdentity,
                                                         WaitMemberState state, long runControlVersion) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            return store.completeMember(new MemberCompletionRequest(groupId, memberIdentity, state,
                    "{\"result\":\"ok\"}", null, runControlVersion));
        }
    }

    private static WaitGroup groupById(long groupId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class))
                    .findGroup(groupId).orElseThrow();
        }
    }

    private static int groupCompletedMembers(long groupId) {
        return groupById(groupId).getCompletedMembers();
    }

    private static int notificationCount(long groupId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class))
                    .listNotifications(groupId).size();
        }
    }

    private static long memberCount(long groupId, String memberIdentity, String state) throws Exception {
        return countRows("SELECT count(*) FROM alphafrog_agent_run_wait_member WHERE group_id = "
                + groupId + " AND member_identity = '" + memberIdentity + "' AND state = '" + state + "'");
    }

    /** 多线程同时做同一件事；每次调用各自开一个连接，和线上并发走的是同一条路。 */
    private static <T> List<T> runConcurrently(int threads, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < threads; index++) {
                int slot = index;
                futures.add(pool.submit(() -> {
                    startGate.await(10, TimeUnit.SECONDS);
                    return task.apply(slot);
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long countRows(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** 插一条应当被约束拒绝的语句：报错里必须点名是那条约束，否则说明拒绝它的不是我们要证明的那条。 */
    private static void expectRejected(String sql, String constraintName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            if (constraintName != null) {
                assertThat(e.getMessage()).as("拒绝这条语句的应当是 " + constraintName).contains(constraintName);
            }
            return;
        }
        throw new AssertionError("这条语句本该被约束拒绝，却写进去了：" + sql);
    }

    // ==================== 连接串 ====================

    private record Target(String jdbcUrl, String user, String password) {
    }

    /** 支持 postgres(ql)://user:pass@host:port/db 与已经写好的 jdbc:postgresql:// 两种写法。 */
    private static Target resolveTarget(String dsn) {
        String user = env("AF_STAGE3_PG_USER");
        String password = env("AF_STAGE3_PG_PASSWORD");
        if (dsn.startsWith("jdbc:")) {
            return new Target(dsn, user, password);
        }
        URI uri = URI.create(dsn);
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int separator = userInfo.indexOf(':');
            if (user == null && separator > 0) {
                user = decode(userInfo.substring(0, separator));
            }
            if (password == null && separator > 0) {
                password = decode(userInfo.substring(separator + 1));
            }
        }
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String database = uri.getPath() == null || uri.getPath().isEmpty() ? "/postgres" : uri.getPath();
        return new Target("jdbc:postgresql://" + host + ":" + port + database, user, password);
    }

    private static DataSource open(Target target, String schema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        String url = target.jdbcUrl();
        if (schema != null) {
            url = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        dataSource.setUrl(url);
        if (target.user() != null) {
            dataSource.setUser(target.user());
        }
        if (target.password() != null) {
            dataSource.setPassword(target.password());
        }
        return dataSource;
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String env(String name) {
        return System.getenv(name);
    }
}
