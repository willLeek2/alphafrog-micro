package world.willfrog.agent.platform.mapper;

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

import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentRunMapper.xml CAS_STATUS recovery 的 host-only 合同验证。
 * 不依赖 PostgreSQL，只验证 MyBatis 可解析、参数绑定、命令类型和关键 SQL 形状。
 */
class AgentRunMapperCasStatusBindingTest {

    private Configuration configuration;
    private Set<String> xmlStatements;
    private Map<String, Set<String>> methodParams;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(AgentRunMapper.class);

        String xmlPath = System.getProperty("user.dir")
                + "/src/main/resources/mapper/AgentRunMapper.xml";
        String xml;
        if (Files.exists(Paths.get(xmlPath))) {
            xml = Files.readString(Paths.get(xmlPath));
        } else {
            xmlPath = "mapper/AgentRunMapper.xml";
            xml = new String(Resources.getResourceAsStream(xmlPath).readAllBytes());
        }
        new XMLMapperBuilder(new StringReader(xml), configuration, xmlPath,
                configuration.getSqlFragments()).parse();

        xmlStatements = new HashSet<>();
        for (MappedStatement ms : configuration.getMappedStatements()) {
            xmlStatements.add(ms.getId().substring(ms.getId().lastIndexOf('.') + 1));
        }

        methodParams = new HashMap<>();
        for (Method m : AgentRunMapper.class.getDeclaredMethods()) {
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

    // ===== 两个新 statement 存在 =====

    @Test
    void listStuckAtCasStatusAnchorsStatementExists() {
        assertThat(xmlStatements).as("应包含 listStuckAtCasStatusAnchorsForDeployment")
                .contains("listStuckAtCasStatusAnchorsForDeployment");
    }

    @Test
    void promoteCasStatusToResumeReadyStatementExists() {
        assertThat(xmlStatements).as("应包含 promoteCasStatusToResumeReady")
                .contains("promoteCasStatusToResumeReady");
    }

    // ===== 命令类型 =====

    @Test
    void listStuckAtCasStatusAnchorsIsSelect() {
        String fullId = AgentRunMapper.class.getName() + ".listStuckAtCasStatusAnchorsForDeployment";
        MappedStatement ms = configuration.getMappedStatement(fullId);
        assertThat(ms.getSqlCommandType())
                .as("listStuckAtCasStatusAnchorsForDeployment 应为 SELECT")
                .isEqualTo(SqlCommandType.SELECT);
    }

    @Test
    void promoteCasStatusToResumeReadyIsUpdate() {
        String fullId = AgentRunMapper.class.getName() + ".promoteCasStatusToResumeReady";
        MappedStatement ms = configuration.getMappedStatement(fullId);
        assertThat(ms.getSqlCommandType())
                .as("promoteCasStatusToResumeReady 应为 UPDATE").isEqualTo(SqlCommandType.UPDATE);
    }

    // ===== 参数绑定一致 =====

    @Test
    void listStuckAtCasStatusAnchorsXmlParamsMatchJavaParamAnnotations() {
        checkParamsMatch("listStuckAtCasStatusAnchorsForDeployment", methodParams);
    }

    @Test
    void promoteCasStatusToResumeReadyXmlParamsMatchJavaParamAnnotations() {
        checkParamsMatch("promoteCasStatusToResumeReady", methodParams);
    }

    @Test
    void promoteCasStatus_bindsAllIdentityParams() {
        List<String> required = List.of(
                "id", "expectedOperationId", "expectedToolCallId",
                "expectedAttempt", "expectedTaskId",
                "expectedResumeLeaseVersion", "newResumeToken");
        checkParamsPresent("promoteCasStatusToResumeReady", required);
    }

    // ===== promoteCasStatusToResumeReady SQL 形状 =====

    @Test
    void promoteCasStatus_tokenHasExplicitTextCast() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        // MyBatis BoundSql 将 #{newResumeToken} 替换为 ?，所以检查 ?::text
        assertThat(sql)
                .as("newResumeToken 参数必须有 ::text 显式类型转换，避免 variadic jsonb_build_object 无法确定参数类型")
                .contains("?::text");
    }

