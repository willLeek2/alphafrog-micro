package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.executor.BaseExecutor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import world.willfrog.agent.platform.dag.CancelResult;
import world.willfrog.agent.platform.dag.ExhaustedAdvance;
import world.willfrog.agent.platform.dag.RetryAdvance;
import world.willfrog.agent.platform.dag.TerminalAdvance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentRunDagNodeMapper.xml 的 host-only 合同验证。
 * 不依赖 PostgreSQL，只验证 MyBatis 可解析、参数绑定、返回类型和关键 SQL 形状。
 */
class AgentRunDagNodeMapperBindingTest {

    private Configuration configuration;
    private Set<String> xmlStatements;
    private Map<String, Set<String>> methodParams; // methodName -> @Param name set

    // ===== SqlSession/Executor 测试支持 =====

    /** 无操作事务，让 BaseExecutor 的 close/commit/rollback 不抛 NPE */
    private static final Transaction NOOP_TX = new Transaction() {
        @Override public Connection getConnection() { return null; }
        @Override public void commit() {}
        @Override public void rollback() {}
        @Override public void close() {}
        @Override public Integer getTimeout() { return null; }
    };

    /** 可控 Executor：返回预置的 Java 对象列表，绕过 JDBC 层，专用于验证 selectOne 零行→null / 一行→record */
    static class ControlledExecutor extends BaseExecutor {
        private final Map<String, List<?>> statementResults = new HashMap<>();

        ControlledExecutor(Configuration configuration) {
            super(configuration, NOOP_TX);
        }

        void setResults(String statementId, List<?> results) {
            statementResults.put(statementId, results);
        }

        @Override public int doUpdate(MappedStatement ms, Object parameter) { return 0; }

        @Override public <E> List<E> doQuery(MappedStatement ms, Object parameter,
                                              RowBounds rowBounds, ResultHandler resultHandler,
                                              BoundSql boundSql) {
            List<?> results = statementResults.getOrDefault(ms.getId(), List.of());
            @SuppressWarnings("unchecked")
            List<E> typed = (List<E>) results;
            return typed;
        }

        @Override public List<BatchResult> doFlushStatements(boolean isRollback) { return List.of(); }

