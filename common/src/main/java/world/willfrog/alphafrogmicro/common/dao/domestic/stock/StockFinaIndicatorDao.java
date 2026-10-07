package world.willfrog.alphafrogmicro.common.dao.domestic.stock;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.stock.StockFinaIndicator;

import java.util.List;

@Mapper
public interface StockFinaIndicatorDao {

    /**
     * 财务指标写入。与本批其它三张新表（转债日线/AH 比价/利润表）用
     * {@code ON CONFLICT DO NOTHING} 不同，这里用 DO UPDATE：022 迁移第 23-25 行的建表注释
     * 写明「同一报告期后到的公告覆盖前值（唯一键 ts_code + end_date，写入方
     * ON CONFLICT DO UPDATE）」，且该表按设计不挂 {@code updated_at} 触发器
     * （022 第 19-20 行），更新时间只由这里的 SET 子句维护——用 DO NOTHING 两处都会落空。
     * DO UPDATE 的 SET 只覆盖本次上报的列：接口未返回的键在 JSONB 里是缺的，
     * 整列覆盖会把上一轮已抓到的值清掉，所以按「非空才更新」逐列处理不了 JSONB 内部，
     * 只能整列覆盖——这与「后到的公告覆盖前值」的语义一致（后到的公告本身就是完整的一行）。
     */
    @Insert("INSERT INTO alphafrog_stock_fina_indicator (ts_code, ann_date, end_date, " +
            "profitability, per_share, capital_cash, growth, extended) " +
            "VALUES (#{tsCode}, #{annDate}, #{endDate}, " +
            "#{profitability}::jsonb, #{perShare}::jsonb, #{capitalCash}::jsonb, " +
            "#{growth}::jsonb, #{extended}::jsonb) " +
            "ON CONFLICT (ts_code, end_date) DO UPDATE SET " +
            "ann_date = EXCLUDED.ann_date, " +
            "profitability = EXCLUDED.profitability, " +
            "per_share = EXCLUDED.per_share, " +
            "capital_cash = EXCLUDED.capital_cash, " +
            "growth = EXCLUDED.growth, " +
            "extended = EXCLUDED.extended, " +
            "updated_at = CURRENT_TIMESTAMP")
    int insertStockFinaIndicator(StockFinaIndicator finaIndicator);

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
