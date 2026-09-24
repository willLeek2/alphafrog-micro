package world.willfrog.agent.platform.childrun;

import java.time.OffsetDateTime;

/** 持久父子关系的只读视图；子 Run 状态为空表示受理记录与 Run 主记录不一致。 */
public record ChildRunIntentView(
        long intentId, String rootRunId, String parentRunId, String childRunId,
        String operationId, long parentWaitGroupId, String parentMemberIdentity,
        String toolCallId, int planGeneration, int nodeAttempt, long parentControlVersion,
        String intentState, String childRunStatus, OffsetDateTime acceptedAt,
        OffsetDateTime childTerminalAt, OffsetDateTime physicalStoppedAt,
        OffsetDateTime capacityReleasedAt) {
}
