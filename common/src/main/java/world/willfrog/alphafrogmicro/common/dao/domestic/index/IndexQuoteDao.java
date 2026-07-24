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

    @Select("SELECT sample.ts_code AS ts_code, BTRIM(i.name) AS name, BTRIM(i.fullname) AS full_name " +
            "FROM (" +
            "  SELECT DISTINCT ts_code " +
            "  FROM alphafrog_index_daily " +
            "  WHERE trade_date BETWEEN #{startDate} AND #{endDate} " +
            "    AND amount >= #{minAmount} " +
            "    AND ts_code IS NOT NULL" +
            ") sample " +
            "JOIN alphafrog_index_info i ON i.ts_code = sample.ts_code " +
            "WHERE NULLIF(BTRIM(i.name), '') IS NOT NULL " +
            "  AND NULLIF(BTRIM(i.fullname), '') IS NOT NULL " +
            "  AND BTRIM(i.name) ~ '[一-龥]' " +
            "  AND BTRIM(i.fullname) ~ '[一-龥]' " +
            "ORDER BY random() " +
            "LIMIT #{limit}")
    List<Map<String, Object>> getRandomIndexNamesByAmountRange(@Param("startDate") Long startDate,
                                                               @Param("endDate") Long endDate,
                                                               @Param("minAmount") Double minAmount,
                                                               @Param("limit") int limit);
}
