package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetOperationRow;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetSnapshotRow;

import java.util.List;

/** 根调用树额度的 PostgreSQL 原子操作。 */
@Mapper
public interface RootTreeBudgetMapper {
    String lockRun(@Param("runId") String runId);

    List<String> listUntrackedActivity(@Param("limit") int limit);

    Boolean hasUnreleasedActiveNodesByRun(@Param("runId") String runId);

    int ensureRoot(@Param("rootRunId") String rootRunId);

    RootTreeBudgetSnapshotRow lockRoot(@Param("rootRunId") String rootRunId);

    RootTreeBudgetSnapshotRow snapshot(@Param("rootRunId") String rootRunId);

    RootTreeBudgetOperationRow operation(@Param("operationId") String operationId);

    int tryIncrement(@Param("rootRunId") String rootRunId,
                     @Param("kind") String kind, @Param("limit") long limit);

    int decrement(@Param("rootRunId") String rootRunId, @Param("kind") String kind);

    int insertOperation(@Param("operationId") String operationId,
                        @Param("rootRunId") String rootRunId, @Param("kind") String kind);

    int updateState(@Param("operationId") String operationId,
                    @Param("expected") String expected, @Param("next") String next);
}
