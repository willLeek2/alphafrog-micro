package world.willfrog.agent.platform.treebudget;

/** 根调用树共享的持久额度。调用方须为每次逻辑操作提供跨重试稳定的身份。 */
public interface RootTreeBudgetStore {
    enum Kind { LLM_CALL, TOOL_CALL, ACTIVE_NODE, EXTERNAL_WAIT }

    enum State { REJECTED, RESERVED, CONFIRMED, RELEASED }

    record Snapshot(long llmCalls, long toolCalls, long activeNodes, long externalWaits) {}

    /** 在调用方事务中先锁根树行，再修改工作项或等待组。 */
    void lockRoot(String rootRunId);

    /** 子 Run 只能使用已存在的根树行；缺行是身份或迁移错误。 */
    void lockExistingRoot(String rootRunId);

    /** 原子占用一个额度；同一操作身份重放返回原状态，不重复占用。 */
    State reserve(String rootRunId, String operationId, Kind kind, long limit);

    /** 确认一次已经发生的调用，或确认节点/等待已进入对应状态。 */
    State confirm(String operationId);

    /** 退回未确认的调用；节点与等待在结束后也须释放。 */
    State release(String operationId);

    Snapshot snapshot(String rootRunId);
}
