package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import org.springframework.cache.annotation.Cacheable;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockDaily;

import java.util.List;

@Mapper
public interface StockQuoteDao {

    @Insert({
            "INSERT INTO alphafrog_stock_daily (ts_code, trade_date, close, open, high, low, pre_close, change, pct_chg, vol, amount) " +
                    "VALUES (#{tsCode}, #{tradeDate}, #{close}, #{open}, #{high}, #{low}, #{preClose}, #{change}," +
                    " #{pctChg}, #{vol}, #{amount})" +
                    "ON CONFLICT(ts_code, trade_date) DO NOTHING"
    })
    int insertStockDaily(StockDaily stockDaily);

    // 每日指标写入：行不存在则写入骨架行（行情列留空），行已存在只更新估值与股本换手两列
    @Insert({
            "INSERT INTO alphafrog_stock_daily (ts_code, trade_date, valuation, share_turnover) " +
                    "VALUES (#{tsCode}, #{tradeDate}, #{valuation}::jsonb, #{shareTurnover}::jsonb) " +
                    "ON CONFLICT(ts_code, trade_date) DO UPDATE SET " +
                    "valuation = EXCLUDED.valuation, share_turnover = EXCLUDED.share_turnover"
    })
    int insertDailyBasicOnConflictUpdate(StockDaily stockDaily);

    // 复权因子写入：行不存在则写入骨架行，行已存在只更新复权因子列
    @Insert({
            "INSERT INTO alphafrog_stock_daily (ts_code, trade_date, adj_factor) " +
                    "VALUES (#{tsCode}, #{tradeDate}, #{adjFactor}) " +
                    "ON CONFLICT(ts_code, trade_date) DO UPDATE SET " +
                    "adj_factor = EXCLUDED.adj_factor"
    })
    int insertAdjFactorOnConflictUpdate(StockDaily stockDaily);

    // 日线任务补齐骨架行行情列：插入语句保持「已有行跳过」，同一批次追加本更新
    @Update({
            "UPDATE alphafrog_stock_daily SET close = #{close}, open = #{open}, high = #{high}, low = #{low}," +
                    " pre_close = #{preClose}, change = #{change}, pct_chg = #{pctChg}, vol = #{vol}, amount = #{amount}" +
                    " WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}"
    })
    int updateStockDailyQuoteColumns(StockDaily stockDaily);

    @Select("SELECT * FROM alphafrog_stock_daily WHERE ts_code = #{tsCode} AND trade_date between #{startDate} and #{endDate}")
    @Results({
            @Result(property = "stockDailyId", column = "id", id = true),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "close", column = "close"),
            @Result(property = "open", column = "open"),
            @Result(property = "high", column = "high"),
            @Result(property = "low", column = "low"),
            @Result(property = "preClose", column = "pre_close"),
            @Result(property = "change", column = "change"),
            @Result(property = "pctChg", column = "pct_chg"),
            @Result(property = "vol", column = "vol"),
            @Result(property = "amount", column = "amount")
    })
    @Cacheable(value = "stockDailyCache", key = "'domestic:stock_daily:' + #tsCode + ':' + #startDate + ':' + #endDate", cacheManager = "stockDailyCacheManager")
    List<StockDaily> getStockDailyByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                                       @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT * FROM alphafrog_stock_daily WHERE trade_date = #{tradeDateTimestamp}")
    List<StockDaily> getStockDailyByTradeDate(@Param("tradeDateTimestamp") long tradeDateTimestamp);
}
