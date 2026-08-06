package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.*;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditLedger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AgentCreditLedgerDao {

    @Insert("INSERT INTO alphafrog_agent_credit_ledger (" +
            "ledger_id, user_id, biz_type, delta, balance_before, balance_after, source_type, source_id, operator_id, idempotency_key, reason, ext" +
            ") VALUES (" +
            "#{ledgerId}, #{userId}, #{bizType}, #{delta}, #{balanceBefore}, #{balanceAfter}, #{sourceType}, #{sourceId}, #{operatorId}, #{idempotencyKey}, #{reason}, CAST(#{ext} AS jsonb)" +
            ") ON CONFLICT (biz_type, source_id, idempotency_key) DO NOTHING")
    int insertIgnoreDuplicate(AgentCreditLedger ledger);

    @Select("SELECT * FROM alphafrog_agent_credit_ledger " +
            "WHERE biz_type = #{bizType} AND source_id = #{sourceId} AND idempotency_key = #{idempotencyKey} " +
            "LIMIT 1")
    @ResultMap("creditLedgerResultMap")
    AgentCreditLedger findByBizSourceIdempotency(@Param("bizType") String bizType,
                                                 @Param("sourceId") String sourceId,
                                                 @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT balance_after FROM alphafrog_agent_credit_ledger " +
            "WHERE user_id = #{userId} ORDER BY created_at DESC, id DESC LIMIT 1")
    BigDecimal latestBalanceByUserId(@Param("userId") String userId);

    @Select("<script>" +
            "SELECT * FROM alphafrog_agent_credit_ledger " +
            "<where>" +
            "<if test='userId != null and userId != \"\"'> AND user_id = #{userId}</if>" +
            "<if test='bizType != null and bizType != \"\"'> AND biz_type = #{bizType}</if>" +
            "<if test='sourceId != null and sourceId != \"\"'> AND source_id = #{sourceId}</if>" +
            "<if test='fromTime != null'> AND created_at <![CDATA[>=]]> #{fromTime}</if>" +
            "<if test='toTime != null'> AND created_at <![CDATA[<=]]> #{toTime}</if>" +
            "</where>" +
            "ORDER BY created_at DESC " +
            "LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    @Results(id = "creditLedgerResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "ledgerId", column = "ledger_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "bizType", column = "biz_type"),
            @Result(property = "delta", column = "delta"),
            @Result(property = "balanceBefore", column = "balance_before"),
            @Result(property = "balanceAfter", column = "balance_after"),
            @Result(property = "sourceType", column = "source_type"),
            @Result(property = "sourceId", column = "source_id"),
            @Result(property = "operatorId", column = "operator_id"),
            @Result(property = "idempotencyKey", column = "idempotency_key"),
            @Result(property = "reason", column = "reason"),
            @Result(property = "ext", column = "ext"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<AgentCreditLedger> list(@Param("userId") String userId,
                                 @Param("bizType") String bizType,
                                 @Param("sourceId") String sourceId,
                                 @Param("fromTime") OffsetDateTime fromTime,
                                 @Param("toTime") OffsetDateTime toTime,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM alphafrog_agent_credit_ledger " +
            "<where>" +
            "<if test='userId != null and userId != \"\"'> AND user_id = #{userId}</if>" +
            "<if test='bizType != null and bizType != \"\"'> AND biz_type = #{bizType}</if>" +
            "<if test='sourceId != null and sourceId != \"\"'> AND source_id = #{sourceId}</if>" +
            "<if test='fromTime != null'> AND created_at <![CDATA[>=]]> #{fromTime}</if>" +
            "<if test='toTime != null'> AND created_at <![CDATA[<=]]> #{toTime}</if>" +
            "</where>" +
            "</script>")
    int count(@Param("userId") String userId,
              @Param("bizType") String bizType,
              @Param("sourceId") String sourceId,
              @Param("fromTime") OffsetDateTime fromTime,
              @Param("toTime") OffsetDateTime toTime);
}
