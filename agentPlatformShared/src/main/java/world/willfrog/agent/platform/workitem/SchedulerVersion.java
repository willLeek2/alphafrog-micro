package world.willfrog.agent.platform.workitem;

/**
 * 调度器版本。
 *
 * <p>权威来源是 Run 主记录的 {@code scheduler_version} 列（{@code alphafrog_agent_run.scheduler_version}）；
 * 节点工作项行上只做冗余复制，用来按版本过滤。取值有三个：旧路径 {@link #LEGACY}、双池骨架
 * {@link #DUAL_POOL_V1}、完整 DAG 与等待组 {@link #DUAL_POOL_V2}。</p>
 *
 * <p>三个取值分工明确：{@link #LEGACY} 用旧的 DAG 子表；{@link #DUAL_POOL_V1} 用 Run 级单工具锚点，
 * 一个 Run 同时只能挂一个长工具；{@link #DUAL_POOL_V2} 用等待组与等待成员，一个节点分段可以同时挂
 * 多个工具，LINEAR 与 DAG 共用同一套节点执行层。因此「是不是双池」和「用不用等待组」是两件事：
 * 前者对 V1 与 V2 都成立，后者只对 V2 成立，判断时必须挑对方法，不能互相替代。</p>
 *
 * <p>未知取值失败关闭：读到一个不认识的版本就抛 {@link UnknownSchedulerVersionException}，既不猜成旧路径
 * （会让旧二进制认领新版本的 Run），也不猜成新路径（会让新二进制接手旧路径的 Run）。MyBatis 只把原始字符串
 * 搬进来，不做默认值映射，解析必须显式经过 {@link #fromWire(String)}。</p>
 */
public enum SchedulerVersion {

    /** 旧路径：线性执行器在 Run 的线程里推进节点，长工具走落库与续跑。 */
    LEGACY,

    /** 双池骨架：Run 侧与节点侧两个池，长工具用 Run 级单工具锚点，一个 Run 同时只挂一个。 */
    DUAL_POOL_V1,

    /** 完整 DAG 与等待组：节点分段可挂整组工具，LINEAR 与 DAG 共用节点执行层。 */
    DUAL_POOL_V2;

    /** 数据库列默认值与存量 Run 的回填值。 */
    public static final SchedulerVersion DEFAULT_FOR_EXISTING_ROWS = LEGACY;

    public static SchedulerVersion fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new UnknownSchedulerVersionException("调度器版本为空，按失败关闭处理");
        }
        String trimmed = raw.strip();
        for (SchedulerVersion version : values()) {
            if (version.name().equals(trimmed)) {
                return version;
            }
        }
        throw new UnknownSchedulerVersionException("未知的调度器版本：" + raw);
    }

    public boolean isDualPool() {
        return this == DUAL_POOL_V1;
    }

    /**
     * 是否属于双池家族（V1 与 V2）。
     *
     * <p>用来判断「这个 Run 走的是双池执行链、受 Run 协调名额与节点执行名额约束」。
     * 它和 {@link #usesWaitGroups()} 不是一回事：V1 也在双池家族里，但它用的是 Run 级单工具锚点。</p>
     */
    public boolean isDualPoolFamily() {
        return this == DUAL_POOL_V1 || this == DUAL_POOL_V2;
    }

    /**
     * 是否用等待组与等待成员保存等待事实。
     *
     * <p>只有 {@link #DUAL_POOL_V2} 为真。为真的版本：节点分段挂起时写等待组、把下一段建成 WAITING，
     * 结果齐备后按恢复通知把下一段改成 RESUMABLE；为假的版本继续读 Run 级工具锚点。</p>
     */
    public boolean usesWaitGroups() {
        return this == DUAL_POOL_V2;
    }

    /**
     * 是否用 Run 级单工具锚点保存等待事实。
     *
     * <p>只有 {@link #DUAL_POOL_V1} 为真。同一时刻一条 Run 只能有一个这种锚点，所以它撑不起
     * 一个分段里并列多个工具的等待组。</p>
     */
    public boolean usesRunLevelToolJobAnchor() {
        return this == DUAL_POOL_V1;
    }
}
