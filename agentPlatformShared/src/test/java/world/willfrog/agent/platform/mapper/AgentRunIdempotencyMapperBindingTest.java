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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run 主记录上幂等两列的 SQL 合同：读回语句存在、参数对得上、插入语句确实带上这两列。
 */
class AgentRunIdempotencyMapperBindingTest {

    private static final String NAMESPACE = AgentRunMapper.class.getName();

    private Configuration configuration;
    private String xml;

    @BeforeEach
    void setUp() throws Exception {
        xml = readXml();
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(AgentRunMapper.class);
        new XMLMapperBuilder(new StringReader(xml), configuration, "AgentRunMapper.xml",
                configuration.getSqlFragments()).parse();
    }

    private static String readXml() throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"),
                "src/main/resources/mapper/AgentRunMapper.xml");
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        path = Paths.get(System.getProperty("user.dir"),
                "agentPlatformShared/src/main/resources/mapper/AgentRunMapper.xml");
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        return new String(Resources.getResourceAsStream("mapper/AgentRunMapper.xml").readAllBytes());
    }

    @Test
    void lookupStatementExistsAndIsBoundToUserAndKey() {
        MappedStatement ms = configuration.getMappedStatement(NAMESPACE + ".findByUserIdempotencyKey");
        BoundSql boundSql = ms.getSqlSource().getBoundSql(Map.of("userId", "u-1", "idempotencyKey", "k-1"));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();
        assertThat(sql).contains("WHERE user_id = ?")
                .contains("AND idempotency_key = ?")
                .as("没有键的行不参与读回，否则会把用户的普通提交错当成命中")
                .contains("AND idempotency_key IS NOT NULL");
        Set<String> names = new LinkedHashSet<>();
        for (ParameterMapping mapping : boundSql.getParameterMappings()) {
            names.add(mapping.getProperty());
        }
        assertThat(names).containsExactlyInAnyOrder("userId", "idempotencyKey");
    }

    @Test
    void lookupMethodIsAnnotatedWithParam() {
        for (Method method : AgentRunMapper.class.getDeclaredMethods()) {
            if (!method.getName().equals("findByUserIdempotencyKey")) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.getAnnotation(Param.class))
                        .as("按名字取参必须带 @Param").isNotNull();
            }
        }
    }

    @Test
    void insertCarriesBothIdempotencyColumns() {
        MappedStatement ms = configuration.getMappedStatement(NAMESPACE + ".insert");
        BoundSql boundSql = ms.getSqlSource().getBoundSql(null);
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();
        assertThat(sql).contains("idempotency_key").contains("request_digest")
                .contains("request_digest, plan_generation");
    }

    @Test
    void resultMapCoversBothIdempotencyColumns() {
        List<String> mapped = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<result property=\"(\\w+)\" column=\"(\\w+)\"/>").matcher(xml);
        while (matcher.find()) {
            mapped.add(matcher.group(2));
        }
        assertThat(mapped).contains("idempotency_key", "request_digest");
    }
}
