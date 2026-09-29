package world.willfrog.agent.tools.sandboxjob;

/**
 * 一个沙箱后台长工具的四个适配器束。
 *
 * <p>生命周期框架按工具名取到这一束，就能完成从创建到终态的全程协作；
 * 工具侧新增能力只需要新写一束，不动框架主体。</p>
 */
public record SandboxJobAdapters(
        SandboxJobRequestAdapter<?> request,
        SandboxJobRunnerAdapter<?, ?> runner,
        SandboxJobResultAdapter result,
        SandboxJobMeteringAdapter metering) {

    public String toolName() {
        return request.toolName();
    }
}
