package world.willfrog.agent.tools.sandboxjob;

/**
 * 一个沙箱后台长工具的四个适配器及可选终态副作用回调。
 *
 * <p>生命周期框架按工具名取到这一束，就能完成从创建到终态的全程协作；
 * 工具侧新增能力只需要新写一束，不动框架主体。</p>
 */
public record SandboxJobAdapters(
        SandboxJobRequestAdapter<?> request,
        SandboxJobRunnerAdapter<?, ?, ?> runner,
        SandboxJobResultAdapter result,
        SandboxJobMeteringAdapter metering,
        TerminalFormatContextFactory terminalFormatContextFactory) {

    public SandboxJobAdapters(SandboxJobRequestAdapter<?> request,
                              SandboxJobRunnerAdapter<?, ?, ?> runner,
                              SandboxJobResultAdapter result,
                              SandboxJobMeteringAdapter metering) {
        this(request, runner, result, metering, null);
    }

    /** 后台接收没有线程内工具上下文，身份必须由持久成员显式传入。 */
    public record TerminalContext(String runId, String userId, String todoId, String toolCallId) {}

    /** 格式化前执行工具自有的终态处理；无副作用的工具不登记。 */
    @FunctionalInterface
    public interface TerminalFormatContextFactory {
        Object prepare(TerminalContext context, SandboxTerminalResultView terminal);
    }

    public SandboxJobResultAdapter.ResolvedResult resolveTerminal(
            TerminalContext context, SandboxTerminalResultView terminal) {
        Object formatContext = terminalFormatContextFactory == null ? null
                : terminalFormatContextFactory.prepare(context, terminal);
        return result.resolveTerminal(terminal, formatContext);
    }

    public String toolName() {
        return request.toolName();
    }
}
