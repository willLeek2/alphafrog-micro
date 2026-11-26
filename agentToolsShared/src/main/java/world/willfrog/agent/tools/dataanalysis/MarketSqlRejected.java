package world.willfrog.agent.tools.dataanalysis;

/** 行情库 SQL 在送到数据库之前被拒绝。 */
public class MarketSqlRejected extends RuntimeException {

    public MarketSqlRejected(String message) {
        super(message);
    }
}
