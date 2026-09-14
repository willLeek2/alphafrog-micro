package world.willfrog.alphafrogmicro.common.dao.agent;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditRecharge;

@Mapper
public interface AgentCreditRechargeDao {

    @Insert("INSERT INTO alphafrog_agent_credit_recharge (" +
            "recharge_id, ledger_id, user_id, username, operator_id, currency, original_amount, " +
            "exchange_rate_to_usd, credit_amount, reason, idempotency_key, ext" +
            ") VALUES (" +
            "#{rechargeId}, #{ledgerId}, #{userId}, #{username}, #{operatorId}, #{currency}, #{originalAmount}, " +
            "#{exchangeRateToUsd}, #{creditAmount}, #{reason}, #{idempotencyKey}, CAST(#{ext} AS jsonb)" +
            ") ON CONFLICT (operator_id, idempotency_key) DO NOTHING")
    int insertIgnoreDuplicate(AgentCreditRecharge recharge);

    @Select("SELECT * FROM alphafrog_agent_credit_recharge " +
            "WHERE operator_id = #{operatorId} AND idempotency_key = #{idempotencyKey} LIMIT 1")
    @Results(id = "creditRechargeResultMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "rechargeId", column = "recharge_id"),
            @Result(property = "ledgerId", column = "ledger_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "username", column = "username"),
            @Result(property = "operatorId", column = "operator_id"),
            @Result(property = "currency", column = "currency"),
            @Result(property = "originalAmount", column = "original_amount"),
            @Result(property = "exchangeRateToUsd", column = "exchange_rate_to_usd"),
            @Result(property = "creditAmount", column = "credit_amount"),
            @Result(property = "reason", column = "reason"),
            @Result(property = "idempotencyKey", column = "idempotency_key"),
            @Result(property = "ext", column = "ext"),
            @Result(property = "createdAt", column = "created_at")
    })
    AgentCreditRecharge findByOperatorAndIdempotencyKey(@Param("operatorId") String operatorId,
                                                        @Param("idempotencyKey") String idempotencyKey);
}
