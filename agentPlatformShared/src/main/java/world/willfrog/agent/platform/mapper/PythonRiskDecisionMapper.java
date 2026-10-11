package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.dataanalysis.PythonRiskDecisionRow;

/** 按沙箱持久操作号保存一次调用的审查决定。 */
@Mapper
public interface PythonRiskDecisionMapper {
    PythonRiskDecisionRow findByOperation(@Param("operationId") String operationId);

    int insertIfAbsent(@Param("operationId") String operationId,
                       @Param("runId") String runId,
                       @Param("decision") String decision,
                       @Param("riskScore") Integer riskScore,
                       @Param("configJson") String configJson);
}
