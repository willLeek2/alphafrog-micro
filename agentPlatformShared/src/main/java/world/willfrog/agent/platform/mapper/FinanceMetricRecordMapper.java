package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.finance.FinanceMetricRecord;

import java.util.List;

@Mapper
public interface FinanceMetricRecordMapper {
    int insertIgnore(FinanceMetricRecord record);

    FinanceMetricRecord findByIdentity(
            @Param("runId") String runId,
            @Param("todoId") String todoId,
            @Param("executePythonToolCallId") String executePythonToolCallId,
            @Param("recordIndex") int recordIndex,
            @Param("rawDigest") String rawDigest);

    List<FinanceMetricRecord> listByRunRenderable(@Param("runId") String runId);

    List<FinanceMetricRecord> listByBatch(
            @Param("runId") String runId,
            @Param("todoId") String todoId,
            @Param("executePythonToolCallId") String executePythonToolCallId);
}
