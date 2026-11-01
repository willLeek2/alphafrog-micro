package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.capacity.SchedulerRoundScope;
import world.willfrog.agent.platform.coordination.RunCoordinationDeferReason;
import world.willfrog.agent.platform.model.AgentRunStatus;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run 协调资格与调度器全局状态两份映射的 host-only 合同验证。
 *
 * <p>盯三件事：候选 Run 的排序必须把轮转位置放在第一位（否则公平性无从谈起）；暂停状态的写入必须
 * 只在从没暂停变成暂停时写起始时间；SQL 里的状态与延期原因字面量必须都在 Java 枚举里。</p>
 */
class Stage3GlobalStateMapperBindingTest {

    private static final List<String> MAPPERS = List.of("RunCoordinationMapper", "SchedulerStateMapper");

    private final Map<String, Configuration> configurations = new HashMap<>();
    private final Map<String, String> xmlByMapper = new HashMap<>();
    private final Map<String, Map<String, Set<String>>> paramsByMapper = new HashMap<>();
    private final Map<String, Set<String>> statementsByMapper = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        for (String mapperName : MAPPERS) {
            Class<?> mapperInterface = Class.forName("world.willfrog.agent.platform.mapper." + mapperName);
            String xml = readXml(mapperName + ".xml");
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.addMapper(mapperInterface);
            new XMLMapperBuilder(new StringReader(xml), configuration, mapperName + ".xml",
                    configuration.getSqlFragments()).parse();
            configurations.put(mapperName, configuration);
            xmlByMapper.put(mapperName, xml);

            Set<String> statements = new HashSet<>();
            for (MappedStatement ms : configuration.getMappedStatements()) {
                statements.add(ms.getId().substring(ms.getId().lastIndexOf('.') + 1));
            }
            statementsByMapper.put(mapperName, statements);

            Map<String, Set<String>> methodParams = new HashMap<>();
            for (Method method : mapperInterface.getDeclaredMethods()) {
                Set<String> names = new HashSet<>();
                for (Parameter parameter : method.getParameters()) {
                    Param annotation = parameter.getAnnotation(Param.class);
                    if (annotation != null) {
                        names.add(annotation.value());
                    }
                }
                methodParams.put(method.getName(), names);
            }
            paramsByMapper.put(mapperName, methodParams);
        }
    }

    private static String readXml(String fileName) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), "src/main/resources/mapper/" + fileName);
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        path = Paths.get(System.getProperty("user.dir"),
                "agentPlatformShared/src/main/resources/mapper/" + fileName);
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        return new String(Resources.getResourceAsStream("mapper/" + fileName).readAllBytes());
    }

    // ===== 解析与接口对应 =====



    // ===== 轮转顺序与延期原因 =====








    // ===== 全局暂停状态与轮次 =====




    // ===== 工具 =====

    private String sql(String mapperName, String id) {
        return sql(mapperName, id, dummyParams(id, paramsByMapper.get(mapperName)));
    }

    private String sql(String mapperName, String id, Map<String, Object> params) {
        MappedStatement ms = configurations.get(mapperName)
                .getMappedStatement("world.willfrog.agent.platform.mapper." + mapperName + "." + id);
        return ms.getSqlSource().getBoundSql(params)
                .getSql().replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Object> dummyParams(String id, Map<String, Set<String>> methodParams) {
        Map<String, Object> params = new HashMap<>();
        for (String name : methodParams.getOrDefault(id, Set.of())) {
            params.put(name, names(name));
        }
        return params;
    }

    private static Object names(String name) {
        if (name.equals("limit")) {
            return 8;
        }
        if (name.equals("roundNumber") || name.equals("limit")) {
            return 1L;
        }
        if (name.equals("planGeneration")) {
            return 0;
        }
        return switch (name) {
            case "runId" -> "run-1";
            case "schedulerVersion" -> "DUAL_POOL_V2";
            case "deploymentId" -> "stable";
            case "deploymentGenerationId" -> "gen-1";
            case "laneTag" -> null;
            case "scopeKey" -> "GLOBAL";
            case "deferReason" -> RunCoordinationDeferReason.PER_ROUND_NEW_NODE_LIMIT.name();
            case "nextVisibleAt" -> java.time.OffsetDateTime.now();
            case "unfinishedCount" -> 0L;
            case "paused" -> false;
            case "highWatermark", "lowWatermark" -> 128;
            default -> {
                if (name.startsWith("roundNumber")) {
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
}
