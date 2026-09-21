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
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.RecoveryNotificationState;
import world.willfrog.agent.platform.wait.WaitGroupState;
import world.willfrog.agent.platform.wait.WaitMemberState;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WaitGroupMapper.xml 的 host-only 合同验证：不连 PostgreSQL，只验证 MyBatis 能解析、参数绑定对得上、
 * 写入语句的条件形状与 Java 侧取值一致。
 *
 * <p>这里盯四件事：三条写入路径都必须先锁 Run 再动子行（与其他长工具语句同一顺序）；成员结束、组齐备、
 * 恢复消费三处的条件必须真的能拦住第二次写入；SQL 里出现的状态字面量必须都在 Java 枚举里（写错一个字母
 * 就会让条件永远不成立）；接口方法与 XML 语句一一对应。</p>
 */
class WaitGroupMapperBindingTest {

    private static final String NAMESPACE = WaitGroupMapper.class.getName();
    private static final String XML_NAME = "WaitGroupMapper.xml";

    /** 每个写入语句的名字，与「先锁 Run 再动子行」的检查对应。 */
    private static final List<String> WRITE_STATEMENTS = List.of(
            "suspendSegment", "completeMember", "reportLateMember", "consumeRecovery", "cancelChain");

    private Configuration configuration;
    private String xml;
    private Set<String> xmlStatements;
    private Map<String, Set<String>> methodParams;
    private Set<String> foreachItems;

    @BeforeEach
    void setUp() throws Exception {
        xml = readXml();
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(WaitGroupMapper.class);
        new XMLMapperBuilder(new StringReader(xml), configuration, XML_NAME,
                configuration.getSqlFragments()).parse();

        xmlStatements = new HashSet<>();
        for (MappedStatement ms : configuration.getMappedStatements()) {
            xmlStatements.add(ms.getId().substring(ms.getId().lastIndexOf('.') + 1));
        }

        methodParams = new HashMap<>();
        for (Method method : WaitGroupMapper.class.getDeclaredMethods()) {
            Set<String> params = new HashSet<>();
            for (Parameter parameter : method.getParameters()) {
                Param annotation = parameter.getAnnotation(Param.class);
                if (annotation != null) {
                    params.add(annotation.value());
                }
            }
            methodParams.put(method.getName(), params);
        }

        foreachItems = new HashSet<>();
        Matcher matcher = Pattern.compile("<foreach[^>]*item=\"(\\w+)\"").matcher(xml);
        while (matcher.find()) {
            foreachItems.add(matcher.group(1));
        }
    }

