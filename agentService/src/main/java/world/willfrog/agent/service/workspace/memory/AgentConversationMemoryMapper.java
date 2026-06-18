package world.willfrog.agent.service.workspace.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Agent 多轮对话 memory Mapper。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>memory CRUD：insert / findById / updateStatus</li>
 *   <li>查询：按 user + scope 拉 active memory；按 source_run_id 反查</li>
 *   <li>演化：supersede(oldId, newId) 把旧 memory 标记为 superseded 并记录取代关系</li>
 * </ul>
 *
 * @author wang
 */
@Mapper
public interface AgentConversationMemoryMapper {

    /**
     * 插入一条 memory 记录。
     *
     * <p>id 由数据库 BIGSERIAL 自动生成，回填到入参对象。
     *
     * @param memory memory 实体
     * @return 影响行数
     */
    int insert(AgentConversationMemory memory);

    /**
     * 按主键查询 memory。
     *
     * @param id memory 主键
     * @return memory 实体；不存在返回 null
     */
    AgentConversationMemory findById(@Param("id") Long id);

    /**
     * 按 (user_id, conversation_scope, status, verification_status 列表) 查 memory。
     *
     * <p>verificationStatuses 传 null 时不附加该条件。
     *
     * @param userId              用户 ID
     * @param conversationScope   会话 scope
     * @param status              状态过滤（active / superseded / deleted），null 时不过滤
     * @param verificationStatuses 验证状态白名单，null 或空时不过滤
     * @param limit               返回条数上限
     * @return memory 列表，按 created_at DESC
     */
    List<AgentConversationMemory> listByUserAndScope(
            @Param("userId") String userId,
            @Param("conversationScope") String conversationScope,
            @Param("status") String status,
            @Param("verificationStatuses") List<String> verificationStatuses,
            @Param("limit") int limit
    );

    /**
     * 按 source_run_id 查 memory。
     *
     * <p>用于从 run 反向追溯它生成了哪些 memory。
     *
     * @param sourceRunId  来源 run ID
     * @return memory 列表，按 created_at ASC
     */
    List<AgentConversationMemory> listBySourceRunId(@Param("sourceRunId") String sourceRunId);

    /**
     * 更新 memory 状态（active / superseded / deleted）。
     *
     * @param id     memory 主键
     * @param status 新状态
     * @return 影响行数
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 把旧 memory 标记为 superseded，并记录取代关系（supersedes_memory_id）。
     *
     * <p>使用 COALESCE 避免覆盖已有的 supersedes_memory_id 字段。
     *
     * @param oldId 被取代的 memory 主键
     * @param newId 取代者的 memory 主键
     * @return 影响行数
     */
    int supersede(@Param("oldId") Long oldId, @Param("newId") Long newId);
}
