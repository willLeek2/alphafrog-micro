package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunLlmCallCredit;

import java.util.List;

@Mapper
public interface AgentRunLlmCallCreditDao {

    @Insert("INSERT INTO alphafrog_agent_run_llm_call_credit (" +
            "record_id, run_id, user_id, llm_call_id, endpoint_name, model_name, cost_source, currency, " +
            "cost_amount, credit_delta, settlement_status, settlement_attempt, reason, idempotency_key, ext" +
            ") VALUES (" +
            "#{recordId}, #{runId}, #{userId}, #{llmCallId}, #{endpointName}, #{modelName}, #{costSource}, #{currency}, " +
            "#{costAmount}, #{creditDelta}, #{settlementStatus}, #{settlementAttempt}, #{reason}, #{idempotencyKey}, CAST(#{ext} AS jsonb)" +
            ") ON CONFLICT (idempotency_key) DO NOTHING")
    int insertIgnoreDuplicate(AgentRunLlmCallCredit record);

    @Select("SELECT * FROM alphafrog_agent_run_llm_call_credit WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    @Results(id = "llmCallCreditResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "recordId", column = "record_id"),
            @Result(property = "runId", column = "run_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "llmCallId", column = "llm_call_id"),
            @Result(property = "endpointName", column = "endpoint_name"),
            @Result(property = "modelName", column = "model_name"),
            @Result(property = "costSource", column = "cost_source"),
            @Result(property = "currency", column = "currency"),
            @Result(property = "costAmount", column = "cost_amount"),
            @Result(property = "creditDelta", column = "credit_delta"),
            @Result(property = "settlementStatus", column = "settlement_status"),
            @Result(property = "settlementAttempt", column = "settlement_attempt"),
            @Result(property = "reason", column = "reason"),
            @Result(property = "idempotencyKey", column = "idempotency_key"),
            @Result(property = "ext", column = "ext"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "expiresAt", column = "expires_at")
    })
    AgentRunLlmCallCredit findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM alphafrog_agent_run_llm_call_credit WHERE run_id = #{runId} ORDER BY created_at ASC")
    @ResultMap("llmCallCreditResultMap")
    List<AgentRunLlmCallCredit> listByRunId(@Param("runId") String runId);

    @Delete("DELETE FROM alphafrog_agent_run_llm_call_credit WHERE expires_at < CURRENT_TIMESTAMP")
    int deleteExpired();
}
