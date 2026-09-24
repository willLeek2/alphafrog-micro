package world.willfrog.agentlangchain.control.dualpool;

import java.util.List;

/**
 * 从持久父子关系读取一条 Run 所属的根调用树及其收尾状态。
 *
 * <p>实现必须在查询失败、关系缺失或关系不一致时抛出异常。把未知子 Run 当作根 Run
 * 会让它额外占用业务名额，并可能绕过同一调用树的容量限制。</p>
 */
public interface RootRunResolver {

    String rootRunId(String runId);

    /** 根 Run 已终态时，是否还有未受理的创建意图、子 Run 或外部任务待收尾。 */
    boolean hasUnsettledDescendants(String rootRunId);

    /** 按根编号升序分页读取仍有子工作额度预留的根；after 为空时从头读取。 */
    List<String> listReservedRootRunIds(String afterRootRunId, int limit);

    /** 只供不建立父子关系的旧版单元测试使用；生产环境必须注入持久实现。 */
    static RootRunResolver identityForTests() {
        return new RootRunResolver() {
            @Override
            public String rootRunId(String runId) {
                return runId;
            }

            @Override
            public boolean hasUnsettledDescendants(String rootRunId) {
                return false;
            }

            @Override
            public List<String> listReservedRootRunIds(String afterRootRunId, int limit) {
                return List.of();
            }
        };
    }
}
