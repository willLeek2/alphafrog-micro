package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** 校验到期标记与删盘确认两处 SQL 都有异步责任栅栏。 */
class PythonWorkspaceRetentionSqlTest {




    private static Configuration mapper(Class<?> type, String path) throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(type);
        try (InputStream input = Resources.getResourceAsStream(path)) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String bound(Configuration configuration, Class<?> type, String id) {
        return configuration.getMappedStatement(type.getName() + '.' + id)
                .getBoundSql(Map.of("id", "run-1", "runId", "run-1")).getSql();
    }
}
