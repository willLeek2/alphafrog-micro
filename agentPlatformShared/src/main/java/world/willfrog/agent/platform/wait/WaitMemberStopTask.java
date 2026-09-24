package world.willfrog.agent.platform.wait;

import lombok.Data;

import java.time.OffsetDateTime;

/** 已取消等待成员的外部任务停机事实与重试位置。 */
@Data
public class WaitMemberStopTask {
    private Long id;
    private Long waitMemberId;
    private String runId;
    private Long groupId;
    private String operationId;
    private String taskId;
    private String requestFingerprint;
    private String cancelRequestId;
    private String state;
    private OffsetDateTime nextAttemptAt;
    private Integer attemptCount;
    private String claimedBy;
    private String claimToken;
    private OffsetDateTime leaseUntil;
    private String lastError;
    private String terminalTaskId;
    private String terminalStatus;
    private OffsetDateTime terminalConfirmedAt;
}
