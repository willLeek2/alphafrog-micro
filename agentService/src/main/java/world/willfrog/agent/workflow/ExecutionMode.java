package world.willfrog.agent.workflow;

public enum ExecutionMode {
    AUTO,
    FORCE_SIMPLE,
    FORCE_SUB_AGENT,
    /** DAG 并行执行模式 */
    DAG
}
