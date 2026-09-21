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

    /** 某个 Run 上第一条指定类型的事件；没有就返回空。接收事实只有一条，按它补投。 */
    AgentRunEvent findFirstByRunIdAndType(@Param("runId") String runId,
                                          @Param("eventType") String eventType);

    /**
     * 保留期内的接收事实，按时间倒序取最新一页：刚失败的那一条要在一轮之内补上。
     *
     * <p>带上部署身份：修的只应该是本部署自己写的行，不替别的部署往两边补事件。</p>
     */
    List<AgentRunEvent> listReceivedFactsForRepair(@Param("deploymentId") String deploymentId,
                                                   @Param("deploymentGenerationId") String deploymentGenerationId,
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
            @Param("deploymentGenerationId") String deploymentGenerationId,
            @Param("createdSince") java.time.OffsetDateTime createdSince,
            @Param("cursorCreatedAt") java.time.OffsetDateTime cursorCreatedAt,
            @Param("cursorId") long cursorId,
            @Param("limit") int limit);

    List<AgentRunEvent> listLatestByRunId(@Param("runId") String runId,
                                          @Param("limit") int limit);

    List<AgentRunEvent> listByRunId(@Param("runId") String runId);

    List<String> listRunIdsWithExecutePythonArtifacts(@Param("runIds") List<String> runIds);
}
