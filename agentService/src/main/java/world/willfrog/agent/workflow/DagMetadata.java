package world.willfrog.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DagMetadata {
    /** 总节点数 */
    private int totalNodes;
    /** 最大深度（关键路径长度） */
    private int maxDepth;
    /** 并行组数量 */
    private int parallelGroups;
}
