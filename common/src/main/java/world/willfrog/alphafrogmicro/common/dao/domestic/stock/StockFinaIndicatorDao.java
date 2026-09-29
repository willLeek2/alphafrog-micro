package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockFinaIndicator;

import java.util.List;

@Mapper
public interface StockFinaIndicatorDao {

    @Select("SELECT * FROM alphafrog_stock_fina_indicator WHERE ts_code = #{tsCode} " +
            "AND end_date BETWEEN #{startDate} AND #{endDate}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "annDate", column = "ann_date"),
            @Result(property = "endDate", column = "end_date"),
            @Result(property = "profitability", column = "profitability"),
            @Result(property = "perShare", column = "per_share"),
            @Result(property = "capitalCash", column = "capital_cash"),
            @Result(property = "growth", column = "growth"),
            @Result(property = "extended", column = "extended")
    })
    List<StockFinaIndicator> getByTsCodeAndEndDateRange(@Param("tsCode") String tsCode,
                                                        @Param("startDate") long startDate,
                                                        @Param("endDate") long endDate);
}
