package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentRunDagNodeMapper.xml 的 host-only 合同验证。
 * 不依赖 PostgreSQL，只验证 MyBatis 可解析、参数绑定、返回类型和关键 SQL 形状。
 */
class AgentRunDagNodeMapperBindingTest {

    private Configuration configuration;
    private Set<String> xmlStatements;
    private Map<String, Set<String>> methodParams; // methodName -> @Param name set

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRunDagNodeMapper.class);

        String xmlPath = System.getProperty("user.dir")
                + "/../agentPlatformShared/src/main/resources/mapper/AgentRunDagNodeMapper.xml";
        String xml;
        if (Files.exists(Paths.get(xmlPath))) {
            xml = Files.readString(Paths.get(xmlPath));
        } else {
            xmlPath = "mapper/AgentRunDagNodeMapper.xml";
            xml = new String(Resources.getResourceAsStream(xmlPath).readAllBytes());
        }
        new XMLMapperBuilder(new StringReader(xml), configuration, xmlPath,
                configuration.getSqlFragments()).parse();

        xmlStatements = new HashSet<>();
        for (MappedStatement ms : configuration.getMappedStatements()) {
            xmlStatements.add(ms.getId().substring(ms.getId().lastIndexOf('.') + 1));
        }

        // 收集每个 Java 方法的 @Param 名称
        methodParams = new HashMap<>();
        for (Method m : AgentRunDagNodeMapper.class.getDeclaredMethods()) {
            Set<String> params = new HashSet<>();
            for (Parameter p : m.getParameters()) {
                org.apache.ibatis.annotations.Param paramAnno =
                        p.getAnnotation(org.apache.ibatis.annotations.Param.class);
                if (paramAnno != null) {
                    params.add(paramAnno.value());
                }
            }
            methodParams.put(m.getName(), params);
        }
    }

    // ===== XML/statement 可解析 =====

    @Test
    void allInterfaceMethodsHaveXmlStatements() {
        List<String> missing = new ArrayList<>();
        for (String method : methodParams.keySet()) {
            if (!xmlStatements.contains(method)) {
                missing.add(method);
            }
        }
        assertThat(missing).as("接口方法缺少 XML statement").isEmpty();
    }

    @Test
    void noOrphanXmlStatements() {
        List<String> orphans = new ArrayList<>();
        for (String stmt : xmlStatements) {
            if (!methodParams.containsKey(stmt)) {
                orphans.add(stmt);
            }
        }
        assertThat(orphans).as("XML statement 无对应接口方法").isEmpty();
    }

    // ===== flushCache / command type =====

    @Test
    void dmlReturningSelectsHaveFlushCache() {
        List<String> dmlIds = List.of(
                "cancelFrontierAndChildrenCTE", "cancelFrontierAndChildrenCTE_resume",
                "incrementCancelNotfoundRetryCount", "incrementCancelRpcRetryCount",
                "atomicTerminalLost", "writePreparingStuck", "writeRpcExhausted");

        for (String id : dmlIds) {
            String fullId = AgentRunDagNodeMapper.class.getName() + "." + id;
            if (!configuration.hasStatement(fullId)) continue;
            MappedStatement ms = configuration.getMappedStatement(fullId);
            assertThat(ms.isFlushCacheRequired())
                    .as(id + " 应有 flushCache=true").isTrue();
        }
    }

    @Test
    void dmlReturningSelectsAreSelectCommandType() {
        List<String> dmlIds = List.of(
                "incrementCancelNotfoundRetryCount", "incrementCancelRpcRetryCount",
                "atomicTerminalLost", "writePreparingStuck", "writeRpcExhausted");

        for (String id : dmlIds) {
            String fullId = AgentRunDagNodeMapper.class.getName() + "." + id;
            if (!configuration.hasStatement(fullId)) continue;
            MappedStatement ms = configuration.getMappedStatement(fullId);
            assertThat(ms.getSqlCommandType())
                    .as(id + " 应为 SELECT (包裹 UPDATE...RETURNING)").isEqualTo(SqlCommandType.SELECT);
        }
    }

    // ===== 参数名绑定 =====

    @Test
    void allXmlBindParamsMatchMethodParamAnnotations() {
        // 收集所有 XML statement 的 ParameterMapping property 根名称
        List<String> mismatches = new ArrayList<>();

        for (String id : xmlStatements) {
            String fullId = AgentRunDagNodeMapper.class.getName() + "." + id;
            MappedStatement ms = configuration.getMappedStatement(fullId);

            // 构造 dummy 参数 Map，所有 @Param 名称都有值
            Set<String> javaParams = methodParams.getOrDefault(id, Set.of());
            Map<String, Object> paramMap = new HashMap<>();
            for (String p : javaParams) {
                paramMap.put(p, buildDummyValue(p));
            }

            BoundSql boundSql = ms.getSqlSource().getBoundSql(paramMap);
            for (ParameterMapping pm : boundSql.getParameterMappings()) {
                String prop = pm.getProperty();
                // property 可能是 "param1.xxx" 或直接 "xxx"，取根名称
                String root = prop.contains(".") ? prop.substring(0, prop.indexOf('.')) : prop;
                if (!javaParams.contains(root)) {
                    mismatches.add(id + ": XML 参数 '" + root + "' 不在 Java @Param 中 [" + javaParams + "]");
                }
            }
        }

        assertThat(mismatches).as("XML #{} 参数应与 Java @Param 名称一致").isEmpty();
    }

    @Test
    void criticalIdentityParamsPresentInCancelStatements() {
        // 反向检查：关键身份/CAS 参数在 XML 中未被遗漏
        List<String> errors = new ArrayList<>();

        // Phase A 需要 runId, generation, expectedFrontierVersion, cancelTime, cancelRequestId, initialBackoffSeconds
        checkParamsPresent("cancelFrontierAndChildrenCTE",
                List.of("runId", "generation", "expectedFrontierVersion", "cancelTime", "cancelRequestId", "initialBackoffSeconds"), errors);

        // Phase A resume 还需要 expectedResumeToken, expectedOwnerId, expectedResumeLeaseVersion
        checkParamsPresent("cancelFrontierAndChildrenCTE_resume",
                List.of("runId", "generation", "expectedFrontierVersion", "cancelTime", "cancelRequestId",
                        "initialBackoffSeconds", "expectedResumeToken", "expectedOwnerId", "expectedResumeLeaseVersion"), errors);

        // increment 需要 runId, generation, nodeId, expectedXxxCount, backoffSeconds, cancelRequestId, expectedNodeVersion
        checkParamsPresent("incrementCancelNotfoundRetryCount",
                List.of("runId", "generation", "nodeId", "expectedNotfoundRetryCount", "backoffSeconds", "cancelRequestId", "expectedNodeVersion"), errors);
        checkParamsPresent("incrementCancelRpcRetryCount",
                List.of("runId", "generation", "nodeId", "expectedRpcRetryCount", "backoffSeconds", "cancelRequestId", "expectedNodeVersion"), errors);

        // atomicTerminalLost: runId, generation, nodeId, expectedNotfoundRetryCount, cancelRequestId, expectedNodeVersion
        checkParamsPresent("atomicTerminalLost",
                List.of("runId", "generation", "nodeId", "expectedNotfoundRetryCount", "cancelRequestId", "expectedNodeVersion"), errors);

        // exhausted: runId, generation, nodeId, cancelRequestId, expectedNodeVersion
        checkParamsPresent("writePreparingStuck",
                List.of("runId", "generation", "nodeId", "cancelRequestId", "expectedNodeVersion"), errors);
        checkParamsPresent("writeRpcExhausted",
                List.of("runId", "generation", "nodeId", "cancelRequestId", "expectedNodeVersion"), errors);

        // claim/release: runId, generation, cancelRequestId, ownerId, leaseSeconds
        checkParamsPresent("claimReconcilerLease",
                List.of("runId", "generation", "cancelRequestId", "ownerId", "leaseSeconds"), errors);
        checkParamsPresent("releaseReconcilerLease",
                List.of("runId", "generation", "cancelRequestId", "ownerId"), errors);

        assertThat(errors).as("关键身份/CAS 参数不应缺失").isEmpty();
    }

    private void checkParamsPresent(String methodId, List<String> required, List<String> errors) {
        String fullId = AgentRunDagNodeMapper.class.getName() + "." + methodId;
        if (!configuration.hasStatement(fullId)) {
            errors.add(methodId + ": statement 不存在");
            return;
        }
        MappedStatement ms = configuration.getMappedStatement(fullId);
        Set<String> javaParams = methodParams.getOrDefault(methodId, Set.of());

        // 构造 dummy 参数 Map，取得 BoundSql 获取实际 ParameterMapping
        Map<String, Object> paramMap = new HashMap<>();
        for (String p : javaParams) paramMap.put(p, buildDummyValue(p));
        BoundSql boundSql = ms.getSqlSource().getBoundSql(paramMap);

        // 从 ParameterMapping 收集 SQL 实际使用的参数名
        Set<String> sqlParamNames = new HashSet<>();
        for (ParameterMapping pm : boundSql.getParameterMappings()) {
            String prop = pm.getProperty();
            String root = prop.contains(".") ? prop.substring(0, prop.indexOf('.')) : prop;
            sqlParamNames.add(root);
        }

        for (String req : required) {
            if (!javaParams.contains(req)) {
                errors.add(methodId + ": Java @Param 中缺少 '" + req + "'");
            }
            if (!sqlParamNames.contains(req)) {
                errors.add(methodId + ": SQL 未使用参数 '" + req + "'（BoundSql 中不存在）");
            }
        }
    }

    // ===== 零行/一行返回类型 + 列别名契约 =====

    @Test
    void returningSelectColumnAliasesMatchRecordComponents() {
        // 验证 SQL RETURNING/SELECT 列别名与 Java record component 对应
        // 使用配置中的 resultType 映射反推列别名匹配
        record AliasCheck(String methodId, String resultType, List<String> requiredAliases) {}

        List<AliasCheck> checks = List.of(
                new AliasCheck("incrementCancelNotfoundRetryCount", "RetryAdvance",
                        List.of("newNotfoundRetryCount", "newNodeVersion")),
                new AliasCheck("incrementCancelRpcRetryCount", "RpcRetryAdvance",
                        List.of("newRpcRetryCount", "newNodeVersion")),
                new AliasCheck("atomicTerminalLost", "TerminalAdvance",
                        List.of("newNodeVersion")),
                new AliasCheck("writePreparingStuck", "ExhaustedAdvance",
                        List.of("newNodeVersion")),
                new AliasCheck("writeRpcExhausted", "ExhaustedAdvance",
                        List.of("newNodeVersion")),
                new AliasCheck("cancelFrontierAndChildrenCTE", "CancelResult",
                        List.of("success", "frontierRows", "childMarkedRows")),
                new AliasCheck("cancelFrontierAndChildrenCTE_resume", "CancelResult",
                        List.of("success", "frontierRows", "childMarkedRows")));

        for (var check : checks) {
            String fullId = AgentRunDagNodeMapper.class.getName() + "." + check.methodId();
            assertThat(configuration.hasStatement(fullId))
                    .as(check.methodId() + " 应存在").isTrue();
            MappedStatement ms = configuration.getMappedStatement(fullId);

            // 验证 resultType 正确
            Class<?> resultType = ms.getResultMaps().get(0).getType();
            assertThat(resultType.getSimpleName())
                    .as(check.methodId() + " resultType").isEqualTo(check.resultType());

            // 验证每个 required alias 在 SQL 文本中出现
            // UPDATE...RETURNING 用 "alias" 格式（已 camelCase）
            // CTE SELECT 用 AS snake_case（mapUnderscoreToCamelCase=true 自动转换）
            String sql = ms.getSqlSource().getBoundSql(Map.of()).getSql();
            for (String alias : check.requiredAliases()) {
                String quoted = "\"" + alias + "\"";
                String snake = camelToSnake(alias);
                String asSnake = "AS " + snake;
                boolean found = sql.contains(quoted) || sql.contains(asSnake);
                assertThat(found)
                        .as(check.methodId() + " SQL 应包含列别名 " + alias + "（\"" + alias + "\" 或 AS " + snake + "）")
                        .isTrue();
            }

            // 验证 resultType record 有对应 component
            for (String alias : check.requiredAliases()) {
                boolean hasComponent = false;
                for (var comp : resultType.getRecordComponents()) {
                    if (comp.getName().equals(alias)) { hasComponent = true; break; }
                }
                assertThat(hasComponent)
                        .as(check.methodId() + " record " + check.resultType() + " 应有 component '" + alias + "'")
                        .isTrue();
            }
        }
    }

    @Test
    void returningRecordStatementsAreSelectNotUpdate() throws Exception {
        // UPDATE...RETURNING 使用 <select> 标签，MyBatis selectOne 零行→null
        // 确认所有 DML-returning statement 的 SqlCommandType 是 SELECT
        for (String id : List.of("incrementCancelNotfoundRetryCount", "incrementCancelRpcRetryCount",
                "atomicTerminalLost", "writePreparingStuck", "writeRpcExhausted",
                "cancelFrontierAndChildrenCTE", "cancelFrontierAndChildrenCTE_resume")) {
            String fullId = AgentRunDagNodeMapper.class.getName() + "." + id;
            assertThat(configuration.hasStatement(fullId)).isTrue();
            MappedStatement ms = configuration.getMappedStatement(fullId);
            assertThat(ms.getSqlCommandType())
                    .as(id + " 必须是 SELECT（确保 selectOne 零行→null 语义）")
                    .isEqualTo(SqlCommandType.SELECT);
            assertThat(ms.getResultMaps()).as(id + " 必须有 resultMap（零行→null 的前提）")
                    .isNotEmpty();
        }
    }

    private Method findMethod(String name) {
        for (Method m : AgentRunDagNodeMapper.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    // ===== RESUMING SQL 合同断言 =====

    @Test
    void resumingPhaseAStatementExistsAndNotSameAsNormal() {
        String fullIdResume = AgentRunDagNodeMapper.class.getName() + ".cancelFrontierAndChildrenCTE_resume";
        String fullIdNormal = AgentRunDagNodeMapper.class.getName() + ".cancelFrontierAndChildrenCTE";
        assertThat(configuration.hasStatement(fullIdResume))
                .as("RESUMING Phase A statement 应存在").isTrue();
        assertThat(configuration.hasStatement(fullIdNormal))
                .as("普通 Phase A statement 应存在").isTrue();
        // 两条是不同的 statement
        String sqlResume = configuration.getMappedStatement(fullIdResume).getSqlSource()
                .getBoundSql(Map.of()).getSql();
        String sqlNormal = configuration.getMappedStatement(fullIdNormal).getSqlSource()
                .getBoundSql(Map.of()).getSql();
        assertThat(sqlResume).as("RESUMING 与普通 Phase A SQL 应不同")
                .isNotEqualTo(sqlNormal);
    }

    @Test
    void resumingSqlContainsCorrectStatusPredicatePairs() {
        String fullId = AgentRunDagNodeMapper.class.getName() + ".cancelFrontierAndChildrenCTE_resume";
        assertThat(configuration.hasStatement(fullId)).isTrue();
        MappedStatement ms = configuration.getMappedStatement(fullId);
        String sql = ms.getSqlSource().getBoundSql(Map.of()).getSql();

        // 验证两组合法状态谓词完整存在（不是笛卡尔式交叉）
        assertThat(sql)
                .as("包含 RECEIVED + LAUNCHING + resultConsumed IS NOT TRUE 的完整配对")
                .contains("RECEIVED")
                .contains("LAUNCHING")
                .contains("resultConsumed")
                .contains("IS NOT TRUE");

        assertThat(sql)
                .as("包含 EXECUTING + (LAUNCHING|ACCEPTED) + resultConsumed IS TRUE 的完整配对")
                .contains("EXECUTING")
                .contains("ACCEPTED")
                .contains("IS TRUE");

        // 验证 resume lease triple fence 在 frontier_result UPDATE 的 WHERE 中
        assertThat(sql).as("resumeToken fence").contains("resumeToken");
        assertThat(sql).as("resumeLauncherOwnerId fence").contains("resumeLauncherOwnerId");
        assertThat(sql).as("resumeLeaseVersion fence").contains("resumeLeaseVersion");

        // 验证两组 OR 结构存在（不是笛卡尔式 IN 列表）
        assertThat(sql).as("应使用 OR 连接两组配对条件而非笛卡尔式 IN")
                .contains("OR");
    }

    @Test
    void resumingSqlRejectsLegacyCrossProductPatterns() {
        String fullId = AgentRunDagNodeMapper.class.getName() + ".cancelFrontierAndChildrenCTE_resume";
        MappedStatement ms = configuration.getMappedStatement(fullId);
        String sql = ms.getSqlSource().getBoundSql(Map.of()).getSql();

        // 旧 WAITING_TOOL_JOB 已完全移除
        assertThat(sql).as("SQL 不应包含 'WAITING_TOOL_JOB'").doesNotContain("WAITING_TOOL_JOB");

        // 没有笛卡尔式 status IN (...) AND resumeState IN (...) 模式
        // 即不会出现两个未配对的 IN 列表
        assertThat(sql).as("不应有 status IN (...) 与 resumeState IN (...) 的笛卡尔交叉")
                .doesNotMatch("status\\s+IN\\s*\\(.*\\).*resumeState\\s+IN\\s*\\(");
    }

    // ===== DML claim/release 存在 =====

    @Test
    void claimAndReleaseStatementsExist() {
        assertThat(configuration.hasStatement(
                AgentRunDagNodeMapper.class.getName() + ".claimReconcilerLease"))
                .as("claimReconcilerLease 应存在").isTrue();
        assertThat(configuration.hasStatement(
                AgentRunDagNodeMapper.class.getName() + ".releaseReconcilerLease"))
                .as("releaseReconcilerLease 应存在").isTrue();
    }

    // ===== 辅助 =====

    private String camelToSnake(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toLowerCase();
    }

    private Object buildDummyValue(String paramName) {
        return switch (paramName) {
            case "runId" -> "dummy-run";
            case "generation", "attempt", "batchSize", "initialBackoffSeconds",
                 "expectedGeneration", "expectedNotfoundRetryCount", "expectedRpcRetryCount",
                 "expectedFrontierVersion", "expectedResumeLeaseVersion",
                 "oldGeneration", "newGeneration",
                 "backoffSeconds", "leaseSeconds" -> 1;
            case "nodeVersion", "expectedNodeVersion", "expectedNodeVersionLong" -> 1L;
            case "cancelTime", "cancelRequestId", "operationId", "toolCallId",
                 "requestDigest", "taskId", "nodeId", "resumeToken", "ownerId",
                 "expectedOwnerId", "expectedResumeToken", "expectedResumeState",
                 "expectedOperationId", "expectedToolCallId",
                 "allNodeIdsJson", "frontierJson", "anchorJson",
                 "sharedContextPatch", "outputJson", "errorJson",
                 "outcome", "terminalErrorJson", "resultJson",
                 "payloadSha256", "payload", "usageRecordId" -> "dummy";
            default -> "dummy";
        };
    }
}
