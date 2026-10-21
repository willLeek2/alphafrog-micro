package world.willfrog.agentlangchain.execution;

import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;

/**
 * AgentRun 的异步执行入口：现由双池管线（{@link DualPoolRunPipeline}）实现，
 * 启动、冻结重启与长工具恢复的接口形状都定义在这里。
 */
public interface AgentRunPipeline {

    void launchAsync(AgentRun run);

    default void launchAsync(AgentRun run, LangchainRunConcurrencyScheduler.Reservation reservation) {
        launchAsync(run);
    }

    /** 复用冻结 Plan 的服务重启入口；实现必须跳过 planner。 */
    default boolean launchRestartedAsync(AgentRun run) {
        return false;
    }

    /** 长工具挂起后的恢复入口；实现按恢复令牌与租约把 Run 接回执行。 */
    default boolean launchResumedAsync(AgentRun run,
                                       world.willfrog.agentlangchain.tooljob.ToolJobResumeContext context,
                                       java.util.function.BooleanSupplier terminalConsumed,
                                       java.util.function.Consumer<Boolean> completion) {
        return false;
    }
}
