package world.willfrog.agent.platform.dataanalysis;

/**
 * 一次性线程级故障命中后的专用中断。
 *
 * <p>它模拟进程在持久写入之后立刻消失：上层必须让当前工作线程退出，不能把它转换成业务失败、
 * 不能写 Run 终态，也不能清理数据库里的长工具锚点。</p>
 */
public final class ToolJobInjectedInterruption extends RuntimeException {

    public ToolJobInjectedInterruption(String scenarioId, String checkpoint) {
        super("tool_job_fault_injected:" + scenarioId + ":" + checkpoint);
    }
}
