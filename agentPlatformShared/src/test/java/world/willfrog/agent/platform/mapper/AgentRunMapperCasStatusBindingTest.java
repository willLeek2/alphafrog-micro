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



    // ===== 命令类型 =====



    // ===== 参数绑定一致 =====




    // ===== promoteCasStatusToResumeReady SQL 形状 =====







    // ===== listStuckAtCasStatusAnchors SQL 形状 =====










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
