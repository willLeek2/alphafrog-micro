package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.finance.FinanceMethodResolution;

import java.util.List;

@Mapper
public interface FinanceMethodResolutionMapper {
    int insertIgnore(FinanceMethodResolution resolution);

    FinanceMethodResolution findExact(
            @Param("runId") String runId,
            @Param("resolverToolCallId") String resolverToolCallId,
            @Param("methodId") String methodId,
            @Param("methodVersion") String methodVersion,
            @Param("specDigest") String specDigest);

    List<FinanceMethodResolution> listByRun(@Param("runId") String runId);
}
