package world.willfrog.agent.platform.asynctask;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一行异步工具任务：一次工具调用从创建到终态的全过程。
 *
 * <p>请求快照用于崩溃后原样重放；任务编号用于查询结果与取消时停机；
 * 名额凭证用于终态确认后精确归还容量。三者都从旧派发证明拆出，不重复存整份证明。</p>
 */
@Data
public class AsyncToolTask {

    private Long jobId;
    /** 取值见 {@link AsyncToolTaskKind}。 */
    private String jobKind;
    private String runId;
    private Long workItemId;
    private Long groupId;
    private Integer memberSeq;
    private String memberIdentity;
    private String toolCallId;
    private String toolName;
    /** 外部作业身份，全局唯一；同一作业不会挂到两行。 */
    private String externalOperationId;
    /** 取值见 {@link AsyncToolTaskState}。 */
    private String state;
    /** 请求快照：创建请求、规范化规格与请求指纹。 */
    private String requestJson;
    /** 沙箱返回的任务编号；停机与查询都按它定位。 */
    private String taskId;
    /** 名额凭证；终态确认后按它精确归还容量。 */
    private String reservationJson;
    /** 结果引用：指向持久结果载荷的位置，不是结果正文。 */
    private String resultRefJson;
    private Integer schemaVersion;
    /** 当前持有推进权的实例；租约过期后别的实例可以接管。 */
    private String ownerInstanceId;
    private OffsetDateTime leaseExpiresAt;
    /** 任务级领取轮次；写回时拒绝旧执行者。 */
    private Long claimEpoch;
    private OffsetDateTime nextPollAt;
    private Integer pollCount;
    /** 退避步数：每次推后查询加一，用于计算下次查询间隔。 */
    private Integer backoffStep;
    /** 停机状态：null 表示未请求，PENDING 待停止，CLAIMED 进行中，CONFIRMED 已确认。 */
    private String stopState;
    private Integer stopAttemptCount;
    private String stopLastError;
    private OffsetDateTime timeoutAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public AsyncToolTaskState stateEnum() {
        return AsyncToolTaskState.fromWire(state);
    }

    public AsyncToolTaskKind kindEnum() {
        return AsyncToolTaskKind.fromWire(jobKind);
    }

    /** 这一行还会不会再变。 */
    public boolean terminal() {
        return stateEnum().isTerminal();
    }
}
