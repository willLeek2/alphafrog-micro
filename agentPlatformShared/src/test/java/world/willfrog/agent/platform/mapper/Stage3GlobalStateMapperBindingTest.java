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

    @Test
    void everyStatementHasAMethodAndEveryMethodHasAStatement() {
        for (String mapperName : MAPPERS) {
            Map<String, Set<String>> methodParams = paramsByMapper.get(mapperName);
            Set<String> statements = statementsByMapper.get(mapperName);
            List<String> missing = new ArrayList<>();
            for (String method : methodParams.keySet()) {
                if (!statements.contains(method)) {
                    missing.add(mapperName + "." + method);
                }
            }
            assertThat(missing).as("接口方法缺少 XML statement").isEmpty();

            List<String> orphans = new ArrayList<>();
            for (String statement : statements) {
                if (!methodParams.containsKey(statement)) {
                    orphans.add(mapperName + "." + statement);
                }
            }
            assertThat(orphans).as("XML statement 无对应接口方法").isEmpty();
        }
    }

    @Test
    void everyBoundParameterComesFromAParamAnnotation() {
        List<String> problems = new ArrayList<>();
        for (String mapperName : MAPPERS) {
            Map<String, Set<String>> methodParams = paramsByMapper.get(mapperName);
            for (String id : statementsByMapper.get(mapperName)) {
                MappedStatement ms = configurations.get(mapperName)
                        .getMappedStatement("world.willfrog.agent.platform.mapper." + mapperName + "." + id);
                BoundSql boundSql = ms.getSqlSource().getBoundSql(dummyParams(id, methodParams));
                Set<String> declared = methodParams.getOrDefault(id, Set.of());
                for (ParameterMapping mapping : boundSql.getParameterMappings()) {
                    if (!declared.contains(mapping.getProperty())) {
                        problems.add(mapperName + "." + id + " 用的参数 '" + mapping.getProperty()
                                + "' 不是接口上的 @Param");
                    }
                }
            }
        }
        assertThat(problems).as("XML 里的 #{} 必须有出处").isEmpty();
    }

    // ===== 轮转顺序与延期原因 =====

    @Test
    void candidateRunsAreSortedByRotationPositionFirst() {
        String sql = sql("RunCoordinationMapper", "scanDue");
        assertThat(sql).as("轮转位置排第一位：没被服务过的轮次最小，先被取到")
                .contains("ORDER BY c.coordination_served_round, c.next_visible_at, c.run_id");
        assertThat(sql).as("候选取的是协调轮的进度，不能拿派发轮的进度来排序")
                .doesNotContain("dispatch_served_round, c.next_visible_at");
        assertThat(sql).as("只取到期的记录").contains("c.next_visible_at <= CURRENT_TIMESTAMP");
        assertThat(sql).as("一次全局扫描：不按调度器版本分池，版本由每行记录自己带着，由调用方路由")
                .doesNotContain("c.scheduler_version =");
    }

    @Test
    void scanDueStatusListOnlyContainsKnownRunStatuses() {
        Set<String> known = new LinkedHashSet<>();
        for (AgentRunStatus status : AgentRunStatus.values()) {
            known.add(status.name());
        }
        String sql = sql("RunCoordinationMapper", "scanDue");
        String inList = sql.substring(sql.indexOf("r.status IN ("), sql.indexOf(")", sql.indexOf("r.status IN (")));
        List<String> unknown = new ArrayList<>();
        for (String value : quotedValues(inList)) {
            if (!known.contains(value)) {
                unknown.add(value);
            }
        }
        assertThat(unknown).as("状态字面量必须是 AgentRunStatus 里的取值").isEmpty();
        assertThat(quotedValues(inList))
                .as("已结束与取消中的状态不进协调候选，各自的路径自己推进")
                .doesNotContain("COMPLETED", "FAILED", "CANCELED", "EXPIRED", "PARTIAL", "CANCELING");
    }

    @Test
    void markCoordinationServedClearsDeferAndRecordsRoundPosition() {
        String sql = sql("RunCoordinationMapper", "markCoordinationServed");
        assertThat(sql).contains("defer_reason = NULL")
                .contains("coordination_served_round = ?")
                .contains("coordination_missed_rounds = 0");
        assertThat(sql).as("协调这一轮不改派发轮的进度")
                .doesNotContain("dispatch_served_round").doesNotContain("dispatch_missed_rounds");
        String refresh = sql("RunCoordinationMapper", "refreshCoordinationMissedRounds");
        assertThat(refresh).as("连续未获协调轮数按「当前轮次减上次协调轮次减一」算，只增不减")
                .contains("GREATEST(? - c.coordination_served_round - 1, 0)")
                .contains("> c.coordination_missed_rounds");
        assertThat(refresh).as("刷新口径与扫描一致：一次全局刷新，不按版本分池")
                .doesNotContain("c.scheduler_version =");
    }

    @Test
    void markDispatchServedOnlyRecordsItsOwnRoundPosition() {
        String sql = sql("RunCoordinationMapper", "markDispatchServed");
        assertThat(sql).contains("dispatch_served_round = ?")
                .contains("dispatch_missed_rounds = 0");
        assertThat(sql).as("延期原因是协调层的事实，派发不许替它清掉")
                .doesNotContain("defer_reason");
        String refresh = sql("RunCoordinationMapper", "refreshDispatchMissedRounds");
        assertThat(refresh).as("派发轮的连续未获轮数按自己的上次派发轮次算")
                .contains("GREATEST(? - c.dispatch_served_round - 1, 0)")
                .doesNotContain("coordination_served_round")
                .contains("> c.dispatch_missed_rounds");
        assertThat(refresh).as("这一组的候选取「此刻确实有到期可领取节点」的 Run")
                .contains("wi.state IN ('RUNNABLE', 'RESUMABLE')");
    }

    @Test
    void deferReasonLiteralsExistInTheJavaEnum() {
        String sql = sql("RunCoordinationMapper", "deferFor");
        assertThat(sql).as("延期只写原因与时间").contains("defer_reason = ?")
                .contains("next_visible_at = ?");
        assertThat(RunCoordinationDeferReason.allWireValues())
                .as("库里对延期原因有 CHECK 约束，枚举与它必须一致")
                .containsExactly("RUN_COORDINATION_PERMIT_FULL", "PER_ROUND_NEW_NODE_LIMIT",
                        "PER_RUN_UNFINISHED_LIMIT", "GLOBAL_UNFINISHED_PAUSED");
        assertThat(RunCoordinationDeferReason.allWireValues())
                .as("节点派发失败的原因记在工作项上，不能混进 Run 这一层")
                .doesNotContain("HINT_QUEUE_FULL");
    }

    // ===== 全局暂停状态与轮次 =====

    @Test
    void capacityDecisionWritesPausedSinceOnlyWhenPausing() {
        String sql = sql("SchedulerStateMapper", "applyCapacityDecision");
        assertThat(sql).contains("add_paused = ?::boolean")
                .contains("paused_since = CASE")
                .contains("COALESCE(paused_since, CURRENT_TIMESTAMP)")
                .contains("ELSE NULL")
                .contains("RETURNING");
        assertThat(sql).as("水位值跟着判定一起落库，便于回看当时用的是哪组数")
                .contains("high_watermark = ?")
                .contains("low_watermark = ?");
    }

    @Test
    void roundAdvanceIsAtomicAndReturnsTheNewNumber() {
        String sql = sql("SchedulerStateMapper", "advanceRound");
        assertThat(sql).contains("round_number = round_number + 1").contains("RETURNING round_number");
    }

    @Test
    void migrationSeedsBothRoundScopes() {
        String migration = MigrationScripts.lastContaining("alphafrog_agent_scheduler_round");
        String normalize = migration.replaceAll("\\s+", " ");
        for (String scope : SchedulerRoundScope.allWireValues()) {
            assertThat(normalize)
                    .as("轮转作用域 " + scope + " 必须在迁移里有种子行，否则轮次推进无处可写")
                    .contains("('" + scope + "', 0)");
        }
    }

    // ===== 工具 =====

    private String sql(String mapperName, String id) {
        MappedStatement ms = configurations.get(mapperName)
                .getMappedStatement("world.willfrog.agent.platform.mapper." + mapperName + "." + id);
        return ms.getSqlSource().getBoundSql(dummyParams(id, paramsByMapper.get(mapperName)))
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