    @Test
    void promoteCasStatus_usesCurrentTimestampNotToChar() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        assertThat(sql)
                .as("resumeClaimedAt 必须直接使用 CURRENT_TIMESTAMP，与 claimResumeLauncher 一致，不要用 to_char 序列化")
                .contains("'resumeClaimedAt', CURRENT_TIMESTAMP");
        assertThat(sql)
                .as("不应出现 to_char 序列化 resumeClaimedAt")
                .doesNotContain("to_char");
    }

    @Test
    void promoteCasStatus_whereBindsResumeLeaseVersionWithSafeNullHandling() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        // BoundSql 已将 #{expectedResumeLeaseVersion} 替换为 ?；
        // 验证 COALESCE(CASE WHEN ... IS NULL OR btrim ... THEN 0 ... ) = ? 形状存在
        assertThat(sql)
                .as("WHERE 必须包含 COALESCE 包裹的 resumeLeaseVersion 解析")
                .contains("COALESCE");
        assertThat(sql)
                .as("resumeLeaseVersion 旧值解析：null 视为 0")
                .contains("IS NULL").contains("THEN 0");
        assertThat(sql)
                .as("resumeLeaseVersion 旧值解析：空白字符串视为 0")
                .contains("btrim").contains("THEN 0");
    }

    @Test
    void promoteCasStatus_setOnlyMergesFiveRecoveryFields() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        // SET 只合并 5 个恢复字段
        assertThat(sql).contains("'resumeState', 'READY'");
        assertThat(sql).contains("'resumeToken'");
        assertThat(sql).contains("'resumeLeaseVersion'");
        assertThat(sql).contains("'resumeClaimedAt'");
        assertThat(sql).contains("'finalizerStep', 'RESUME_READY'");
        // 不能覆盖终态字段
        assertThat(sql).as("不应触碰 terminalStatus").doesNotContain("terminalStatus");
        assertThat(sql).as("不应触碰 reservationJson").doesNotContain("reservationJson");
        assertThat(sql).as("不应触碰 finalizerError").doesNotContain("finalizerError");
    }

    @Test
    void promoteCasStatus_setVersionIncrementsWithSafeParsing() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        // SET 中 leaseVersion: null/空 → 1, 纯数字在范围内 → cast+1, 否则 → -1
        assertThat(sql)
                .as("leaseVersion SET 必须处理 null/空 → 1")
                .contains("WHEN").contains("IS NULL").contains("THEN 1");
        assertThat(sql)
                .as("leaseVersion SET 必须用 ^[0-9]{1,19}$ 限制位数")
                .contains("^[0-9]{1,19}$");
        assertThat(sql)
                .as("leaseVersion SET 必须先 ::numeric range 再 ::bigint + 1")
                .contains("::numeric").contains("BETWEEN 0 AND 9223372036854775806");
    }

    @Test
    void promoteCasStatus_whereAttemptUsesSafeRegexCast() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        // WHERE 中 attempt: CASE WHEN regex THEN ::bigint ELSE -1 END = expectedAttempt
        // 不需要 ::int 或额外的 BETWEEN（range 在发现查询已过滤，Java guard 保证 expectedAttempt 合法）
        assertThat(sql)
                .as("attempt WHERE 必须用 ^[0-9]{1,10}$ 限制位数")
                .contains("^[0-9]{1,10}$");
        assertThat(sql)
                .as("attempt WHERE: CASE WHEN regex THEN ::bigint ELSE -1 END，cast 受 CASE 保护")
                .contains("CASE WHEN").contains("'attempt'").contains("::bigint").contains("ELSE -1");
    }

    // ===== listStuckAtCasStatusAnchors SQL 形状 =====

    @Test
    void discoverySql_usesBtrimForResumeStateEmptiness() {
        String sql = getStatementSql("listStuckAtCasStatusAnchorsForDeployment");
        assertThat(sql)
                .as("resumeState 空白判断必须用 btrim，不能只认长度为 0")
                .contains("btrim");
    }

    @Test
    void discoverySql_excludesMalformedIdentity() {
        String sql = getStatementSql("listStuckAtCasStatusAnchorsForDeployment");
        // operationId/toolCallId/taskId 非空 + 非纯空白
        assertThat(sql).as("operationId IS NOT NULL").contains("operationId' IS NOT NULL");
        assertThat(sql).as("toolCallId IS NOT NULL").contains("toolCallId' IS NOT NULL");
        assertThat(sql).as("taskId IS NOT NULL").contains("taskId' IS NOT NULL");
        // btrim <> '' 过滤纯空白
        assertThat(sql).as("排除空白 operationId").contains("btrim").contains("<> ''");
    }

    @Test
    void discoverySql_attemptUsesCaseProtectedCast() {
        String sql = getStatementSql("listStuckAtCasStatusAnchorsForDeployment");
        assertThat(sql)
                .as("attempt cast 必须包裹在 CASE WHEN regex THEN ::bigint ELSE -1 END 中，"
                    + "不能写成 regex AND ::bigint（PG 不保证求值顺序）")
                .contains("CASE WHEN").contains("'attempt'").contains("::bigint").contains("ELSE -1");
        assertThat(sql)
                .as("attempt 范围在 CASE 结果上做 BETWEEN，不在同一 WHEN 里搭配 AND")
                .contains("BETWEEN 1 AND 2147483647");
    }

    @Test
    void discoverySql_leaseVersionUsesCaseProtectedCast() {
        String sql = getStatementSql("listStuckAtCasStatusAnchorsForDeployment");
        assertThat(sql)
                .as("leaseVersion cast 必须包裹在 CASE WHEN regex THEN ::numeric ELSE -1 END 中")
                .contains("CASE WHEN").contains("resumeLeaseVersion' ~")
                .contains("::numeric").contains("ELSE -1");
        assertThat(sql)
                .as("leaseVersion 范围在 CASE 结果上做 BETWEEN")
                .contains("BETWEEN 0 AND 9223372036854775806");
    }

    @Test
    void promoteCasStatus_attemptWhereUsesCaseProtectedCast() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        // CASE WHEN regex THEN ::bigint ELSE -1 END，cast 在 THEN 分支内受 CASE 保护
        assertThat(sql)
                .as("promote WHERE attempt: CASE WHEN regex THEN ::bigint ELSE -1 END = expectedAttempt")
                .contains("CASE WHEN").contains("'attempt'").contains("::bigint").contains("ELSE -1");
    }

    @Test
    void promoteCasStatus_leaseVersionWhereUsesCaseProtectedCast() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        // 嵌套 CASE: 外层 WHEN regex AND 内层 CASE WHEN regex THEN ::numeric ELSE -1 END BETWEEN ...
        // 然后 THEN ::bigint。cast 全程受 CASE 保护。
        assertThat(sql)
                .as("promote WHERE leaseVersion 必须用嵌套 CASE 保护 ::numeric cast")
                .contains("CASE WHEN").contains("'resumeLeaseVersion'").contains("::numeric");
    }

    @Test
    void promoteCasStatus_setLeaseVersionUsesCaseProtectedCast() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        // SET: 外层 CASE null/blank→1, 内层 CASE WHEN regex THEN ::numeric ELSE -1 END BETWEEN range, THEN ::bigint+1
        assertThat(sql)
                .as("SET leaseVersion 必须用嵌套 CASE 保护 ::numeric，再 safe ::bigint + 1")
                .contains("CASE WHEN").contains("::numeric").contains("ELSE -1")
                .contains("BETWEEN 0 AND 9223372036854775806");
    }

    @Test
    void discoverySql_doesNotMergeIntoExistingResumeReadyLogic() {
        String sql = getStatementSql("listStuckAtCasStatusAnchorsForDeployment");
        // 这是一个独立的新查询，不能包含 READY/LAUNCHING/ACCEPTED/CONSUMED 这些既有关键词
        assertThat(sql).as("不应包含 READY（resumeState）").doesNotContain("'READY'");
        assertThat(sql).as("不应包含 LAUNCHING").doesNotContain("'LAUNCHING'");
        assertThat(sql).as("不应包含 EXECUTING 状态").doesNotContain("'EXECUTING'");
    }

    @Test
    void promoteCasStatus_whereUsesBtrimForResumeStateEmptiness() {
        String sql = getStatementSql("promoteCasStatusToResumeReady");
        assertThat(sql)
                .as("promote WHERE 的 resumeState 空白判断也必须用 btrim")
                .contains("btrim");
    }

    // ===== 辅助方法 =====

    private String getStatementSql(String methodId) {
        String fullId = AgentRunMapper.class.getName() + "." + methodId;
        assertThat(configuration.hasStatement(fullId))
                .as(methodId + " statement 应存在").isTrue();
        MappedStatement ms = configuration.getMappedStatement(fullId);
        Set<String> params = methodParams.getOrDefault(methodId, Set.of());
        Map<String, Object> paramMap = new HashMap<>();
        for (String p : params) paramMap.put(p, buildDummyValue(p));
        return ms.getSqlSource().getBoundSql(paramMap).getSql();
    }

    private void checkParamsMatch(String methodId, Map<String, Set<String>> allMethodParams) {
        String fullId = AgentRunMapper.class.getName() + "." + methodId;
        MappedStatement ms = configuration.getMappedStatement(fullId);
        Set<String> javaParams = allMethodParams.getOrDefault(methodId, Set.of());
        Map<String, Object> paramMap = new HashMap<>();
        for (String p : javaParams) paramMap.put(p, buildDummyValue(p));
        BoundSql boundSql = ms.getSqlSource().getBoundSql(paramMap);

        List<String> mismatches = new ArrayList<>();
        for (ParameterMapping pm : boundSql.getParameterMappings()) {
            String prop = pm.getProperty();
            String root = prop.contains(".") ? prop.substring(0, prop.indexOf('.')) : prop;
            if (!javaParams.contains(root)) {
                mismatches.add(methodId + ": XML 参数 '" + root + "' 不在 Java @Param 中 [" + javaParams + "]");
            }
        }
        assertThat(mismatches).as("XML #{} 参数应与 Java @Param 名称一致").isEmpty();
    }

    private void checkParamsPresent(String methodId, List<String> required) {
        String fullId = AgentRunMapper.class.getName() + "." + methodId;
        assertThat(configuration.hasStatement(fullId))
                .as(methodId + " statement 应存在").isTrue();
        MappedStatement ms = configuration.getMappedStatement(fullId);
        Set<String> javaParams = methodParams.getOrDefault(methodId, Set.of());

        Map<String, Object> paramMap = new HashMap<>();
        for (String p : javaParams) paramMap.put(p, buildDummyValue(p));
        BoundSql boundSql = ms.getSqlSource().getBoundSql(paramMap);

        Set<String> sqlParamNames = new HashSet<>();
        for (ParameterMapping pm : boundSql.getParameterMappings()) {
            String prop = pm.getProperty();
            String root = prop.contains(".") ? prop.substring(0, prop.indexOf('.')) : prop;
            sqlParamNames.add(root);
        }

        List<String> errors = new ArrayList<>();
        for (String req : required) {
            if (!javaParams.contains(req)) {
                errors.add(methodId + ": Java @Param 缺少 '" + req + "'");
            }
            if (!sqlParamNames.contains(req)) {
                errors.add(methodId + ": SQL 未使用参数 '" + req + "'（BoundSql 中不存在）");
            }
        }
        assertThat(errors).as("关键身份/CAS 参数不应缺失").isEmpty();
    }

    private Object buildDummyValue(String paramName) {
        return switch (paramName) {
            case "id" -> "dummy-id";
            case "expectedAttempt" -> 1;
            case "expectedResumeLeaseVersion" -> 0L;
            case "limit" -> 20;
            default -> "dummy";
        };
    }
}
