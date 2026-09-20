package world.willfrog.agent.platform.workitem;

/**
 * 调度器版本。
 *
 * <p>权威来源是 Run 主记录的 {@code scheduler_version} 列（{@code alphafrog_agent_run.scheduler_version}）；
 * 节点工作项行上只做冗余复制，用来按版本过滤。本段只有两个取值：旧路径 {@link #LEGACY} 与新路径
 * {@link #DUAL_POOL_V1}。</p>
 *
 * <p>未知取值失败关闭：读到一个不认识的版本就抛 {@link UnknownSchedulerVersionException}，既不猜成旧路径
 * （会让旧二进制认领新版本的 Run），也不猜成新路径（会让新二进制接手旧路径的 Run）。MyBatis 只把原始字符串
 * 搬进来，不做默认值映射，解析必须显式经过 {@link #fromWire(String)}。</p>
 */
public enum SchedulerVersion {

    /** 旧路径：线性执行器在 Run 的线程里推进节点，长工具走落库与续跑。 */
    LEGACY,

    /** 新路径：Run 侧与节点侧两个池，节点工作项落在通用工作项表上。 */
    DUAL_POOL_V1;

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
}
