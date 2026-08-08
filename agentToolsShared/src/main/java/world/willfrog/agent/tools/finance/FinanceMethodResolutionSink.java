package world.willfrog.agent.tools.finance;

import java.util.List;

/**
 * 解析快照批量原子保存接口。实现由 agentPlatformShared 工作包 E 提供（JDBC）。
 *
 * <p>保存失败时必须抛出 {@link FinanceMethodResolutionSinkException}，使工具调用作为普通工具失败返回，
 * 不生成 adviceDurable/persisted 等字段，也不阻止后续 executePython 调用。</p>
 */
public interface FinanceMethodResolutionSink {

    /**
     * 整批原子保存解析快照。全成功或全失败。
     *
     * @param snapshots 同一 resolver 工具调用产生的全部建议快照
     * @throws FinanceMethodResolutionSinkException 保存失败时抛出
     */
    void saveAll(List<FinanceMethodResolutionSnapshot> snapshots);
}
