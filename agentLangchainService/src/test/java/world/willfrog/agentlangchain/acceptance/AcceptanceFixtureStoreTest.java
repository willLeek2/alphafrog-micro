package world.willfrog.agentlangchain.acceptance;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 夹具读取与使用登记：列名、类型与「只有一行被改到才算用上」这三件事。
 *
 * <p>这里的库是替身，验的是映射关系（列名写错、类型取错会当场露馅），不是 SQL 在真库上的行为；
 * 真库上验的是迁移 014 的表结构与约束。</p>
 */
class AcceptanceFixtureStoreTest {

    private static final String LANE = "lane-beta";
    private static final String GENERATION = "gen-1";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AcceptanceFixtureStore store = new AcceptanceFixtureStore(jdbcTemplate);



}
