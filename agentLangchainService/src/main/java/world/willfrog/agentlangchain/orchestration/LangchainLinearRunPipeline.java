package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.platform.entity.AgentRun;

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
}
