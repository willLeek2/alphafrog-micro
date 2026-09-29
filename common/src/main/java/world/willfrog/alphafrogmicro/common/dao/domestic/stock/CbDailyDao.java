package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.CbDaily;

import java.util.List;

@Mapper
public interface CbDailyDao {

    @Insert({
            "INSERT INTO alphafrog_cb_daily (ts_code, trade_date, pre_close, open, high, low, close, change," +
                    " pct_chg, vol, amount, premium) " +
                    "VALUES (#{tsCode}, #{tradeDate}, #{preClose}, #{open}, #{high}, #{low}, #{close}, #{change}," +
                    " #{pctChg}, #{vol}, #{amount}, #{premium}::jsonb)" +
                    "ON CONFLICT(ts_code, trade_date) DO NOTHING"
    })
    int insertCbDaily(CbDaily cbDaily);

    @Select("SELECT * FROM alphafrog_cb_daily WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "cbDailyId", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "preClose", column = "pre_close"),
            @Result(property = "open", column = "open"),
            @Result(property = "high", column = "high"),
            @Result(property = "low", column = "low"),
            @Result(property = "close", column = "close"),
            @Result(property = "change", column = "change"),
            @Result(property = "pctChg", column = "pct_chg"),
            @Result(property = "vol", column = "vol"),
            @Result(property = "amount", column = "amount"),
            @Result(property = "premium", column = "premium")
    })
    List<CbDaily> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                          @Param("startDate") long startDate,
                                          @Param("endDate") long endDate);
}
