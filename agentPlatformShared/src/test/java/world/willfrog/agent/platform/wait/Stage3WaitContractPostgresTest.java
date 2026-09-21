package world.willfrog.agent.platform.wait;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import world.willfrog.agent.platform.capacity.MybatisSchedulerStateStore;
import world.willfrog.agent.platform.capacity.SchedulerPauseDecision;
import world.willfrog.agent.platform.capacity.SchedulerStateStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.service.AgentRunEventProjectionRepair;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.coordination.MybatisRunCoordinationStore;
import world.willfrog.agent.platform.lease.MybatisRunServiceLeaseStore;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.mapper.RunServiceLeaseMapper;
import world.willfrog.agent.platform.coordination.RunCoordination;
import world.willfrog.agent.platform.coordination.RunCoordinationDeferReason;
import world.willfrog.agent.platform.coordination.RunCoordinationStore;
import world.willfrog.agent.platform.mapper.MigrationStatements;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;
import world.willfrog.agent.platform.mapper.RunCoordinationMapper;
import world.willfrog.agent.platform.mapper.SchedulerStateMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunEventRedisStore;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.workitem.MybatisNodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.ServiceOwnershipFence;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import javax.sql.DataSource;
import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段三数据合同与等待链在真实 PostgreSQL 上的验证。
 *
 * <p>只在给了外部连接串时才跑：环境变量 {@code AF_STAGE3_PG_DSN}（形如
 * {@code postgresql://host:5432/db} 或 {@code jdbc:postgresql://...}；账号口令只从
 * {@code AF_STAGE3_PG_USER} / {@code AF_STAGE3_PG_PASSWORD} 读，连接串里带 userinfo 会直接失败）。本机禁止起 Docker，所以本地一律跳过；
 * 证据要在负责人授权的外部 PostgreSQL 上跑出来。</p>
 *
 * <p>做法：建一个临时 schema，用数据源自己的 schema 属性指到它（建表之前先读回
 * {@code current_schema()} 确认写入落在这里）→ 走真实升级链（init 建表脚本加各版本升级脚本，一路升到
 * 006）→ 把这一阶段的各份脚本按顺序整份执行两遍（第二遍要一样通过，证明脚本可重复执行）
 * → 逐条插反例确认被约束拒绝 → 再用真的 MyBatis 语句跑并发用例（成员只结束一次、最后成员只产生一次
 * 恢复资格、组只齐备一次、旧领取提交为零、同一条恢复资格只被消费一次）→ 收尾删掉整个 schema。</p>
 *
 * <p>正式出证据时要同时置上 {@code AF_STAGE3_PG_DSN} 与 {@code AF_STAGE3_PG_REQUIRED}：后者让缺连接串
 * 变成一条失败用例，而不是一次「跳过」——跳过的报告照样能让 Maven 以 0 退出，光看退出码会把没跑当跑过。</p>
 *
 * <p>夹具（Run、节点分段、等待组）都走真实写入语句，不手写列清单：列集合与约束由映射文件保证，
 * 免得真库上一份手写的前置表把合同测成了另一回事。</p>
 *
 * <p>全程只碰临时 schema：所有连接都通过 {@code currentSchema} 落在它里面，既有库表一行都不动；
 * 万一中途进程被杀，残留的 schema 名字都带 {@code stage3_contract_} 前缀，可以直接删掉。</p>
 */
class Stage3WaitContractPostgresTest {

    private static final String STAGE3_SCRIPT = "007_agent_run_dag_wait_group.sql";
    private static final String DISPATCH_PROOF_SCRIPT = "008_agent_run_wait_member_dispatch_proof.sql";
    private static final String CONSUMED_BY_SCRIPT = "009_agent_run_recovery_consumed_by_check.sql";
    private static final String REPAIR_INDEX_SCRIPT = "010_agent_run_event_received_repair_index.sql";
    private static final String SERVICE_LEASE_SCRIPT = "011_agent_run_service_lease.sql";
    private static final String SHARED_CANDIDATE_SCRIPT = "012_agent_run_coordination_shared_candidate.sql";
    private static final String RECOVERY_CLOSE_SCRIPT =
            "013_agent_run_recovery_notification_close.sql";
    private static final String ACCEPTANCE_FIXTURE_SCRIPT =
            "014_agent_run_acceptance_fixture.sql";
    private static final String RELEASE_POINT_SCRIPT = "015_agent_run_release_point.sql";
    /** 轮转用例自己造的四条 Run：断言只看这几条，别的用例留下的行不参与。 */
    private static final List<String> ROTATION_RUNS =
            List.of("run-cold", "run-warm", "run-hot", "run-legacy");
    private static final String SCHEMA = "stage3_contract_" + UUID.randomUUID().toString().replace("-", "");
    private static final int CONCURRENT_THREADS = 4;
    private static final int HIGH_WATERMARK = 128;
    private static final int LOW_WATERMARK = 96;

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    // ==================== 起停 ====================

    @BeforeAll
    static void setUp() throws Exception {
        String dsn = env("AF_STAGE3_PG_DSN");
        if (dsn == null || dsn.isBlank()) {
            // 正式出证据时把 AF_STAGE3_PG_REQUIRED 置上：那时缺连接串是配置错误，必须是一条红色用例。
            // 没有这个开关就跳过：本机禁止起 Docker，开发机上跑不出真库，跳过才不至于把红当常态。
            if (flag("AF_STAGE3_PG_REQUIRED")) {
                throw new IllegalStateException(
                        "AF_STAGE3_PG_REQUIRED 已经要求真库验证，但没有给 AF_STAGE3_PG_DSN");
            }
            Assumptions.assumeTrue(false,
                    "未提供 AF_STAGE3_PG_DSN：真库合同验证要在负责人授权的 PostgreSQL 上执行，本机不跑");
        }
        Target target = resolveTarget(dsn);
        try (Connection connection = open(target, null).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        dataSource = open(target, SCHEMA);
        // 先确认连接真的落在本次随机临时 schema 里再动任何脚本：连接串自带 currentSchema 或
        // search_path 参数时，后面的建表与升级会写到别的 schema，而报告看起来仍然是通过的。
        assertThat(currentSchema()).as("所有连接都必须落在本次随机临时 schema 里").isEqualTo(SCHEMA);
        applyUpgradeChain();
        applyStage3ScriptsTwice();
        // 空表与列形状的检查放在这里：这些用例共用同一个 schema，JUnit 又不保证方法顺序，
        // 放到某一条用例里断言「表还是空的」会变成顺序依赖。
        assertFreshlyMigratedSchema();
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

    // ==================== 反例约束 ====================

    @Test
    void runIdempotencyColumnsArePairedAndUniquePerUser() throws Exception {
        createRunFor("run-idem-1", "user-idem", 0, 0L);
        // 键与摘要必须成对：只给键不给摘要、只给摘要不给键都要被拒。
        expectRejected("UPDATE alphafrog_agent_run SET idempotency_key = 'k-1' WHERE id = 'run-idem-1'",
                "alphafrog_agent_run_idempotency_pair_check");
        expectRejected("UPDATE alphafrog_agent_run SET request_digest = 'd-1' WHERE id = 'run-idem-1'",
                "alphafrog_agent_run_idempotency_pair_check");
        execute("UPDATE alphafrog_agent_run SET idempotency_key = 'k-1', request_digest = 'd-1' "
                + "WHERE id = 'run-idem-1'");
        // 同一个用户下同一个键只能有一条。
        createRunFor("run-idem-2", "user-idem", 0, 0L);
        expectRejected("UPDATE alphafrog_agent_run SET idempotency_key = 'k-1', request_digest = 'd-2' "
                + "WHERE id = 'run-idem-2'", "uq_agent_run_user_idempotency_key");
        // 另一个用户用同一个键是允许的：唯一范围按用户分组。
        createRunFor("run-idem-3", "user-other", 0, 0L);
        execute("UPDATE alphafrog_agent_run SET idempotency_key = 'k-1', request_digest = 'd-3' "
                + "WHERE id = 'run-idem-3'");
    }

    @Test
    void coordinationAcceptsEveryKnownVersionAndRejectsNodeDispatchReason() throws Exception {
        String insert = "INSERT INTO alphafrog_agent_run_coordination (run_id, scheduler_version, "
                + "defer_reason) VALUES ";
        createRun("run-coord-v2", 0, 0L);
        createRun("run-coord-v1", 0, 0L);
        createRun("run-coord-old", 0, 0L);
        execute(insert + "('run-coord-v2', 'DUAL_POOL_V2', 'PER_ROUND_NEW_NODE_LIMIT')");
        execute(insert + "('run-coord-v1', 'DUAL_POOL_V1', NULL)");
        execute(insert + "('run-coord-old', 'LEGACY', NULL)");
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_coordination "
                + "WHERE run_id IN ('run-coord-v2', 'run-coord-v1', 'run-coord-old')"))
                .as("旧版本也要能建出协调资格：协调名额与轮转是新旧版本共用的入口")
                .isEqualTo(3);
        expectRejected(insert + "('run-coord-v2', 'DUAL_POOL_V9', NULL)",
                "alphafrog_agent_run_coordination_scheduler_version_check");
        execute("UPDATE alphafrog_agent_run_coordination SET defer_reason = 'SERVICE_OWNERSHIP_ELSEWHERE' "
                + "WHERE run_id = 'run-coord-old'");
        expectRejected("UPDATE alphafrog_agent_run_coordination SET defer_reason = 'HINT_QUEUE_FULL' "
                + "WHERE run_id = 'run-coord-v2'",
                "alphafrog_agent_run_coordination_defer_reason_check");
    }

    @Test
    void acceptanceFixturePairsEnablementUseAndIdentity() throws Exception {
        String insert = "INSERT INTO alphafrog_agent_run_acceptance_fixture (fixture_id, lane_id, "
                + "traffic_scope_id, deployment_version, scenario_id, model_script_json, expires_at, "
                + "enabled, enabled_at, use_count, last_used_at) VALUES ";
        String expires = "CURRENT_TIMESTAMP + interval '1 day'";
        String ready = "'fx-1', 'lane-a', 'lane-a', 'gen-1', 'scenario-a', '{}'::jsonb, " + expires
                + ", TRUE, CURRENT_TIMESTAMP, 0, NULL";
        execute(insert + "(" + ready + ")");
        // 启用与启用时间成对：只写一个都会被拒，因为「这条夹具什么时候被打开的」必须说得清。
        expectRejected(insert + "('fx-2', 'lane-a', 'lane-a', 'gen-1', 'scenario-a', '{}'::jsonb, "
                        + expires + ", TRUE, NULL, 0, NULL)",
                "alphafrog_agent_run_acceptance_fixture_enablement_check");
        expectRejected(insert + "('fx-3', 'lane-a', 'lane-a', 'gen-1', 'scenario-a', '{}'::jsonb, "
                        + expires + ", FALSE, CURRENT_TIMESTAMP, 0, NULL)",
                "alphafrog_agent_run_acceptance_fixture_enablement_check");
        // 使用次数与最后一次使用时间成对：证据里「用过几次、最后一次什么时候」不能只留一半。
        expectRejected(insert + "('fx-4', 'lane-a', 'lane-a', 'gen-1', 'scenario-a', '{}'::jsonb, "
                        + expires + ", TRUE, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP)",
                "alphafrog_agent_run_acceptance_fixture_use_check");
        expectRejected(insert + "('fx-5', 'lane-a', 'lane-a', 'gen-1', 'scenario-a', '{}'::jsonb, "
                        + expires + ", TRUE, CURRENT_TIMESTAMP, 3, NULL)",
                "alphafrog_agent_run_acceptance_fixture_use_check");
        // 同一个泳道、同一个部署代际下，一个编号只能有一份夹具：查回来的内容必须唯一。
        expectRejected(insert + "(" + ready + ")", "alphafrog_agent_run_acceptance_fixture_identity_key");
        // 换个泳道或换个代际，同一个编号可以各有各的一份：作用域必须按泳道与代际分开。
        execute(insert + "('fx-1', 'lane-b', 'lane-b', 'gen-1', 'scenario-a', '{}'::jsonb, "
                + expires + ", TRUE, CURRENT_TIMESTAMP, 0, NULL)");
        execute(insert + "('fx-1', 'lane-a', 'lane-a', 'gen-2', 'scenario-a', '{}'::jsonb, "
                + expires + ", TRUE, CURRENT_TIMESTAMP, 0, NULL)");
    }

    @Test
    void aReleasePointIsOpenedOnlyByTheControlPlaneAndItsEvidenceIsPaired() throws Exception {
        String insert = "INSERT INTO alphafrog_agent_run_release_point (run_id, release_key, lane_id, "
                + "deployment_version) VALUES ";
        createRun("run-release", 0, 0L);
        // 行还没建出来时按「没放行」处理：夹具策略点名的放行点必须先由控制面建出来。
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_release_point "
                + "WHERE run_id = 'run-release'")).isZero();
        execute(insert + "('run-release', 'point-a', 'lane-a', 'gen-1')");
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_release_point "
                + "WHERE run_id = 'run-release' AND release_key = 'point-a' AND opened_at IS NULL"))
                .as("建出来但没标放行时仍是等待态").isEqualTo(1);
        // 放行时刻与放行人成对：只写一半都要被拒，放行的证据说不清就不算放行。
        expectRejected("UPDATE alphafrog_agent_run_release_point SET opened_at = CURRENT_TIMESTAMP "
                        + "WHERE run_id = 'run-release' AND release_key = 'point-a'",
                "alphafrog_agent_run_release_point_open_check");
        expectRejected("INSERT INTO alphafrog_agent_run_release_point (run_id, release_key, lane_id, "
                        + "deployment_version, opened_by) VALUES ('run-release', 'point-c', 'lane-a', "
                        + "'gen-1', 'control-plane')",
                "alphafrog_agent_run_release_point_open_check");
        // 同一个 Run 的同一个放行点只能有一行：查回来的是哪一行必须唯一。
        expectRejected(insert + "('run-release', 'point-a', 'lane-b', 'gen-2')",
                "alphafrog_agent_run_release_point_identity_key");
        // 放行点挂在一条不存在的 Run 上要被外键拒掉。
        expectRejected(insert + "('run-release-missing', 'point-a', 'lane-a', 'gen-1')",
                "alphafrog_agent_run_release_point_run_id_fkey");
        // 控制面把点标成已放行之后，读到的就是已放行；重复读一样，放行按许可而不是按取走一次。
        execute("UPDATE alphafrog_agent_run_release_point SET opened_at = CURRENT_TIMESTAMP, "
                + "opened_by = 'control-plane' WHERE run_id = 'run-release' "
                + "AND release_key = 'point-a'");
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_release_point "
                + "WHERE run_id = 'run-release' AND release_key = 'point-a' "
                + "AND opened_at IS NOT NULL AND opened_by = 'control-plane'"))
                .as("放行之后这一行同时带着时刻与放行人").isEqualTo(1);
        // 另一个放行点不受影响：一条策略点几个点，放哪个点由控制面一个一个决定。
        execute(insert + "('run-release', 'point-b', 'lane-a', 'gen-1')");
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_release_point "
                + "WHERE run_id = 'run-release' AND opened_at IS NULL")).isEqualTo(1);
    }

    @Test
    void holdingAMemberOnlyPushesItsNextQueryTime() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-hold", 2, 0L);
        assertThat(dispatchMember(fixture.groupId(), "call-a", fixture.runControlVersion()))
                .as("成员先要真的转成执行中").isTrue();
        WaitMember before = memberByIdentity(fixture.groupId(), "call-a");
        assertThat(before.getState()).isEqualTo(WaitMemberState.RUNNING.name());

        OffsetDateTime heldUntil = OffsetDateTime.now().plusSeconds(5);
        assertThat(holdMember(fixture.groupId(), "call-a", heldUntil)).as("压住要写进去").isTrue();

        WaitMember after = memberByIdentity(fixture.groupId(), "call-a");
        assertThat(after.getNextPollAt().toInstant()).as("下次查询时间按压住给的那一刻走")
                .isEqualTo(heldUntil.toInstant());
        assertThat(after.getPollCount()).as("被压住不算「问过一轮」")
                .isEqualTo(before.getPollCount());
        assertThat(after.getBackoffStep()).as("被压住与「问不到结论」是两回事，退避步数不涨")
                .isEqualTo(before.getBackoffStep());
        assertThat(after.getState()).as("压住不改成员状态").isEqualTo(WaitMemberState.RUNNING.name());

        // 另外一个成员一点都不受影响：压住的写法只落在被点名的那一行。
        assertThat(memberByIdentity(fixture.groupId(), "call-b").getNextPollAt())
                .as("没被点名的成员下次查询时间不变").isNull();

        // 已经落终态的成员压不住：这时它已经在别人手里收尾了。
        assertThat(completeMember(fixture.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                fixture.runControlVersion()).applied()).isTrue();
        assertThat(holdMember(fixture.groupId(), "call-a", heldUntil.plusSeconds(60)))
                .as("落终态之后压住写不进去").isFalse();
        assertThat(memberByIdentity(fixture.groupId(), "call-a").getNextPollAt().toInstant())
                .as("没写进去就不能改已经落定的那一行").isEqualTo(heldUntil.toInstant());
    }

    @Test
    void latestSegmentWinsForEachLogicalNode() throws Exception {
        String runId = "run-latest";
        createRun(runId, 2, 0L);
        // 第一段把整组工具交出去之后已提交：节点没完成，下一段才是它现在的样子。
        createSegment(runId, 2, "node-1", 0, 0, 3, "worker-1", 3L, 0L, "RESULT_COMMITTED");
        execute("UPDATE alphafrog_agent_run_work_item SET payload_json = payload_json || "
                + "'{\"waitGroup\":{\"modelTurn\":0,\"nextSegmentSequence\":1,\"memberCount\":2}}'::jsonb "
                + "WHERE run_id = '" + runId + "' AND node_id = 'node-1' AND segment_sequence = 0");
        createSegment(runId, 2, "node-1", 0, 1, 0, null, 3L, 0L, "WAITING");
        // 同一个逻辑节点被重做过一次：最新尝试里的最新分段才算数。
        createSegment(runId, 2, "node-1", 1, 0, 0, null, 3L, 0L, "RUNNABLE");
        createSegment(runId, 2, "node-2", 0, 0, 0, null, 3L, 0L, "RUNNABLE");
        // 另一个计划代际的行不属于这一次读取。
        createSegment(runId, 3, "node-1", 0, 0, 0, null, 3L, 0L, "RUNNABLE");

        List<NodeWorkItem> latest = latestSegments(runId, 2);
        assertThat(latest).extracting(NodeWorkItem::getNodeId)
                .containsExactlyInAnyOrder("node-1", "node-2");
        NodeWorkItem node = latest.stream()
                .filter(item -> "node-1".equals(item.getNodeId())).findFirst().orElseThrow();
        assertThat(node.getNodeAttempt()).as("同一个节点取尝试次数最大的那次").isEqualTo(1);
        assertThat(node.getSegmentSequence()).isZero();
    }

    /**
     * 三个版本在同一份候选里竞争，谁等得久谁先被服务。
     *
     * <p>资格记录上的版本必须是从 Run 主表派生出来的：造数据时给父 Run 定版本，不再另外插一条
     * 版本不一致的子记录，否则「父子一致」这件事根本没被测到。旧版本的行也在这一份候选里占一个
     * 位置、跟着同一套轮次走；它能不能被接手是另一回事。</p>
     */
    @Test
    void coordinationRotationServesTheLongestWaitingRunFirstAcrossEveryVersion() throws Exception {
        createRunFor("run-cold", "user-1", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        createRunFor("run-warm", "user-1", SchedulerVersion.DUAL_POOL_V1, 0, 0L);
        createRunFor("run-hot", "user-1", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        createRunFor("run-legacy", "user-1", SchedulerVersion.LEGACY, 0, 0L);
        RunCoordinationStore store = coordinationStore();

        assertThat(store.ensure("run-cold")).isTrue();
        assertThat(store.ensure("run-warm")).isTrue();
        assertThat(store.ensure("run-hot")).isTrue();
        // 同一个 Run 再写一次不改任何东西：资格记录一个 Run 只有一行。
        assertThat(store.ensure("run-hot")).isFalse();
        assertThat(store.ensure("run-legacy"))
                .as("旧版本的 Run 也在这张表里排队：候选是三个版本共用的")
                .isTrue();
        assertThat(store.ensure("run-missing"))
                .as("Run 不存在时建不出资格记录，影响 0 行")
                .isFalse();
        assertThat(store.find("run-cold").orElseThrow().getSchedulerVersion())
                .as("记录上的版本取自 Run 主表，不由调用方指定")
                .isEqualTo("DUAL_POOL_V2");
        assertThat(store.find("run-warm").orElseThrow().getSchedulerVersion())
                .as("同一个双池家族里的另一个版本也一样，各记各的冻结版本")
                .isEqualTo("DUAL_POOL_V1");
        assertThat(store.find("run-cold").orElseThrow().getPlanGeneration()).isZero();

        // 插入时间各自不同，抹平之后这一轮的所有图都在同一份候选里。
        execute("UPDATE alphafrog_agent_run_coordination SET next_visible_at = CURRENT_TIMESTAMP");
        assertThat(dueRunIds(store, ROTATION_RUNS))
                .as("一次全局扫描：三个版本排在同一份候选里，按每行的冻结版本路由")
                .containsExactlyInAnyOrder("run-cold", "run-warm", "run-hot", "run-legacy");

        store.markCoordinationServed("run-cold", 1, 0, fenceFor("run-cold"));
        store.markCoordinationServed("run-warm", 7, 0, fenceFor("run-warm"));
        store.markCoordinationServed("run-hot", 9, 0, fenceFor("run-hot"));
        store.markHandoffServed("run-legacy", 10, 0);
        assertThat(dueRunIds(store, ROTATION_RUNS))
                .as("按最近被服务的轮次升序：越久没被服务的越靠前，旧版本一样排在里面")
                .containsExactly("run-cold", "run-warm", "run-hot", "run-legacy");

        // 轮次位置只许前进：迟到的旧轮次写进来影响 0 行，不能把新事实改回旧事实。
        assertThat(store.markCoordinationServed("run-hot", 3, 0, fenceFor("run-hot"))).isFalse();
        assertThat(store.find("run-hot").orElseThrow().getCoordinationServedRound()).isEqualTo(9);

        // 延期要写清原因与下次可见时间，并带上这一轮读到的计划代际与轮次做条件。
        assertThat(store.deferFor("run-cold", RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 0L, fenceFor("run-cold")))
                .as("轮次对不上：这是旧观察，写进去只会把新事实拉回旧事实")
                .isFalse();
        assertThat(store.deferFor("run-cold", RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 1L, fenceFor("run-cold"))).isTrue();
        assertThat(store.find("run-cold").orElseThrow().deferReasonEnum())
                .isEqualTo(RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED);
        assertThat(dueRunIds(store, ROTATION_RUNS))
                .containsExactly("run-warm", "run-hot", "run-legacy");

        // Run 推进计划代际：资格记录跟着走，只许前进，也不许写一个不属于 Run 的代际。
        execute("UPDATE alphafrog_agent_run SET plan_generation = 1 WHERE id = 'run-cold'");
        assertThat(store.syncPlanGeneration("run-cold", 0, fenceFor("run-cold"))).as("代际倒退不写").isFalse();
        assertThat(store.syncPlanGeneration("run-cold", 2, fenceFor("run-cold")))
                .as("声明的这一代必须就是 Run 主表上的当前一代")
                .isFalse();
        assertThat(store.syncPlanGeneration("run-cold", 1, fenceFor("run-cold"))).isTrue();
        assertThat(store.find("run-cold").orElseThrow().getPlanGeneration()).isEqualTo(1);
        assertThat(store.deferFor("run-cold", RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 1L, fenceFor("run-cold")))
                .as("父 Run 已经升到第 1 代而记录还停在第 0 代：拿旧代际写的延期不生效")
                .isFalse();

        // 成功推进：延期原因清掉、轮次位置更新，于是它排到最后（这一轮别人先来）。
        assertThat(store.markCoordinationServed("run-cold", 11, 1, fenceFor("run-cold"))).isTrue();
        RunCoordination served = store.find("run-cold").orElseThrow();
        assertThat(served.getDeferReason()).isNull();
        assertThat(served.getCoordinationServedRound()).isEqualTo(11);
        assertThat(dueRunIds(store, ROTATION_RUNS))
                .containsExactly("run-warm", "run-hot", "run-legacy", "run-cold");
    }

    @Test
    void missedRoundsCountOnlyTheRoundsTheRunWasActuallyCompeting() throws Exception {
        createRunFor("run-competing", "user-missed", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        RunCoordinationStore store = coordinationStore();
        assertThat(store.ensure("run-competing")).isTrue();

        // 第一轮它在候选里没被服务：记一轮。
        store.refreshCoordinationMissedRounds(1);
        assertThat(store.find("run-competing").orElseThrow().getCoordinationMissedRounds())
                .isEqualTo(1);

        // 主动延期一小时：这段间隔里它根本没排队，刷新一轮都不该算到它头上。
        assertThat(store.deferFor("run-competing", RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT,
                OffsetDateTime.now().plusHours(1), 0, 0L, fenceFor("run-competing"))).isTrue();
        for (long round = 2; round <= 6; round++) {
            store.refreshCoordinationMissedRounds(round);
        }
        assertThat(store.find("run-competing").orElseThrow().getCoordinationMissedRounds())
                .as("延期等待中的记录不在候选里，这五轮不算「没被服务」")
                .isEqualTo(1);

        // 重新可见：只加真正排队的这一轮。用轮次差补算会一次写出 6，那是它没在竞争的轮数。
        execute("UPDATE alphafrog_agent_run_coordination SET next_visible_at = CURRENT_TIMESTAMP "
                + "WHERE run_id = 'run-competing'");
        store.refreshCoordinationMissedRounds(7);
        assertThat(store.find("run-competing").orElseThrow().getCoordinationMissedRounds())
                .as("重新具备资格的第一轮只加一轮")
                .isEqualTo(2);
        store.refreshCoordinationMissedRounds(8);
        assertThat(store.find("run-competing").orElseThrow().getCoordinationMissedRounds())
                .as("连续两轮站着排队都没轮到：加两轮")
                .isEqualTo(3);

        // 被服务：清零；已经服务过的这一轮不算没被服务。
        assertThat(store.markCoordinationServed("run-competing", 9, 0, fenceFor("run-competing"))).isTrue();
        assertThat(store.find("run-competing").orElseThrow().getCoordinationMissedRounds()).isZero();
        store.refreshCoordinationMissedRounds(9);
        assertThat(store.find("run-competing").orElseThrow().getCoordinationMissedRounds())
                .as("服务轮次就是刚结束这一轮：它被服务了，不加")
                .isZero();
        store.refreshCoordinationMissedRounds(10);
        assertThat(store.find("run-competing").orElseThrow().getCoordinationMissedRounds())
                .isEqualTo(1);

        // 派发那一组：候选资格看的是「此刻确实有到期可领取的节点」。
        createRunFor("run-competing-node", "user-missed-node", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        assertThat(store.ensure("run-competing-node")).isTrue();
        createSegment("run-competing-node", 0, "node-1", 0, 0, 0, null, 1L, 0L, "RUNNABLE");
        store.refreshDispatchMissedRounds(20);
        assertThat(store.find("run-competing-node").orElseThrow().getDispatchMissedRounds())
                .isEqualTo(1);
        execute("UPDATE alphafrog_agent_run_work_item "
                + "SET next_visible_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' "
                + "WHERE run_id = 'run-competing-node'");
        store.refreshDispatchMissedRounds(21);
        assertThat(store.find("run-competing-node").orElseThrow().getDispatchMissedRounds())
                .as("没有到期可领节点的图谈不上被派发机会漏掉")
                .isEqualTo(1);
        execute("UPDATE alphafrog_agent_run_work_item SET next_visible_at = CURRENT_TIMESTAMP "
                + "WHERE run_id = 'run-competing-node'");
        store.refreshDispatchMissedRounds(22);
        store.refreshDispatchMissedRounds(23);
        assertThat(store.find("run-competing-node").orElseThrow().getDispatchMissedRounds())
                .as("连续两轮有可领节点都没轮到：加两轮")
                .isEqualTo(3);
        assertThat(store.markDispatchServed("run-competing-node", 24, 0, fenceFor("run-competing-node"))).isTrue();
        assertThat(store.find("run-competing-node").orElseThrow().getDispatchMissedRounds()).isZero();
    }

    /**
     * 成功写与延期写都要核这一回合开始时读到的计划代际，父 Run 与资格记录上任一已经变了就不写。
     *
     * <p>旧回合拿着旧代际去写成功，会把延期原因清掉、把轮转位置往前挪，等于拿旧回合冒充新回合；
     * 延期也一样，子记录还没跟着同步时旧延期不许写进去。</p>
     */
    @Test
    void servedAndDeferredWritesAreFencedByBothPlanGenerations() throws Exception {
        createRunFor("run-fence", "user-fence", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        RunCoordinationStore store = coordinationStore();
        assertThat(store.ensure("run-fence")).isTrue();

        // Run 推进到下一代，资格记录还没同步：这一回合的三种写都不许生效。
        execute("UPDATE alphafrog_agent_run SET plan_generation = 1 WHERE id = 'run-fence'");
        assertThat(store.markCoordinationServed("run-fence", 5, 0, fenceFor("run-fence")))
                .as("父 Run 已经升代，旧回合的成功写不生效").isFalse();
        assertThat(store.markDispatchServed("run-fence", 5, 0, fenceFor("run-fence")))
                .as("派发那一组同样按父 Run 的当前代际拦").isFalse();
        assertThat(store.deferFor("run-fence", RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 0L, fenceFor("run-fence")))
                .as("延期也要核父 Run 的当前代际，子记录没同步不算数").isFalse();
        RunCoordination untouched = store.find("run-fence").orElseThrow();
        assertThat(untouched.getCoordinationServedRound()).isZero();
        assertThat(untouched.getDispatchServedRound()).isZero();
        assertThat(untouched.getDeferReason()).isNull();

        // 记录同步到当前代际之后，带着这一代的写就能落地。
        assertThat(store.syncPlanGeneration("run-fence", 1, fenceFor("run-fence"))).isTrue();
        assertThat(store.markCoordinationServed("run-fence", 5, 1, fenceFor("run-fence"))).isTrue();
        assertThat(store.markDispatchServed("run-fence", 6, 1, fenceFor("run-fence"))).isTrue();
        assertThat(store.deferFor("run-fence", RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT,
                OffsetDateTime.now().plusMinutes(5), 1, 5L, fenceFor("run-fence"))).isTrue();
        RunCoordination written = store.find("run-fence").orElseThrow();
        assertThat(written.getCoordinationServedRound()).isEqualTo(5);
        assertThat(written.getDispatchServedRound()).isEqualTo(6);
        assertThat(written.deferReasonEnum())
                .isEqualTo(RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT);
    }

    /**
     * 三个版本共用一份候选：旧版本的 Run 一样建得出资格记录，也一样按同一份排序参与竞争。
     *
     * <p>选出来之后按每一行自己冻结的版本交给对应入口；候选这一步不按版本分家——给某一版单独开
     * 一份候选，等于让它在另一套轮次里插队，「谁等得更久」就没有可比性了。旧版本的行进了候选，
     * 只是共享分发器现在还不接手它：那要有能跨进程核对、会过期、能原子转交的所有权事实，
     * 这件事留到持久所有权那一组。</p>
     */
    @Test
    void everySchedulerVersionSharesTheSameCoordinationCandidates() throws Exception {
        List<String> mine = List.of("run-legacy-scan", "run-v2-scan");
        createRunFor("run-legacy-scan", "user-legacy-scan", SchedulerVersion.LEGACY, 0, 0L);
        createRunFor("run-v2-scan", "user-v2-scan", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        RunCoordinationStore store = coordinationStore();

        assertThat(store.ensure("run-legacy-scan"))
                .as("旧版本的 Run 也建得出资格记录").isTrue();
        assertThat(store.ensure("run-v2-scan")).isTrue();
        assertThat(store.find("run-legacy-scan").orElseThrow().getSchedulerVersion())
                .as("记录上的版本取自 Run 主表，不由调用方指定").isEqualTo("LEGACY");
        assertThat(store.find("run-v2-scan").orElseThrow().getSchedulerVersion())
                .isEqualTo("DUAL_POOL_V2");

        // 插入时间各自不同，抹平之后这一轮两条都在候选里。
        execute("UPDATE alphafrog_agent_run_coordination SET coordination_served_round = 0, "
                + "next_visible_at = CURRENT_TIMESTAMP "
                + "WHERE run_id IN ('run-legacy-scan', 'run-v2-scan')");

        assertThat(dueRunIds(store, mine))
                .as("两条走同一份排序：先比服务轮次，再比下次可见时间，最后比 Run 编号")
                .containsExactly("run-legacy-scan", "run-v2-scan");
    }

    /**
     * 页首的旧版本行被推后之后，只剩下一条名额的扫描能轮到后面的双池 Run。
     *
     * <p>三个版本共用候选之后，旧版本的行也会被选出来。它接不了手（所有权不在本进程）时如果就
     * 停在页首，页数一满，后面的双池 Run 永远看不见。这条量的是推后确实能把它移出页首：
     * 名额只有一条，取到的必须是双池那一条。</p>
     */
    @Test
    void aDeferredLegacyRowStopsBlockingTheSingleCandidateSlot() throws Exception {
        createRunFor("run-legacy-blocking", "user-blocking", SchedulerVersion.LEGACY, 0, 0L);
        createRunFor("run-v2-behind", "user-blocking", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        RunCoordinationStore store = coordinationStore();
        assertThat(store.ensure("run-legacy-blocking")).isTrue();
        assertThat(store.ensure("run-v2-behind")).isTrue();
        // 整个用例类共用一个 schema：先把别的用例留下的候选推出一小时之外，这条只量本用例的两行。
        execute("UPDATE alphafrog_agent_run_coordination SET next_visible_at = CURRENT_TIMESTAMP "
                + "+ INTERVAL '1 hour' "
                + "WHERE run_id NOT IN ('run-legacy-blocking', 'run-v2-behind')");
        execute("UPDATE alphafrog_agent_run_coordination SET coordination_served_round = 0, "
                + "next_visible_at = CURRENT_TIMESTAMP, defer_reason = NULL "
                + "WHERE run_id IN ('run-legacy-blocking', 'run-v2-behind')");

        // 旧版本排在前面（编号更小、轮次相同），名额只有一条时先取它。
        assertThat(store.scanDue(1))
                .extracting(RunCoordination::getRunId)
                .containsExactly("run-legacy-blocking");

        // 接不了手，按所有权原因推后：下一轮它让位，名额落到双池那条上。
        assertThat(store.deferHandoff("run-legacy-blocking",
                RunCoordinationDeferReason.SERVICE_OWNERSHIP_ELSEWHERE,
                OffsetDateTime.now().plusMinutes(1), 0, 0L)).isTrue();
        assertThat(store.scanDue(1))
                .as("推后之后一条名额也能轮到后面的双池 Run")
                .extracting(RunCoordination::getRunId)
                .containsExactly("run-v2-behind");
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
        ServiceOwnershipFence fence = fenceFor(runId);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NodeWorkItemMapper mapper = session.getMapper(NodeWorkItemMapper.class);
            assertThat(mapper.claim(fence.ownerInstanceId(), fence.fencingToken(),
                    runId, 0, "node-1", 0, 0, "DUAL_POOL_V2", 1L, 0L,
                    "worker-1", java.time.OffsetDateTime.now().plusMinutes(1)))
                    .as("第一次领取成功").isEqualTo(1);
            assertThat(mapper.claim(fence.ownerInstanceId(), fence.fencingToken(),
                    runId, 0, "node-1", 0, 0, "DUAL_POOL_V2", 1L, 0L,
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
        long token = ownRun(fixture.runId(), "dispatcher-1");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            RecoveryConsumptionResult wrongVersion = store.consumeRecovery(notificationId, "dispatcher-1",
                    fixture.runControlVersion() + 7, "dispatcher-1", token);
            assertThat(wrongVersion.consumed())
                    .as("控制版本不符时通知取不走，下一段也不会被放行").isFalse();
            RecoveryConsumptionResult first = store.consumeRecovery(notificationId, "dispatcher-1",
                    fixture.runControlVersion(), "dispatcher-1", token);
            assertThat(first.consumed()).isTrue();
            assertThat(first.promoted()).isTrue();
            assertThat(first.groupId())
                    .as("消费结果要带回放行的是哪条链：这条数从消费语句里取，取不到就说明语句点错了列")
                    .isEqualTo(fixture.groupId());
            RecoveryConsumptionResult second = store.consumeRecovery(notificationId, "dispatcher-1",
                    fixture.runControlVersion(), "dispatcher-1", token);
            assertThat(second.consumed())
                    .as("同一代际的恢复资格只能被取走一次").isFalse();
            assertThat(second.rejection())
                    .as("取不走的原因由语句给出：这条资格已经有主")
                    .isEqualTo(world.willfrog.agent.platform.wait.RecoveryRejection.NOTIFICATION_NOT_WAITING);
            assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_work_item "
                    + "WHERE run_id = '" + fixture.runId() + "' AND state = 'RESUMABLE'"))
                    .as("下一段只被放行一次").isEqualTo(1);
        }
    }

    // ==================== 恢复资格的真实主键 ====================

    /**
     * 第二条及以后的恢复通知必须返回自己的真实主键。
     *
     * <p>只断言「编号非空」的话，第一条通知在空库里碰巧就是 1，把常量当主键也看不出来；这里让同一个
     * schema 里出现第二条通知，再用它去消费。</p>
     */
    @Test
    void everyRecoveryNotificationKeepsItsOwnPrimaryKey() throws Exception {
        GroupFixture first = suspendSimpleGroup("run-notify-a", 1, 0L);
        Long firstId = completeMember(first.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                first.runControlVersion()).notificationId();
        assertThat(firstId).isNotNull();

        GroupFixture second = suspendSimpleGroup("run-notify-b", 2, 0L);
        completeMember(second.groupId(), "call-a", WaitMemberState.SUCCEEDED, second.runControlVersion());
        Long secondId = completeMember(second.groupId(), "call-b", WaitMemberState.SUCCEEDED,
                second.runControlVersion()).notificationId();

        assertThat(secondId).as("第二条通知不能返回一个常量").isNotNull().isNotEqualTo(firstId);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_recovery_notification "
                + "WHERE id = " + secondId + " AND group_id = " + second.groupId()
                + " AND state = 'WAITING'"))
                .as("返回的编号必须真的指向这条链自己那条通知")
                .isEqualTo(1);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            long token = ownRun(second.runId(), "dispatcher-2");
            RecoveryConsumptionResult result = store.consumeRecovery(secondId, "dispatcher-2",
                    second.runControlVersion(), "dispatcher-2", token);
            assertThat(result.consumed()).isTrue();
            assertThat(result.promoted()).isTrue();
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_work_item WHERE run_id = '"
                + second.runId() + "' AND segment_sequence = 1 AND state = 'RESUMABLE'"))
                .as("消费第二条通知把第二条链的下一段放行")
                .isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_recovery_notification "
                + "WHERE id = " + firstId + " AND state = 'WAITING'"))
                .as("第一条链的恢复资格不受影响：两条链各拿各的号")
                .isEqualTo(1);
    }

    // ==================== 计划代际的栅栏 ====================

    /**
     * Run 推进计划代际之后，旧计划的挂起、成员结果、恢复消费都必须影响 0 行。
     *
     * <p>控制版本这一个条件挡不住这种情形：计划代际已经往前走，控制版本可能还没变。三处写入都要
     * 各自核对 Run 当前计划代际。</p>
     */
    @Test
    void oldPlanGenerationCannotSuspendCompleteOrConsume() throws Exception {
        GroupFixture suspending = suspendSimpleGroup("run-gen-suspend", 2, 0L);
        execute("UPDATE alphafrog_agent_run SET plan_generation = 1 WHERE id = '" + suspending.runId() + "'");
        WaitSuspensionResult again = suspendAgain(suspending.runId(), 0, suspending.runControlVersion());
        assertThat(again.suspended()).as("旧计划的挂起整条不生效").isFalse();
        assertThat(again.outcome())
                .as("回报的是「这个分段不归你」，不能是「已经挂起过」：后者会让执行者以为分段交出去了")
                .isEqualTo(WaitSuspensionOutcome.SEGMENT_NOT_MATCHED);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_wait_group WHERE run_id = '"
                + suspending.runId() + "'"))
                .as("不能因此再建第二条链")
                .isEqualTo(1);

        MemberCompletionResult staleMember = completeMember(suspending.groupId(), "call-a",
                WaitMemberState.SUCCEEDED, suspending.runControlVersion());
        assertThat(staleMember.applied()).as("旧计划的成员结果不算数").isFalse();
        assertThat(groupCompletedMembers(suspending.groupId())).isZero();
        assertThat(memberCount(suspending.groupId(), "call-a", "SUCCEEDED")).isZero();

        // 另一条链先把恢复资格挣到手，再推进计划代际：这时旧通知不该被取走。
        GroupFixture consuming = suspendSimpleGroup("run-gen-consume", 1, 0L);
        long notificationId = completeMember(consuming.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                consuming.runControlVersion()).notificationId();
        execute("UPDATE alphafrog_agent_run SET plan_generation = 1 WHERE id = '" + consuming.runId() + "'");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            long token = ownRun(consuming.runId(), "dispatcher-3");
            RecoveryConsumptionResult result = store.consumeRecovery(notificationId, "dispatcher-3",
                    consuming.runControlVersion(), "dispatcher-3", token);
            assertThat(result.consumed()).as("计划代际已经变了，这条恢复资格取不走").isFalse();
            assertThat(result.promoted()).isFalse();
            assertThat(result.rejection())
                    .as("原因是计划代际已经推进，不是「还没轮到我」")
                    .isEqualTo(world.willfrog.agent.platform.wait.RecoveryRejection.PLAN_GENERATION_STALE);
            assertThat(result.permanent())
                    .as("计划代际推进之后这条资格不会再有下一次：该收口，不该一直退避")
                    .isTrue();
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_recovery_notification "
                + "WHERE id = " + notificationId + " AND state = 'WAITING'"))
                .as("通知留在等待态，没有变成「已消费但没放行」")
                .isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_work_item WHERE run_id = '"
                + consuming.runId() + "' AND segment_sequence = 1 AND state = 'WAITING'"))
                .as("下一段还在等待态，没有被提前放行")
                .isEqualTo(1);
    }

    /**
     * 下一段不可放行时，通知必须留在等待态：既不能消费掉，也不能把下一段改成可恢复。
     */
    @Test
    void notificationStaysWaitingWhenTheNextSegmentCannotBeReleased() throws Exception {
        // (a) 下一段不是等待态。
        GroupFixture movedAhead = suspendSimpleGroup("run-block-a", 1, 0L);
        long notificationA = completeMember(movedAhead.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                movedAhead.runControlVersion()).notificationId();
        execute("UPDATE alphafrog_agent_run_work_item SET state = 'RUNNABLE' WHERE run_id = '"
                + movedAhead.runId() + "' AND segment_sequence = 1");

        // (b) 组的恢复代际已经往前走了一格，这条通知落后了。
        GroupFixture newerGeneration = suspendSimpleGroup("run-block-b", 1, 0L);
        long notificationB = completeMember(newerGeneration.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                newerGeneration.runControlVersion()).notificationId();
        execute("UPDATE alphafrog_agent_run_wait_group SET recovery_generation = recovery_generation + 1 "
                + "WHERE id = " + newerGeneration.groupId());

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            long tokenA = ownRun(movedAhead.runId(), "dispatcher-a");
            RecoveryConsumptionResult blockedA = store.consumeRecovery(notificationA, "dispatcher-a",
                    movedAhead.runControlVersion(), "dispatcher-a", tokenA);
            assertThat(blockedA.consumed()).isFalse();
            assertThat(blockedA.rejection())
                    .as("下一段已经在执行链上，这条资格已经用掉了")
                    .isEqualTo(world.willfrog.agent.platform.wait.RecoveryRejection.NEXT_SEGMENT_ACTIVE);

            long tokenB = ownRun(newerGeneration.runId(), "dispatcher-b");
            RecoveryConsumptionResult blockedB = store.consumeRecovery(notificationB, "dispatcher-b",
                    newerGeneration.runControlVersion(), "dispatcher-b", tokenB);
            assertThat(blockedB.consumed()).isFalse();
            assertThat(blockedB.rejection())
                    .as("通知的恢复代际落后于组当前代际")
                    .isEqualTo(world.willfrog.agent.platform.wait.RecoveryRejection.GENERATION_STALE);
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_recovery_notification "
                + "WHERE id IN (" + notificationA + ", " + notificationB + ") AND state = 'WAITING'"))
                .as("两条通知都留着，等下一轮再来")
                .isEqualTo(2);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_work_item WHERE run_id IN ('"
                + movedAhead.runId() + "', '" + newerGeneration.runId() + "') AND state = 'RESUMABLE'"))
                .as("两条链的下一段都没有被放行")
                .isZero();
    }

    // ==================== 服务所有权与关闭态 ====================

    /**
     * 不是所有者 / 旧代际拿不走恢复资格；合法接手之后新主人拿得走。
     *
     * <p>恢复消费是「把下一段放成可恢复」这种不可逆的动作，所以它必须与 Run 的服务所有权绑在一起：
     * 消费语句在同一次条件更新里核对持有者与代际，并在同一句里锁住租约行，判断与写入之间不被接手。</p>
     */
    @Test
    void onlyTheLeaseOwnerCanConsumeTheRecoveryEntitlement() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-lease-consume", 1, 0L);
        long notificationId = completeMember(fixture.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                fixture.runControlVersion()).notificationId();
        long firstToken = ownRun(fixture.runId(), "owner-a");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            RecoveryConsumptionResult notMine = store.consumeRecovery(notificationId, "dispatcher-b",
                    fixture.runControlVersion(), "owner-b", firstToken);
            assertThat(notMine.consumed()).as("不是所有者：一行都改不动").isFalse();
            assertThat(notMine.rejection())
                    .isEqualTo(world.willfrog.agent.platform.wait.RecoveryRejection.LEASE_NOT_OWNED);
            assertThat(notMine.permanent())
                    .as("所有权在别人手上只是暂时取不走，不该收口").isFalse();
        }

        // 让租约过期，由另一个持有者接手：代际加一，旧代际立刻作废。
        execute("UPDATE alphafrog_agent_run_service_lease SET expires_at = CURRENT_TIMESTAMP "
                + "- interval '1 minute' WHERE run_id = '" + fixture.runId() + "'");
        long secondToken = ownRun(fixture.runId(), "owner-b");
        assertThat(secondToken).as("接手之后代际往前走了").isEqualTo(firstToken + 1);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            RecoveryConsumptionResult staleToken = store.consumeRecovery(notificationId, "dispatcher-a",
                    fixture.runControlVersion(), "owner-a", firstToken);
            assertThat(staleToken.consumed())
                    .as("旧代际在业务写入上写不动任何一行").isFalse();
            assertThat(staleToken.rejection())
                    .isEqualTo(world.willfrog.agent.platform.wait.RecoveryRejection.LEASE_NOT_OWNED);

            RecoveryConsumptionResult mine = store.consumeRecovery(notificationId, "dispatcher-b",
                    fixture.runControlVersion(), "owner-b", secondToken);
            assertThat(mine.consumed()).as("接手之后新主人能正常消费").isTrue();
            assertThat(mine.nextSegment()).as("消费成功必须带回完整的下一段身份").isNotNull();
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_work_item WHERE run_id = '"
                + fixture.runId() + "' AND segment_sequence = 1 AND state = 'RESUMABLE'")).isEqualTo(1);
    }

    /**
     * 下一段自己保存的控制版本与 Run 当前控制版本不一致时不许放行。
     *
     * <p>暂停、取消这些控制动作会让旧控制代际的工作项作废。只核对外层传进来的控制版本挡不住它：
     * 外层刚读到的就是「当前版本」，作废的是那一段自己存着的版本。</p>
     */
    @Test
    void aSegmentFromAnOldControlGenerationCannotBeReleased() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-seg-version", 1, 0L);
        long notificationId = completeMember(fixture.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                fixture.runControlVersion()).notificationId();
        execute("UPDATE alphafrog_agent_run_work_item SET run_control_version = run_control_version + 1 "
                + "WHERE run_id = '" + fixture.runId() + "' AND segment_sequence = 1");
        long token = ownRun(fixture.runId(), "dispatcher-seg");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            RecoveryConsumptionResult result = store.consumeRecovery(notificationId, "dispatcher-seg",
                    fixture.runControlVersion(), "dispatcher-seg", token);
            assertThat(result.consumed()).as("作废的那一段不能当新的一段放出去").isFalse();
            assertThat(result.rejection())
                    .isEqualTo(world.willfrog.agent.platform.wait.RecoveryRejection
                            .SEGMENT_CONTROL_VERSION_STALE);
            assertThat(result.permanent()).as("这种资格不会再有下一次").isTrue();
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_recovery_notification "
                + "WHERE id = " + notificationId + " AND state = 'WAITING'"))
                .as("通知留在等待态：收口是调用方拿着原因去做的，语句本身不改状态")
                .isEqualTo(1);
    }

    /**
     * 收口成关闭态：只对等待态生效，原因与时刻都落库，重复收口影响 0 行。
     */
    @Test
    void closingANotificationWritesReasonAndStopsAtTerminalStates() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-close", 1, 0L);
        long notificationId = completeMember(fixture.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                fixture.runControlVersion()).notificationId();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            assertThat(store.closeRecoveryNotification(notificationId, "run_terminal:COMPLETED"))
                    .as("等待态的通知可以收口").isTrue();
            assertThat(store.closeRecoveryNotification(notificationId, "run_terminal:COMPLETED"))
                    .as("已经关闭的通知再收口影响 0 行").isFalse();
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_recovery_notification "
                + "WHERE id = " + notificationId + " AND state = 'CLOSED' "
                + "AND close_reason = 'run_terminal:COMPLETED' AND closed_at IS NOT NULL"))
                .as("关闭状态、原因与时刻一起落库").isEqualTo(1);

        // 已经取走或随组取消的通知不能被收口改状态：收口是条件更新，不是覆盖。
        GroupFixture consumed = suspendSimpleGroup("run-close-consumed", 1, 0L);
        long consumedId = completeMember(consumed.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                consumed.runControlVersion()).notificationId();
        long token = ownRun(consumed.runId(), "dispatcher-close");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            assertThat(store.consumeRecovery(consumedId, "dispatcher-close",
                    consumed.runControlVersion(), "dispatcher-close", token).consumed()).isTrue();
            assertThat(store.closeRecoveryNotification(consumedId, "run_terminal:COMPLETED"))
                    .as("已经取走的通知不会被收口改成关闭态").isFalse();
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_recovery_notification "
                + "WHERE id = " + consumedId + " AND state = 'CONSUMED'"))
                .as("取走就是取走，不能被后来的收口覆盖").isEqualTo(1);
    }

    /**
     * 取消之后结果才到：只留审计，组与分段都不复活；同组其他成员也不再出现在到期扫描的口径里。
     */
    @Test
    void afterCancelALateResultOnlyLeavesAudit() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-cancel", 2, 0L);
        // 一个成员看起来已经在外面跑：结果到达时它是要留审计的那一个。
        execute("UPDATE alphafrog_agent_run_wait_member SET state = 'RUNNING', "
                + "external_operation_id = 'op-late' WHERE group_id = " + fixture.groupId()
                + " AND member_seq = 0");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            WaitChainCancelResult canceled = store.cancelChain(fixture.groupId());
            assertThat(canceled.canceled()).isTrue();
            assertThat(canceled.membersCanceled())
                    .as("同组还没结束的成员一起停，不能漏下")
                    .isEqualTo(2);

            MemberCompletionResult late = store.reportLateMember(new LateMemberRequest(fixture.groupId(),
                    "call-a", "{\"note\":\"late-1\"}", "op-late", fixture.runControlVersion()));
            assertThat(late.memberState()).isEqualTo(WaitMemberState.LATE);
            assertThat(groupById(fixture.groupId()).getState())
                    .as("迟到结果不重新激活组")
                    .isEqualTo(WaitGroupState.CANCELED.name());

            // 再报一次：审计字段只写一次，后到的不能覆盖先到的。
            store.reportLateMember(new LateMemberRequest(fixture.groupId(), "call-a",
                    "{\"note\":\"late-2\"}", "op-late", fixture.runControlVersion()));
            assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_wait_member WHERE group_id = "
                    + fixture.groupId() + " AND member_identity = 'call-a' "
                    + "AND result_ref_json->>'note' = 'late-1'"))
                    .as("第一次留下的引子不许被后来的上报覆盖")
                    .isEqualTo(1);
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_wait_member WHERE group_id = "
                + fixture.groupId() + " AND state IN ('PENDING', 'RUNNING')"))
                .as("取消之后没有成员还留在待派发或执行中的状态，不会有下一次派发")
                .isZero();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_work_item WHERE run_id = '"
                + fixture.runId() + "' AND state = 'RESUMABLE'"))
                .as("下一段没有被放行")
                .isZero();
    }

    // ==================== 恢复通知的消费方 ====================

    /**
     * 消费方标识写进去就必须有意义：空白字符串、以及「状态说已消费却没有消费方」都要被数据库挡住。
     *
     * <p>恢复通知是等待链唯一的一次放行资格，谁取走的必须查得到，否则事后只剩一条「有消费时刻、
     * 没有消费方」的审计记录。这条约束来自 009 脚本，它已经放进同一份升级链里整份跑过两遍。</p>
     */
    @Test
    void recoveryNotificationConsumedByMustBeMeaningful() throws Exception {
        GroupFixture fixture = suspendSimpleGroup("run-consumed-by", 1, 0L);
        Long notificationId = completeMember(fixture.groupId(), "call-a", WaitMemberState.SUCCEEDED,
                fixture.runControlVersion()).notificationId();
        assertThat(notificationId).isNotNull();
        String notification = "alphafrog_agent_run_recovery_notification WHERE id = " + notificationId;

        expectRejected("UPDATE " + notification + " SET consumed_by = '   '",
                "alphafrog_agent_run_recovery_notification_consumed_by_check");
        expectRejected("UPDATE " + notification + " SET consumed_by = ''",
                "alphafrog_agent_run_recovery_notification_consumed_by_check");
        expectRejected("UPDATE " + notification + " SET state = 'CONSUMED', "
                        + "consumed_at = CURRENT_TIMESTAMP",
                "alphafrog_agent_run_recovery_notification_consumed_by_check");

        // 还没被取走时留空是常态，约束不许把它当成错的。
        execute("UPDATE " + notification + " SET consumed_by = NULL");
        // 正常取走：状态、时刻、消费方三样齐全，写进去。
        execute("UPDATE " + notification + " SET state = 'CONSUMED', "
                + "consumed_at = CURRENT_TIMESTAMP, consumed_by = 'dispatcher-1'");
        assertThat(countRows("SELECT count(*) FROM " + notification
                + " AND state = 'CONSUMED' AND consumed_by = 'dispatcher-1'"))
                .isEqualTo(1);
    }

    // ==================== Run 创建与接收事实 ====================

    /**
     * Run 主记录与 RUN_RECEIVED 接收事实必须落在同一条事务里：接收事实写不进去时，Run 也不许留下。
     *
     * <p>做法是把事件表临时改名，让那条插入必然失败，再回头看 Run 主表有没有残留。留下的半成品
     * （有 Run、没有接收事实）会被幂等重试直接读回，缺失的事实再也没人补，所以这一条要在真库上量。
     * 名字改回来之后同一个幂等键必须还能正常建：回滚不许留下挡住重试的东西。</p>
     */
    @Test
    void aRunWithoutItsReceivedFactIsRolledBack() throws Exception {
        AgentRunEventService service = admissionService();
        String key = "key-rollback-1";
        execute("ALTER TABLE alphafrog_agent_run_event RENAME TO alphafrog_agent_run_event_hidden");
        try {
            assertThatThrownBy(() -> createNewRun(service, "user-rollback", key))
                    .as("接收事实写不进去，创建必须整体失败")
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            execute("ALTER TABLE alphafrog_agent_run_event_hidden "
                    + "RENAME TO alphafrog_agent_run_event");
        }
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run WHERE user_id = 'user-rollback' "
                + "AND idempotency_key = '" + key + "'"))
                .as("事件没写成，Run 主记录也不许留下")
                .isZero();

        AgentRunEventService.RunCreation retried = createNewRun(service, "user-rollback", key);
        assertThat(retried.created()).as("回滚之后同一个键还能正常建").isTrue();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run WHERE user_id = 'user-rollback' "
                + "AND idempotency_key = '" + key + "'")).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_event WHERE run_id = '"
                + retried.run().getId() + "' AND event_type = 'RUN_RECEIVED'"))
                .as("新建的 Run 恰好一条接收事实")
                .isEqualTo(1);
    }

    /**
     * 同一个幂等键的两个请求同时到达：库里只留一条 Run、一条接收事实，其中一个请求拿到「新建」。
     *
     * <p>另一条走的是并发分支——要么在唯一索引上撞车后读回先写入的那条，要么在插入之前就查到它。
     * 两条路都只允许读回，不许再建一条：同一次请求执行两遍的根子就在这里。返回的 {@code created}
     * 就是上层据此决定「要不要准入、要不要启动」的那个标志。</p>
     */
    @Test
    void twoRequestsWithTheSameKeyLeaveExactlyOneRunAndOneReceivedFact() throws Exception {
        AgentRunEventService service = admissionService();
        String key = "key-race-1";
        List<AgentRunEventService.RunCreation> results = runConcurrently(2,
                ignored -> createNewRun(service, "user-race", key));

        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(AgentRunEventService.RunCreation::created).count())
                .as("同时到达的同键请求只有一个真的新建")
                .isEqualTo(1);
        assertThat(results).extracting(result -> result.run().getId())
                .as("两条路返回的必须是同一条 Run")
                .containsOnly(results.get(0).run().getId());

        String runId = results.get(0).run().getId();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run WHERE user_id = 'user-race' "
                + "AND idempotency_key = '" + key + "'"))
                .as("库里只留一条 Run")
                .isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_event WHERE run_id = '" + runId
                + "' AND event_type = 'RUN_RECEIVED'"))
                .as("库里只留一条接收事实：读回的那条不许再写一次")
                .isEqualTo(1);
    }

    /**
     * 事件流投射失败不回退已经提交的创建：数据库那一行才是权威。
     *
     * <p>投射挪到事务提交之后，就不会再出现「Redis 里有、库里没有」的孤儿事件；反过来，
     * 投射失败也不能把已经落库的 Run 与接收事实说成没建成，事件流可以由库里那一行重建。</p>
     */
    @Test
    void aFailedProjectionDoesNotUndoTheCommittedRun() throws Exception {
        AgentRunEventRedisStore throwing = Mockito.mock(AgentRunEventRedisStore.class);
        Mockito.doThrow(new IllegalStateException("事件流写不进去")).when(throwing)
                .append(Mockito.any());
        AgentRunEventService service = admissionService(throwing);

        AgentRunEventService.RunCreation creation = createNewRun(service, "user-projection",
                "key-projection-1");
        assertThat(creation.created()).as("投射失败发生在提交之后，不该让创建失败").isTrue();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run WHERE id = '"
                + creation.run().getId() + "'"))
                .as("Run 已经提交，投射失败不能把它抹掉")
                .isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_event WHERE run_id = '"
                + creation.run().getId() + "' AND event_type = 'RUN_RECEIVED'"))
                .as("接收事实也在库里：投射失败时它就是重建事件流的那份来源")
                .isEqualTo(1);
    }

    /**
     * 补投：库里有接收事实、事件流里没有时，周期修补按数据库那一行再投一次。
     *
     * <p>用的是行里原来的序号、时间与负载：事件流按 seq 记成员，重投要让成员与分数和当初那条逐字一致，
     * 否则同一条事实会在流里变成两条。这一条就是「谁是那个消费者」的真库证据——不靠重试运气，
     * 靠一个按数据库事实重扫的修补。</p>
     */
    @Test
    void theRepairReappendsThePersistedReceivedFactVerbatim() throws Exception {
        AgentRunEventRedisStore broken = Mockito.mock(AgentRunEventRedisStore.class);
        Mockito.doThrow(new IllegalStateException("事件流写不进去")).when(broken).append(Mockito.any());
        AgentRunEventService service = admissionService(broken);
        AgentRunEventService.RunCreation creation = createNewRun(service, "user-repair", "key-repair-1");
        assertThat(creation.created()).as("投射失败不影响创建").isTrue();

        AgentRunEventMapper eventMapper =
                new SqlSessionTemplate(sqlSessionFactory).getMapper(AgentRunEventMapper.class);
        AgentRunEvent persisted = eventMapper.findFirstByRunIdAndType(creation.run().getId(), "RUN_RECEIVED");
        assertThat(persisted).as("接收事实在库里").isNotNull();

        AgentRunEventRedisStore healed = Mockito.mock(AgentRunEventRedisStore.class);
        Mockito.lenient().when(healed.repairMissing(Mockito.any())).thenReturn(true);
        // 保留期只有一份出处：回扫窗口读的就是事件流自己那份 TTL。
        Mockito.lenient().when(healed.retention()).thenReturn(java.time.Duration.ofDays(7));
        DeploymentIdentityProvider identityProvider = Mockito.mock(DeploymentIdentityProvider.class);
        Mockito.lenient().when(identityProvider.current())
                .thenReturn(new DeploymentIdentity("stable", "gen-" + "a".repeat(64)));
        AgentRunEventProjectionRepair repair =
                new AgentRunEventProjectionRepair(eventMapper, healed, identityProvider, 200, 16, 2000);

        assertThat(repair.repair()).as("保留期内至少读到刚建的那一条").isGreaterThanOrEqualTo(1);

        ArgumentCaptor<AgentRunEvent> appended = ArgumentCaptor.forClass(AgentRunEvent.class);
        Mockito.verify(healed, Mockito.atLeastOnce()).repairMissing(appended.capture());
        AgentRunEvent repaired = appended.getAllValues().stream()
                .filter(event -> creation.run().getId().equals(event.getRunId()))
                .findFirst().orElseThrow();
        assertThat(repaired.getSeq())
                .as("序号取自行里的原值，不重新生成").isEqualTo(persisted.getSeq());
        assertThat(repaired.getCreatedAt())
                .as("时间也取自行里的原值").isEqualTo(persisted.getCreatedAt());
        assertThat(repaired.getPayloadJson())
                .as("负载逐字一致：事件流的成员必须和当初那条一样").isEqualTo(persisted.getPayloadJson());
    }

    /**
     * 修补的扫描要有自己的索引：事件表只增不减，每半分钟一轮的扫描不能全表翻一遍。
     *
     * <p>断言的是索引定义本身（列顺序与固定条件），不是「规划器在这一刻选了它」——表里只有几行时
     * 全表扫更便宜，规划器选哪条路取决于数据量，那种断言会随数据量变。</p>
     */
    @Test
    void theRepairScanHasItsOwnIndex() throws Exception {
        assertThat(countRows("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND tablename = 'alphafrog_agent_run_event'"
                + " AND indexname = 'idx_agent_run_event_received_created'"
                + " AND indexdef LIKE '%(created_at, id)%'"
                + " AND indexdef LIKE '%RUN_RECEIVED%'"))
                .as("接收事实修补的索引按（时间，编号）建好，且只收 RUN_RECEIVED 这一种事件")
                .isEqualTo(1);
    }

    /**
     * 表里塞满别的类型的事件之后，修补那条查询在真实规划器下也不做整表扫描。
     *
     * <p>上面那条只证明索引按对的列建好了；这条把库喂到「整表翻一遍明显更贵」的规模，再看规划器
     * 给这条查询什么计划。断言只看一件事：事件表这一侧不被整表翻——这正是建这条索引的原因，
     * 事件表只增不减，每半分钟一轮的扫描不能随着它一起变慢。计划全文放在失败消息里，
     * 真跑出来不一样时能直接看到分歧在哪。</p>
     */
    @Test
    void theRepairScanDoesNotWalkTheWholeEventTable() throws Exception {
        String runId = "run-repair-plan";
        createRun(runId, 0, 0L);
        // 二十万条别的事件：它们不进偏索引，所以「整表翻」在这里看起来越来越划不来。
        execute("INSERT INTO alphafrog_agent_run_event (run_id, seq, event_type, payload_json) "
                + "SELECT '" + runId + "', seq, 'LLM_CALL_STARTED', '{}'::jsonb "
                + "FROM generate_series(1, 200000) AS seq");
        insertReceivedFact(runId, 200001);
        execute("ANALYZE alphafrog_agent_run_event");

        String plan = explain("SELECT e.id, e.run_id, e.seq, e.event_type, "
                + "e.payload_json::text AS payload_json, e.created_at "
                + "FROM alphafrog_agent_run_event e "
                + "JOIN alphafrog_agent_run r ON r.id = e.run_id "
                + "WHERE e.event_type = 'RUN_RECEIVED' "
                + "AND e.created_at > now() - interval '7 days' "
                + "AND (e.created_at, e.id) > (now() - interval '7 days', 0) "
                + "AND r.deployment_id = 'stable' "
                + "ORDER BY e.created_at ASC, e.id ASC LIMIT 200");

        assertThat(plan)
                .as("修补查询不能在事件表上整表扫描，实际计划：\n" + plan)
                .doesNotContain("Seq Scan on alphafrog_agent_run_event");
    }

    /** 规划器怎么走这条查询：只要计划文本，不执行。 */
    private static String explain(String sql) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery("EXPLAIN (COSTS OFF) " + sql)) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    /**
     * 投射要用数据库实际保存的那一行：写入不写 {@code created_at}（用库自己的当前时间），
     * 负载又存成 jsonb。
     *
     * <p>事件流的成员里带着时间和负载文本，所以「按 Java 对象投一次、按库里的行再投一次」
     * 会写出两个不同的成员，同一个事件在流里出现两条。这条量的是前提：库里那一行确实与
     * Java 对象里的那两样不一样，所以投射必须从库里取。</p>
     */
    @Test
    void thePersistedEventRowIsTheCanonicalProjectionSource() throws Exception {
        AgentRunEventRedisStore store = Mockito.mock(AgentRunEventRedisStore.class);
        AgentRunEventService service = admissionService(store);
        AgentRunEventService.RunCreation creation = createNewRun(service, "user-canonical", "key-canonical-1");
        assertThat(creation.created()).isTrue();

        AgentRunEventMapper eventMapper =
                new SqlSessionTemplate(sqlSessionFactory).getMapper(AgentRunEventMapper.class);
        AgentRunEvent persisted = eventMapper.findByRunIdAndSeq(creation.run().getId(), 1);
        assertThat(persisted).as("按（Run，序号）读回那一条").isNotNull();
        assertThat(persisted.getCreatedAt())
                .as("时间来自数据库的当前时间，不是 Java 对象里那个时刻")
                .isNotNull();
        assertThat(persisted.getPayloadJson())
                .as("负载读回来的是 jsonb 的文本形式").isNotBlank();
        // 同一个事件再读一次，两次取到的必须是同一份值：投射与补投各读写一次，值一样才不会变成两个成员。
        AgentRunEvent again = eventMapper.findByRunIdAndSeq(creation.run().getId(), 1);
        assertThat(again.getSeq()).isEqualTo(persisted.getSeq());
        assertThat(again.getCreatedAt()).isEqualTo(persisted.getCreatedAt());
        assertThat(again.getPayloadJson()).isEqualTo(persisted.getPayloadJson());
    }

    /**
     * 补投按部署取，不按构建代际：滚动替换时旧实例可能在「库里已提交、事件流还没投」的窗口里退场，
     * 按当前代际过滤会把那道缺口永远关在门外。
     */
    @Test
    void theRepairWindowSpansGenerationsWithinTheSameDeployment() throws Exception {
        String otherGeneration = "gen-" + "b".repeat(64);
        createRunFor("run-generation-a", "user-generation-a", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        createRunFor("run-generation-b", "user-generation-b", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        execute("UPDATE alphafrog_agent_run SET deployment_generation_id = '" + otherGeneration
                + "' WHERE id = 'run-generation-b'");
        insertReceivedFact("run-generation-a", 1);
        insertReceivedFact("run-generation-b", 2);

        AgentRunEventMapper eventMapper =
                new SqlSessionTemplate(sqlSessionFactory).getMapper(AgentRunEventMapper.class);
        java.time.OffsetDateTime retentionStart = java.time.OffsetDateTime.now().minusDays(7);
        assertThat(eventMapper.listReceivedFactsForRepair("stable", retentionStart, 50))
                .as("同一个部署下的两个代际都在扫描窗口里：只按部署收窄，代际不是过滤条件")
                .extracting(AgentRunEvent::getRunId)
                .contains("run-generation-a", "run-generation-b");
    }

    /** 直接插一条接收事实：这条用例只关心扫描窗口，不关心创建那条路。 */
    private static void insertReceivedFact(String runId, int seq) throws Exception {
        execute("INSERT INTO alphafrog_agent_run_event (run_id, seq, event_type, payload_json) "
                + "VALUES ('" + runId + "', " + seq + ", 'RUN_RECEIVED', '{}'::jsonb)");
    }

    // ==================== Run 的服务所有权租约 ====================

    /**
     * 一条 Run 的服务所有权：领取、续期、让出、接手，以及换主人之后旧主人写不动。
     *
     * <p>这是「哪一代进程此刻可以动这条 Run」的持久事实：内存登记只能说明本进程自己在做什么，
     * 按「多久没被改过」推断也不行——模型调用、工具等待期间本来就不写数据库。</p>
     */
    @Test
    void serviceLeaseIsAcquiredRenewedTakenOverAndReleased() throws Exception {
        createRun("run-lease", 0, 0L);
        RunServiceLeaseStore store = leaseStore();
        java.time.Duration ttl = java.time.Duration.ofMinutes(2);

        RunServiceLease first = store.acquire("run-lease", "owner-a", ttl).orElseThrow();
        assertThat(first.fencingToken()).as("第一次领取代际从 1 开始").isEqualTo(1L);
        assertThat(first.ownerInstanceId()).isEqualTo("owner-a");

        assertThat(store.acquire("run-lease", "owner-a", ttl).orElseThrow().fencingToken())
                .as("同一位主人重复领取是续上，不是换代：换号会把自己在飞的操作一起作废")
                .isEqualTo(1L);
        assertThat(store.acquire("run-lease", "owner-b", ttl))
                .as("别人正活着持有：领不到，也不该领到").isEmpty();
        assertThat(store.renew("run-lease", "owner-b", 1L, ttl))
                .as("续期要核对主人").isFalse();
        assertThat(store.renew("run-lease", "owner-a", 1L, ttl)).isTrue();
        assertThat(store.renew("run-lease", "owner-a", 2L, ttl))
                .as("续期要核对代际：号对不上说明这条已经换了主人").isFalse();
        assertThat(store.renewOwned("owner-a", ttl)).as("这位主人名下的租约都在续").isGreaterThanOrEqualTo(1);
        assertThat(store.renewOwned("owner-b", ttl)).as("别人的租约一条都不动").isZero();

        // 到期之后可以被接手：代际加一，旧主人手里那个号立刻写不动。
        execute("UPDATE alphafrog_agent_run_service_lease SET expires_at = CURRENT_TIMESTAMP, "
                + "renewed_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE run_id = 'run-lease'");
        RunServiceLease takenOver = store.acquire("run-lease", "owner-b", ttl).orElseThrow();
        assertThat(takenOver.ownerInstanceId()).isEqualTo("owner-b");
        assertThat(takenOver.fencingToken()).as("接手要换代，旧主人的号从此作废").isEqualTo(2L);
        assertThat(store.renew("run-lease", "owner-a", 1L, ttl))
                .as("旧主人迟到的续期写不动").isFalse();
        assertThat(store.release("run-lease", "owner-a", 1L))
                .as("旧主人迟到的让出也写不动").isFalse();

        // 让出：立刻变成可以接手，但代际号留着继续往上涨，不会回到 1。
        assertThat(store.release("run-lease", "owner-b", 2L)).isTrue();
        RunServiceLease reacquired = store.acquire("run-lease", "owner-a", ttl).orElseThrow();
        assertThat(reacquired.fencingToken())
                .as("让出不删行：号要是回到 1，旧主人手里那个号有可能正好对上")
                .isEqualTo(3L);
    }

    /** 三个代际的进程同时抢同一条 Run：只有一个领到，别的都领不到。 */
    @Test
    void concurrentAcquireLeavesExactlyOneOwner() throws Exception {
        createRun("run-lease-race", 0, 0L);
        List<java.util.Optional<RunServiceLease>> results = runConcurrently(4, slot -> {
            RunServiceLeaseStore store = leaseStore();
            return store.acquire("run-lease-race", "race-owner-" + slot,
                    java.time.Duration.ofMinutes(5));
        });
        assertThat(results.stream().filter(java.util.Optional::isPresent).count())
                .as("四个人同时领，只可能有一个领到")
                .isEqualTo(1L);
        assertThat(results.stream().flatMap(java.util.Optional::stream)
                .map(RunServiceLease::fencingToken).toList())
                .as("唯一领到的那一个代际是 1").containsExactly(1L);
    }

    /** 接手扫描只挑「已经到期、且这条 Run 还在跑」的那些：结束的 Run 谁接手都没意义。 */
    @Test
    void onlyExpiredLeasesOfStillRunningRunsShowUpForTakeover() throws Exception {
        createRun("run-lease-live", 0, 0L);
        createRunFor("run-lease-done", "user-lease-done", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        execute("UPDATE alphafrog_agent_run SET status = 'COMPLETED' WHERE id = 'run-lease-done'");
        RunServiceLeaseStore store = leaseStore();
        java.time.Duration ttl = java.time.Duration.ofMinutes(5);
        store.acquire("run-lease-live", "owner-a", ttl).orElseThrow();
        store.acquire("run-lease-done", "owner-a", ttl).orElseThrow();
        execute("UPDATE alphafrog_agent_run_service_lease SET expires_at = CURRENT_TIMESTAMP "
                + "WHERE run_id IN ('run-lease-live', 'run-lease-done')");

        assertThat(store.listExpiredWithLiveRun(java.time.OffsetDateTime.now(), 100))
                .extracting(RunServiceLease::runId)
                .as("到期的两行里只挑还在跑的那一条")
                .contains("run-lease-live")
                .doesNotContain("run-lease-done");
    }

    /**
     * 批量续期只续「自己手上、Run 还在跑」的那些，别人手上的与已经结束的都不碰。
     *
     * <p>续期循环靠「清点几条、续上几条」对不对得上来发现租约被接手，两个数必须由同一份口径算出来：
     * 清点少算一个状态，就会每轮都报一次假的「被接手」。这条把两种边界都放进真库：别人持有的、
     * 以及自己持有的但 Run 已结束的。</p>
     */
    @Test
    void renewOwnedOnlyTouchesMyLiveRuns() throws Exception {
        createRun("run-renew-mine", 0, 0L);
        createRun("run-renew-peer", 0, 0L);
        createRun("run-renew-done", 0, 0L);
        RunServiceLeaseStore store = leaseStore();
        java.time.Duration ttl = java.time.Duration.ofMinutes(2);

        store.acquire("run-renew-mine", "owner-renew", ttl).orElseThrow();
        store.acquire("run-renew-peer", "owner-other", ttl).orElseThrow();
        store.acquire("run-renew-done", "owner-renew", ttl).orElseThrow();
        // 结束的 Run 上留着一条自己持有的租约，但续期与清点都不该算它。
        execute("UPDATE alphafrog_agent_run SET status = 'COMPLETED' WHERE id = 'run-renew-done'");

        assertThat(store.listOwnedWithLiveRun("owner-renew", 50))
                .as("清点只看还在跑的：自己的两条里有一条已经结束了")
                .extracting(RunServiceLease::runId)
                .containsExactly("run-renew-mine");
        assertThat(store.renewOwned("owner-renew", ttl))
                .as("续上的条数与清点的一致，才不会每轮报假的「被接手」")
                .isEqualTo(1);

        java.time.OffsetDateTime before = store.find("run-renew-peer").orElseThrow().expiresAt();
        assertThat(store.find("run-renew-peer").orElseThrow().expiresAt())
                .as("别人持有的那条一点都不动")
                .isEqualTo(before);
        assertThat(store.renewOwned("owner-other", ttl)).as("别人续自己的那条照常").isEqualTo(1);
    }

    /**
     * 被接手之后旧主人续不上，清点里也不再出现——这是「本进程已经不再持有」的机器事实。
     */
    @Test
    void aTakenOverLeaseCannotBeRenewedByItsFormerOwner() throws Exception {
        createRun("run-renew-taken", 0, 0L);
        RunServiceLeaseStore store = leaseStore();
        java.time.Duration ttl = java.time.Duration.ofMinutes(2);

        RunServiceLease mine = store.acquire("run-renew-taken", "owner-first", ttl).orElseThrow();
        // 让租约过期，让另一个持有者接手：代际号往前走一格。
        execute("UPDATE alphafrog_agent_run_service_lease SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'"
                + " WHERE run_id = 'run-renew-taken'");
        RunServiceLease taken = store.acquire("run-renew-taken", "owner-second", ttl).orElseThrow();
        assertThat(taken.fencingToken()).isEqualTo(mine.fencingToken() + 1);

        assertThat(store.renew("run-renew-taken", "owner-first", mine.fencingToken(), ttl))
                .as("旧主人手里那个代际号续不动了")
                .isFalse();
        assertThat(store.renewOwned("owner-first", ttl))
                .as("批量续期也一样：这条已经不归它")
                .isZero();
        assertThat(store.listOwnedWithLiveRun("owner-first", 50))
                .as("清点里也不再出现：旧主人这轮的「该续几条」自然对得上")
                .isEmpty();
        assertThat(store.renewOwned("owner-second", ttl)).isEqualTo(1);
    }

    /**
     * 新建的 Run 一出生就在共享候选里，不需要谁再补一次。
     *
     * <p>这是「三个版本共用一份候选」能成立的前提：候选表里没有位置，这条 Run 就永远不会被
     * 任何版本的调度器看见。</p>
     */
    @Test
    void aRunCreatedThroughTheServiceIsInTheSharedCandidateSetAtOnce() throws Exception {
        AgentRunEventService service = admissionService();
        AgentRunEventService.RunCreation creation =
                createNewRun(service, "user-candidate-set", "key-candidate-set-1");
        assertThat(creation.created()).isTrue();

        RunCoordinationStore store = coordinationStore();
        assertThat(store.find(creation.run().getId()))
                .as("创建那条事务里就排进了共享候选")
                .isPresent();
        assertThat(store.find(creation.run().getId()).orElseThrow().getSchedulerVersion())
                .as("资格行上的版本取自 Run 主表")
                .isEqualTo(creation.run().getSchedulerVersion());
    }

    /**
     * 真库上的服务对象：映射器与事务管理器都是真的，只有 Redis、消息、提示词这些外部协作者用替身。
     *
     * <p>要量的是事务与唯一约束的行为，映射器和事务管理器就不能是替身；Redis 顺手用替身，
     * 事件序号按固定 1 返回，正好覆盖「每条链只写一条接收事实」。</p>
     */
    private static AgentRunEventService admissionService() {
        return admissionService(Mockito.mock(AgentRunEventRedisStore.class));
    }

    /**
     * 同上，但把事件流的投射端换成调用方给的那一个，用来量「投射失败」这一路。
     */
    private static AgentRunEventService admissionService(AgentRunEventRedisStore eventRedisStore) {
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.lenient().when(valueOperations.increment(Mockito.anyString())).thenReturn(1L);
        AgentPromptService promptService = Mockito.mock(AgentPromptService.class);
        Mockito.lenient().when(promptService.snapshotPromptSelection(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new PromptRunSelection(PromptRunSelection.SCHEMA_VERSION, "default-v1",
                        "control", "bundle-digest", "capability-digest",
                        java.time.LocalDate.of(2025, 2, 3)));
        Mockito.lenient().when(promptService.snapshotDataFreshness()).thenReturn(null);

        AgentRunEventService service = new AgentRunEventService(
                template.getMapper(AgentRunMapper.class),
                template.getMapper(AgentRunEventMapper.class),
                eventRedisStore,
                new ObjectMapper(),
                redisTemplate,
                Mockito.mock(AgentLlmLocalConfigLoader.class),
                Mockito.mock(AgentMessageService.class),
                promptService,
                new DataSourceTransactionManager(dataSource),
                // 创建那条事务里会往共享候选里排队：用真的资格表，量的才是「Run 一出生就在候选里」。
                coordinationStore());
        // 这些值平时由配置注入，直接 new 出来是 0/空；显式给上，免得量的是个退化配置。
        ReflectionTestUtils.setField(service, "ttlMinutes", 60);
        ReflectionTestUtils.setField(service, "checkpointVersion", "v2");
        ReflectionTestUtils.setField(service, "payloadMaxChars", 10000);
        ReflectionTestUtils.setField(service, "payloadPreviewChars", 4096);
        return service;
    }

    /** 一次正常的创建请求：内容固定，便于同键请求算出同一份请求摘要。 */
    private static AgentRunEventService.RunCreation createNewRun(AgentRunEventService service,
                                                                String userId, String idempotencyKey) {
        return service.createRun(userId, "真库探针创建请求", "{}", idempotencyKey, "m", "e", false,
                "openrouter", 2, false, "{}", "stable", "gen-" + "a".repeat(64), null,
                SchedulerVersion.DUAL_POOL_V2.name(), false, false);
    }

    // ==================== 全局容量的并发判定 ====================

    /**
     * 两个线程同时跨越水位：最终暂停状态必须等于库里的值，而不是各写各的。
     *
     * <p>这里用一小段 Spring 上下文把存储层包起来跑：{@code @Transactional} 与行锁只有在真实的代理
     * 后面才成立，直接 new 一个存储对象是量不出并发行为的。</p>
     */
    @Test
    void twoThreadsCrossingTheWatermarkEndWithTheDatabaseValue() throws Exception {
        SchedulerStateStore store = proxiedCapacityStore();
        execute("UPDATE alphafrog_agent_scheduler_capacity_state SET add_paused = FALSE, paused_since = NULL, "
                + "unfinished_count = 0 WHERE scope_key = 'GLOBAL'");

        List<SchedulerPauseDecision> pausing = runConcurrently(2,
                ignored -> store.decideAndRecord(HIGH_WATERMARK + 4, HIGH_WATERMARK, LOW_WATERMARK));
        assertThat(pausing).allSatisfy(decision -> assertThat(decision.paused()).isTrue());
        assertThat(pausing.stream().filter(SchedulerPauseDecision::changed).count())
                .as("两个线程同时判「该停」，只有一个能把标记真的从没停改成停")
                .isEqualTo(1);
        assertThat(globalPaused()).as("库里留下的就是最终状态").isTrue();

        List<SchedulerPauseDecision> resuming = runConcurrently(2,
                ignored -> store.decideAndRecord(LOW_WATERMARK - 4, HIGH_WATERMARK, LOW_WATERMARK));
        assertThat(resuming).allSatisfy(decision -> assertThat(decision.paused()).isFalse());
        assertThat(resuming.stream().filter(SchedulerPauseDecision::changed).count())
                .as("回落到底水位以下时，也只有一次是真的恢复")
                .isEqualTo(1);
        assertThat(globalPaused()).isFalse();
    }

    // ==================== 服务所有权闸门 ====================

    /**
     * 别人接手这条 Run 之后，原来的持有者连「把死掉的分段放回可领取」这一步也做不了。
     *
     * <p>取到所有权与动手改行之间隔着一段时间，这段时间里租约可能到期、别的进程可能接手。
     * 所以放回领取态这句写入自己就核服务所有权（持有人 + 代际号 + 没过期），不是先查再写。</p>
     */
    @Test
    void aFormerLeaseOwnerCannotRequeueAfterAnotherProcessTakesTheRunOver() throws Exception {
        String runId = "run-takeover-requeue";
        createRun(runId, 0, 0L);
        ServiceOwnershipFence former = fenceFor(runId);
        createSegment(runId, 0, "node-1", 0, 0, 1, former.ownerInstanceId(), 1L, 0L, "EXECUTING");

        execute("UPDATE alphafrog_agent_run_service_lease SET expires_at = CURRENT_TIMESTAMP, "
                + "renewed_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE run_id = '" + runId + "'");
        String successor = "stage3-successor";
        long successorToken = ownRun(runId, successor);
        ServiceOwnershipFence takenOver = new ServiceOwnershipFence(successor, successorToken);
        assertThat(takenOver.fencingToken()).as("接手换代：旧凭据从这一刻起是废的")
                .isEqualTo(former.fencingToken() + 1);

        NodeWorkItemIdentity identity = new NodeWorkItemIdentity(runId, 0, "node-1", 0, 0);
        NodeWorkItemVersions versions = new NodeWorkItemVersions(1L, 0L, 1);
        NodeWorkItemStore store = workItemStore();
        assertThat(store.requeueAbandonedClaim(identity, versions, former, SchedulerVersion.DUAL_POOL_V2)
                .applied())
                .as("旧持有者拿着过期凭据放回分段：一行都写不进去").isFalse();
        assertThat(stateOf(runId, "node-1")).as("被拒之后这一行保持原样").isEqualTo("EXECUTING");
        assertThat(store.requeueAbandonedClaim(identity, versions, takenOver, SchedulerVersion.DUAL_POOL_V2)
                .applied())
                .as("现在的持有者放得回去").isTrue();
        assertThat(stateOf(runId, "node-1")).isEqualTo("RUNNABLE");
    }

    /**
     * 恢复扫描读到一行之后、动手之前，控制面把这条 Run 的控制版本推了一代：旧回合这条写入作废。
     *
     * <p>取消、追问、计划推进都会让控制版本往前走。没有这一条，一次读到旧版本再慢慢写入的恢复，
     * 会把已经被取消或已经被替代的那一段放回可领取，接着跑起来。</p>
     */
    @Test
    void aRecoveryWriteFromBeforeTheControlVersionAdvanceUpdatesNothing() throws Exception {
        String runId = "run-ctrl-advance";
        createRun(runId, 0, 2L);
        ServiceOwnershipFence fence = fenceFor(runId);
        createSegment(runId, 0, "node-1", 0, 0, 1, "worker-1", 1L, 2L, "EXECUTING");

        execute("UPDATE alphafrog_agent_run SET run_control_version = 3 WHERE id = '" + runId + "'");

        NodeWorkItemIdentity identity = new NodeWorkItemIdentity(runId, 0, "node-1", 0, 0);
        NodeWorkItemStore store = workItemStore();
        assertThat(store.requeueAbandonedClaim(identity, new NodeWorkItemVersions(1L, 2L, 1), fence,
                SchedulerVersion.DUAL_POOL_V2).applied())
                .as("旧控制版本的恢复写入：一行都写不进去").isFalse();
        assertThat(stateOf(runId, "node-1")).isEqualTo("EXECUTING");
        assertThat(store.requeueAbandonedClaim(identity, new NodeWorkItemVersions(1L, 3L, 1), fence,
                SchedulerVersion.DUAL_POOL_V2).applied())
                .as("换成当前控制版本也进不去：这一行自己记的还是旧版本，它属于谁都不该再放回去")
                .isFalse();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_work_item WHERE run_id = '"
                + runId + "' AND claim_epoch = 1 AND state = 'EXECUTING'"))
                .as("两次被拒之后这一行原样留着，等失效闸门收尾").isEqualTo(1);
    }

    /**
     * 一条 V2 的 Run 下面混着一行 V1 的未完成分段：按 Run 取行的扫描必须把它读出来，
     * 而这一行哪个版本都领不走。
     *
     * <p>证明「这条 Run 的全部未完成分段都属于这一版」的前提是先把它们读全。按行自己的版本先筛，
     * 被筛掉的行根本看不见，那个全称判断就成了永远为真。读出来之后逐行比对才发现版本不一致，
     * 结论是整条 Run 隔离，而不是让这一行在别的版本下被执行。</p>
     */
    @Test
    void aMixedVersionRowUnderOneRunIsVisibleToTheRunScanAndClaimableFromNoVersion() throws Exception {
        String runId = "run-mixed-version";
        createRunFor(runId, "user-mixed-version", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        ServiceOwnershipFence fence = fenceFor(runId);
        createSegment(runId, 0, "node-v2", 0, 0, 0, null, 1L, 0L, "RUNNABLE");
        createSegment(runId, 0, "node-v1", 0, 0, 0, null, 1L, 0L, "RUNNABLE");
        execute("UPDATE alphafrog_agent_run_work_item SET scheduler_version = 'DUAL_POOL_V1' "
                + "WHERE run_id = '" + runId + "' AND node_id = 'node-v1'");

        NodeWorkItemStore store = workItemStore();
        assertThat(store.listUnfinishedByRunSchedulerVersion(SchedulerVersion.DUAL_POOL_V2, 10))
                .filteredOn(item -> runId.equals(item.getRunId()))
                .as("按 Run 的调度器版本取行时，行自己的版本不参与筛选：混在里面的 V1 行也看得见")
                .extracting(NodeWorkItem::getNodeId)
                .containsExactlyInAnyOrder("node-v1", "node-v2");
        assertThat(store.countUnfinishedByRunSchedulerVersion(SchedulerVersion.DUAL_POOL_V2))
                .as("读全的条数与按同样口径的计数对得上，调用方才能判断有没有漏行")
                .isGreaterThanOrEqualTo(2);

        assertThat(claim(runId, "node-v1", "DUAL_POOL_V2", fence))
                .as("拿这条 Run 的版本去领那一行：行自己的版本对不上，领不走").isNull();
        assertThat(claim(runId, "node-v1", "DUAL_POOL_V1", fence))
                .as("反过来拿行自己的版本去领：父 Run 冻结的不是这一版，也领不走").isNull();
        assertThat(claim(runId, "node-v2", "DUAL_POOL_V2", fence))
                .as("同一条 Run 里版本一致的那一行领得走：上面两个空不是别的原因造成的")
                .isEqualTo(1);
    }

    /**
     * 协调资格的写入只有两种结果：写进去一条，或者影响 0 行。
     *
     * <p>写不进的那一次不该顺手补一行出来——「这个 Run 有没有资格记录」本身就是一个事实，
     * 读取侧靠它决定要不要排队，凭空多出来的行会让一条从来没进过双池的 Run 出现在候选里。</p>
     */
    @Test
    void aRejectedCoordinationWriteLeavesTheEntitlementRowUntouched() throws Exception {
        String runId = "run-entitlement-rows";
        createRun(runId, 0, 0L);
        RunCoordinationStore store = coordinationStore();
        assertThat(store.ensure(runId)).as("第一条资格记录建出来").isTrue();
        assertThat(store.ensure(runId)).as("再调一次不重复建行").isTrue();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_coordination WHERE run_id = '"
                + runId + "'")).as("一个 Run 一条资格记录").isEqualTo(1);

        ServiceOwnershipFence fence = fenceFor(runId);
        assertThat(store.deferFor(runId, RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 7, 0L, fence))
                .as("拿一个对不上的计划代际来延期：影响 0 行").isFalse();
        assertThat(store.markCoordinationServed(runId, 4, 0, fence)).isTrue();
        assertThat(store.deferFor(runId, RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 1L, fence))
                .as("轮次对不上的延期同样写不进去").isFalse();
        assertThat(deferReasonOf(runId)).as("两次被拒都没有留下延期原因").isNull();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_coordination WHERE run_id = '"
                + runId + "'")).as("拒绝不建行、也不复制行").isEqualTo(1);
        assertThat(store.markCoordinationServed(runId, 5, 0, fence)).isTrue();
        assertThat(store.find(runId).orElseThrow().getCoordinationServedRound()).isEqualTo(5L);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_coordination WHERE run_id = '"
                + runId + "'")).as("成功的写入也还是那一行").isEqualTo(1);
    }

    /**
     * 启动隔离的输入每次都从库里重算，结论本身不落表。
     *
     * <p>两次独立的读各自新开会话，模拟两个进程看到同一份库：读到的分段行与逐行比对用的数据
     * （行自己的版本、计划代际、控制版本）必须一模一样。库里也没有任何一列记着「这条 Run 被隔离」——
     * 所以重启之后结论是重算出来的，不依赖上一次进程的内存。</p>
     */
    @Test
    void isolationInputsAreRecomputedFromTheDatabaseAndTheConclusionIsNotPersisted() throws Exception {
        String runId = "run-isolation-recompute";
        createRunFor(runId, "user-isolation-recompute", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        ServiceOwnershipFence fence = fenceFor(runId);
        createSegment(runId, 0, "node-v2", 0, 0, 0, null, 1L, 0L, "RUNNABLE");
        createSegment(runId, 0, "node-v1", 0, 0, 0, null, 1L, 0L, "RUNNABLE");
        execute("UPDATE alphafrog_agent_run_work_item SET scheduler_version = 'DUAL_POOL_V1' "
                + "WHERE run_id = '" + runId + "' AND node_id = 'node-v1'");

        List<String> firstRead = isolationInputsOf(runId);
        List<String> secondRead = isolationInputsOf(runId);
        assertThat(secondRead).as("两次独立的读给出同样的输入").isEqualTo(firstRead);
        assertThat(firstRead)
                .as("混在 V2 Run 下面的 V1 行也读得出来，两边带着各自的版本与代际供逐行比对")
                .containsExactly("node-v1:DUAL_POOL_V1:plan=0:ctrl=0",
                        "node-v2:DUAL_POOL_V2:plan=0:ctrl=0");
        assertThat(countRows("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND column_name ILIKE '%isolat%'"))
                .as("隔离是这个进程当时的结论，库里没有记它的列").isZero();
        assertThat(fence.fencingToken()).as("造数据这件事本身也要有活着的服务所有者").isPositive();
    }

    /** 一个 Run 的未完成分段按「读出来要比对的数据」排好序：节点身份、行自己的版本、两个代际。 */
    private static List<String> isolationInputsOf(String runId) {
        return workItemStore()
                .listUnfinishedByRunSchedulerVersion(SchedulerVersion.DUAL_POOL_V2, 100).stream()
                .filter(item -> runId.equals(item.getRunId()))
                .map(item -> item.getNodeId() + ":" + item.getSchedulerVersion()
                        + ":plan=" + item.getPlanGeneration() + ":ctrl=" + item.getRunControlVersion())
                .sorted()
                .toList();
    }

    /** 资格记录建到一半的事务回滚：库里不留半条记录；同一个入口再来一次并提交，留下恰好一条。 */
    @Test
    void aRolledBackEntitlementWriteLeavesNoRowBehind() throws Exception {
        String runId = "run-entitlement-rollback";
        createRun(runId, 0, 0L);
        // 会话要挂在 Spring 事务上：直接 new 出来的会话不参与回滚，量出来的是假的。
        RunCoordinationMapper mapper = new SqlSessionTemplate(sqlSessionFactory)
                .getMapper(RunCoordinationMapper.class);
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        Integer affected = template.execute(status -> {
            int rows = mapper.ensure(runId);
            status.setRollbackOnly();
            return rows;
        });
        assertThat(affected).as("事务里那条语句确实建了行").isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_coordination WHERE run_id = '"
                + runId + "'")).as("回滚之后不留行").isZero();

        Integer secondAttempt = template.execute(status -> mapper.ensure(runId));
        assertThat(secondAttempt).as("同一个入口再来一次：语句还是建了行").isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_coordination WHERE run_id = '"
                + runId + "'")).as("提交之后恰好一条").isEqualTo(1);
    }

    // ==================== 语句与工具 ====================

    /** 真实升级链：先建表脚本，再按版本顺序升到 006；这样验证的是部署时真正会走的路径。 */
    private static void applyUpgradeChain() throws Exception {
        List<java.nio.file.Path> chain = MigrationStatements.upgradeChainUpTo(
                "006_agent_tool_job_test_control.sql");
        assertThat(chain).as("升级链不能是手工摆的简化前置表").hasSizeGreaterThan(30);
        for (java.nio.file.Path path : chain) {
            for (String statement : MigrationStatements.split(MigrationStatements.read(path))) {
                try {
                    execute(statement);
                } catch (SQLException e) {
                    throw new IllegalStateException("升级链在这一份脚本上失败：" + path.getFileName(), e);
                }
            }
        }
    }

    /** 每份脚本整份执行两遍：第二遍必须一样通过，证明脚本可以重复执行。 */
    private static void applyStage3ScriptsTwice() throws Exception {
        for (int round = 1; round <= 2; round++) {
            for (String script : List.of(STAGE3_SCRIPT, DISPATCH_PROOF_SCRIPT, CONSUMED_BY_SCRIPT,
                    REPAIR_INDEX_SCRIPT, SERVICE_LEASE_SCRIPT, SHARED_CANDIDATE_SCRIPT,
                    RECOVERY_CLOSE_SCRIPT, ACCEPTANCE_FIXTURE_SCRIPT, RELEASE_POINT_SCRIPT)) {
                List<String> statements = MigrationStatements.split(MigrationStatements.read(script));
                assertThat(statements).as("脚本要能被切成可执行语句：" + script).isNotEmpty();
                for (String statement : statements) {
                    try {
                        execute(statement);
                    } catch (SQLException e) {
                        throw new IllegalStateException(
                                "第 " + round + " 遍执行 " + script + " 失败：" + statement, e);
                    }
                }
            }
        }
    }

    private static SqlSessionFactory buildSessionFactory(DataSource source) throws Exception {
        // 事务工厂用 Spring 那一套：容量并发用例走 SqlSessionTemplate + DataSourceTransactionManager，
        // 会话要能挂进当前事务。直接用 JdbcTransactionFactory 建出来的工厂接不上 Spring 的事务，
        // 用例会在「代理后面才有行锁」这件事上量了个空。
        Configuration configuration = new Configuration(
                new Environment("stage3-postgres", new SpringManagedTransactionFactory(), source));
        for (String resource : List.of("mapper/WaitGroupMapper.xml", "mapper/NodeWorkItemMapper.xml",
                "mapper/AgentRunMapper.xml", "mapper/AgentRunEventMapper.xml",
                "mapper/RunCoordinationMapper.xml",
                "mapper/RunServiceLeaseMapper.xml",
                "mapper/SchedulerStateMapper.xml")) {
            try (InputStream xml = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 一条 Run 的服务所有权凭据：真库上必须是确实存在、没过期的那一行，
     * 语句里的所有权条件（持有人 + 代际号 + 未过期）才过得去。
     */
    private static ServiceOwnershipFence fenceFor(String runId) {
        String owner = "stage3-fence-owner";
        return new ServiceOwnershipFence(owner, ownRun(runId, owner));
    }

    /** 造一条 Run：走真实插入语句，列集合与约束由映射文件保证，不手写一大串列名。 */
    private static void createRun(String runId, int planGeneration, long runControlVersion) {
        createRunFor(runId, "user-1", planGeneration, runControlVersion);
    }

    /** 指定用户的 Run：幂等唯一约束按用户分组，造数据时要能把用户分开。 */
    private static void createRunFor(String runId, String userId, int planGeneration,
                                     long runControlVersion) {
        createRunFor(runId, userId, SchedulerVersion.DUAL_POOL_V2, planGeneration, runControlVersion);
    }

    /**
     * 指定冻结版本的 Run：协调资格记录上的版本要从这里派生，不能由测试另外插一份子记录，
     * 否则「父子版本一致」这件事就测不到了。
     */
    private static void createRunFor(String runId, String userId, SchedulerVersion schedulerVersion,
                                     int planGeneration, long runControlVersion) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        run.setDeploymentId("stable");
        run.setDeploymentGenerationId("gen-" + "a".repeat(64));
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setCurrentStep(0);
        run.setMaxSteps(20);
        run.setPlanJson("{}");
        run.setSnapshotJson("{}");
        run.setTtlExpiresAt(java.time.OffsetDateTime.now().plusHours(1));
        run.setExt("{}");
        run.setToolJobAnchorJson("{}");
        run.setSchedulerVersion(schedulerVersion.name());
        run.setPlanGeneration(planGeneration);
        run.setRunControlVersion(runControlVersion);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertThat(session.getMapper(AgentRunMapper.class).insert(run)).isEqualTo(1);
        }
    }

    /** 造一个节点分段：同样走真实插入语句。 */
    private static void createSegment(String runId, int planGeneration, String nodeId, int nodeAttempt,
                                      int segmentSequence, int claimEpoch, String claimedBy,
                                      long contextVersion, long runControlVersion, String state) {
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(runId);
        item.setPlanGeneration(planGeneration);
        item.setNodeId(nodeId);
        item.setNodeAttempt(nodeAttempt);
        item.setSegmentSequence(segmentSequence);
        item.setState(state);
        item.setContextVersion(contextVersion);
        item.setRunControlVersion(runControlVersion);
        item.setClaimEpoch(claimEpoch);
        item.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        item.setClaimedBy(claimedBy);
        item.setLeaseExpiresAt(claimedBy == null ? null
                : java.time.OffsetDateTime.now().plusMinutes(5));
        item.setNextVisibleAt(java.time.OffsetDateTime.now().minusSeconds(1));
        item.setPayloadJson("{}");
        ServiceOwnershipFence fence = ownershipForInsert(runId);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertThat(session.getMapper(NodeWorkItemMapper.class)
                    .insert(item, fence.ownerInstanceId(), fence.fencingToken())).isEqualTo(1);
        }
    }

    /**
     * 造分段之前先确认这条 Run 有活着的服务所有者：已经有活着的持有人就沿用它，没有才以测试
     * 自己的身份取一次。插入语句核的是「此刻确实有一行没过期的租约」，造数据不该把别人手里的
     * 所有权抢回来——那是接管测试要单独摆出来的事。
     */
    private static ServiceOwnershipFence ownershipForInsert(String runId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            RunServiceLease existing = session.getMapper(RunServiceLeaseMapper.class).find(runId);
            if (existing != null && !existing.expiredAt(java.time.OffsetDateTime.now())) {
                return new ServiceOwnershipFence(existing.ownerInstanceId(), existing.fencingToken());
            }
        }
        String owner = "stage3-fence-owner";
        return new ServiceOwnershipFence(owner, ownRun(runId, owner));
    }

    /** 读每个逻辑节点的最新分段：复用产品代码里那条查询。 */
    private static List<NodeWorkItem> latestSegments(String runId, int planGeneration) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(NodeWorkItemMapper.class).listLatestSegments(runId, planGeneration);
        }
    }

    /** 让这条 Run 的服务所有权归某个持有者，返回代际号；恢复消费必须先有这条事实。 */
    private static long ownRun(String runId, String owner) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return new MybatisRunServiceLeaseStore(session.getMapper(RunServiceLeaseMapper.class))
                    .acquire(runId, owner, java.time.Duration.ofMinutes(2))
                    .orElseThrow(() -> new IllegalStateException("没能取得服务所有权：" + runId))
                    .fencingToken();
        }
    }

    private static RunServiceLeaseStore leaseStore() {
        SqlSession session = sqlSessionFactory.openSession(true);
        return new MybatisRunServiceLeaseStore(session.getMapper(RunServiceLeaseMapper.class));
    }

    private static RunCoordinationStore coordinationStore() {
        SqlSession session = sqlSessionFactory.openSession(true);
        return new MybatisRunCoordinationStore(session.getMapper(RunCoordinationMapper.class));
    }

    private static NodeWorkItemStore workItemStore() {
        SqlSession session = sqlSessionFactory.openSession(true);
        return new MybatisNodeWorkItemStore(session.getMapper(NodeWorkItemMapper.class));
    }

    /** 一条分段此刻的状态；用例用它核对被拒的写入确实没改动这一行。 */
    private static String stateOf(String runId, String nodeId) throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(NodeWorkItemMapper.class)
                    .findByIdentity(runId, 0, nodeId, 0, 0).getState();
        }
    }

    /** 按指定的调度器版本领一条分段：返回新领取代际，领不到返回 null。 */
    private static Integer claim(String runId, String nodeId, String schedulerVersion,
                                 ServiceOwnershipFence fence) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(NodeWorkItemMapper.class).claim(
                    fence.ownerInstanceId(), fence.fencingToken(), runId, 0, nodeId, 0, 0,
                    schedulerVersion, 1L, 0L, "stage3-claimer",
                    java.time.OffsetDateTime.now().plusMinutes(1));
        }
    }

    /** 资格记录上的延期原因：没被延期过就是 null。 */
    private static String deferReasonOf(String runId) {
        return coordinationStore().find(runId).map(RunCoordination::getDeferReason).orElse(null);
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
                    "{\"result\":\"ok\"}", null, 0, 2L, runControlVersion));
        }
    }

    /** 把一个还没派发的成员转成执行中：压住只对执行中的成员生效，这一步不能省。 */
    private static boolean dispatchMember(long groupId, String memberIdentity, long runControlVersion) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            return store.markMemberDispatched(groupId, memberIdentity, "op-" + memberIdentity,
                    "{\"taskId\":\"t-" + memberIdentity + "\"}", OffsetDateTime.now(), runControlVersion);
        }
    }

    /** 按成员身份读一行成员：读回来的是库里现在的样子。 */
    private static WaitMember memberByIdentity(long groupId, String memberIdentity) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class))
                    .findMemberByIdentity(groupId, memberIdentity).orElseThrow();
        }
    }

    private static boolean holdMember(long groupId, String memberIdentity, OffsetDateTime nextPollAt) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class))
                    .holdMember(groupId, memberIdentity, nextPollAt);
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

    /**
     * 候选里属于本用例的那几条，按扫描给出的先后排列。
     *
     * <p>整个真库用例类共用一个 schema、方法顺序又不固定：别的用例留下的候选行可能还在窗口里，
     * 直接对整份扫描结果断言先后，就会变成依赖执行顺序。这里先把不属于本用例的行滤掉。</p>
     */
    private static List<String> dueRunIds(RunCoordinationStore store, List<String> mine) {
        return store.scanDue(500).stream()
                .map(RunCoordination::getRunId)
                .filter(mine::contains)
                .toList();
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

    /**
     * 迁移刚做完时的形状检查：脚本只加结构，不写业务数据。
     *
     * <p>它在 {@code setUp} 里跑，不是某一条用例的断言：共用 schema 的用例之间没有固定顺序。</p>
     */
    private static void assertFreshlyMigratedSchema() throws Exception {
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_wait_group")).isZero();
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_scheduler_round")).isEqualTo(2);
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_scheduler_capacity_state")).isEqualTo(1);
        // 008 加的那一列：重复执行两遍之后仍然只有一列，且类型就是 JSONB。
        assertThat(countRows("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = current_schema() "
                + "AND table_name = 'alphafrog_agent_run_wait_member' "
                + "AND column_name = 'dispatch_proof_json' AND data_type = 'jsonb'"))
                .as("等待成员表要有后台派发证明列").isEqualTo(1);
        // 014 那张验收夹具表：脚本只加结构不写数据；三份内容都是 JSONB（读取时要按 JSON 取文本）；
        // 只对待用的行建索引，未启用的夹具不进索引。
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_acceptance_fixture")).isZero();
        assertThat(countRows("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = current_schema() "
                + "AND table_name = 'alphafrog_agent_run_acceptance_fixture' "
                + "AND column_name IN ('plan_json', 'model_script_json', 'dispatch_policy_json') "
                + "AND data_type = 'jsonb'"))
                .as("夹具表的三份内容列都应当是 JSONB").isEqualTo(3);
        assertThat(countRows("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND indexname = 'idx_agent_run_acceptance_fixture_ready' "
                + "AND indexdef LIKE '%enabled%'"))
                .as("夹具表要有只含待用行的索引").isEqualTo(1);
        // 015 那张放行点表：同样只加结构不写数据；放行的判断按「Run + 放行点」精确命中，
        // 没放行的行不进索引。
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_release_point")).isZero();
        assertThat(countRows("SELECT count(*) FROM pg_constraint WHERE connamespace = "
                + "current_schema()::regnamespace AND conname IN ("
                + "'alphafrog_agent_run_release_point_identity_key', "
                + "'alphafrog_agent_run_release_point_open_check')"))
                .as("放行点表要有「一个放行点一行」与「时刻与放行人成对」两条约束").isEqualTo(2);
        assertThat(countRows("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND indexname = 'idx_agent_run_release_point_opened' "
                + "AND indexdef LIKE '%opened_at IS NOT NULL%'"))
                .as("放行点表要有只含已放行行的索引").isEqualTo(1);
    }

    /** 再报一次同一段的挂起：用来验「旧计划的挂起整条不生效」。 */
    private static WaitSuspensionResult suspendAgain(String runId, int planGeneration, long runControlVersion) {
        List<WaitMemberDraft> members = List.of(
                new WaitMemberDraft(0, "call-a", "executePython", null),
                new WaitMemberDraft(1, "call-b", "executePython", null));
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            WaitGroupStore store = new MybatisWaitGroupStore(session.getMapper(WaitGroupMapper.class));
            return store.suspendSegment(new WaitSuspensionRequest(
                    new NodeWorkItemIdentity(runId, planGeneration, "node-1", 0, 0),
                    new NodeWorkItemVersions(2L, runControlVersion, 3),
                    "worker-1", 0, SchedulerVersion.DUAL_POOL_V2, members,
                    "{\"waitSuspension\":true}", "{\"checkpoint\":\"c-1\"}"));
        }
    }

    /** 库里全局那一行的暂停标记：并发判定的最终状态以它为准。 */
    private static boolean globalPaused() throws Exception {
        return countRows("SELECT count(*) FROM alphafrog_agent_scheduler_capacity_state "
                + "WHERE scope_key = 'GLOBAL' AND add_paused") == 1;
    }

    /**
     * 用一小段 Spring 上下文把容量存储层包起来：{@code @Transactional} 与行锁只有在代理后面才成立。
     * 直接 new 出来的存储对象上没有事务，量不出并发行为。
     */
    private static SchedulerStateStore proxiedCapacityStore() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("probeDataSource", DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(SchedulerStateMapper.class,
                () -> new SqlSessionTemplate(sqlSessionFactory).getMapper(SchedulerStateMapper.class));
        context.registerBean(MybatisSchedulerStateStore.class);
        context.register(TransactionManagementEnablement.class);
        context.refresh();
        return context.getBean(SchedulerStateStore.class);
    }

    /** 只为了给上面那段上下文打开注解事务管理。 */
    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement
    static class TransactionManagementEnablement {
    }

    // ==================== 连接串 ====================

    /** 解析出来的连接信息；包内可见，好让不需要真库的守卫用例直接调解析函数。 */
    record Target(String jdbcUrl, String user, String password) {
    }

    /**
     * 连接信息只从环境变量来：连接串里不许带账号口令。
     *
     * <p>带 userinfo 的连接串会把明文口令留在进程参数、shell 历史或日志里，解析失败时还会被整串
     * 带进异常消息。所以这里见到 userinfo（或 JDBC 串里的 {@code user=}/{@code password=} 参数）直接
     * 失败关闭，并且报错里不回显原串；账号口令一律走 {@code AF_STAGE3_PG_USER} 与
     * {@code AF_STAGE3_PG_PASSWORD} 两个环境变量。</p>
     *
     * <p>两种写法都支持：{@code postgres(ql)://host:port/db} 与已经写好的
     * {@code jdbc:postgresql://...}。前一种只取主机、端口、库名与查询参数，不会把整串照搬过去。
     * 两种写法都要查查询参数：{@code ?user=...&password=...} 会一路交给驱动，等于把口令带进连接。</p>
     */
    static Target resolveTarget(String dsn) {
        String user = env("AF_STAGE3_PG_USER");
        String password = env("AF_STAGE3_PG_PASSWORD");
        if (dsn.startsWith("jdbc:")) {
            rejectUserInfo(dsn.substring("jdbc:".length()), "AF_STAGE3_PG_DSN");
            rejectCredentialParams(rawQueryOf(dsn));
            return new Target(dsn, user, password);
        }
        URI uri;
        try {
            uri = URI.create(dsn);
        } catch (IllegalArgumentException e) {
            // 解析异常的消息通常带着原串，这里只报「解析不了」，不把可能含口令的串带进日志。
            throw new IllegalStateException("AF_STAGE3_PG_DSN 解析不了：请用 "
                    + "postgresql://host:port/db 或 jdbc:postgresql://host:port/db 的写法");
        }
        rejectUserInfo(dsn, "AF_STAGE3_PG_DSN");
        rejectCredentialParams(uri.getRawQuery());
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String database = uri.getPath() == null || uri.getPath().isEmpty() ? "/postgres" : uri.getPath();
        // 查询参数用原始串带上（ssl 之类不能丢，编码也原样保留），但不重建连接串里的凭证部分。
        String rawQuery = uri.getRawQuery();
        String query = rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery;
        return new Target("jdbc:postgresql://" + host + ":" + port + database + query, user, password);
    }

    /** 连接串的主机前面有没有 {@code user:pass@}。解析不了的串在这里当没有，交给参数检查那一关。 */
    private static void rejectUserInfo(String url, String variableName) {
        try {
            if (URI.create(url).getUserInfo() != null) {
                throw new IllegalStateException(variableName + " 里不许带账号口令：请改用 "
                        + "AF_STAGE3_PG_USER 与 AF_STAGE3_PG_PASSWORD 两个环境变量传入");
            }
        } catch (IllegalArgumentException ignored) {
            // 结构不对的串在这里不拦：真正的失败会在连接时报出来。
        }
    }

    /**
     * 连接串的查询参数里不许有账号口令。
     *
     * <p>参数按原始查询串拆，不先解码整串：先解码会把 {@code %26} 这类编码拆成新的分隔符，
     * 参数边界就变了。参数名解码之后再比，大小写不同的 {@code USER=}/{@code Password=} 一样挡住。</p>
     */
    private static void rejectCredentialParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String name;
            try {
                name = java.net.URLDecoder.decode(rawName, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                name = rawName;
            }
            String lowered = name.strip().toLowerCase(java.util.Locale.ROOT);
            if ("user".equals(lowered) || "password".equals(lowered)) {
                throw new IllegalStateException("连接串里不许带账号口令：请改用 AF_STAGE3_PG_USER 与 "
                        + "AF_STAGE3_PG_PASSWORD 两个环境变量传入");
            }
        }
    }

    /** 从连接串里取出原始查询串（解码之前），没有就返回空串。 */
    private static String rawQueryOf(String url) {
        int question = url.indexOf('?');
        if (question < 0) {
            return "";
        }
        String rest = url.substring(question + 1);
        int hash = rest.indexOf('#');
        return hash < 0 ? rest : rest.substring(0, hash);
    }

    /**
     * 打开数据源：临时 schema 用数据源自己的 schema 属性指定，不往连接串后面拼参数——
     * 连接串里已经带了同名参数时，拼上去会各说一套。
     */
    private static DataSource open(Target target, String schema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(target.jdbcUrl());
        if (schema != null) {
            dataSource.setCurrentSchema(schema);
        }
        if (target.user() != null) {
            dataSource.setUser(target.user());
        }
        if (target.password() != null) {
            dataSource.setPassword(target.password());
        }
        return dataSource;
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    /** 布尔开关：只认 1/true/yes（大小写不敏感），其余都当没开。 */
    private static boolean flag(String name) {
        String raw = env(name);
        if (raw == null) {
            return false;
        }
        String trimmed = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return "1".equals(trimmed) || "true".equals(trimmed) || "yes".equals(trimmed);
    }

    /** 当前连接的默认 schema，用来证明写入没有被连接串参数带到别处。 */
    private static String currentSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("SELECT current_schema()")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
