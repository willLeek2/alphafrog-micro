package world.willfrog.agentlangchain.execution;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;

/**
 * 新调度器的子代理工具入口。实现方用持久化的父子 Run 关系处理创建与等待，
 * 不能把请求交给父进程内的旧子代理任务。
 */
public interface PersistentSubAgentToolBridge {

    /** 当前 Run 的子代理入口是否开启；子 Run 和关闭开关的 Run 必须返回 false。 */
    boolean availableForRun(String runId);

    /** 持久化子 Run 的身份；旧调度器普通父 Run 的工具目录不能受此接口的 V2 开关影响。 */
    boolean isChildRun(String runId);

    /**
     * 在父等待组挂起的同一数据库事务中预留子 Run 创建意图。
     * 相同操作身份重复调用必须返回同一决定。基础设施失败应抛异常，使整组挂起回滚。
     */
    ReservationOutcome reserveSpawn(ReservationRequest request);

    /**
     * 在父等待组挂起的同一数据库事务中持久化待等待的子 Run 顺序和绝对截止时间。
     * 相同操作身份重复调用必须返回同一决定。
     */
    ReservationOutcome reserveWait(ReservationRequest request);

    /**
     * 在预留事务提交后派发或恢复一次调用。创建工具只能读取已经落库的意图；
     * 找不到意图时必须失败闭合，不能临时创建另一个子 Run。
     */
    NodeToolDispatcher.DispatchOutcome dispatch(NodeToolDispatcher.DispatchRequest request);

    enum ReservationOutcome {
        RESERVED,
        LIMIT_EXCEEDED,
        INVALID_REQUEST,
        NOT_FOUND
    }

    record ReservationRequest(String parentRunId,
                              NodeWorkItemIdentity segment,
                              NodeWorkItemVersions versions,
                              long groupId,
                              int memberSeq,
                              String memberIdentity,
                              String toolCallId,
                              String operationId,
                              String argumentsJson) {
    }
}
