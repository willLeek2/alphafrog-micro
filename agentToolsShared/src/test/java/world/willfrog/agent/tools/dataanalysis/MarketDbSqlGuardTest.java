package world.willfrog.agent.tools.dataanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDbSqlGuardTest {

    private static final String RANKING_SQL = """
            SELECT i.ts_code, BTRIM(i.name) AS name, AVG(d.amount) AS average_amount
            FROM alphafrog_index_daily d
            JOIN alphafrog_index_info i ON i.ts_code = d.ts_code
            WHERE d.trade_date BETWEEN 1 AND 2
            GROUP BY i.ts_code, BTRIM(i.name)
            ORDER BY average_amount DESC
            LIMIT 100
            """;

    @Test
    void rankingQueryKeepsLimitWhenItFitsTheCap() {
        MarketDbSqlGuard.Prepared prepared = MarketDbSqlGuard.prepare(RANKING_SQL, 100);
        assertEquals(100, prepared.appliedLimit());
        assertFalse(prepared.capped());
        assertTrue(prepared.sql().toLowerCase().contains("limit 100"));
        assertFalse(prepared.sql().toLowerCase().contains("limit 1000"));
    }

    @Test
    void largerLimitIsRewrittenToTheTierCap() {
        MarketDbSqlGuard.Prepared prepared = MarketDbSqlGuard.prepare(
                "SELECT ts_code, amount FROM alphafrog_index_daily LIMIT 100000", 100);
        assertEquals(100, prepared.appliedLimit());
        assertTrue(prepared.capped());
        assertTrue(prepared.sql().toLowerCase().contains("limit 100"));
        assertFalse(prepared.sql().contains("100000"));
    }

    @Test
    void missingLimitReceivesTheTierCap() {
        MarketDbSqlGuard.Prepared prepared = MarketDbSqlGuard.prepare(
                "SELECT ts_code FROM alphafrog_index_info", 100);
        assertEquals(100, prepared.appliedLimit());
        assertTrue(prepared.capped());
    }

    @Test
    void secondStatementIsRejectedBeforeAnyExecution() {
        MarketSqlRejected rejected = assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT ts_code FROM alphafrog_index_info; DROP TABLE alphafrog_index_info", 100));
        assertTrue(rejected.getMessage().contains("一条"));
    }

    @Test
    void tableOutsideTheAllowlistIsRejectedWithTheCurrentList() {
        MarketSqlRejected rejected = assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT ts_code FROM alphafrog_stock_daily", 100));
        assertTrue(rejected.getMessage().contains("alphafrog_index_daily"));
        assertTrue(rejected.getMessage().contains("alphafrog_index_info"));
    }

    @Test
    void guessedColumnAndStarAreRejected() {
        assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT close FROM alphafrog_index_daily", 100));
        assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT * FROM alphafrog_index_daily", 100));
    }

    @Test
    void schemaQualifiedForbiddenFunctionsAreRejected() {
        MarketSqlRejected sleep = assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT pg_catalog.pg_sleep(1) FROM alphafrog_index_daily", 100));
        assertTrue(sleep.getMessage().contains("pg_sleep"));
        MarketSqlRejected readFile = assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT pg_catalog.pg_read_file('x') FROM alphafrog_index_daily", 100));
        assertTrue(readFile.getMessage().contains("pg_read_file"));
    }

    @Test
    void columnInsideExpressionSubqueryIsRejected() {
        MarketSqlRejected rejected = assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT amount FROM alphafrog_index_daily WHERE trade_date IN "
                        + "(SELECT trade_date FROM alphafrog_index_daily WHERE close > 100)",
                100));
        assertTrue(rejected.getMessage().contains("close"));
    }

    @Test
    void fetchFirstIsRejected() {
        MarketSqlRejected rejected = assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT amount FROM alphafrog_index_daily FETCH FIRST 10000 ROWS ONLY", 100));
        assertTrue(rejected.getMessage().contains("LIMIT"));
    }

    @Test
    void schemaOtherThanPublicIsRejected() {
        assertThrows(MarketSqlRejected.class, () -> MarketDbSqlGuard.prepare(
                "SELECT ts_code FROM secret.alphafrog_index_info", 100));
    }

    @Test
    void adminSnapshotUsesTheCreationFlagOnly() {
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(MarketDbRunAccess.isAdminSnapshot("{\"is_admin\":true}", mapper));
        assertTrue(MarketDbRunAccess.isAdminSnapshot("{\"isAdmin\":1}", mapper));
        assertFalse(MarketDbRunAccess.isAdminSnapshot("{\"is_admin\":false}", mapper));
        assertFalse(MarketDbRunAccess.isAdminSnapshot("{}", mapper));
        assertFalse(MarketDbRunAccess.isAdminSnapshot(null, mapper));
    }

    @Test
    void executorSetsReadOnlyAndTimeoutOnTheSameConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("ts_code");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(new BigDecimal("1.50"));

        MarketDbQueryExecutor.Rows rows = new MarketDbQueryExecutor()
                .execute(dataSource, "SELECT ts_code FROM alphafrog_index_info LIMIT 100", 30, 100);

        InOrder order = inOrder(connection);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setAutoCommit(false);
        verify(statement).execute("SET LOCAL statement_timeout = '30s'");
        verify(statement).setQueryTimeout(30);
        verify(connection).commit();
        verify(connection).setReadOnly(false);
        verify(connection).setAutoCommit(true);
        assertEquals(List.of("ts_code"), rows.columns());
        assertEquals(1.5d, rows.rows().get(0).get(0));
    }
}
