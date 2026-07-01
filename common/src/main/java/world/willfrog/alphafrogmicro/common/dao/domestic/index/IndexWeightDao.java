package world.willfrog.alphafrogmicro.common.dao.domestic.index;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;

import java.util.List;
import java.util.Map;

@Mapper
public interface IndexWeightDao {

    @Insert("INSERT INTO alphafrog_index_weight (index_code, con_code, trade_date, weight) " +
            "VALUES (#{indexCode}, #{conCode}, #{tradeDate}, #{weight}) " +
            "ON CONFLICT (index_code, con_code, trade_date) DO NOTHING")
    int insertIndexWeight(IndexWeight indexWeight);

    @Select("SELECT * FROM alphafrog_index_weight WHERE index_code = #{tsCode} AND trade_date BETWEEN #{startDate} AND #{endDate} ORDER BY trade_date DESC")
    @Results({
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "conCode", column = "con_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "weight", column = "weight")
    })
    List<IndexWeight> getIndexWeightsByTsCodeAndDateRange(@Param("tsCode") String tsCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT DISTINCT ON (con_code) * FROM alphafrog_index_weight WHERE index_code = #{tsCode} AND trade_date BETWEEN #{startDate} AND #{endDate} ORDER BY con_code, trade_date DESC")
    @Results({
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "conCode", column = "con_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "weight", column = "weight")
    })
    List<IndexWeight> getLatestIndexWeightsByTsCodeAndDateRange(@Param("tsCode") String tsCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT * FROM alphafrog_index_weight WHERE index_code = #{tsCode} AND trade_date = #{tradeDate}")
    @Results({
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "conCode", column = "con_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "weight", column = "weight")
    })
    List<IndexWeight> getIndexWeightsByTsCodeAndTradeDate(@Param("tsCode") String tsCode, @Param("tradeDate") long tradeDate);

    @Select("SELECT MAX(trade_date) FROM alphafrog_index_weight WHERE index_code = #{tsCode} AND trade_date BETWEEN #{startDate} AND #{endDate}")
    Long getMaxTradeDateByTsCode(@Param("tsCode") String tsCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT * FROM alphafrog_index_weight WHERE con_code = #{conCode} AND trade_date BETWEEN #{startDate} AND #{endDate} ORDER BY trade_date DESC")
    List<IndexWeight> getIndexWeightsByConCodeAndDateRange(@Param("conCode") String conCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT DISTINCT ON (index_code) * FROM alphafrog_index_weight WHERE con_code = #{conCode} AND trade_date BETWEEN #{startDate} AND #{endDate} ORDER BY index_code, trade_date DESC")
    @Results({
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "conCode", column = "con_code"),
            @Result(property = "tradeDate", column = "trade_date"),
            @Result(property = "weight", column = "weight")
    })
    List<IndexWeight> getLatestIndexWeightsByConCodeAndDateRange(@Param("conCode") String conCode, @Param("startDate") long startDate, @Param("endDate") long endDate);

    @Select("SELECT latest.con_code AS ts_code, COALESCE(s.name, latest.con_code) AS name " +
            "FROM (" +
            "  SELECT DISTINCT ON (con_code) con_code, trade_date " +
            "  FROM alphafrog_index_weight " +
            "  WHERE index_code = #{tsCode} " +
            "    AND trade_date BETWEEN #{startDate} AND #{endDate} " +
            "    AND con_code IS NOT NULL " +
            "  ORDER BY con_code, trade_date DESC" +
            ") latest " +
            "LEFT JOIN alphafrog_stock_info s ON s.ts_code = latest.con_code " +
            "ORDER BY random() " +
            "LIMIT #{limit}")
    List<Map<String, Object>> getRandomConstituentStocksByIndexAndDateRange(@Param("tsCode") String tsCode,
                                                                            @Param("startDate") long startDate,
                                                                            @Param("endDate") long endDate,
                                                                            @Param("limit") int limit);

}
