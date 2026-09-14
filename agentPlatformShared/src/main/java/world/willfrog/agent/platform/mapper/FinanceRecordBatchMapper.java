package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.finance.FinanceRecordBatch;

@Mapper
public interface FinanceRecordBatchMapper {
    int insertIgnore(FinanceRecordBatch batch);

    FinanceRecordBatch findByIdentity(
            @Param("runId") String runId,
            @Param("todoId") String todoId,
            @Param("executePythonToolCallId") String executePythonToolCallId);
}
