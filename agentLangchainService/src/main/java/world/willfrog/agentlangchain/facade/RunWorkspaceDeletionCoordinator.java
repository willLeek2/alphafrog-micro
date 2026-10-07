package world.willfrog.agentlangchain.facade;

import java.util.List;

/**
 * Agent 数据库删除事务之间的沙箱协调接口。实现方必须逐个 Run 确认：持久封口、
 * 只读查找工作区，以及查到的工作区已删除；任一步无法确认就抛异常。
 */
public interface RunWorkspaceDeletionCoordinator {
    void sealFindAndDelete(List<String> runIds);
}
