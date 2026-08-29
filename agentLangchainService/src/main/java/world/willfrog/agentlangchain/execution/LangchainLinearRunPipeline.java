package world.willfrog.agentlangchain.execution;

import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;

/**
 * P1 linear execution hook implemented by Codex B1 ({@code sequenceBuilder} path).
 */
public interface LangchainLinearRunPipeline {

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
