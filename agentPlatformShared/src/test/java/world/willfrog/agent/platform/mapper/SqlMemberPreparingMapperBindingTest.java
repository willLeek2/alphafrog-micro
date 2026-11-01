package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMemberPreparingMapperBindingTest {


    private Configuration mapper(Class<?> type, String path) throws Exception {
        Configuration config = new Configuration(); config.addMapper(type);
        try (var input = Resources.getResourceAsStream(path)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
        return config;
    }
}
