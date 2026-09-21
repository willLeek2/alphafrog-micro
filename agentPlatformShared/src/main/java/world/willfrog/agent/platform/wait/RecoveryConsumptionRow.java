package world.willfrog.agent.platform.wait;

import lombok.Data;

/**
 * 恢复消费语句返回的原始计数行：通知取走了没有、下一段放行了没有。
 */
@Data
public class RecoveryConsumptionRow {

    private Integer consumed;
    private Integer promoted;
    private Long groupId;
    private Integer nextSegmentSequence;
}
