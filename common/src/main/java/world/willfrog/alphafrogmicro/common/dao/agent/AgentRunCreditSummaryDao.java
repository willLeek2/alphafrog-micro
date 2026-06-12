package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunCreditSummary;

import java.math.BigDecimal;

@Mapper
public interface AgentRunCreditSummaryDao {

    @Insert("INSERT INTO alphafrog_agent_run_credit_summary (" +
            "run_id, user_id, total_credit_consumed, immediate_credit_consumed, delayed_credit_consumed, " +
            "currency, settlement_status, idempotency_key, ext, last_settlement_at" +
            ") VALUES (" +
            "#{runId}, #{userId}, #{totalCreditConsumed}, #{immediateCreditConsumed}, #{delayedCreditConsumed}, " +
            "#{currency}, #{settlementStatus}, #{idempotencyKey}, CAST(#{ext} AS jsonb), #{lastSettlementAt}" +
            ") ON CONFLICT (run_id) DO UPDATE SET " +
            "total_credit_consumed = EXCLUDED.total_credit_consumed, " +
            "immediate_credit_consumed = EXCLUDED.immediate_credit_consumed, " +
            "delayed_credit_consumed = EXCLUDED.delayed_credit_consumed, " +
            "currency = EXCLUDED.currency, " +
            "settlement_status = EXCLUDED.settlement_status, " +
            "idempotency_key = EXCLUDED.idempotency_key, " +
            "ext = EXCLUDED.ext, " +
            "last_settlement_at = EXCLUDED.last_settlement_at, " +
            "updated_at = CURRENT_TIMESTAMP")
    int upsert(AgentRunCreditSummary summary);

    @Select("SELECT * FROM alphafrog_agent_run_credit_summary WHERE run_id = #{runId} LIMIT 1")
    @Results(id = "runCreditSummaryResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "runId", column = "run_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "totalCreditConsumed", column = "total_credit_consumed"),
            @Result(property = "immediateCreditConsumed", column = "immediate_credit_consumed"),
            @Result(property = "delayedCreditConsumed", column = "delayed_credit_consumed"),
            @Result(property = "currency", column = "currency"),
            @Result(property = "settlementStatus", column = "settlement_status"),
            @Result(property = "idempotencyKey", column = "idempotency_key"),
            @Result(property = "ext", column = "ext"),
            @Result(property = "lastSettlementAt", column = "last_settlement_at"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    AgentRunCreditSummary findByRunId(@Param("runId") String runId);

    @Select("SELECT * FROM alphafrog_agent_run_credit_summary WHERE run_id = #{runId} AND user_id = #{userId} LIMIT 1")
    @ResultMap("runCreditSummaryResultMap")
    AgentRunCreditSummary findByRunIdAndUserId(@Param("runId") String runId,
                                               @Param("userId") String userId);

    @Select("SELECT COALESCE(SUM(total_credit_consumed), 0) " +
            "FROM alphafrog_agent_run_credit_summary WHERE user_id = #{userId}")
    BigDecimal sumByUserId(@Param("userId") String userId);
}
