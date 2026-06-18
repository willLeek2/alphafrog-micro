package world.willfrog.agent.service.workspace;

import java.util.List;

/**
 * workspace 校验结果。
 *
 * <p>同一次 traversal 同时产出 brokenRefs[] 和 manifestMembers[]，
 * 保证 manifest.json.brokenRefs[] 与 meta.json.health.brokenRefs 状态一致。</p>
 *
 * @author wang
 */
public record WorkspaceHealth(
        int totalRefs,
        List<BrokenRef> brokenRefs,
        List<ManifestMemberView> manifestMembers
) {
}
