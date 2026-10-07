package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StkAh;

import java.util.List;

@Mapper
public interface StkAhDao {

    @Insert({
            "INSERT INTO alphafrog_stk_ah (ts_code, hk_code, trade_date, close, hk_close, pct_chg, hk_pct_chg," +
                    " ah_comparison, ah_premium) " +
                    "VALUES (#{tsCode}, #{hkCode}, #{tradeDate}, #{close}, #{hkClose}, #{pctChg}, #{hkPctChg}," +
                    " #{ahComparison}, #{ahPremium})" +
                    "ON CONFLICT(ts_code, trade_date) DO NOTHING"
    })
    int insertStkAh(StkAh stkAh);

    @Select("SELECT * FROM alphafrog_stk_ah WHERE ts_code = #{tsCode} " +
            "AND trade_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "stkAhId", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "hkCode", column = "hk_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "close", column = "close"),
            @Result(property = "hkClose", column = "hk_close"),
            @Result(property = "pctChg", column = "pct_chg"),
            @Result(property = "hkPctChg", column = "hk_pct_chg"),
            @Result(property = "ahComparison", column = "ah_comparison"),
            @Result(property = "ahPremium", column = "ah_premium")
    })
    List<StkAh> getByTsCodeAndDateRange(@Param("tsCode") String tsCode,
                                        @Param("startDate") long startDate,
                                        @Param("endDate") long endDate);
}
