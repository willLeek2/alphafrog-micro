package world.willfrog.agentlangchain.execution.dag;

/**
 * DAG 调度生命周期新增事件名。既有 {@code DAG_NODE_*} 终态事件名不在这里，也不改那些名字。
 */
public final class LangchainDagScheduleEvents {

    public static final String REGISTERED = "DAG_SCHEDULE_REGISTERED";
    public static final String WAITING = "DAG_SCHEDULE_WAITING";
    public static final String SUBMITTED = "DAG_SCHEDULE_SUBMITTED";
    public static final String STARTED = "DAG_SCHEDULE_STARTED";

    /** OTel GenAI 语义约定里的操作名，用作追踪区间名。 */
    public static final String TRACE_OPERATION_INVOKE_AGENT = "invoke_agent";

    private LangchainDagScheduleEvents() {
    }
}
