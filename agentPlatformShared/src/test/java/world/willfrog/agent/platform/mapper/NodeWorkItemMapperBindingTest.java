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


    // ===== 状态取值三处一致 =====







    // ===== 迁移与代码里的列名一致 =====


    // ===== 派发成功与失败的留痕 =====



    // ===== 领取与转交的 SQL 形状 =====






    private String sql(String id) {
        return normalized(configuration.getMappedStatement(NAMESPACE + "." + id)
                .getSqlSource().getBoundSql(dummyParams(id)).getSql());
    }






    // ===== 参数绑定 =====



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
            case "deploymentId" -> "stable";
            case "deploymentGenerationId" -> "gen-1";
            case "laneTag" -> null;
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
