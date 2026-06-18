package world.willfrog.agent.service.workspace;

/**
 * 校验出的 broken reference。
 *
 * @param assetId      资产 ID（dataset id / manifest id / member dataset id）
 * @param expectedPath 期望路径
 * @param reason       失败原因
 *
 * @author wang
 */
public record BrokenRef(
        String assetId,
        String expectedPath,
        String reason
) {
}
