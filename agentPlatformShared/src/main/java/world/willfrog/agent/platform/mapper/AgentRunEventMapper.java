package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.entity.AgentRunEvent;

import java.util.List;

@Mapper
public interface AgentRunEventMapper {

    int insert(AgentRunEvent event);

    int insertOnce(AgentRunEvent event);

    AgentRunEvent findByRunIdAndDedupeKey(@Param("runId") String runId,
                                          @Param("dedupeKey") String dedupeKey);

    List<AgentRunEvent> listByRunIdAfterSeq(@Param("runId") String runId,
                                           @Param("afterSeq") int afterSeq,
                                           @Param("limit") int limit);

    Integer findMaxSeq(@Param("runId") String runId);

    AgentRunEvent findLatestByRunId(@Param("runId") String runId);

    /**
     * 按（Run，序号）读回那一条事件；没有就返回空。
     *
     * <p>写进去之后要拿库里的那一行当投射来源：插入不写 {@code created_at}（用库自己的当前时间），
     * 负载又存成 jsonb，读回来的文本与 Java 对象里的字符串不保证一样。事件流的成员里带着这两样，
     * 按 Java 对象投一次、按库里的行再投一次，同一个事件就会变成两个成员。</p>
     */
    AgentRunEvent findByRunIdAndSeq(@Param("runId") String runId, @Param("seq") int seq);

    /** 某个 Run 上第一条指定类型的事件；没有就返回空。接收事实只有一条，按它补投。 */
    AgentRunEvent findFirstByRunIdAndType(@Param("runId") String runId,
                                          @Param("eventType") String eventType);

    /**
     * 保留期内的接收事实，按时间倒序取最新一页：刚失败的那一条要在一轮之内补上。
     *
     * <p>只按部署收窄，不按构建代际：滚动替换时旧实例可能在「库里已提交、事件流还没投」的窗口里
     * 退场。按当前代际过滤会把旧代际的缺口排除在外，而写下那条事实的进程已经不在了——那道缺口
     * 再也没人补。滚动重叠是真实存在的部署边界，所以窗口按部署取，代际不是过滤条件。</p>
     */
    List<AgentRunEvent> listReceivedFactsForRepair(@Param("deploymentId") String deploymentId,
                                                   @Param("createdSince") java.time.OffsetDateTime createdSince,
                                                   @Param("limit") int limit);

    /**
     * 从游标位置往后取一页接收事实，按时间升序。
     *
     * <p>游标是 {@code (created_at, id)}：只按时间会在同一毫秒的多行上原地打转，带上编号才能
     * 稳定地往前走。整条语句就是「保留期内、本部署、这一页」，不做整表扫描。</p>
     */
    List<AgentRunEvent> listReceivedFactsAfterCursor(
            @Param("deploymentId") String deploymentId,
            @Param("createdSince") java.time.OffsetDateTime createdSince,
            @Param("cursorCreatedAt") java.time.OffsetDateTime cursorCreatedAt,
            @Param("cursorId") long cursorId,
            @Param("limit") int limit);

    List<AgentRunEvent> listLatestByRunId(@Param("runId") String runId,
                                          @Param("limit") int limit);

    List<AgentRunEvent> listByRunId(@Param("runId") String runId);

    List<String> listRunIdsWithExecutePythonArtifacts(@Param("runIds") List<String> runIds);
}
