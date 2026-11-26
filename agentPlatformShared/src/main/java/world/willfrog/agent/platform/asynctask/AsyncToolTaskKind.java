package world.willfrog.agent.platform.asynctask;

/**
 * 异步工具任务的种类：沙箱作业，或等待子运行。
 *
 * <p>停机方式不同：沙箱作业按任务编号调沙箱停止接口，等待子运行对子运行发起取消。</p>
 */
public enum AsyncToolTaskKind {
    SANDBOX_TOOL,
    CHILD_RUN_WAIT;

    public static AsyncToolTaskKind fromWire(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("异步工具任务种类不能为空");
        }
        return valueOf(wire);
    }
}
