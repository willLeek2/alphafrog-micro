package world.willfrog.alphafrogmicro.common.datasource;

/**
 * 核对行情只读连接指向生产库 {@code alphafrog}，拒绝指到 beta 库或非 PostgreSQL URL。
 *
 * <p>beta 实例跨机读 101 生产库时，host 由 {@code AF_DB_MARKET_HOST} 注入；本类不写死 IP，
 * 只锁定协议、库名与 host 非空。</p>
 */
public final class MarketReadConnectionTarget {

    public static final String REQUIRED_DATABASE = "alphafrog";
    public static final String JDBC_PREFIX = "jdbc:postgresql://";

    private MarketReadConnectionTarget() {
    }

    public static void verify(String jdbcUrl) {
        Parsed parsed = parse(jdbcUrl);
        if (!REQUIRED_DATABASE.equals(parsed.database())) {
            throw new IllegalStateException(
                    "market-read jdbc url must target database '" + REQUIRED_DATABASE
                            + "', got '" + parsed.database() + "'");
        }
        if (parsed.host().isEmpty()) {
            throw new IllegalStateException("market-read jdbc url host must not be empty");
        }
    }

    public static Parsed parse(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("market-read jdbc url is required");
        }
        String trimmed = jdbcUrl.trim();
        if (!trimmed.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
            throw new IllegalStateException("market-read jdbc url must start with " + JDBC_PREFIX);
        }
        String rest = trimmed.substring(JDBC_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalStateException("market-read jdbc url must include a database name");
        }
        String hostPort = rest.substring(0, slash);
        String dbAndQuery = rest.substring(slash + 1);
        if (dbAndQuery.isEmpty()) {
            throw new IllegalStateException("market-read jdbc url must include a database name");
        }
        int queryAt = indexOfQuery(dbAndQuery);
        String database = queryAt < 0 ? dbAndQuery : dbAndQuery.substring(0, queryAt);
        if (database.isEmpty() || database.contains("/")) {
            throw new IllegalStateException("market-read jdbc url database name is invalid: " + database);
        }
        String host = extractHost(hostPort);
        return new Parsed(host, database, trimmed);
    }

    private static int indexOfQuery(String dbAndQuery) {
        int q = dbAndQuery.indexOf('?');
        int s = dbAndQuery.indexOf(';');
        if (q < 0) {
            return s;
        }
        if (s < 0) {
            return q;
        }
        return Math.min(q, s);
    }

    private static String extractHost(String hostPort) {
        String value = hostPort;
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                throw new IllegalStateException("market-read jdbc url has invalid IPv6 host");
            }
            return value.substring(1, end);
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0) {
            return value.substring(0, colon);
        }
        return value;
    }

    public record Parsed(String host, String database, String jdbcUrl) {
    }
}
