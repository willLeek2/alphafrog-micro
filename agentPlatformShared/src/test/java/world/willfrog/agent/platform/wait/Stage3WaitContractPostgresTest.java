package world.willfrog.agent.platform.wait;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import world.willfrog.agent.platform.capacity.MybatisSchedulerStateStore;
import world.willfrog.agent.platform.capacity.SchedulerPauseDecision;
import world.willfrog.agent.platform.capacity.SchedulerStateStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.coordination.MybatisRunCoordinationStore;
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
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
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
 * 006）→ 把这一阶段的 007 与 008 两份脚本按顺序整份执行两遍（第二遍要一样通过，证明脚本可重复执行）
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
        assertThat(countRows("SELECT count(*) FROM alphafrog_agent_run_coordination"))
                .as("旧版本也要能建出协调资格：协调名额与轮转是新旧版本共用的入口")
                .isEqualTo(3);
        expectRejected(insert + "('run-coord-v2', 'DUAL_POOL_V9', NULL)",
                "alphafrog_agent_run_coordination_scheduler_version_check");
        expectRejected("UPDATE alphafrog_agent_run_coordination SET defer_reason = 'HINT_QUEUE_FULL' "
                + "WHERE run_id = 'run-coord-v2'",
                "alphafrog_agent_run_coordination_defer_reason_check");
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
     * 两个双池版本在同一份候选里竞争，谁等得久谁先被服务。
     *
     * <p>资格记录上的版本必须是从 Run 主表派生出来的：造数据时给父 Run 定版本，不再另外插一条
     * 版本不一致的子记录，否则「父子一致」这件事根本没被测到。旧版本不在这份候选里，
     * 它的行连建都建不出来，另有用例单独量。</p>
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
                .as("旧版本的 Run 不归这张表：它由旧引擎服务，建行只会白占候选名额")
                .isFalse();
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
        assertThat(store.scanDue(10))
                .as("一次全局扫描：两个双池版本排在同一份候选里，按每行的冻结版本路由")
                .extracting(RunCoordination::getRunId)
                .containsExactlyInAnyOrder("run-cold", "run-warm", "run-hot");

        store.markCoordinationServed("run-cold", 1, 0);
        store.markCoordinationServed("run-warm", 7, 0);
        store.markCoordinationServed("run-hot", 9, 0);
        assertThat(store.scanDue(10))
                .as("按最近被服务的轮次升序：越久没被服务的越靠前")
                .extracting(RunCoordination::getRunId)
                .containsExactly("run-cold", "run-warm", "run-hot");

        // 轮次位置只许前进：迟到的旧轮次写进来影响 0 行，不能把新事实改回旧事实。
        assertThat(store.markCoordinationServed("run-hot", 3, 0)).isFalse();
        assertThat(store.find("run-hot").orElseThrow().getCoordinationServedRound()).isEqualTo(9);

        // 延期要写清原因与下次可见时间，并带上这一轮读到的计划代际与轮次做条件。
        assertThat(store.deferFor("run-cold", RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 0L))
                .as("轮次对不上：这是旧观察，写进去只会把新事实拉回旧事实")
                .isFalse();
        assertThat(store.deferFor("run-cold", RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 1L)).isTrue();
        assertThat(store.find("run-cold").orElseThrow().deferReasonEnum())
                .isEqualTo(RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED);
        assertThat(store.scanDue(10))
                .extracting(RunCoordination::getRunId)
                .containsExactly("run-warm", "run-hot");

        // Run 推进计划代际：资格记录跟着走，只许前进，也不许写一个不属于 Run 的代际。
        execute("UPDATE alphafrog_agent_run SET plan_generation = 1 WHERE id = 'run-cold'");
        assertThat(store.syncPlanGeneration("run-cold", 0)).as("代际倒退不写").isFalse();
        assertThat(store.syncPlanGeneration("run-cold", 2))
                .as("声明的这一代必须就是 Run 主表上的当前一代")
                .isFalse();
        assertThat(store.syncPlanGeneration("run-cold", 1)).isTrue();
        assertThat(store.find("run-cold").orElseThrow().getPlanGeneration()).isEqualTo(1);
        assertThat(store.deferFor("run-cold", RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 1L))
                .as("父 Run 已经升到第 1 代而记录还停在第 0 代：拿旧代际写的延期不生效")
                .isFalse();

        // 成功推进：延期原因清掉、轮次位置更新，于是它排到最后（这一轮别人先来）。
        assertThat(store.markCoordinationServed("run-cold", 11, 1)).isTrue();
        RunCoordination served = store.find("run-cold").orElseThrow();
        assertThat(served.getDeferReason()).isNull();
        assertThat(served.getCoordinationServedRound()).isEqualTo(11);
        assertThat(store.scanDue(10))
                .extracting(RunCoordination::getRunId)
                .containsExactly("run-warm", "run-hot", "run-cold");
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
                OffsetDateTime.now().plusHours(1), 0, 0L)).isTrue();
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
        assertThat(store.markCoordinationServed("run-competing", 9, 0)).isTrue();
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
        assertThat(store.markDispatchServed("run-competing-node", 24, 0)).isTrue();
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
        assertThat(store.markCoordinationServed("run-fence", 5, 0))
                .as("父 Run 已经升代，旧回合的成功写不生效").isFalse();
        assertThat(store.markDispatchServed("run-fence", 5, 0))
                .as("派发那一组同样按父 Run 的当前代际拦").isFalse();
        assertThat(store.deferFor("run-fence", RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED,
                OffsetDateTime.now().plusMinutes(5), 0, 0L))
                .as("延期也要核父 Run 的当前代际，子记录没同步不算数").isFalse();
        RunCoordination untouched = store.find("run-fence").orElseThrow();
        assertThat(untouched.getCoordinationServedRound()).isZero();
        assertThat(untouched.getDispatchServedRound()).isZero();
        assertThat(untouched.getDeferReason()).isNull();

        // 记录同步到当前代际之后，带着这一代的写就能落地。
        assertThat(store.syncPlanGeneration("run-fence", 1)).isTrue();
        assertThat(store.markCoordinationServed("run-fence", 5, 1)).isTrue();
        assertThat(store.markDispatchServed("run-fence", 6, 1)).isTrue();
        assertThat(store.deferFor("run-fence", RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT,
                OffsetDateTime.now().plusMinutes(5), 1, 5L)).isTrue();
        RunCoordination written = store.find("run-fence").orElseThrow();
        assertThat(written.getCoordinationServedRound()).isEqualTo(5);
        assertThat(written.getDispatchServedRound()).isEqualTo(6);
        assertThat(written.deferReasonEnum())
                .isEqualTo(RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT);
    }

    /**
     * 旧版本的 Run 既不进资格表，也不会占掉扫描条数。
     *
     * <p>资格表是双池执行层的入口：旧版本的 Run 走旧引擎，它不读这张表。要塞进一行旧记录，
     * 那行永远没人接走，却会一直占着一次扫描的名额，排在它后面的双池 Run 连被看见的机会都没有。</p>
     */
    @Test
    void legacyRunsNeitherGetACoordinationRowNorEatTheScanBudget() throws Exception {
        createRunFor("run-legacy-scan", "user-legacy-scan", SchedulerVersion.LEGACY, 0, 0L);
        RunCoordinationStore store = coordinationStore();
        assertThat(store.ensure("run-legacy-scan"))
                .as("旧版本的 Run 建不出资格记录，影响 0 行").isFalse();
        assertThat(store.find("run-legacy-scan")).isEmpty();

        // 绕过创建入口硬塞一行旧记录，模拟历史残留。
        execute("INSERT INTO alphafrog_agent_run_coordination "
                + "(run_id, scheduler_version, plan_generation) VALUES ('run-legacy-scan', 'LEGACY', 0)");
        createRunFor("run-v2-scan", "user-v2-scan", SchedulerVersion.DUAL_POOL_V2, 0, 0L);
        assertThat(store.ensure("run-v2-scan")).isTrue();
        execute("UPDATE alphafrog_agent_run_coordination SET next_visible_at = CURRENT_TIMESTAMP");

        assertThat(store.scanDue(1))
                .as("只给一条名额：拿到的必须是双池那条，旧记录不占坑")
                .extracting(RunCoordination::getRunId)
                .containsExactly("run-v2-scan");
        assertThat(store.scanDue(10))
                .as("多给几条名额也不放旧记录进来")
                .extracting(RunCoordination::getRunId)
                .doesNotContain("run-legacy-scan");
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
            assertThat(first.groupId())
                    .as("消费结果要带回放行的是哪条链：这条数从消费语句里取，取不到就说明语句点错了列")
                    .isEqualTo(fixture.groupId());
            RecoveryConsumptionResult second = store.consumeRecovery(notificationId, "dispatcher-2",
                    fixture.runControlVersion());
            assertThat(second.consumed())
                    .as("同一代际的恢复资格只能被取走一次").isFalse();
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
            RecoveryConsumptionResult result = store.consumeRecovery(secondId, "dispatcher-2",
                    second.runControlVersion());
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
            RecoveryConsumptionResult result = store.consumeRecovery(notificationId, "dispatcher-3",
                    consuming.runControlVersion());
            assertThat(result.consumed()).as("计划代际已经变了，这条恢复资格取不走").isFalse();
            assertThat(result.promoted()).isFalse();
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
            assertThat(store.consumeRecovery(notificationA, "dispatcher-a",
                    movedAhead.runControlVersion()).consumed()).isFalse();
            assertThat(store.consumeRecovery(notificationB, "dispatcher-b",
                    newerGeneration.runControlVersion()).consumed()).isFalse();
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
                new DataSourceTransactionManager(dataSource));
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
            for (String script : List.of(STAGE3_SCRIPT, DISPATCH_PROOF_SCRIPT, CONSUMED_BY_SCRIPT)) {
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
                "mapper/SchedulerStateMapper.xml")) {
            try (InputStream xml = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
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
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertThat(session.getMapper(NodeWorkItemMapper.class).insert(item)).isEqualTo(1);
        }
    }

    /** 读每个逻辑节点的最新分段：复用产品代码里那条查询。 */
    private static List<NodeWorkItem> latestSegments(String runId, int planGeneration) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(NodeWorkItemMapper.class).listLatestSegments(runId, planGeneration);
        }
    }

    private static RunCoordinationStore coordinationStore() {
        SqlSession session = sqlSessionFactory.openSession(true);
        return new MybatisRunCoordinationStore(session.getMapper(RunCoordinationMapper.class));
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
