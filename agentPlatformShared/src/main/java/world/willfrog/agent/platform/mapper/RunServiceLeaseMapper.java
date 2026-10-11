package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.lease.RunServiceLease;

import java.time.OffsetDateTime;
import java.util.List;

/** 服务所有权租约的语句。领取、续期、让出都自带条件，返回的行数与行就是事实。 */
public interface RunServiceLeaseMapper {

    /** 领取或续上；拿不到时返回空。 */
    RunServiceLease acquire(@Param("runId") String runId,
                            @Param("owner") String owner,
                            @Param("ttlMillis") long ttlMillis);

    /** 续期；返回影响行数。 */
    int renew(@Param("runId") String runId,
              @Param("owner") String owner,
              @Param("fencingToken") long fencingToken,
              @Param("ttlMillis") long ttlMillis);

    /** 这一位持有、且对应 Run 还在跑的租约一起续；返回影响行数。 */
    int renewOwned(@Param("owner") String owner,
                   @Param("ttlMillis") long ttlMillis);

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
