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



    // ===== flushCache / command type =====



    // ===== 参数名绑定 =====



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




    private Method findMethod(String name) {
        for (Method m : AgentRunDagNodeMapper.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    // ===== RESUMING SQL 合同断言 =====



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


    // ===== DML claim/release 存在 =====


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
