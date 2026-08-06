package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexDaily;

import java.util.List;
import java.util.Map;

public interface IndexQuoteDao {
    @Insert("INSERT INTO alphafrog_index_daily (ts_code, trade_date, close, open, high, low, pre_close, change, pct_chg, vol, amount) " +
            "VALUES (#{tsCode}, #{tradeDate,jdbcType=BIGINT}, #{close}, #{open}, #{high}, #{low}, #{preClose}, #{change}, #{pctChg}, #{vol}, #{amount})" +
            "ON CONFLICT (ts_code, trade_date) DO NOTHING")
    int insertIndexDaily(IndexDaily indexDaily);

    @Select("SELECT trade_date FROM alphafrog_index_daily " +
            "WHERE ts_code = #{tsCode} AND trade_date BETWEEN #{startDate} AND #{endDate} " +
            "ORDER BY trade_date")
    List<Long> getExistingTradeDates(@Param("tsCode") String tsCode,
                                     @Param("startDate") Long startDate,
                                     @Param("endDate") Long endDate);

    @Select("SELECT * FROM alphafrog_index_daily WHERE ts_code = #{tsCode} AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(column = "ts_code", property = "tsCode"),
            @Result(column = "trade_date", property = "tradeDate"),
            @Result(column = "close", property = "close"),
            @Result(column = "open", property = "open"),
            @Result(column = "high", property = "high"),
            @Result(column = "low", property = "low"),
            @Result(column = "pre_close", property = "preClose"),
            @Result(column = "change", property = "change"),
            @Result(column = "pct_chg", property = "pctChg"),
            @Result(column = "vol", property = "vol"),
            @Result(column = "amount", property = "amount")
    })
    List<IndexDaily> getIndexDailiesByTsCodeAndDateRange(@Param("tsCode") String tsCode, @Param("startDate") Long startDate, @Param("endDate") Long endDate);

    @Select("SELECT EXISTS(SELECT 1 FROM alphafrog_index_daily WHERE ts_code = #{tsCode} LIMIT 1)")
    boolean hasAnyIndexDaily(@Param("tsCode") String tsCode);

    @Select("WITH candidates AS (" +
            "  SELECT i.ts_code, BTRIM(i.name) AS name, BTRIM(i.fullname) AS full_name " +
            "  FROM alphafrog_index_info i " +
            "  WHERE i.ts_code IS NOT NULL " +
            "    AND NULLIF(BTRIM(i.name), '') IS NOT NULL " +
            "    AND NULLIF(BTRIM(i.fullname), '') IS NOT NULL " +
            "    AND BTRIM(i.name) ~ '[一-龥]' " +
            "    AND BTRIM(i.fullname) ~ '[一-龥]' " +
            "  ORDER BY random() " +
            "  LIMIT #{candidateLimit}" +
            ") " +
            "SELECT c.ts_code AS ts_code, c.name AS name, c.full_name AS full_name, " +
            "       COUNT(*) AS daily_count, AVG(d.amount) AS average_amount " +
            "FROM candidates c " +
            "JOIN alphafrog_index_daily d ON d.ts_code = c.ts_code " +
            "WHERE d.trade_date BETWEEN #{startDate} AND #{endDate} " +
            "GROUP BY c.ts_code, c.name, c.full_name " +
            "HAVING COUNT(*) >= #{requiredDailyCount} " +
            "   AND (CAST(#{minAverageAmount} AS DOUBLE PRECISION) IS NULL " +
            "        OR AVG(d.amount) >= CAST(#{minAverageAmount} AS DOUBLE PRECISION)) " +
            "ORDER BY random()")
    List<Map<String, Object>> getEligibleRandomIndices(
            @Param("startDate") Long startDate,
            @Param("endDate") Long endDate,
            @Param("requiredDailyCount") int requiredDailyCount,
            @Param("minAverageAmount") Double minAverageAmount,
            @Param("candidateLimit") int candidateLimit);
}
