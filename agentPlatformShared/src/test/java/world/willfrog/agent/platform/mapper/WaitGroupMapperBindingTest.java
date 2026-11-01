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




    // ===== 锁顺序：先 Run 再子行 =====


    // ===== 整组挂起 =====


    // ===== 成员结束 =====



    // ===== 恢复消费 =====



    // ===== 字面量与 Java 取值一致 =====


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
