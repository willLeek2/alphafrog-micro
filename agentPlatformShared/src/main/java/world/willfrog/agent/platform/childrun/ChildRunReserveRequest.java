package world.willfrog.agent.platform.childrun;

/** 父节点已经准备好持久等待组时，预留一次子执行所需的稳定身份与容量。 */
public record ChildRunReserveRequest(
        String rootRunId,
        String parentRunId,
        long parentWaitGroupId,
        String parentMemberIdentity,
        String parentNodeId,
        String toolCallId,
        int planGeneration,
        int nodeAttempt,
        long parentControlVersion,
        String goal,
        String context,
        String childModelName,
        String childEndpointName,
        int childMaxSteps,
        String parentConfigSnapshotDigest) {
}
