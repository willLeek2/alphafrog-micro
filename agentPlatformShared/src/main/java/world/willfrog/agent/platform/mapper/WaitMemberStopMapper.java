package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.wait.WaitMemberStopTask;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface WaitMemberStopMapper {
    Long claimDue(@Param("deploymentId") String deploymentId,
                  @Param("deploymentGenerationId") String deploymentGenerationId,
                  @Param("owner") String owner, @Param("claimToken") String claimToken,
                  @Param("now") OffsetDateTime now, @Param("leaseUntil") OffsetDateTime leaseUntil);

    WaitMemberStopTask findById(@Param("stopId") long stopId);

    WaitMemberStopTask findByWaitMemberId(@Param("waitMemberId") long waitMemberId);

    int retry(@Param("stopId") long stopId, @Param("claimToken") String claimToken,
              @Param("nextAttemptAt") OffsetDateTime nextAttemptAt, @Param("reason") String reason);

    int blockProof(@Param("stopId") long stopId, @Param("claimToken") String claimToken,
                   @Param("reason") String reason);

    int confirmSandboxTerminal(@Param("stopId") long stopId, @Param("claimToken") String claimToken,
                               @Param("taskId") String taskId, @Param("terminalStatus") String terminalStatus);

    List<WaitMemberStopTask> listUnconfirmedByRun(@Param("runId") String runId,
                                                   @Param("afterStopId") long afterStopId,
                                                   @Param("limit") int limit);

    boolean hasUnconfirmedByRootRunId(@Param("rootRunId") String rootRunId);
}
