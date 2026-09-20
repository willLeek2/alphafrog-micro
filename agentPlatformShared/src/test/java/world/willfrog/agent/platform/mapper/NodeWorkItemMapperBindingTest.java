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
            "reportExecutionFailure", "renewLease", "cancel", "markStale");

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
        String migration = readMigration();
        String check = slice(migration, "alphafrog_agent_run_work_item_state_check",
                "scheduler_version_check");
        assertThat(quotedValues(check))
                .as("迁移里的状态约束应与 NodeWorkItemState 的全部取值一致")
                .containsExactlyElementsOf(NodeWorkItemState.allWireValues());
    }

    @Test
    void migrationSchedulerVersionCheckMatchesEnum() {
        String migration = readMigration();
        List<String> expected = List.of(SchedulerVersion.values()).stream().map(Enum::name).toList();
        String workItemCheck = slice(migration, "alphafrog_agent_run_work_item_scheduler_version_check",
                "alphafrog_agent_run_work_item_counter_check");
        assertThat(quotedValues(workItemCheck))
                .as("工作项表的调度器版本约束应与枚举取值一致")
                .containsExactlyElementsOf(expected);
        String runCheck = slice(migration, "alphafrog_agent_run_scheduler_version_check", "CREATE TABLE");
        assertThat(quotedValues(runCheck))
                .as("Run 表的调度器版本约束应与枚举取值一致")
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
                "claimed_by", "lease_expires_at", "next_visible_at", "payload_json");
        assertThat(xml).as("id 列也要映射").contains("<id property=\"id\" column=\"id\"/>");
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
        assertThat(claimSql).as("领取只从可运行状态领").contains("state = 'RUNNABLE'");
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

    @Test
    void submitAndFailureAndRenewCarryClaimEpoch() {
        for (String id : List.of("commitSegmentResult", "reportExecutionFailure", "renewLease")) {
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
