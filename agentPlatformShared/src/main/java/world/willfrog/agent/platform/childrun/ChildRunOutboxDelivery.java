package world.willfrog.agent.platform.childrun;

/** 待投递行及创建子 Run 所需的冻结输入。领取不代表子 Run 已受理。 */
public record ChildRunOutboxDelivery(
        long outboxId, long intentId, String claimToken, String childRunId,
        String operationId, String rootRunId, String parentRunId,
        long parentWaitGroupId, String parentMemberIdentity, String parentNodeId, String toolCallId,
        int planGeneration, int nodeAttempt, long parentControlVersion,
        String goal, String context, String childModelName, String childEndpointName,
        int childMaxSteps,
        String parentSchedulerVersion,
        String parentDeploymentId, String parentDeploymentGenerationId,
        String parentConfigSnapshotDigest) {
}