    private static String readXml() throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), "src/main/resources/mapper/" + XML_NAME);
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        path = Paths.get(System.getProperty("user.dir"), "agentPlatformShared/src/main/resources/mapper/" + XML_NAME);
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        return new String(Resources.getResourceAsStream("mapper/" + XML_NAME).readAllBytes());
    }

    // ===== 解析与接口对应 =====

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

    @Test
    void everyBoundParameterHasASource() {
        List<String> problems = new ArrayList<>();
        for (String id : xmlStatements) {
            Set<String> declared = methodParams.getOrDefault(id, Set.of());
            Set<String> used = boundParamNames(configuration.getMappedStatement(NAMESPACE + "." + id),
                    dummyParams(id));
            for (String property : used) {
                if (property.contains(".")) {
                    String holder = property.substring(0, property.indexOf('.'));
                    if (foreachItems.contains(holder)) {
                        continue;
                    }
                }
                if (!declared.contains(property)) {
                    problems.add(id + " 用的参数 '" + property + "' 不是接口上的 @Param");
                }
            }
        }
        assertThat(problems).as("XML 里的 #{} 必须有出处").isEmpty();
    }

    @Test
    void everyParameterIsAnnotatedWithParam() {
        List<String> problems = new ArrayList<>();
        for (Method method : WaitGroupMapper.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getAnnotation(Param.class) == null) {
                    problems.add(method.getName() + " 的参数 " + parameter.getName() + " 没有 @Param");
                }
            }
        }
        assertThat(problems).as("所有参数都要有 @Param，避免按名字取参时对不上").isEmpty();
    }

    // ===== 锁顺序：先 Run 再子行 =====

    @Test
    void everyWriteStatementLocksTheRunBeforeTouchingChildren() {
        for (String id : WRITE_STATEMENTS) {
            String sql = sql(id);
            int lock = sql.indexOf("FOR UPDATE");
            assertThat(lock).as(id + " 必须先锁 Run 行").isGreaterThanOrEqualTo(0);
            assertThat(sql).as(id + " 的 Run 锁要落在 Run 表上").contains("FROM alphafrog_agent_run r");
            int firstChildWrite = sql.indexOf("UPDATE alphafrog_agent_run_");
            assertThat(firstChildWrite)
                    .as(id + " 必须在锁住 Run 之后再动子行")
                    .isGreaterThan(lock);
        }
    }

    // ===== 整组挂起 =====

    @Test
    void suspensionFencesTheSegmentAndTheRunControlVersion() {
        String sql = sql("suspendSegment");
        assertThat(sql).contains("wi.state = 'EXECUTING'")
                .contains("wi.claim_epoch = ?")
                .contains("wi.claimed_by = ?")
                .contains("r.run_control_version = ?")
                .contains("r.status = 'EXECUTING'");
        assertThat(sql).as("五个身份字段一个都不能少")
                .contains("wi.run_id = ?")
                .contains("wi.plan_generation = ?")
                .contains("wi.node_id = ?")
                .contains("wi.node_attempt = ?")
                .contains("wi.segment_sequence = ?");
        assertThat(sql).as("计划代际与冻结版本都要在栅栏里")
                .contains("r.plan_generation = ?")
                .contains("r.scheduler_version = ?");
        assertThat(sql).as("同一身份的下一段已经存在时让整条语句失败，不做静默跳过")
                .doesNotContain("ON CONFLICT");
        assertThat(sql).as("当前分段以「分段结果已提交」收尾，节点是否完成另按最新分段判断")
                .contains("state = 'RESULT_COMMITTED'");
        assertThat(sql).as("下一段建成等待态、领取代际归零，绝不能在保存挂起时就变成可执行")
                .contains("'WAITING', ?::bigint, ?::bigint, 0, ?::varchar");
        assertThat(sql).as("下一段的身份是本段加一")
                .contains("s.segment_sequence + 1");
        assertThat(sql).as("期望成员数按实际提交的成员算，不另外传一个数进来")
                .contains("(SELECT count(*) FROM member_input)");
        assertThat(sql).as("读回已有的组也要过同一道 Run 栅栏，否则升代之后旧工人会把作废的组当成自己的")
                .contains("JOIN run_fence f ON f.run_id = g.run_id")
                .contains("existing_group_id");
        assertThat(xml).as("成员逐条写入时用的是集合参数").contains("<foreach collection=\"members\"");
    }

    // ===== 成员结束 =====

    @Test
    void completionOnlyWritesStillOpenMembersAndCountsOnce() {
        String sql = sql("completeMember");
        assertThat(sql).contains("m.state IN ('PENDING', 'RUNNING')")
                .contains("AND g.state = 'WAITING'")
                .contains("b.completed_members = b.expected_members");
        String counted = String.join(", ",
                WaitMemberState.completedWireValues().stream().map(v -> "'" + v + "'").toList());
        assertThat(sql).as("只有成功与失败计入完成数，取消与迟到加零")
                .contains("IN (" + counted + ") THEN 1 ELSE 0");
        assertThat(sql).as("组齐备与写通知在同一条语句里")
                .contains("'READY'")
                .contains("recovery_generation = g.recovery_generation + 1")
                .contains("INSERT INTO alphafrog_agent_run_recovery_notification");
        assertThat(sql).as("通知只写一条靠唯一约束兜底")
                .contains("ON CONFLICT (group_id, recovery_generation) DO NOTHING");
        assertThat(sql).as("外部作业身份给了就必须对上，两边都是空也只算同一成员")
                .contains("m.external_operation_id = ?::varchar")
                .contains("m.external_operation_id IS NULL AND ?::varchar IS NULL");
        assertThat(sql).as("Run 的当前计划代际与这一段的上下文版本都要在同一写入里核对")
                .contains("r.plan_generation = ?")
                .contains("wi.context_version = ?")
                .contains("wi.run_control_version = ?");
    }

    @Test
    void lateResultStopsTheWholeChain() {
        String sql = sql("reportLateMember");
        assertThat(sql).contains("state = 'LATE'")
                .contains("g.state IN ('WAITING', 'READY')")
                .contains("state IN ('WAITING', 'RESUMABLE', 'RUNNABLE')")
                .doesNotContain("INSERT")
                .doesNotContain("completed_members = completed_members + 1");
        // 已经被取消的成员也要能补审计：否则取消之后真到的结果连引用都留不下来。
        assertThat(sql).as("迟到结果允许从「已取消」补一次审计")
                .contains("m.state IN ('PENDING', 'RUNNING', 'CANCELED')");
        // 审计只写一次：结果引用与外部作业身份都只在还是空的时候写。
        assertThat(sql).as("重复上报不许覆盖先到的审计")
                .contains("result_ref_json = COALESCE(m.result_ref_json,")
                .contains("finished_at = COALESCE(m.finished_at,");
        assertThat(sql).as("停链要 Run 还在这一代执行，版本对不上只留审计")
                .contains("f.status = 'EXECUTING'")
                .contains("f.run_plan_generation = g.plan_generation")
                .contains("f.run_control_version = ?");
    }

    // ===== 恢复消费 =====

    @Test
    void recoveryConsumptionTakesTheNotificationAndPromotesTheNextSegmentOnce() {
        String sql = sql("consumeRecovery");
        assertThat(sql).contains("n.state = 'WAITING'")
                .contains("state = 'CONSUMED'")
                .contains("g.state = 'READY'")
                .contains("state = 'RESUMED'")
                .contains("wi.state = 'WAITING'")
                .contains("state = 'RESUMABLE'")
                .contains("r.run_control_version = ?")
                .contains("wi.segment_sequence = g.next_segment_sequence")
                .contains("runnable_since = CURRENT_TIMESTAMP");
        assertThat(sql).as("通知的恢复代际要与组当前那一代逐字对上")
                .contains("n.recovery_generation = g.recovery_generation");
        assertThat(sql).as("Run 的当前计划代际要与组的计划代际对上")
                .contains("r.plan_generation = g.plan_generation");
        assertThat(sql).as("三条写入用 RETURNING 串起来，只有全成或全不写")
                .contains("FROM segment_promoted")
                .contains("FROM group_resumed");
        assertThat(sql).as("消费那条语句要把组号一起返回：主查询要按它回报放行的是哪条链")
                .contains("RETURNING n.id, n.group_id");
    }

    @Test
    void cancellationStopsGroupMembersSegmentAndNotification() {
        String sql = sql("cancelChain");
        assertThat(sql).contains("g.state IN ('WAITING', 'READY')")
                .contains("m.state IN ('PENDING', 'RUNNING')")
                .contains("wi.state IN ('WAITING', 'RESUMABLE', 'RUNNABLE')")
                .contains("n.state = 'WAITING'")
                .contains("state = 'CANCELED'");
    }

    // ===== 字面量与 Java 取值一致 =====

    @Test
    void everyStateLiteralInXmlExistsInJavaEnums() {
        Set<String> known = new LinkedHashSet<>();
        WaitGroupState.allWireValues().forEach(known::add);
        WaitMemberState.allWireValues().forEach(known::add);
        RecoveryNotificationState.allWireValues().forEach(known::add);
        world.willfrog.agent.platform.workitem.NodeWorkItemState.allWireValues().forEach(known::add);
        for (AgentRunStatus status : AgentRunStatus.values()) {
            known.add(status.name());
        }
        List<String> unknown = new ArrayList<>();
        for (String literal : quotedValues(xml)) {
            if (!known.contains(literal)) {
                unknown.add(literal);
            }
        }
        assertThat(unknown)
                .as("SQL 里的状态字面量必须都是 Java 枚举里的取值，写错一个字母条件就永远不成立")
                .isEmpty();
    }

    // ===== 工具 =====

    private String sql(String id) {
        return normalized(configuration.getMappedStatement(NAMESPACE + "." + id)
                .getSqlSource().getBoundSql(dummyParams(id)).getSql());
    }

    private Set<String> boundParamNames(MappedStatement ms, Object parameter) {
        BoundSql boundSql = ms.getSqlSource().getBoundSql(parameter);
        Set<String> names = new LinkedHashSet<>();
        for (ParameterMapping mapping : boundSql.getParameterMappings()) {
            names.add(mapping.getProperty());
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
            case "members" -> List.of();
            case "runId", "nodeId" -> "run-1";
            case "claimedBy", "dispatcherId", "memberIdentity" -> "worker-1";
            case "schedulerVersion" -> "DUAL_POOL_V2";
            case "suspensionPayloadJson", "nextSegmentPayloadJson", "resultRefJson",
                 "externalOperationId" -> "{}";
            case "memberState" -> WaitMemberState.SUCCEEDED.name();
            case "nextPollAt" -> java.time.OffsetDateTime.now();
            case "maxBackoffStep" -> 9;
            default -> {
                if (name.startsWith("planGeneration") || name.startsWith("nodeAttempt")
                        || name.startsWith("segmentSequence") || name.startsWith("claimEpoch")) {
                    yield 0;
                }
                if (name.startsWith("groupId") || name.startsWith("notificationId")
                        || name.startsWith("contextVersion") || name.startsWith("runControlVersion")
                        || name.startsWith("modelTurn")) {
                    yield 1L;
                }
                yield null;
            }
        };
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
        return sql.replaceAll("\\s+", " ").trim();
    }
}
