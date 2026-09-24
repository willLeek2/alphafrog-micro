package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.childrun.ChildRunIntentRow;
import world.willfrog.agent.platform.childrun.ChildRunParentSnapshot;
import world.willfrog.agent.platform.childrun.ChildRunReserveRequest;

import java.time.OffsetDateTime;
import java.util.List;

/** 子 Run 创建意图、投递记录和根调用树名额的 PostgreSQL 操作。 */
@Mapper
public interface ChildRunIntentMapper {
    ChildRunParentSnapshot lockParentRun(@Param("runId") String runId);

    ChildRunIntentRow findByCall(@Param("request") ChildRunReserveRequest request);

    ChildRunIntentRow findByIntentId(@Param("intentId") long intentId);

    ChildRunIntentRow findByOutboxId(@Param("outboxId") long outboxId);

    ChildRunIntentRow findByChildRunId(@Param("childRunId") String childRunId);

    ChildRunIntentRow lockIntent(@Param("intentId") long intentId);

    int matchesParentWaitMember(@Param("request") ChildRunReserveRequest request);

    int parentWaitMemberStillOpen(@Param("intentId") long intentId);

    int ensureTreeCapacity(@Param("rootRunId") String rootRunId);

    int reserveTreeCapacity(@Param("rootRunId") String rootRunId, @Param("limit") int limit);

    int releaseTreeCapacity(@Param("rootRunId") String rootRunId);

    int pruneEmptyTreeCapacity(@Param("rootRunId") String rootRunId);

    Long insertIntent(@Param("request") ChildRunReserveRequest request,
                      @Param("childRunId") String childRunId,
                      @Param("operationId") String operationId,
                      @Param("parent") ChildRunParentSnapshot parent);

    Long insertOutbox(@Param("intentId") long intentId);

    Long claimDueOutbox(@Param("owner") String owner, @Param("claimToken") String claimToken,
                        @Param("now") OffsetDateTime now, @Param("leaseUntil") OffsetDateTime leaseUntil);

    int markIntentAccepted(@Param("intentId") long intentId);

    int acknowledgeOutbox(@Param("outboxId") long outboxId, @Param("claimToken") String claimToken);

    int cancelUnaccepted(@Param("intentId") long intentId);

    int cancelOutbox(@Param("intentId") long intentId);

    int requestCancellation(@Param("intentId") long intentId);

    int markChildTerminal(@Param("intentId") long intentId);

    int markPhysicalStopped(@Param("intentId") long intentId);

    int releaseIntentCapacity(@Param("intentId") long intentId);

    String rootRunIdOf(@Param("runId") String runId);

    Boolean hasUnsettledDescendants(@Param("rootRunId") String rootRunId);

    List<String> listReservedRootRunIds(@Param("afterRootRunId") String afterRootRunId,
                                        @Param("limit") int limit);

    List<ChildRunIntentRow> listAcceptedChildrenNeedingLaunch(@Param("afterIntentId") long afterIntentId,
                                                              @Param("limit") int limit);

    List<ChildRunIntentRow> listUnsettledByParent(@Param("parentRunId") String parentRunId,
                                                   @Param("afterIntentId") long afterIntentId,
                                                   @Param("limit") int limit);
}
