package world.willfrog.agent.tools.sandboxjob;

/**
 * 结果适配器：把终态结果变成给模型的文本与错误码。
 *
 * <p>同步返回、后台终态注入、恢复注入三条路必须共用同一个出口，所以格式化收敛在
 * 这里。错误码是用户可见协议（前端与验收证据会引用），每个工具维护自己的码表。</p>
 */
public interface SandboxJobResultAdapter {

    /** 终态是否算业务成功（例如 SUCCEEDED 且退出码为 0）。 */
    boolean isSuccess(SandboxTerminalResultView result);

    /** 终态对应的用户可见错误码（如 PYTHON_EXECUTION_FAILED）。 */
    String errorCodeOf(SandboxTerminalResultView result);

    /**
     * 把终态结果格式化成给模型的文本，三条路径共用。
     *
     * @param formatContext 终态副作用钩子产出的格式化上下文（如 finance 提取结果），
     *                      由工具自己的钩子与适配器约定类型；没有副作用的路径传 null
     */
    String formatTerminalResult(SandboxTerminalResultView result, Object formatContext);
}
