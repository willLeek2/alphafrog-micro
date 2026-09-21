package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.workitem.NodeDispatchDeferReason;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemState;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NodeWorkItemMapper.xml 的 host-only 合同验证：不依赖 PostgreSQL，只验证 MyBatis 能解析、参数绑定对得上、
 * 关键 SQL 形状与 Java 侧取值一致。
 *
 * <p>这里盯的是三类容易漂移的地方：状态取值在 Java 枚举、XML 字面量与迁移脚本三处必须一致；五个身份字段
 * 必须出现在每一条状态迁移的条件里；领取与显式转交必须是 {@code UPDATE ... RETURNING} 包在 SELECT 里返回新代际。</p>
 *
 * <p>迁移脚本按文件名在 upgrades 目录树里找，不写死版本目录；找不到就报错，不跳过。</p>
 */
class NodeWorkItemMapperBindingTest {

    private static final String NAMESPACE = NodeWorkItemMapper.class.getName();
    private static final String IDENTITY_UNIQUE_COLUMNS =
            "(run_id, plan_generation, node_id, node_attempt, segment_sequence)";
    private static final List<String> IDENTITY_PARAMS =
            List.of("runId", "planGeneration", "nodeId", "nodeAttempt", "segmentSequence");
    private static final List<String> TRANSITION_STATEMENTS = List.of(
            "claim", "handOverClaim", "startExecution", "commitSegmentResult",
            "suspendForToolJob", "promoteToolJobResumable", "commitResumedToolJobResult",
            "requeueInterruptedToolJob", "requeueAbandonedClaim", "reportExecutionFailure",
            "renewLease", "cancel", "markStale");

    private Configuration configuration;
    private String xml;
    private Set<String> xmlStatements;
    private Map<String, Set<String>> methodParams;

    @BeforeEach
    void setUp() throws Exception {
        xml = readXml();
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(NodeWorkItemMapper.class);
        new XMLMapperBuilder(new StringReader(xml), configuration, "NodeWorkItemMapper.xml",
                configuration.getSqlFragments()).parse();

        xmlStatements = new HashSet<>();
        for (MappedStatement ms : configuration.getMappedStatements()) {
            xmlStatements.add(ms.getId().substring(ms.getId().lastIndexOf('.') + 1));
        }

        methodParams = new HashMap<>();
        for (Method method : NodeWorkItemMapper.class.getDeclaredMethods()) {
            Set<String> params = new HashSet<>();
            for (Parameter parameter : method.getParameters()) {
                Param annotation = parameter.getAnnotation(Param.class);
                if (annotation != null) {
                    params.add(annotation.value());
                }
            }
            methodParams.put(method.getName(), params);
        }
    }

    // ===== 文件与解析 =====

