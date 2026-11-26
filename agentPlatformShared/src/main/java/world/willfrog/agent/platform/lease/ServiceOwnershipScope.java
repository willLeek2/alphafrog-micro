package world.willfrog.agent.platform.lease;

/**
 * 续期和到期后强制接手时，运行主表必须对上的部署、代际、泳道。
 *
 * <p>泳道为空表示主 Beta，对应运行主表 lane_tag 为空。</p>
 */
public record ServiceOwnershipScope(
        String deploymentId,
        String deploymentGenerationId,
        String laneTag) {
}
