package world.willfrog.alphafrogmicro.common.dao.domestic.etf;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.domestic.etf.EtfInfo;

import java.util.List;
import java.util.Map;

@Mapper
public interface EtfInfoDao {

    @Insert("INSERT INTO alphafrog_domestic_etf (ts_code, name, full_name, exchange, mgr_name, list_status, " +
            "etf_type, index_code, index_name, list_date, setup_date, extended) " +
            "VALUES (#{tsCode}, #{name}, #{fullName}, #{exchange}, #{mgrName}, #{listStatus}, " +
            "#{etfType}, #{indexCode}, #{indexName}, #{listDate}, #{setupDate}, CAST(#{extended} AS jsonb)) " +
            "ON CONFLICT (ts_code) DO NOTHING")
    int insertEtfInfo(EtfInfo etfInfo);

    @Select("SELECT * FROM alphafrog_domestic_etf WHERE ts_code = #{tsCode}")
    @Results(id = "etfInfoMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "tsCode", column = "ts_code"),
            @Result(property = "name", column = "name"),
            @Result(property = "fullName", column = "full_name"),
            @Result(property = "exchange", column = "exchange"),
            @Result(property = "mgrName", column = "mgr_name"),
            @Result(property = "listStatus", column = "list_status"),
            @Result(property = "etfType", column = "etf_type"),
            @Result(property = "indexCode", column = "index_code"),
            @Result(property = "indexName", column = "index_name"),
            @Result(property = "listDate", column = "list_date"),
            @Result(property = "setupDate", column = "setup_date"),
            @Result(property = "extended", column = "extended")
    })
    EtfInfo getByTsCode(@Param("tsCode") String tsCode);

    @Select("SELECT * FROM alphafrog_domestic_etf WHERE name ILIKE CONCAT('%', #{keyword}, '%') " +
            "OR ts_code ILIKE CONCAT('%', #{keyword}, '%') LIMIT #{limit}")
    @ResultMap("etfInfoMap")
    List<EtfInfo> searchByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    @Select("SELECT * FROM alphafrog_domestic_etf WHERE index_code = #{indexCode} LIMIT #{limit}")
    @ResultMap("etfInfoMap")
    List<EtfInfo> getByIndexCode(@Param("indexCode") String indexCode, @Param("limit") int limit);

    @Select("SELECT count(*) FROM alphafrog_domestic_etf")
    int getEtfInfoCount();

    @Select("SELECT * FROM alphafrog_domestic_etf ORDER BY ts_code LIMIT #{limit} OFFSET #{offset}")
    @ResultMap("etfInfoMap")
    List<EtfInfo> getAllEtfInfo(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT ts_code FROM alphafrog_domestic_etf ORDER BY ts_code LIMIT #{limit} OFFSET #{offset}")
    List<String> getEtfTsCodes(@Param("offset") int offset, @Param("limit") int limit);

    @Select("WITH candidates AS (" +
            "  SELECT etf.ts_code, BTRIM(etf.name) AS name " +
            "  FROM alphafrog_domestic_etf etf " +
            "  WHERE etf.list_status = 'L' " +
            "    AND etf.ts_code IS NOT NULL " +
            "    AND NULLIF(BTRIM(etf.name), '') IS NOT NULL " +
            "  ORDER BY random() " +
            "  LIMIT #{candidateLimit}" +
            ") " +
            "SELECT c.ts_code AS ts_code, c.name AS name, " +
            "       COUNT(*) AS daily_count, AVG(d.amount) AS average_amount " +
            "FROM candidates c " +
            "JOIN alphafrog_domestic_etf_daily d ON d.ts_code = c.ts_code " +
            "WHERE d.trade_date BETWEEN #{startDate} AND #{endDate} " +
            "GROUP BY c.ts_code, c.name " +
            "HAVING COUNT(*) >= #{requiredDailyCount} " +
            "   AND (CAST(#{minAverageAmount} AS DOUBLE PRECISION) IS NULL " +
            "        OR AVG(d.amount) >= CAST(#{minAverageAmount} AS DOUBLE PRECISION)) " +
            "ORDER BY random()")
    List<Map<String, Object>> getEligibleRandomEtfs(
            @Param("startDate") Long startDate,
            @Param("endDate") Long endDate,
            @Param("requiredDailyCount") int requiredDailyCount,
            @Param("minAverageAmount") Double minAverageAmount,
            @Param("candidateLimit") int candidateLimit);
}