        @Override protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter,
                                                          RowBounds rowBounds, BoundSql boundSql) {
            throw new UnsupportedOperationException("cursor not supported in test");
        }
    }

    /** 构建包含 Mapper + XML 的 Configuration（可被 setUp 和 SqlSession 测试复用） */
    private static Configuration buildConfiguration() throws Exception {
        Configuration cfg = new Configuration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setLocalCacheScope(LocalCacheScope.STATEMENT);
        cfg.addMapper(AgentRunDagNodeMapper.class);

        String xmlPath = System.getProperty("user.dir")
                + "/../agentPlatformShared/src/main/resources/mapper/AgentRunDagNodeMapper.xml";
        String xml;
        if (Files.exists(Paths.get(xmlPath))) {
            xml = Files.readString(Paths.get(xmlPath));
        } else {
            xmlPath = "mapper/AgentRunDagNodeMapper.xml";
            xml = new String(Resources.getResourceAsStream(xmlPath).readAllBytes());
        }
        new XMLMapperBuilder(new StringReader(xml), cfg, xmlPath,
                cfg.getSqlFragments()).parse();
        return cfg;
    }

    @BeforeEach
    void setUp() throws Exception {
        configuration = buildConfiguration();

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
    void returningRecordStatementsAreSelectNotUpdate() {
        // 静态前提：所有 DML-returning statement 的 SqlCommandType 是 SELECT 且有 resultMap
        for (String id : List.of("incrementCancelNotfoundRetryCount", "incrementCancelRpcRetryCount",
                "atomicTerminalLost", "writePreparingStuck", "writeRpcExhausted",
                "cancelFrontierAndChildrenCTE", "cancelFrontierAndChildrenCTE_resume")) {
            String fullId = AgentRunDagNodeMapper.class.getName() + "." + id;
            assertThat(configuration.hasStatement(fullId)).isTrue();
            MappedStatement ms = configuration.getMappedStatement(fullId);
            assertThat(ms.getSqlCommandType())
                    .as(id + " 必须是 SELECT").isEqualTo(SqlCommandType.SELECT);
            assertThat(ms.getResultMaps()).as(id + " 必须有 resultMap").isNotEmpty();
        }
    }

    @Test
    void returningMethodsReturnNullForZeroRowsAndRecordForOneRow() throws Exception {
        // 用 ControlledExecutor 绕过 JDBC，实际验证 mapper.selectOne 的零行→null、一行→record 行为
        Configuration cfg = buildConfiguration();
        ControlledExecutor executor = new ControlledExecutor(cfg);

        DefaultSqlSession session = new DefaultSqlSession(cfg, executor, false);
        try {
            AgentRunDagNodeMapper mapper = session.getMapper(AgentRunDagNodeMapper.class);

            // ---- RetryAdvance（incrementCancelNotfoundRetryCount）----
            String retryId = AgentRunDagNodeMapper.class.getName() + ".incrementCancelNotfoundRetryCount";
            executor.setResults(retryId, List.of());
            assertThat(mapper.incrementCancelNotfoundRetryCount(
                    "run-1", 2, "node-1", 0, 30, "req-a", 1L))
                    .as("RetryAdvance 零行应返回 null").isNull();

            RetryAdvance expectedRetry = new RetryAdvance(1, 5L);
            executor.setResults(retryId, List.of(expectedRetry));
            assertThat(mapper.incrementCancelNotfoundRetryCount(
                    "run-1", 2, "node-1", 0, 30, "req-a", 1L))
                    .as("RetryAdvance 一行应返回对应 record").isEqualTo(expectedRetry);

            // ---- ExhaustedAdvance（writePreparingStuck）----
            String exhaustedId = AgentRunDagNodeMapper.class.getName() + ".writePreparingStuck";
            executor.setResults(exhaustedId, List.of());
            assertThat(mapper.writePreparingStuck("run-1", 2, "node-1", "req-a", 1L))
                    .as("ExhaustedAdvance 零行应返回 null").isNull();

            ExhaustedAdvance expectedExhausted = new ExhaustedAdvance(3L);
            executor.setResults(exhaustedId, List.of(expectedExhausted));
            assertThat(mapper.writePreparingStuck("run-1", 2, "node-1", "req-a", 1L))
                    .as("ExhaustedAdvance 一行应返回对应 record").isEqualTo(expectedExhausted);

            // ---- TerminalAdvance（atomicTerminalLost）----
            String terminalId = AgentRunDagNodeMapper.class.getName() + ".atomicTerminalLost";
            executor.setResults(terminalId, List.of());
            assertThat(mapper.atomicTerminalLost("run-1", 2, "node-1", 5, "req-a", 1L))
                    .as("TerminalAdvance 零行应返回 null").isNull();

            TerminalAdvance expectedTerminal = new TerminalAdvance(7L);
            executor.setResults(terminalId, List.of(expectedTerminal));
            assertThat(mapper.atomicTerminalLost("run-1", 2, "node-1", 5, "req-a", 1L))
                    .as("TerminalAdvance 一行应返回对应 record").isEqualTo(expectedTerminal);

            // ---- CancelResult（cancelFrontierAndChildrenCTE）----
            String cancelId = AgentRunDagNodeMapper.class.getName() + ".cancelFrontierAndChildrenCTE";
            executor.setResults(cancelId, List.of());
            assertThat(mapper.cancelFrontierAndChildrenCTE("run-1", 2, 1L, "2026-01-01", "req-a", 60))
                    .as("CancelResult 零行应返回 null").isNull();

            CancelResult expectedCancel = new CancelResult(true, 1, 3);
            executor.setResults(cancelId, List.of(expectedCancel));
            assertThat(mapper.cancelFrontierAndChildrenCTE("run-1", 2, 1L, "2026-01-01", "req-a", 60))
                    .as("CancelResult 一行应返回对应 record").isEqualTo(expectedCancel);
        } finally {
            session.close();
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

        // 用带参数的 BoundSql 以便通过 ParameterMapping 校验 identity fence
        Set<String> javaParams = methodParams.getOrDefault("cancelFrontierAndChildrenCTE_resume", Set.of());
        Map<String, Object> paramMap = new HashMap<>();
        for (String p : javaParams) paramMap.put(p, buildDummyValue(p));
        BoundSql boundSql = ms.getSqlSource().getBoundSql(paramMap);
        String sql = boundSql.getSql();
        List<ParameterMapping> pms = boundSql.getParameterMappings();

        // 构建 ? 位置 → ParameterMapping 索引的映射
        List<Integer> qmPositions = new ArrayList<>();
        int idx = 0;
        while ((idx = sql.indexOf('?', idx)) != -1) {
            qmPositions.add(idx);
            idx++;
        }

        // 1) 规范化：折叠空白、trim
        String norm = sql.replaceAll("\\s+", " ").trim();

        // 2) 提取 frontier_result UPDATE 的 WHERE 子句
        int frontierStart = norm.indexOf("frontier_result AS (");
        assertThat(frontierStart).as("SQL 应包含 frontier_result CTE").isNotNegative();
        int returnIdx = norm.indexOf("RETURNING 1", frontierStart);
        assertThat(returnIdx).as("frontier_result 应有 RETURNING 1").isNotNegative();
        String frontierBlock = norm.substring(frontierStart, returnIdx);

        int whereIdx = frontierBlock.indexOf("WHERE");
        assertThat(whereIdx).as("frontier_result UPDATE 应有 WHERE 子句").isNotNegative();
        String whereClause = frontierBlock.substring(whereIdx);

        // 3) 谓词组一：status = 'RECEIVED' AND resumeState = 'LAUNCHING' AND resultConsumed IS NOT TRUE
        assertThat(whereClause)
                .as("谓词组一：三条件必须用 AND 连接，不能出现 OR")
                .containsPattern(
                        "status\\s*=\\s*'RECEIVED'"
                        + "\\s+AND\\s+.+resumeState.+'LAUNCHING'"
                        + "\\s+AND\\s+.+resultConsumed.+IS\\s+NOT\\s+TRUE");

        // 4) 谓词组二：status = 'EXECUTING' AND resumeState IN ('LAUNCHING','ACCEPTED') AND resultConsumed IS TRUE
        assertThat(whereClause)
                .as("谓词组二：三条件必须用 AND 连接，不能出现 OR")
                .containsPattern(
                        "status\\s*=\\s*'EXECUTING'"
                        + "\\s+AND\\s+.+resumeState.+IN\\s*\\(\\s*'LAUNCHING'\\s*,\\s*'ACCEPTED'\\s*\\)"
                        + "\\s+AND\\s+.+resultConsumed.+IS\\s+TRUE");

        // 5) 两组之间用 OR 连接（非 AND），且包裹在同一对外层括号内
        int group1End = whereClause.indexOf("IS NOT TRUE)") + "IS NOT TRUE)".length();
        int group2Start = whereClause.indexOf("(status = 'EXECUTING'");
        assertThat(group1End).as("group1 结束位置应在 group2 之前").isLessThan(group2Start);
        assertThat(whereClause.substring(group1End, group2Start).trim())
                .as("group1 和 group2 之间应由 OR 连接，不能是 AND")
                .isEqualTo("OR");

        // 外层结构：AND ( (group1) OR (group2) ) AND tool_job_anchor_json
        int outerOpen = whereClause.indexOf("AND ( (status = 'RECEIVED'");
        int outerClose = whereClause.indexOf(") ) AND tool_job_anchor_json");
        assertThat(outerOpen).as("外层应形如 AND ( (group1...").isNotNegative();
        assertThat(outerClose).as("外层应形如 ...group2) ) AND tool_job_anchor_json")
                .isNotNegative()
                .isGreaterThan(outerOpen);

        // 6) 三项 identity fence 完整形状 + ParameterMapping 双锁
        //    用规范化 WHERE 做完整形状断言，用 raw SQL Position → ParameterMapping 做 @Param 校验
        assertFenceComplete(whereClause, sql, qmPositions, pms,
                "resumeToken", "expectedResumeToken",
                "tool_job_anchor_json\\s*#>>\\s*'\\{resumeToken\\}'\\s*(?<![<>!])=\\s*\\?");
        assertFenceComplete(whereClause, sql, qmPositions, pms,
                "resumeLauncherOwnerId", "expectedOwnerId",
                "tool_job_anchor_json\\s*#>>\\s*'\\{resumeLauncherOwnerId\\}'\\s*(?<![<>!])=\\s*\\?");
        assertFenceComplete(whereClause, sql, qmPositions, pms,
                "resumeLeaseVersion", "expectedResumeLeaseVersion",
                "\\(tool_job_anchor_json\\s*#>>\\s*'\\{resumeLeaseVersion\\}'\\)::bigint\\s*(?<![<>!])=\\s*\\?");
    }

    /**
     * 验证 frontier_result WHERE 中一条 identity fence 的完整形状和参数绑定。
     * completeShapeRegex 使用 (?<![<>!])= 确保操作符是严格 = 而非 >= / <= / !=。
     * 再通过 raw SQL 中 ? 的位置 → ParameterMapping 锁住 @Param 名称。
     */
    private void assertFenceComplete(String whereClause, String fullSql,
                                      List<Integer> qmPositions,
                                      List<ParameterMapping> pms,
                                      String jsonKeyName, String expectedParam,
                                      String completeShapeRegex) {
        // 1) 规范化 WHERE 中完整形状断言：从 JSON 路径到 ? 必须是 = 比较
        assertThat(whereClause)
                .as(jsonKeyName + " fence 必须是完整等号比较，拒绝 >= / <= / != / IS NOT NULL 等")
                .containsPattern(completeShapeRegex);

        // 2) raw SQL 中找 JSON 键及其后的 ?，通过 ParameterMapping 锁 @Param
        String jsonKey = "'{" + jsonKeyName + "}'";
        int keyPos = fullSql.indexOf(jsonKey);
        assertThat(keyPos).as(jsonKey + " 应在 SQL 中").isNotNegative();
        int qmIdx = fullSql.indexOf('?', keyPos);
        assertThat(qmIdx).as(jsonKey + " 之后应有 ? 占位符").isNotNegative();
        int frontierReturning = fullSql.indexOf("RETURNING 1", keyPos);
        assertThat(qmIdx).as(jsonKey + " 的 ? 应在 frontier_result 的 RETURNING 1 之前")
                .isLessThan(frontierReturning);

        int pmIndex = qmPositions.indexOf(qmIdx);
        assertThat(pmIndex).as(jsonKey + " 的 ? 应有对应 ParameterMapping").isNotNegative();
        String prop = pms.get(pmIndex).getProperty();
        String root = prop.contains(".") ? prop.substring(0, prop.indexOf('.')) : prop;
        assertThat(root).as(jsonKey + " = ? 的 ? 必须对应 @Param(\"" + expectedParam + "\")")
                .isEqualTo(expectedParam);
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
