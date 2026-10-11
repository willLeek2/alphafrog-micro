package world.willfrog.agent.platform.wait;

/**
 * 「这一次工具调用属于哪个等待成员」的线程上下文。
 *
 * <p>新调度器版本派发工具之前，先把这份身份装到当前线程上。工具层读到它会得到两个事实：这次调用是
 * 某个等待组里的一次成员调用，不写旧路径那份「一条 Run 一个长工具进度记录」；这次后台作业的外部身份
 * 已经由调度侧算好并写进成员行，工具层原样使用，不再自己算一个。</p>
 *
 * <p>它只在当前线程内有效。工具执行是同线程的同步调用，整条调用链没有跨线程传递的需要。</p>
 */
public final class WaitGroupMemberExecutionContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private WaitGroupMemberExecutionContext() {
    }

    /** 装上这一次成员派发的身份；返回的收尾器负责恢复装上之前的上下文。 */
    public static Scope install(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("成员派发上下文不能为空");
        }
        Snapshot previous = CURRENT.get();
        CURRENT.set(snapshot);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** 当前线程正在派发的等待成员；不在成员派发里时返回空。 */
    public static Snapshot current() {
        return CURRENT.get();
    }

    /**
     * 一次成员派发的事实。
     *
     * @param runId               这条 Run
     * @param groupId             等待组编号
     * @param memberIdentity      成员稳定身份，只用于日志与排查
     * @param memberSeq           成员在整组请求里的原始序号
     * @param durableToolCallId   持久化的工具调用身份（模型给的调用身份加节点分段摘要）
     * @param expectedOperationId 整组落库时已经写进成员行的外部作业身份
     * @param segmentDescribe     当前分段的可读描述，只用于日志
     */
    public record Snapshot(String runId,
                           long groupId,
                           String memberIdentity,
                           int memberSeq,
                           String durableToolCallId,
                           String expectedOperationId,
                           String segmentDescribe) {

        public Snapshot {
            runId = requireText(runId, "runId");
            memberIdentity = requireText(memberIdentity, "memberIdentity");
            durableToolCallId = requireText(durableToolCallId, "持久化的工具调用身份");
            expectedOperationId = requireText(expectedOperationId, "外部作业身份");
            if (groupId <= 0) {
                throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
            }
            if (memberSeq < 0) {
                throw new IllegalArgumentException("成员序号不能是负数：" + memberSeq);
            }
            segmentDescribe = segmentDescribe == null ? "" : segmentDescribe;
        }

        /** 日志用的短描述，不带参数与结果正文。 */
        public String describe() {
            return "operation=" + expectedOperationId + " member=" + memberIdentity + " seq=" + memberSeq
                    + (segmentDescribe.isBlank() ? "" : " segment=" + segmentDescribe);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + "不能为空");
            }
            return value;
        }
    }

    /** 收尾器：把上下文恢复成装上之前的样子。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
