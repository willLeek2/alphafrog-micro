package world.willfrog.agent.service.workspace;

/**
 * manifest member 视图（带 broken 后的 effective status）。
 *
 * @author wang
 */
public record ManifestMemberView(
        String manifestId,
        String tsCode,
        String datasetId,
        String status,
        int rowCount
) {
}
