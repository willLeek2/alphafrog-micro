package world.willfrog.agentlangchain.workspace;

import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * workspace dump 指纹的唯一计算入口（D21-A，W5 task #105）。
 *
 * <p>fingerprint = {@code completedAt|updatedAt|lastMessageSeq}，标识"这份 run 数据
 * 是否已经与上次 dump 时完全一致"。写入侧（{@link WorkspaceManifestWriter} 落
 * workspace_state.json）与跳过判定侧（{@link WorkspaceDumpService} dump 前比对）
 * 必须共用本类，保证"manifest 完整性判定与指纹计算一致"——不允许任何一方私自拼指纹。</p>
 *
 * <p>空值语义：completedAt/updatedAt 为 null 时以空串参与拼接；messages 为 null/空
 * 时 lastMessageSeq = 0。指纹只做相等比较，不做排序或解析。</p>
 *
 * @author wang
 */
public final class WorkspaceFingerprints {

    private WorkspaceFingerprints() {
    }

    /**
     * 从 run 与消息列表计算指纹。
     *
     * @param run      目标 run（completedAt/updatedAt 来源），不能为空
     * @param messages 消息列表，可为 null（按空列表处理）
     * @return 指纹字符串
     */
    public static String compute(AgentRun run, List<AgentRunMessage> messages) {
        if (run == null) {
            throw new IllegalArgumentException("run 不能为空");
        }
        return compute(run.getCompletedAt(), run.getUpdatedAt(), lastSeqOf(messages));
    }

    /**
     * 指纹拼接的底层实现：{@code completedAt|updatedAt|lastMessageSeq}。
     */
    public static String compute(OffsetDateTime completedAt, OffsetDateTime updatedAt, int lastMessageSeq) {
        return String.format("%s|%s|%d",
                completedAt == null ? "" : completedAt.toString(),
                updatedAt == null ? "" : updatedAt.toString(),
                lastMessageSeq);
    }

    /**
     * 消息列表中的最大 seq；null/空列表返回 0。
     */
    public static int lastSeqOf(List<AgentRunMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int lastSeq = 0;
        for (AgentRunMessage m : messages) {
            if (m != null && m.getSeq() != null && m.getSeq() > lastSeq) {
                lastSeq = m.getSeq();
            }
        }
        return lastSeq;
    }
}
