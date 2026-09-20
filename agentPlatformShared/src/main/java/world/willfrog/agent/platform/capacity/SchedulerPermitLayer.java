package world.willfrog.agent.platform.capacity;

/**
 * 三层名额。各自的含义与占用时间不同，所以分别计数、分别读数，不能合成一个「队列深度」。
 */
public enum SchedulerPermitLayer {

    /** 业务准入：Run 从被接受到到达终态期间持续占用，用来限制系统已经承担了多少业务。 */
    BUSINESS_ADMISSION("业务准入", "按整个 Run 占用"),

    /** Run 协调许可：只在一次协调回合里占用，完成一次数据库提交后立即交还。 */
    RUN_COORDINATION_TURN("Run 协调许可", "按一次协调回合占用"),

    /** 节点执行许可：节点执行一个分段期间占用；节点在原地等外部结果时继续占着。 */
    NODE_EXECUTION_SEGMENT("节点执行许可", "按一个执行分段占用");

    private final String label;
    private final String holdingScope;

    SchedulerPermitLayer(String label, String holdingScope) {
        this.label = label;
        this.holdingScope = holdingScope;
    }

    public String label() {
        return label;
    }

    /** 占用区间的人话说明，写进日志与验收证据里。 */
    public String holdingScope() {
        return holdingScope;
    }
}
