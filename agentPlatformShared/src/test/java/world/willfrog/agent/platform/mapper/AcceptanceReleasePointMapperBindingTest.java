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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AcceptanceReleasePointMapper.xml 的 host-only 合同验证：不连 PostgreSQL，只验证 MyBatis 能解析、
 * 参数绑定对得上、这条语句的形状就是「只读地按 Run + 放行点问一次」。
 *
 * <p>这张表的写入方是宿主机上的受限控制面，Agent 进程只有这一条读语句，所以这里盯两件事：整份 XML
 * 里不许出现任何写入；读法必须是「只认标了放行时刻的行」，行还没建、建了没标都按没放行处理。</p>
 */
class AcceptanceReleasePointMapperBindingTest {

    private static final String NAMESPACE = AcceptanceReleasePointMapper.class.getName();
    private static final String XML_NAME = "AcceptanceReleasePointMapper.xml";

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
        configuration.addMapper(AcceptanceReleasePointMapper.class);
        new XMLMapperBuilder(new StringReader(xml), configuration, XML_NAME,
                configuration.getSqlFragments()).parse();

        xmlStatements = new HashSet<>();
        for (MappedStatement ms : configuration.getMappedStatements()) {
            xmlStatements.add(ms.getId().substring(ms.getId().lastIndexOf('.') + 1));
        }

        methodParams = new HashMap<>();
        for (Method method : AcceptanceReleasePointMapper.class.getDeclaredMethods()) {
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

    private static String readXml() throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), "src/main/resources/mapper/" + XML_NAME);
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        path = Paths.get(System.getProperty("user.dir"),
                "agentPlatformShared/src/main/resources/mapper/" + XML_NAME);
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        return new String(Resources.getResourceAsStream("mapper/" + XML_NAME).readAllBytes());
    }

    @Test
    void interfaceAndXmlStatementsMatch() {
        assertThat(xmlStatements).as("接口方法与 XML 语句一一对应")
                .containsExactlyInAnyOrderElementsOf(methodParams.keySet());
    }

    @Test
    void everyBoundParameterHasASource() {
        List<String> problems = new ArrayList<>();
        for (String id : xmlStatements) {
            Set<String> declared = methodParams.getOrDefault(id, Set.of());
            Set<String> used = boundParamNames(configuration.getMappedStatement(NAMESPACE + "." + id),
                    dummyParams(id));
            for (String property : used) {
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
        for (Method method : AcceptanceReleasePointMapper.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getAnnotation(Param.class) == null) {
                    problems.add(method.getName() + " 的参数 " + parameter.getName() + " 没有 @Param");
                }
            }
        }
        assertThat(problems).as("所有参数都要有 @Param，避免按名字取参时对不上").isEmpty();
    }

    /** Agent 这一侧只有读：写入能力整个不在这个进程里。 */
    @Test
    void theMapperNeverWritesAnything() {
        String upper = xml.toUpperCase();
        assertThat(upper).as("这张表的写入方是宿主机上的受限控制面")
                .doesNotContain("INSERT")
                .doesNotContain("UPDATE ")
                .doesNotContain("DELETE");
    }

    /** 放行判断只认标了放行时刻的行，并按 Run + 放行点精确命中。 */
    @Test
    void openingIsReadAsAPermissionForOneRunAndOnePoint() {
        String sql = sql("countOpened");
        assertThat(sql).contains("SELECT COUNT(*)")
                .contains("FROM alphafrog_agent_run_release_point")
                .contains("run_id = ?")
                .contains("release_key = ?")
                .contains("opened_at IS NOT NULL");
        assertThat(sql).as("标成已放行之后每一轮读到的都是已放行，重复读没有副作用")
                .doesNotContain("FOR UPDATE");
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
            params.put(name, switch (name) {
                case "runId" -> "run-1";
                case "releaseKey" -> "point-a";
                default -> null;
            });
        }
        return params;
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
