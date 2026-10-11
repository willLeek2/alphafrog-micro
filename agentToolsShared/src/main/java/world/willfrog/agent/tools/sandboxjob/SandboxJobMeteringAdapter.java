package world.willfrog.agent.tools.sandboxjob;

import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;

/**
 * 计量适配器：把终态结果里的资源用量映射成容量账本要的用量模型。
 *
 * <p>用量字段必须与容量账本的校验口径精确匹配（必填字段缺失会让终态信封校验失败），
 * 新工具要么发出同构用量、要么在这里做映射。</p>
 */
public interface SandboxJobMeteringAdapter {

    /** 从终态视图提取资源用量；取不到必填字段时返回 null，由框架按缺失处理。 */
    DataAnalysisResourceUsage toUsage(SandboxTerminalResultView result, DataAnalysisResourceClass resourceClass);
}
