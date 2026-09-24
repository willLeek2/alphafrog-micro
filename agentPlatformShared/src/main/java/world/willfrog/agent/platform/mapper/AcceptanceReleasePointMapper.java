package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 验收夹具的放行点：被策略压住的成员结果，等这张表上对应的点被标成已放行才收尾。
 *
 * <p>只有读：写入方是宿主机上的受限控制面（与夹具同一套形状），Agent 进程拿不到这张表的写入能力。</p>
 *
 * <p>读法按「许可」而不是「取走令牌」：标成已放行之后每一轮读到的都是已放行，重复读没有副作用，
 * 进程在两次读之间退出也不会把这次放行弄丢。同一条成员终态仍然只写一次，由成员自己的状态机保证。</p>
 */
@Mapper
public interface AcceptanceReleasePointMapper {

    /**
     * 这个 Run 的这个放行点被标成已放行了吗。
     *
     * <p>行还没建出来、或者建出来但没标放行，都返回 0（都按「没放行」处理）。</p>
     */
    int countOpened(@Param("runId") String runId,
                    @Param("releaseKey") String releaseKey);
}
