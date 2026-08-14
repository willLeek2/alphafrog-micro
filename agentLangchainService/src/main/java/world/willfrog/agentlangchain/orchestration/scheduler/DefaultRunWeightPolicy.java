package world.willfrog.agentlangchain.orchestration.scheduler;

import org.springframework.stereotype.Component;

/**
 * 当前唯一的权重策略：所有 Run 都按普通任务（1 容量单位）计。
 */
@Component
public class DefaultRunWeightPolicy implements RunWeightPolicy {

    @Override
    public int weightUnitsFor(String runId) {
        return STANDARD_WEIGHT;
    }
}
