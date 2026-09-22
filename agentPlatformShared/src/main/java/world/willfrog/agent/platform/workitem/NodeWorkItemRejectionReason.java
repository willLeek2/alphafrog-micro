package world.willfrog.agent.platform.workitem;

/**
 * 条件更新影响行数为 0 时，这条工作项被拒的原因。
 *
 * <p>拒绝原因只用来解释「为什么这次没写进去」，不改变任何状态：工作项此刻属于谁、处于什么状态，
 * 以数据库里那一行为准。</p>
 */
public enum NodeWorkItemRejectionReason {

    /** 旧领取者带着旧领取代际来提交：这一行此刻属于新的领取者。 */
    STALE_SUBMISSION("领取代际已过期：这一行此刻属于新的领取者"),

    /** 状态或版本条件不匹配：可能是别人先动了这一行，也可能是调用方拿的是旧快照。 */
    CONDITION_MISMATCH("状态或版本条件不匹配"),

    /** 工作项已经进入终态，不再发生状态迁移。 */
    TERMINAL_ALREADY("工作项已经进入终态，不再迁移"),

    /** 同一个身份已经有一行，唯一约束拒绝了这次创建。 */
    DUPLICATE_IDENTITY("同一个身份已经有一行"),

    /** 这一行不存在。 */
    NOT_FOUND("这一行不存在"),

    /**
     * 这条 Run 级写入现在不被允许：语句里的条件挡下了它。
     *
     * <p>可能是服务所有权已经不在本进程手上，也可能是父 Run 的调度器版本、计划代际、控制版本或状态
     * 已经从这次协调回合读到的那一版往前走了。两种情况下调用方都按「这条 Run 现在不归我推进」处理，
     * 等下一轮重新读事实。</p>
     */
    OWNERSHIP_LOST("这条 Run 的服务所有权已经不在本进程，或者父 Run 的版本与状态已经往前走了");

    private final String detail;

    NodeWorkItemRejectionReason(String detail) {
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