    private static String readXml() throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"),
                "src/main/resources/mapper/NodeWorkItemMapper.xml");
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        path = Paths.get(System.getProperty("user.dir"),
                "agentPlatformShared/src/main/resources/mapper/NodeWorkItemMapper.xml");
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        return new String(Resources.getResourceAsStream("mapper/NodeWorkItemMapper.xml").readAllBytes());
    }

    /**
     * 读那份建工作项表的迁移脚本。
     *
     * <p>按「在 upgrades 目录下找到任何一份以 _agent_run_work_item.sql 结尾的脚本」来找，不写死是哪个版本目录；
     * 找不到就报错而不是跳过——脚本不在了本身就说明合同漂了。</p>
     */
    private static String readMigration() {
        List<Path> upgradesDirs = new ArrayList<>();
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path base = userDir; base != null; base = base.getParent()) {
            Path candidate = base.resolve("migrate/migrations/upgrades");
            if (Files.isDirectory(candidate)) {
                upgradesDirs.add(candidate);
            }
        }
        for (Path upgrades : upgradesDirs) {
            try (var stream = Files.walk(upgrades, 2)) {
                Optional<Path> found = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith("_agent_run_work_item.sql"))
                        .findFirst();
                if (found.isPresent()) {
                    return Files.readString(found.get());
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("遍历迁移目录失败：" + upgrades, e);
            }
        }
        throw new IllegalStateException(
                "在 " + upgradesDirs + " 下找不到 *_agent_run_work_item.sql：建表迁移必须跟着代码一起在仓库里");
    }

    /**
     * 读出「最后一份定义了某个约束的迁移脚本」。
     *
     * <p>约束会被后续迁移重写（宽化取值、补列），只比对第一份脚本会漏掉漂移；具体排序与查找规则
     * 在 {@link MigrationScripts} 里，迁移相关测试共用一份。</p>
     */
    private static String readLastMigrationContaining(String marker) {
        return MigrationScripts.lastContaining(marker);
    }

    @Test
    void interfaceAndXmlStatementsMatch() {
        List<String> missing = new ArrayList<>();
        for (String method : methodParams.keySet()) {
            if (!xmlStatements.contains(method)) {
                missing.add(method);
            }
        }
        assertThat(missing).as("接口方法缺少 XML statement").isEmpty();

        List<String> orphans = new ArrayList<>();
        for (String statement : xmlStatements) {
            if (!methodParams.containsKey(statement)) {
                orphans.add(statement);
            }
        }
        assertThat(orphans).as("XML statement 无对应接口方法").isEmpty();
    }

    // ===== 状态取值三处一致 =====

    @Test
    void terminalStatesLiteralMatchesEnum() {
        String literal = extractSqlFragment("terminalStates");
        List<String> fromXml = quotedValues(literal);
        assertThat(fromXml)
                .as("terminalStates 字面量应与 NodeWorkItemState 的终态逐项一致")
                .containsExactlyElementsOf(NodeWorkItemState.terminalWireValues());
    }

    @Test
    void migrationStateCheckCoversExactlyAllWireValues() {
        String migration = readLastMigrationContaining("alphafrog_agent_run_work_item_state_check");
        String check = slice(migration, "alphafrog_agent_run_work_item_state_check",
                "scheduler_version_check");
        assertThat(quotedValues(check))
                .as("迁移里的状态约束应与 NodeWorkItemState 的全部取值一致")
                .containsExactlyElementsOf(NodeWorkItemState.allWireValues());
    }

    @Test
    void migrationRunnableSinceColumnIsAddedAndBackfilled() {
        String migration = readLastMigrationContaining("runnable_since");
        assertThat(migration).as("加列要幂等").contains("ADD COLUMN IF NOT EXISTS runnable_since");
        assertThat(migration).as("存量行要回填，退避中的行取当前时间，别回填出将来的起点")
                .contains("SET runnable_since = LEAST(next_visible_at, CURRENT_TIMESTAMP)");
        assertThat(migration).as("存量值是近似值，注释里要说清楚，免得拿它当精确排队起点")
                .contains("近似值");
        assertThat(migration).as("加完要收紧成非空").contains("ALTER COLUMN runnable_since SET NOT NULL");
    }

    @Test
    void migrationDispatchDeferReasonColumnMatchesEnum() {
        String migration = readLastMigrationContaining("dispatch_defer_reason");
        assertThat(migration).as("加列要幂等")
                .contains("ADD COLUMN IF NOT EXISTS dispatch_defer_reason");
        assertThat(MigrationScripts.constraintValues(migration,
                "alphafrog_agent_run_work_item_dispatch_defer_reason_check"))
                .as("节点派发延期原因应与 NodeDispatchDeferReason 逐项一致")
                .containsExactlyElementsOf(NodeDispatchDeferReason.allWireValues());
    }

    @Test
    void migrationSchedulerVersionCheckMatchesEnum() {
        List<String> expected = List.of(SchedulerVersion.values()).stream().map(Enum::name).toList();
        String workItemScript =
                readLastMigrationContaining("alphafrog_agent_run_work_item_scheduler_version_check");
        assertThat(MigrationScripts.constraintValues(workItemScript,
                "alphafrog_agent_run_work_item_scheduler_version_check"))
                .as("工作项表的调度器版本约束应与枚举取值一致（含后续迁移的宽化）")
                .containsExactlyElementsOf(expected);
        String runScript = readLastMigrationContaining("alphafrog_agent_run_scheduler_version_check");
        assertThat(MigrationScripts.constraintValues(runScript,
                "alphafrog_agent_run_scheduler_version_check"))
                .as("Run 表的调度器版本约束应与枚举取值一致（含后续迁移的宽化）")
                .containsExactlyElementsOf(expected);
    }

    @Test
    void migrationIdentityUniqueConstraintCoversFiveFields() {
        String migration = readMigration();
        assertThat(migration.replaceAll("\\s+", " "))
                .as("五个身份字段一组必须有唯一约束，条件更新替代不了它")
                .contains("UNIQUE " + IDENTITY_UNIQUE_COLUMNS);
    }

    // ===== 迁移与代码里的列名一致 =====

    @Test
    void resultMapCoversAllColumnsNamedInJava() {
        Set<String> mappedColumns = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("<result property=\"(\\w+)\" column=\"(\\w+)\"/>").matcher(xml);
        while (matcher.find()) {
            mappedColumns.add(matcher.group(2));
        }
        assertThat(mappedColumns).as("结果映射应覆盖这些列").contains(
                "run_id", "plan_generation", "node_id", "node_attempt", "segment_sequence",
                "state", "context_version", "run_control_version", "claim_epoch", "scheduler_version",
                "claimed_by", "lease_expires_at", "next_visible_at", "runnable_since",
                "dispatch_defer_reason", "payload_json");
        assertThat(xml).as("id 列也要映射").contains("<id property=\"id\" column=\"id\"/>");
    }

    // ===== 派发成功与失败的留痕 =====

    @Test
    void dispatchDeferAndSuccessWriteTheSameColumnInOppositeDirections() {
        String defer = sql("deferDispatch");
        assertThat(defer).as("派发失败写下原因并推后可见时间")
                .contains("dispatch_defer_reason = ?")
                .contains("next_visible_at = ?");
        assertThat(defer).as("只有可派发的两种状态会被这条路碰到")
                .contains("state IN ('RUNNABLE', 'RESUMABLE')");
        assertThat(defer).as("这条不做状态迁移，别把状态一起改了").doesNotContain("state = '");
        String dispatched = sql("markDispatched");
        assertThat(dispatched).as("派发成功清掉上一次的原因")
                .contains("dispatch_defer_reason = NULL");
        assertThat(dispatched).as("两个方向的条件要对称，否则成功清不掉失败写下的值")
                .contains("state IN ('RUNNABLE', 'RESUMABLE')");
        assertThat(dispatched).as("清原因不改可见时间").doesNotContain("next_visible_at =");
    }

    @Test
    void claimingAnItemClearsTheStaleDispatchFailure() {
        for (String id : List.of("claim", "handOverClaim")) {
            assertThat(sql(id)).as(id + " 成功之后这条工作项已经有人接手，上一次派发失败的原因不再成立")
                    .contains("dispatch_defer_reason = NULL");
        }
    }

    // ===== 领取与转交的 SQL 形状 =====

    @Test
    void claimAndHandOverReturnNewEpochInsideSelect() {
        for (String id : List.of("claim", "handOverClaim")) {
            MappedStatement ms = configuration.getMappedStatement(NAMESPACE + "." + id);
            assertThat(ms.getSqlCommandType())
                    .as(id + " 应为 SELECT（包着 UPDATE ... RETURNING）").isEqualTo(SqlCommandType.SELECT);
            assertThat(ms.isFlushCacheRequired()).as(id + " 应有 flushCache=true").isTrue();
            assertThat(ms.getResultMaps().get(0).getType())
                    .as(id + " 应返回新的领取代际").isEqualTo(Integer.class);
            String sql = normalized(ms.getSqlSource().getBoundSql(dummyParams(id)).getSql());
            assertThat(sql).as(id + " 必须 RETURNING claim_epoch").contains("RETURNING claim_epoch");
            assertThat(sql).as(id + " 领取时把代际加一").contains("claim_epoch = claim_epoch + 1");
        }
        String claimSql = normalized(configuration.getMappedStatement(NAMESPACE + ".claim")
                .getSqlSource().getBoundSql(dummyParams("claim")).getSql());
        String claimable = String.join(", ",
                NodeWorkItemState.claimableWireValues().stream().map(v -> "'" + v + "'").toList());
        assertThat(claimSql).as("领取只从首次可运行与结果齐备可恢复两个状态领")
                .contains("state IN (" + claimable + ")");
        assertThat(claimSql).as("领取要等到下次可领取时间").contains("next_visible_at <= CURRENT_TIMESTAMP");
        assertThat(claimSql).as("领取要按调度器版本过滤").contains("scheduler_version = ?");
        String handOverSql = normalized(configuration.getMappedStatement(NAMESPACE + ".handOverClaim")
                .getSqlSource().getBoundSql(dummyParams("handOverClaim")).getSql());
        assertThat(handOverSql).as("只有已领取或执行中能被显式转交")
                .contains("state IN ('CLAIMED', 'EXECUTING')");
        assertThat(handOverSql).as("显式转交不看租约，只看期望代际")
                .contains("claim_epoch = ?").doesNotContain("lease_expires_at <=");
    }

    @Test
    void everyTransitionCarriesTheFullIdentityFence() {
        List<String> problems = new ArrayList<>();
        for (String id : TRANSITION_STATEMENTS) {
            MappedStatement ms = configuration.getMappedStatement(NAMESPACE + "." + id);
            Set<String> used = boundParamNames(ms, dummyParams(id));
            for (String identity : IDENTITY_PARAMS) {
                if (!used.contains(identity)) {
                    problems.add(id + " 缺少身份字段 " + identity);
                }
            }
        }
        assertThat(problems).as("每条状态迁移都要带齐五个身份字段").isEmpty();
    }

    /**
     * 启动恢复把死在领取态的分段放回可领取：只动领取态的行，放回时代际加一、清掉领取者与租约。
     *
     * <p>代际加一是这道语句的全部价值：旧执行者万一还活着，提交结果时会因为代际对不上被拒。</p>
     */
    @Test
    void abandonedClaimRequeueOnlyTouchesClaimedStatesAndMovesEpochForward() {
        String requeue = sql("requeueAbandonedClaim");
        assertThat(requeue)
                .as("只有已领取或执行中的行会被放回")
                .contains("state IN ('CLAIMED', 'EXECUTING')")
                .as("放回成可运行")
                .contains("state = 'RUNNABLE'")
                .as("代际加一，旧领取者的提交会被拒")
                .contains("claim_epoch = claim_epoch + 1")
                .as("领取者与领取租约都要清掉")
                .contains("claimed_by = NULL")
                .contains("lease_expires_at = NULL")
                .as("放回之后立刻可领取")
                .contains("next_visible_at = CURRENT_TIMESTAMP")
                .as("期望代际、上下文版本、控制版本三样都要对上")
                .contains("claim_epoch = ?")
                .contains("context_version = ?")
                .contains("run_control_version = ?");
        Set<String> used = boundParamNames(configuration.getMappedStatement(NAMESPACE + ".requeueAbandonedClaim"),
                dummyParams("requeueAbandonedClaim"));
        assertThat(used).as("放回带期望代际做条件").contains("claimEpoch");
    }

    @Test
    void submitAndFailureAndRenewCarryClaimEpoch() {
        for (String id : List.of("commitSegmentResult", "reportExecutionFailure", "renewLease",
                "requeueAbandonedClaim")) {
            Set<String> used = boundParamNames(configuration.getMappedStatement(NAMESPACE + "." + id),
                    dummyParams(id));
            assertThat(used).as(id + " 必须带领取代际做条件").contains("claimEpoch");
        }
        Set<String> submit = boundParamNames(
                configuration.getMappedStatement(NAMESPACE + ".commitSegmentResult"),
                dummyParams("commitSegmentResult"));
        assertThat(submit).as("提交分段结果要带上下文版本与控制版本").contains("contextVersion", "runControlVersion");
        Set<String> cancel = boundParamNames(configuration.getMappedStatement(NAMESPACE + ".cancel"),
                dummyParams("cancel"));
        assertThat(cancel).as("控制取消带控制版本与领取代际").contains("runControlVersion", "claimEpoch");
        Set<String> stale = boundParamNames(configuration.getMappedStatement(NAMESPACE + ".markStale"),
                dummyParams("markStale"));
        assertThat(stale).as("标过期带上下文版本与控制版本").contains("contextVersion", "runControlVersion");
    }

    @Test
    void longToolTransitionsFenceRunAnchorAndClaimEpoch() {
        String suspend = sql("suspendForToolJob");
        assertThat(suspend)
                .contains("owner_run.status = 'WAITING_TOOL_JOB'")
                .contains("'{workItemClaimEpoch}'")
                .contains("wi.claim_epoch = ?");

        String promote = sql("promoteToolJobResumable");
        assertThat(promote)
                .contains("WITH run_locked AS")
                .contains("item_locked AS")
                .contains("FOR UPDATE")
                .contains("run_advanced AS")
                .contains("status = 'EXECUTING'")
                .contains("state = 'RESUMABLE'")
                .contains("EXISTS (SELECT 1 FROM run_advanced)");
        assertThat(promote.indexOf("run_locked AS"))
                .as("推进与提交必须统一为先锁 Run、再锁工作项")
                .isLessThan(promote.indexOf("item_locked AS"));

        String commit = sql("commitResumedToolJobResult");
        assertThat(commit)
                .contains("WITH run_locked AS")
                .contains("FOR UPDATE")
                .contains("item_committed AS")
                .contains("state = 'RESULT_COMMITTED'")
                .contains("tool_job_anchor_json = '{}'::jsonb")
                .contains("'{resumeState}' = 'DUAL_POOL_READY'")
                .contains("'{workItemClaimEpoch}')::int < ?");

        String requeue = sql("requeueInterruptedToolJob");
        assertThat(requeue)
                .contains("state IN ('CLAIMED', 'EXECUTING')")
                .contains("state = 'RESUMABLE'")
                .contains("'{workItemClaimEpoch}')::int < ?");
    }

    private String sql(String id) {
        return normalized(configuration.getMappedStatement(NAMESPACE + "." + id)
                .getSqlSource().getBoundSql(dummyParams(id)).getSql());
    }

    @Test
    void unfinishedQueriesExcludeTerminalStatesOnly() {
        String terminal = String.join(", ",
                NodeWorkItemState.terminalWireValues().stream().map(v -> "'" + v + "'").toList());
        for (String id : List.of("listUnfinishedByRun", "listUnfinishedBySchedulerVersion",
                "countUnfinished", "countUnfinishedByRun", "countUnfinishedBySchedulerVersion")) {
            String sql = normalized(configuration.getMappedStatement(NAMESPACE + "." + id)
                    .getSqlSource().getBoundSql(dummyParams(id)).getSql());
            assertThat(sql).as(id + " 未完成的范围就是「不在终态里」")
                    .contains("state NOT IN (" + terminal + ")");
        }
    }

    /**
     * 节点派发的那一次全局扫描：新旧双池版本的到期分段放在一份候选里，一次取回；
     * 顺序里排第一位的是这张图最近被派发的轮次，从没被派发过的排最前。
     */
    @Test
    void globalDispatchScanIsOneCandidateSetOrderedByDispatchRotation() {
        String sql = normalized(configuration.getMappedStatement(NAMESPACE + ".scanClaimableAcrossDualPool")
                .getSqlSource().getBoundSql(dummyParams("scanClaimableAcrossDualPool")).getSql());
        assertThat(sql).as("双池家族的版本列在同一份候选里，不是每种版本各扫一次")
                .contains("scheduler_version IN ('DUAL_POOL_V1', 'DUAL_POOL_V2')")
                .doesNotContain("scheduler_version = ?");
        assertThat(sql).as("只取到期可领取的分段")
                .contains("state IN ('RUNNABLE', 'RESUMABLE')")
                .contains("next_visible_at <= CURRENT_TIMESTAMP");
        assertThat(sql).as("轮转位置排第一位：没被派发过的按 0 算，排最前")
                .contains("ORDER BY COALESCE((SELECT c.dispatch_served_round");
        assertThat(sql).as("还没有协调资格记录的图按 0 处理，不因此被跳过")
                .contains("), 0)");
    }

    // ===== 参数绑定 =====

    @Test
    void xmlBindParamsMatchJavaParamsOrEntityProperties() {
        List<String> problems = new ArrayList<>();
        Set<String> entityProperties = entityProperties();
        for (String id : xmlStatements) {
            Set<String> javaParams = methodParams.getOrDefault(id, Set.of());
            MappedStatement ms = configuration.getMappedStatement(NAMESPACE + "." + id);
            Set<String> used = boundParamNames(ms, dummyParams(id));
            for (String property : used) {
                boolean known = javaParams.contains(property) || entityProperties.contains(property);
                if (!known) {
                    problems.add(id + " 用的参数 '" + property + "' 既不是 @Param 也不是实体属性");
                }
            }
        }
        assertThat(problems).as("XML 里的 #{} 必须有出处").isEmpty();
    }

    @Test
    void everyNonEntityParameterIsAnnotatedWithParam() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Method method : NodeWorkItemMapper.class.getDeclaredMethods()) {
            if (method.getName().equals("insert")) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getAnnotation(Param.class) == null) {
                    problems.add(method.getName() + " 的参数 " + parameter.getName() + " 没有 @Param");
                }
            }
        }
        assertThat(problems).as("除了接收实体的创建方法，其余参数都要有 @Param").isEmpty();
    }

    // ===== 工具 =====

    private Set<String> entityProperties() {
        Set<String> properties = new HashSet<>();
        for (Method method : NodeWorkItem.class.getDeclaredMethods()) {
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                properties.add(Character.toLowerCase(name.charAt(3)) + name.substring(4));
            }
        }
        return properties;
    }

    private Set<String> boundParamNames(MappedStatement ms, Object parameter) {
        BoundSql boundSql = ms.getSqlSource().getBoundSql(parameter);
        Set<String> names = new LinkedHashSet<>();
        for (ParameterMapping mapping : boundSql.getParameterMappings()) {
            String property = mapping.getProperty();
            names.add(property.contains(".") ? property.substring(0, property.indexOf('.')) : property);
        }
        return names;
    }

    private Map<String, Object> dummyParams(String id) {
        Map<String, Object> params = new HashMap<>();
        for (String name : methodParams.getOrDefault(id, Set.of())) {
            params.put(name, dummyValue(name));
        }
        return params;
    }

    private static Object dummyValue(String name) {
        return switch (name) {
            case "runId" -> "run-1";
            case "nodeId" -> "node-1";
            case "claimedBy", "newOwner" -> "worker-1";
            case "schedulerVersion" -> SchedulerVersion.DUAL_POOL_V1.name();
            case "payloadPatchJson", "payloadJson" -> "{}";
            case "reason" -> "test";
            case "limit" -> 10;
            case "contextVersion", "runControlVersion" -> 1L;
            default -> {
                if (name.startsWith("planGeneration") || name.startsWith("nodeAttempt")
                        || name.startsWith("segmentSequence") || name.startsWith("claimEpoch")
                        || name.startsWith("expectedClaimEpoch") || name.startsWith("nodeVersion")) {
                    yield 0;
                }
                if (name.endsWith("At")) {
                    yield java.time.OffsetDateTime.now();
                }
                yield null;
            }
        };
    }

    private String extractSqlFragment(String fragmentId) {
        Matcher matcher = Pattern.compile(
                "<sql id=\"" + fragmentId + "\">(.*?)</sql>", Pattern.DOTALL).matcher(xml);
        assertThat(matcher.find()).as("应存在 <sql id=\"" + fragmentId + "\">").isTrue();
        return matcher.group(1);
    }

    /** 取 [from, to) 之间的片段，用来把迁移脚本里的某个约束单独摘出来。 */
    private static String slice(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as("应找到：" + from).isGreaterThanOrEqualTo(0);
        int end = text.indexOf(to, start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    private static List<String> quotedValues(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("'([A-Z0-9_]+)'").matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")")
                .trim();
    }
}
