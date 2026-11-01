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
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunOutboxDelivery;
import world.willfrog.agent.platform.childrun.ChildRunReservation;
import world.willfrog.agent.platform.childrun.ChildRunReserveRequest;
import world.willfrog.agent.platform.childrun.MybatisChildRunIntentStore;
import world.willfrog.agent.platform.capacity.SchedulerPauseDecision;
import world.willfrog.agent.platform.capacity.SchedulerStateStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AcceptanceReleasePointMapper;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.service.AgentRunEventProjectionRepair;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.mapper.ChildRunIntentMapper;
import world.willfrog.agent.platform.coordination.MybatisRunCoordinationStore;
import world.willfrog.agent.platform.lease.MybatisRunServiceLeaseStore;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.lease.ServiceOwnershipScope;
import world.willfrog.agent.platform.mapper.RunServiceLeaseMapper;
import world.willfrog.agent.platform.coordination.RunCoordination;
import world.willfrog.agent.platform.coordination.RunCoordinationDeferReason;
import world.willfrog.agent.platform.coordination.RunCoordinationStore;
import world.willfrog.agent.platform.mapper.MigrationStatements;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;
import world.willfrog.agent.platform.mapper.RunCoordinationMapper;
import world.willfrog.agent.platform.mapper.RootTreeBudgetMapper;
import world.willfrog.agent.platform.mapper.SchedulerStateMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;
import world.willfrog.agent.platform.mapper.WaitMemberStopMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.treebudget.MybatisRootTreeBudgetStore;
import world.willfrog.agent.platform.treebudget.RootTreeActivityBudget;
import world.willfrog.agent.platform.treebudget.RootTreeActivityLimitException;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetStore;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunEventRedisStore;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.workitem.MybatisNodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemClaim;
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
import java.util.Optional;
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
    private static final String FIXTURE_CALL_SCRIPT = "016_agent_run_acceptance_fixture_call.sql";
    private static final String FIXTURE_POLICY_SCRIPT = "017_agent_run_acceptance_fixture_policy.sql";
    private static final String CHILD_INTENT_SCRIPT = "018_agent_run_child_intent.sql";
    private static final String TREE_BUDGET_SCRIPT = "019_agent_run_tree_budget.sql";
    private static final String WAIT_MEMBER_STOP_SCRIPT = "020_agent_run_wait_member_stop.sql";
    private static final String TREE_ACTIVITY_SCRIPT = "021_agent_run_tree_activity_gate.sql";
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













    /** Agent 侧那条读语句：走真的映射语句，测试里不另写一份 SQL。 */
    private static int openReleasePoints(String runId, String releaseKey) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(AcceptanceReleasePointMapper.class)
                    .countOpened(runId, releaseKey);
        }
    }













    // ==================== 夹具的调用身份与放行规则 ====================




    /** 认领语句的写法：与服务端 {@code FixtureCallStore} 发出的那条完全一致。 */
    private static String claimStatement(String runId, String callIdentity, int turnIndex) {
        return "INSERT INTO alphafrog_agent_run_acceptance_fixture_call"
                + " (run_id, fixture_id, scenario_id, call_identity, call_stage, turn_index,"
                + " script_digest, declared_for, optional_turn)"
                + " VALUES ('" + runId + "', 'fx-probe', 'scenario-probe', '" + callIdentity + "', 'NODE',"
                + " " + turnIndex + ", '" + "d".repeat(64) + "', '声明的原文', FALSE)"
                + " ON CONFLICT DO NOTHING";
    }

    private static int insertClaim(String runId, String callIdentity, int turnIndex) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            return statement.executeUpdate(claimStatement(runId, callIdentity, turnIndex));
        }
    }

    /** 并发用例里用的那一份：`IntFunction` 不许抛受检异常，这里把失败包成运行时异常。 */
    private static int insertClaimQuietly(String runId, String callIdentity, int turnIndex) {
        try {
            return insertClaim(runId, callIdentity, turnIndex);
        } catch (Exception e) {
            throw new IllegalStateException("并发认领时出错：" + e.getMessage(), e);
        }
    }

    private static long claimRowCount(String runId) throws Exception {
        return countRows("SELECT count(*) FROM alphafrog_agent_run_acceptance_fixture_call"
                + " WHERE run_id = '" + runId + "'");
    }

    /** 这个调用身份当初领到的是哪一个回合。 */
    private static long claimedTurnOf(String runId, String callIdentity) throws Exception {
        return countRows("SELECT turn_index FROM alphafrog_agent_run_acceptance_fixture_call"
                + " WHERE run_id = '" + runId + "' AND call_identity = '" + callIdentity + "'");
    }

    private static void insertScenario(String runId) throws Exception {
        execute("INSERT INTO alphafrog_agent_run_acceptance_fixture_scenario"
                + " (run_id, fixture_id, scenario_id, script_digest, script_size, declarations_json,"
                + " required_turn_count, optional_turn_count)"
                + " VALUES ('" + runId + "', 'fx-probe', 'scenario-probe', '" + "e".repeat(64) + "', 1,"
                + " '[{\"turn\":0,\"stage\":\"answer\",\"optional\":false,\"scope\":{}}]'::jsonb, 1, 0)");
    }

    private static int insertPolicy(String runId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            return statement.executeUpdate("INSERT INTO alphafrog_agent_run_acceptance_fixture_policy"
                    + " (run_id, fixture_id, scenario_id, policy_digest, rules_json, rule_count)"
                    + " VALUES ('" + runId + "', 'fx-probe', 'scenario-probe', '" + "f".repeat(64) + "',"
                    + " '[{\"index\":0,\"action\":\"holdUntilPoint\",\"selector\":\"memberSeq=0;nodeId=n1\"}]'::jsonb,"
                    + " 2) ON CONFLICT DO NOTHING");
        }
    }

    private static int insertRuleHit(String runId, int ruleIndex, int memberSeq) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            return statement.executeUpdate(insertRuleHitSql(runId, ruleIndex, memberSeq)
                    + " ON CONFLICT DO NOTHING");
        }
    }

    /** 命中行的写入语句：与服务端 {@code FixtureRuleHitStore.recordMatch} 发出的那条列一致。 */
    private static String insertRuleHitSql(String runId, int ruleIndex, int memberSeq) {
        return "INSERT INTO alphafrog_agent_run_acceptance_fixture_rule_hit"
                + " (run_id, rule_index, selector_text, action, group_id,"
                + " plan_generation, node_id, node_attempt, segment_sequence, model_turn,"
                + " member_seq, tool_call_id)"
                + " VALUES ('" + runId + "', " + ruleIndex + ", 'memberSeq=0;nodeId=n1',"
                + " 'holdUntilPoint', 9001, 7, 'n1', 1, 0, 0, " + memberSeq + ", 'call-" + memberSeq + "')";
    }

    private static String queryString(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    // ==================== 并发用例 ====================






    // ==================== 恢复资格的真实主键 ====================


    // ==================== 计划代际的栅栏 ====================



    // ==================== 服务所有权与关闭态 ====================





    // ==================== 恢复通知的消费方 ====================


    // ==================== Run 创建与接收事实 ====================







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



    /** 直接插一条接收事实：这条用例只关心扫描窗口，不关心创建那条路。 */
    private static void insertReceivedFact(String runId, int seq) throws Exception {
        execute("INSERT INTO alphafrog_agent_run_event (run_id, seq, event_type, payload_json) "
                + "VALUES ('" + runId + "', " + seq + ", 'RUN_RECEIVED', '{}'::jsonb)");
    }

    // ==================== Run 的服务所有权租约 ====================







    private static ServiceOwnershipScope testScope() {
        return new ServiceOwnershipScope("stable", "gen-" + "a".repeat(64), null);
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

        RunServiceLeaseStore leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        ProcessInstanceIdentity instanceIdentity = Mockito.mock(ProcessInstanceIdentity.class);
        Mockito.lenient().when(instanceIdentity.value()).thenReturn("test-instance");
        Mockito.lenient().when(leaseStore.acquire(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> Optional.of(new RunServiceLease(
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, String.class),
                        1L,
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusMinutes(2))));
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
                coordinationStore(),
                leaseStore,
                instanceIdentity);
        // 这些值平时由配置注入，直接 new 出来是 0/空；显式给上，免得量的是个退化配置。
        ReflectionTestUtils.setField(service, "ttlMinutes", 60);
        ReflectionTestUtils.setField(service, "checkpointVersion", "v2");
        ReflectionTestUtils.setField(service, "payloadMaxChars", 10000);
        ReflectionTestUtils.setField(service, "payloadPreviewChars", 4096);
        ReflectionTestUtils.setField(service, "serviceLeaseTtlSeconds", 120L);
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


    // ==================== 服务所有权闸门 ====================






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

    /** Run 行自己的计划代际：启动时与分段行逐条比对的就是它。 */
    private static int planGenerationOf(String runId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT plan_generation FROM alphafrog_agent_run WHERE id = '" + runId + "'")) {
            assertThat(rows.next()).as("这条 Run 读得回来：" + runId).isTrue();
            return rows.getInt(1);
        }
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
                    RECOVERY_CLOSE_SCRIPT, ACCEPTANCE_FIXTURE_SCRIPT, RELEASE_POINT_SCRIPT,
                    FIXTURE_CALL_SCRIPT, FIXTURE_POLICY_SCRIPT, CHILD_INTENT_SCRIPT,
                    TREE_BUDGET_SCRIPT, WAIT_MEMBER_STOP_SCRIPT, TREE_ACTIVITY_SCRIPT)) {
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
                "mapper/SchedulerStateMapper.xml",
                "mapper/AcceptanceReleasePointMapper.xml",
                "mapper/ChildRunIntentMapper.xml", "mapper/WaitMemberStopMapper.xml",
                "mapper/RootTreeBudgetMapper.xml")) {
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
     * 按给定版本造一条分段，返回语句实际影响的行数。
     *
     * <p>0 行有两种成因：所有权条件挡下（凭据被换掉或租约过期），父 Run 版本条件挡下（计划代际、
     * 控制版本或状态已经往前走了）。用例按「哪一项写错」分别造，读回来的行数就是答案。</p>
     */
    private static int insertSegmentRows(String runId, ServiceOwnershipFence fence, int planGeneration,
                                         long runControlVersion, String nodeId) {
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(runId);
        item.setPlanGeneration(planGeneration);
        item.setNodeId(nodeId);
        item.setNodeAttempt(0);
        item.setSegmentSequence(0);
        item.setState("RUNNABLE");
        item.setContextVersion(0L);
        item.setRunControlVersion(runControlVersion);
        item.setClaimEpoch(0);
        item.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        item.setNextVisibleAt(java.time.OffsetDateTime.now().minusSeconds(1));
        item.setPayloadJson("{}");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(NodeWorkItemMapper.class)
                    .insert(item, fence.ownerInstanceId(), fence.fencingToken());
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
                    schedulerVersion, 1L, 0L, 0, "stage3-claimer",
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
        // 016/017 那几张夹具表：认领、场景结论、策略快照与命中记录，都只加结构不写数据。
        for (String table : List.of("alphafrog_agent_run_acceptance_fixture_call",
                "alphafrog_agent_run_acceptance_fixture_scenario",
                "alphafrog_agent_run_acceptance_fixture_policy",
                "alphafrog_agent_run_acceptance_fixture_rule_hit")) {
            assertThat(countRows("SELECT count(*) FROM " + table))
                    .as("刚升级完的表里不该有数据：" + table).isZero();
        }
        // 一组唯一约束撑起「谁拿了哪个回复」这件事：一次调用一行、一个回合一行、一条规则打中一条成员一行、
        // 每条 Run 的场景与策略各一份。少任何一条，两个进程同时跑同一条 Run 时就会出现重复消费或重复计账。
        assertThat(countRows("SELECT count(*) FROM pg_constraint WHERE connamespace = current_schema()::regnamespace"
                + " AND conname IN ("
                + "'alphafrog_agent_run_acceptance_fixture_call_identity_key', "
                + "'alphafrog_agent_run_acceptance_fixture_call_turn_key', "
                + "'alphafrog_agent_run_acceptance_fixture_scenario_run_key', "
                + "'alphafrog_agent_run_acceptance_fixture_policy_run_key', "
                + "'alphafrog_agent_run_acceptance_fixture_rule_hit_key')"))
                .as("夹具的认领、场景、策略与命中记录都要有各自的唯一约束").isEqualTo(5);
        for (String table : List.of("alphafrog_agent_run_acceptance_fixture_scenario",
                "alphafrog_agent_run_acceptance_fixture_policy")) {
            String column = "alphafrog_agent_run_acceptance_fixture_scenario".equals(table)
                    ? "declarations_json" : "rules_json";
            assertThat(countRows("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = current_schema() AND table_name = '" + table + "'"
                    + " AND column_name = '" + column + "' AND data_type = 'jsonb'"))
                    .as("快照列要是 JSONB：" + table + "." + column).isEqualTo(1);
        }
        assertThat(countRows("SELECT count(*) FROM pg_constraint WHERE connamespace = current_schema()::regnamespace"
                + " AND conname = 'alphafrog_agent_run_acceptance_fixture_scenario_verdict_check'"))
                .as("结论列要有取值约束：以后加结论取值时旧代码写出来的行不会悄悄混进证据").isEqualTo(1);
        // 命中记录这一张表分两个时刻写：打中了哪条成员、动作有没有真的落到它身上。
        assertThat(countRows("SELECT count(*) FROM pg_constraint WHERE connamespace = current_schema()::regnamespace"
                + " AND conname IN ("
                + "'alphafrog_agent_run_acceptance_fixture_rule_hit_outcome_check', "
                + "'alphafrog_agent_run_acceptance_fixture_rule_hit_settled_check')"))
                .as("动作结果要有取值约束，且结果与时刻必须成对").isEqualTo(2);
        assertThat(countRows("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = current_schema()"
                + " AND table_name = 'alphafrog_agent_run_acceptance_fixture_scenario'"
                + " AND column_name = 'policy_unapplied_count' AND data_type = 'integer'"))
                .as("结论里要有「打中了、动作没生效」的规则条数").isEqualTo(1);
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

    private static AnnotationConfigApplicationContext childStoreContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("probeDataSource", DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(ChildRunIntentMapper.class,
                () -> new SqlSessionTemplate(sqlSessionFactory).getMapper(ChildRunIntentMapper.class));
        context.registerBean(MybatisChildRunIntentStore.class);
        context.register(TransactionManagementEnablement.class);
        context.refresh();
        return context;
    }

    private static AnnotationConfigApplicationContext stopStoreContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("probeDataSource", DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(WaitMemberStopMapper.class,
                () -> new SqlSessionTemplate(sqlSessionFactory).getMapper(WaitMemberStopMapper.class));
        context.registerBean(MybatisWaitMemberStopStore.class);
        context.register(TransactionManagementEnablement.class);
        context.refresh();
        return context;
    }

    private static long suspendExternalGroup(String runId) {
        WaitGroupStore store = new MybatisWaitGroupStore(
                new SqlSessionTemplate(sqlSessionFactory).getMapper(WaitGroupMapper.class));
        WaitSuspensionResult result = store.suspendSegment(new WaitSuspensionRequest(
                new NodeWorkItemIdentity(runId, 0, "node-1", 0, 0),
                new NodeWorkItemVersions(2L, 0L, 3), "worker-1", 0,
                SchedulerVersion.DUAL_POOL_V2,
                List.of(new WaitMemberDraft(0, "call-a", "executePython", null)),
                "{\"waitSuspension\":true}", "{\"checkpoint\":\"external\"}"));
        assertThat(result.suspended()).isTrue();
        return result.groupId();
    }

    private static long suspendChildGroup(String runId, List<String> toolCallIds) {
        List<WaitMemberDraft> members = new ArrayList<>();
        for (int index = 0; index < toolCallIds.size(); index++) {
            members.add(new WaitMemberDraft(index, toolCallIds.get(index), "spawnSubAgent", null));
        }
        WaitGroupStore store = new MybatisWaitGroupStore(
                new SqlSessionTemplate(sqlSessionFactory).getMapper(WaitGroupMapper.class));
        WaitSuspensionResult result = store.suspendSegment(new WaitSuspensionRequest(
                new NodeWorkItemIdentity(runId, 0, "node-1", 0, 0),
                new NodeWorkItemVersions(2L, 0L, 3), "worker-1", 0,
                SchedulerVersion.DUAL_POOL_V2, members,
                "{\"waitSuspension\":true}", "{\"checkpoint\":\"child\"}"));
        assertThat(result.suspended()).isTrue();
        return result.groupId();
    }

    private static ChildRunReserveRequest childRequest(String runId, long groupId, String toolCallId) {
        return new ChildRunReserveRequest(runId, runId, groupId, toolCallId, "node-1",
                toolCallId, 0, 0, 0L, "分析目标", "上下文", "model-a", "endpoint-a", 6, "sha256:test");
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
