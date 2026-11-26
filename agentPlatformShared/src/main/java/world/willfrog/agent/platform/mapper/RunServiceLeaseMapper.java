package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.ServiceOwnershipScope;

import java.time.OffsetDateTime;
import java.util.List;

/** 服务所有权租约的语句。领取、续期、让出都自带条件，返回的行数与行就是事实。 */
public interface RunServiceLeaseMapper {

    /** 领取或续上；拿不到时返回空。 */
    RunServiceLease acquire(@Param("runId") String runId,
                            @Param("owner") String owner,
                            @Param("ttlMillis") long ttlMillis);

    /**
     * 创建时到期时间已过，且部署、代际、泳道与本进程一致时，换持有人并把代际号加一。
     * 别人的服务所有权还没到期也写得进去。当前持有人必须仍是 expectedOwner。拿不到时返回空。
     */
    RunServiceLease acquirePastDeadline(@Param("runId") String runId,
                                        @Param("owner") String owner,
                                        @Param("ttlMillis") long ttlMillis,
                                        @Param("scope") ServiceOwnershipScope scope,
                                        @Param("expectedOwner") String expectedOwner);

    /** 续期；部署、代际、泳道也对不上时影响行数为 0。 */
    int renew(@Param("runId") String runId,
              @Param("owner") String owner,
              @Param("fencingToken") long fencingToken,
              @Param("ttlMillis") long ttlMillis,
              @Param("scope") ServiceOwnershipScope scope);

    /** 这一位持有、运行还在进行、且部署代际泳道一致的服务所有权一起续；返回影响行数。 */
    int renewOwned(@Param("owner") String owner,
                   @Param("ttlMillis") long ttlMillis,
                   @Param("scope") ServiceOwnershipScope scope);

    /** 这一位持有、且对应 Run 还在跑的租约，按取得时间升序取有界一页。 */
    List<RunServiceLease> listOwnedWithLiveRun(@Param("owner") String owner,
                                               @Param("limit") int limit);

    /** 让出；返回影响行数。 */
    int release(@Param("runId") String runId,
                @Param("owner") String owner,
                @Param("fencingToken") long fencingToken);

    RunServiceLease find(@Param("runId") String runId);

    List<RunServiceLease> listExpiredWithLiveRun(@Param("now") OffsetDateTime now,
                                                 @Param("limit") int limit);
}
