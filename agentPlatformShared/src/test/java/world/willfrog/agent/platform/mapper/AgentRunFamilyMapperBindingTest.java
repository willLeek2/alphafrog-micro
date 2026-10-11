package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunFamilyMapperBindingTest {
    @Test
    void childControlAndTreeDeletionStatementsBind() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(AgentRunMapper.class);
        Path path = Path.of("src/main/resources/mapper/AgentRunMapper.xml");
        if (!Files.exists(path)) {
            path = Path.of("agentPlatformShared/src/main/resources/mapper/AgentRunMapper.xml");
        }
        new XMLMapperBuilder(new StringReader(Files.readString(path)), configuration,
                "AgentRunMapper.xml", configuration.getSqlFragments()).parse();

        String prefix = AgentRunMapper.class.getName() + ".";
        for (String statement : new String[]{"findByIdAndUserForUpdate", "isChildRun",
                "listChildRunsByRootForUpdate", "deleteChildIntentsByRoot",
                "deleteEmptyTreeCapacityByRoot"}) {
            assertThat(configuration.hasStatement(prefix + statement)).isTrue();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("userId", "u");
        params.put("limit", 20);
        params.put("offset", 0);
        BoundSql list = configuration.getMappedStatement(prefix + "listByUser").getBoundSql(params);
        BoundSql count = configuration.getMappedStatement(prefix + "countByUser").getBoundSql(params);
        assertThat(list.getSql()).contains("run_row.ext").contains("child_run");
        assertThat(count.getSql()).contains("run_row.ext").contains("child_run");
    }
}
