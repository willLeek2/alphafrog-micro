package world.willfrog.agent.platform.childrun;

import lombok.Data;
import java.time.OffsetDateTime;

/** MyBatis 读取的一条子执行意图及其唯一投递行。 */
@Data
public class ChildRunIntentRow {
    private Long id;
    private Long outboxId;
    private String rootRunId;
    private String parentRunId;
    private String childRunId;
    private Long parentWaitGroupId;
    private String parentMemberIdentity;
    private String parentNodeId;
    private String toolCallId;
    private Integer planGeneration;
    private Integer nodeAttempt;
    private String operationId;
    private String goal;
    private String context;
    private String childModelName;
    private String childEndpointName;
    private String parentSchedulerVersion;
    private String parentDeploymentId;
    private String parentDeploymentGenerationId;
    private String parentConfigSnapshotDigest;
    private Long parentControlVersion;
    private String state;
    private String claimToken;
    private String outboxState;
    private String childRunStatus;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime childTerminalAt;
    private OffsetDateTime physicalStoppedAt;
    private OffsetDateTime capacityReleasedAt;
}
