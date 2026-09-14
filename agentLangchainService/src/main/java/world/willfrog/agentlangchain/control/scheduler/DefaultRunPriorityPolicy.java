package world.willfrog.agentlangchain.control.scheduler;

import org.springframework.stereotype.Component;

/**
 * 当前唯一的优先级策略：所有 Run 都使用 {@link RunPriority#NORMAL}。
 */
@Component
public class DefaultRunPriorityPolicy implements RunPriorityPolicy {

    @Override
    public RunPriority priorityFor(String runId) {
        return RunPriority.NORMAL;
    }
}
