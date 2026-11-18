package world.willfrog.agent.tools.dataanalysis;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * 在已有主库连接上执行一条已检查过的 SELECT。
 * 同一条连接、同一个只读事务里设置语句超时，归还连接前恢复自动提交。
 */
public final class MarketDbQueryExecutor {

    public record Rows(List<String> columns, List<List<Object>> rows) {}

    public Rows execute(DataSource dataSource, String sql, int timeoutSeconds, int appliedLimit) {
        try (Connection connection = dataSource.getConnection()) {
            boolean committed = false;
            try {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL statement_timeout = '" + timeoutSeconds + "s'");
                    statement.setQueryTimeout(timeoutSeconds);
                    statement.setMaxRows(appliedLimit);
                    try (ResultSet resultSet = statement.executeQuery(sql)) {
                        Rows rows = read(resultSet, appliedLimit);
                        connection.commit();
                        committed = true;
                        return rows;
                    }
                }
            } catch (SQLException failure) {
                throw map(failure);
            } finally {
                if (!committed) {
                    rollback(connection);
                }
                restore(connection);
            }
        } catch (SQLException openFailure) {
            throw map(openFailure);
        }
    }

    private static Rows read(ResultSet resultSet, int appliedLimit) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            columns.add(metaData.getColumnLabel(index));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() < appliedLimit) {
            List<Object> row = new ArrayList<>();
            for (int index = 1; index <= metaData.getColumnCount(); index++) {
                row.add(normalize(resultSet.getObject(index)));
            }
            rows.add(row);
        }
        return new Rows(columns, rows);
    }

    static Object normalize(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof byte[]) {
            return null;
        }
        return value;
    }

    private static RuntimeException map(SQLException failure) {
        String state = failure.getSQLState() == null ? "" : failure.getSQLState();
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
        if ("57014".equals(state) || message.contains("statement timeout") || message.contains("query timeout")) {
            return new MarketDbTimeoutException();
        }
        return new MarketDbQueryException(failure.getMessage() == null ? "行情库查询失败" : failure.getMessage());
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 归还前仍会恢复自动提交。
        }
    }

    private static void restore(Connection connection) {
        try {
            connection.setReadOnly(false);
        } catch (SQLException ignored) {
            // 继续恢复自动提交。
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接关闭时由连接池丢弃异常连接。
        }
    }

    public static final class MarketDbTimeoutException extends RuntimeException {
    }

    public static final class MarketDbQueryException extends RuntimeException {
        public MarketDbQueryException(String message) {
            super(message);
        }
    }
}
