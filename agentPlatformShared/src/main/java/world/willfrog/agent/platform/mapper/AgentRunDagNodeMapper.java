package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.dag.*;

import java.util.List;

/**
 * DAG 并行计划节点子表 Mapper — D08 CAS 窄契约的全部 SQL 入口。
 * 所有 UPDATE 都使用 CAS 条件，返回 0 表示调用者失去所有权。
 */
@Mapper
public interface AgentRunDagNodeMapper {

    // ===== Stage 1: 计划写入 =====

    /** 1a: CAS 写入 dag_frontier_json，同时推进 status=EXECUTING */
    int casWriteFrontier(@Param("runId") String runId,
                         @Param("frontierJson") String frontierJson,
                         @Param("oldGeneration") int oldGeneration);

    /** 1b: 批量插入子节点，返回实际插入的 node_id 数组 */
    List<String> batchInsertChildren(@Param("runId") String runId,
                                     @Param("newGeneration") int newGeneration,
                                     @Param("allNodeIdsJson") String allNodeIdsJson);

    // ===== Stage 2: per-node PREPARING =====

    /** 2a: DRAFT → PREPARING CAS，五要素身份验证 */
    int casDraftToPreparing(@Param("runId") String runId,
                            @Param("generation") int generation,
                            @Param("nodeId") String nodeId,
                            @Param("anchorJson") String anchorJson,
                            @Param("expectedNodeVersion") long expectedNodeVersion,
                            @Param("operationId") String operationId,
                            @Param("toolCallId") String toolCallId,
                            @Param("attempt") int attempt,
                            @Param("requestDigest") String requestDigest);

    /** 2b: frontier nodeStates 写入 SUSPENDING + frontierVersion+1 */
    int updateFrontierNodeSuspending(@Param("runId") String runId,
                                      @Param("generation") int generation,
                                      @Param("nodeId") String nodeId,
                                      @Param("expectedFrontierVersion") long expectedFrontierVersion);

    /** 2c: 诊断查询 — 2a 返回 0 时读取当前 anchor 判断重试策略 */
    String selectAnchorForDiagnostic(@Param("runId") String runId,
                                     @Param("generation") int generation,
                                     @Param("nodeId") String nodeId);

    /** 2d: 显式失败回退 — child 回到 DRAFT + frontier nodeStates 回到 PENDING */
    int revertPreparingToDraft(@Param("runId") String runId,
                                @Param("generation") int generation,
                                @Param("nodeId") String nodeId,
                                @Param("expectedNodeVersion") long expectedNodeVersion,
                                @Param("operationId") String operationId,
                                @Param("toolCallId") String toolCallId,
                                @Param("attempt") int attempt,
                                @Param("requestDigest") String requestDigest,
                                @Param("expectedFrontierVersion") long expectedFrontierVersion);

    // ===== Stage 3: 聚合 =====

    /** 3a: COMPLETED 聚合 — frontier 消费 FINALIZED child 的 resultJson */
    int aggregateCompleted(@Param("runId") String runId,
                           @Param("generation") int generation,
                           @Param("nodeId") String nodeId,
                           @Param("expectedFrontierVersion") long expectedFrontierVersion,
                           @Param("operationId") String operationId,
                           @Param("toolCallId") String toolCallId,
                           @Param("attempt") int attempt,
                           @Param("requestDigest") String requestDigest);

    /** 3b: FAILED 聚合 — frontier 消费 FINALIZED child 的 terminalError */
    int aggregateFailed(@Param("runId") String runId,
                         @Param("generation") int generation,
                         @Param("nodeId") String nodeId,
                         @Param("expectedFrontierVersion") long expectedFrontierVersion,
                         @Param("operationId") String operationId,
                         @Param("toolCallId") String toolCallId,
                         @Param("attempt") int attempt,
                         @Param("requestDigest") String requestDigest);

    // ===== Stage 4: 普通节点终态（ordinary node） =====

    int stage4CompleteFrontier(@Param("runId") String runId,
                               @Param("generation") int generation,
                               @Param("nodeId") String nodeId,
                               @Param("expectedFrontierVersion") long expectedFrontierVersion,
                               @Param("sharedContextPatch") String sharedContextPatch,
                               @Param("outputJson") String outputJson,
                               @Param("resumeToken") String resumeToken,
                               @Param("ownerId") String ownerId,
                               @Param("expectedResumeLeaseVersion") long expectedResumeLeaseVersion);

    int stage4CompleteChild(@Param("runId") String runId,
                            @Param("generation") int generation,
                            @Param("nodeId") String nodeId);

    int stage4FailedFrontier(@Param("runId") String runId,
                             @Param("generation") int generation,
                             @Param("nodeId") String nodeId,
                             @Param("expectedFrontierVersion") long expectedFrontierVersion,
                             @Param("sharedContextPatch") String sharedContextPatch,
                             @Param("errorJson") String errorJson,
                             @Param("resumeToken") String resumeToken,
                             @Param("ownerId") String ownerId,
                             @Param("expectedResumeLeaseVersion") long expectedResumeLeaseVersion);

    int stage4FailedChild(@Param("runId") String runId,
                           @Param("generation") int generation,
                           @Param("nodeId") String nodeId,
                           @Param("errorJson") String errorJson);

    int stage4SkippedFrontier(@Param("runId") String runId,
                               @Param("generation") int generation,
                               @Param("nodeId") String nodeId,
                               @Param("expectedFrontierVersion") long expectedFrontierVersion,
                               @Param("sharedContextPatch") String sharedContextPatch,
                               @Param("errorJson") String errorJson,
                               @Param("resumeToken") String resumeToken,
                               @Param("ownerId") String ownerId,
                               @Param("expectedResumeLeaseVersion") long expectedResumeLeaseVersion);

    int stage4SkippedChild(@Param("runId") String runId,
                            @Param("generation") int generation,
                            @Param("nodeId") String nodeId,
                            @Param("errorJson") String errorJson);

    // ===== Stage 5: 单节点收口 =====

    /** B2: PREPARING → CREATED，写入 taskId */
    int writeB2Created(@Param("runId") String runId,
                       @Param("generation") int generation,
                       @Param("nodeId") String nodeId,
                       @Param("expectedNodeVersion") long expectedNodeVersion,
                       @Param("operationId") String operationId,
                       @Param("toolCallId") String toolCallId,
                       @Param("attempt") int attempt,
                       @Param("requestDigest") String requestDigest,
                       @Param("taskId") String taskId);

    /** 5b: → TERMINAL，写入 outcome + terminalError */
    int write5bTerminal(@Param("runId") String runId,
                         @Param("generation") int generation,
                         @Param("nodeId") String nodeId,
                         @Param("expectedNodeVersion") long expectedNodeVersion,
                         @Param("operationId") String operationId,
                         @Param("toolCallId") String toolCallId,
                         @Param("attempt") int attempt,
                         @Param("requestDigest") String requestDigest,
                         @Param("outcome") String outcome,
                         @Param("terminalErrorJson") String terminalErrorJson);

    /** 5c: TERMINAL+COMPLETED → RESULT_VALIDATED */
    int write5cResultValidated(@Param("runId") String runId,
                                @Param("generation") int generation,
                                @Param("nodeId") String nodeId,
                                @Param("expectedNodeVersion") long expectedNodeVersion,
                                @Param("operationId") String operationId,
                                @Param("toolCallId") String toolCallId,
                                @Param("attempt") int attempt,
                                @Param("requestDigest") String requestDigest,
                                @Param("resultJson") String resultJson);

    /** 5d: → USAGE_RECORDED，包含 RESULT_LOST 分支 */
    int write5dUsageRecorded(@Param("runId") String runId,
                              @Param("generation") int generation,
                              @Param("nodeId") String nodeId,
                              @Param("expectedNodeVersion") long expectedNodeVersion,
                              @Param("operationId") String operationId,
                              @Param("toolCallId") String toolCallId,
                              @Param("attempt") int attempt,
                              @Param("requestDigest") String requestDigest,
                              @Param("usageRecordId") long usageRecordId,
                              @Param("payloadSha256") String payloadSha256);

    /** 5f: USAGE_RECORDED → RELEASED */
    int write5fReleased(@Param("runId") String runId,
                         @Param("generation") int generation,
                         @Param("nodeId") String nodeId,
                         @Param("expectedNodeVersion") long expectedNodeVersion,
                         @Param("operationId") String operationId,
                         @Param("toolCallId") String toolCallId,
                         @Param("attempt") int attempt,
                         @Param("requestDigest") String requestDigest);

    /** 5g: RELEASED → FINALIZED */
    int write5gFinalized(@Param("runId") String runId,
                          @Param("generation") int generation,
                          @Param("nodeId") String nodeId,
                          @Param("expectedNodeVersion") long expectedNodeVersion,
                          @Param("operationId") String operationId,
                          @Param("toolCallId") String toolCallId,
                          @Param("attempt") int attempt,
                          @Param("requestDigest") String requestDigest);

    /** 5h: 聚合 — 正常 COMPLETED */
    int aggregate5hCompleted(@Param("runId") String runId,
                              @Param("generation") int generation,
                              @Param("nodeId") String nodeId,
                              @Param("expectedFrontierVersion") long expectedFrontierVersion,
                              @Param("operationId") String operationId,
                              @Param("toolCallId") String toolCallId,
                              @Param("attempt") int attempt,
                              @Param("requestDigest") String requestDigest);

    /** 5h: 聚合 — 正常 FAILED */
    int aggregate5hFailed(@Param("runId") String runId,
                           @Param("generation") int generation,
                           @Param("nodeId") String nodeId,
                           @Param("expectedFrontierVersion") long expectedFrontierVersion,
                           @Param("operationId") String operationId,
                           @Param("toolCallId") String toolCallId,
                           @Param("attempt") int attempt,
                           @Param("requestDigest") String requestDigest);

    /** 5h: 聚合 — Cancel COMPLETED (phase=CANCELLING) */
    int aggregate5hCancelCompleted(@Param("runId") String runId,
                                    @Param("generation") int generation,
                                    @Param("nodeId") String nodeId,
                                    @Param("expectedFrontierVersion") long expectedFrontierVersion,
                                    @Param("operationId") String operationId,
                                    @Param("toolCallId") String toolCallId,
                                    @Param("attempt") int attempt,
                                    @Param("requestDigest") String requestDigest);

    /** 5h: 聚合 — Cancel FAILED (phase=CANCELLING) */
    int aggregate5hCancelFailed(@Param("runId") String runId,
                                 @Param("generation") int generation,
                                 @Param("nodeId") String nodeId,
                                 @Param("expectedFrontierVersion") long expectedFrontierVersion,
                                 @Param("operationId") String operationId,
                                 @Param("toolCallId") String toolCallId,
                                 @Param("attempt") int attempt,
                                 @Param("requestDigest") String requestDigest);

    /** 5h: 聚合 — Cancel CANCELED (phase=CANCELLING, tombstone child，校验四元身份与 writeChildFinalizedTombstone 一致) */
    int aggregate5hCancelCanceled(@Param("runId") String runId,
                                   @Param("generation") int generation,
                                   @Param("nodeId") String nodeId,
                                   @Param("expectedFrontierVersion") long expectedFrontierVersion,
                                   @Param("operationId") String operationId,
                                   @Param("toolCallId") String toolCallId,
                                   @Param("attempt") int attempt,
                                   @Param("requestDigest") String requestDigest);

    // ===== Cancel 协议 =====

    /** Phase A: 原子写入 CANCELLING frontier + 标记所有活跃 child（当前仅支持 SUSPENDING/SUSPENDED） */
    CancelResult cancelFrontierAndChildrenCTE(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("expectedFrontierVersion") long expectedFrontierVersion,
            @Param("cancelTime") String cancelTime,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("initialBackoffSeconds") int initialBackoffSeconds);

    /** Phase A RESUMING 变体：lease triple fence + (RECEIVED+LAUNCHING+resultConsumed!=true) OR (EXECUTING+(LAUNCHING|ACCEPTED)+resultConsumed=true) */
    CancelResult cancelFrontierAndChildrenCTE_resume(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("expectedFrontierVersion") long expectedFrontierVersion,
            @Param("cancelTime") String cancelTime,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("initialBackoffSeconds") int initialBackoffSeconds,
            @Param("expectedResumeToken") String expectedResumeToken,
            @Param("expectedOwnerId") String expectedOwnerId,
            @Param("expectedResumeLeaseVersion") long expectedResumeLeaseVersion);

    /** 崩溃恢复重入：补标记漏网的 child */
    int recoverCancelChildMarking(@Param("runId") String runId,
                                   @Param("generation") int generation,
                                   @Param("cancelRequestId") String cancelRequestId,
                                   @Param("initialBackoffSeconds") int initialBackoffSeconds);

    /** 查询 frontier 并锁定（Cancel Phase A 入口） */
    String selectFrontierForUpdate(@Param("runId") String runId);

    // ===== Selector（六桶路由） =====

    /** 查询到期的待取消 child，FOR UPDATE SKIP LOCKED */
    List<java.util.Map<String, Object>> selectCancelDueChildren(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("batchSize") int batchSize);

    // ===== Cancel 原子操作 =====

    RetryAdvance incrementCancelNotfoundRetryCount(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("nodeId") String nodeId,
            @Param("expectedNotfoundRetryCount") int expectedNotfoundRetryCount,
            @Param("backoffSeconds") int backoffSeconds,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("expectedNodeVersion") long expectedNodeVersion);

    RpcRetryAdvance incrementCancelRpcRetryCount(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("nodeId") String nodeId,
            @Param("expectedRpcRetryCount") int expectedRpcRetryCount,
            @Param("backoffSeconds") int backoffSeconds,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("expectedNodeVersion") long expectedNodeVersion);

    TerminalAdvance atomicTerminalLost(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("nodeId") String nodeId,
            @Param("expectedNotfoundRetryCount") int expectedNotfoundRetryCount,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("expectedNodeVersion") long expectedNodeVersion);

    ExhaustedAdvance writePreparingStuck(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("nodeId") String nodeId,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("expectedNodeVersion") long expectedNodeVersion);

    ExhaustedAdvance writeRpcExhausted(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("nodeId") String nodeId,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("expectedNodeVersion") long expectedNodeVersion);

    int writeChildFinalizedTombstone(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("nodeId") String nodeId,
            @Param("expectedNodeVersion") long expectedNodeVersion,
            @Param("operationId") String operationId,
            @Param("toolCallId") String toolCallId,
            @Param("attempt") int attempt,
            @Param("requestDigest") String requestDigest,
            @Param("cancelRequestId") String cancelRequestId);

    int writePreparingToCreatedCancel(
            @Param("runId") String runId,
            @Param("generation") int generation,
            @Param("nodeId") String nodeId,
            @Param("expectedNodeVersion") long expectedNodeVersion,
            @Param("operationId") String operationId,
            @Param("toolCallId") String toolCallId,
            @Param("attempt") int attempt,
            @Param("requestDigest") String requestDigest,
            @Param("taskId") String taskId,
            @Param("cancelRequestId") String cancelRequestId,
            @Param("initialBackoffSeconds") int initialBackoffSeconds);

    // ===== CancelReconciler 扫描与 claim =====

    /** 扫描 phase=CANCELLING 的 run，返回 (runId, generation, cancelRequestId) */
    List<java.util.Map<String, Object>> selectCANCELLINGRuns(@Param("limit") int limit);

    /**
     * 跨实例 claim：原子写入 reconcilerOwner + reconcilerLeaseUntil。
     * 同时校验 generation + cancelRequestId，防止旧扫描快照 claim 新一代的 cancel。
     * 返回 1 表示 claim 成功，0 表示已被其他实例 claim 或身份不匹配。
     */
    int claimReconcilerLease(@Param("runId") String runId,
                              @Param("generation") int generation,
                              @Param("cancelRequestId") String cancelRequestId,
                              @Param("ownerId") String ownerId,
                              @Param("leaseSeconds") long leaseSeconds);

    /**
     * 释放 lease：只有当前 owner 能释放自己的 lease。
     * 防止旧 worker 误清新 owner 的 lease。
     * 返回 1 表示释放成功，0 表示 owner 不匹配（已被接管）。
     */
    int releaseReconcilerLease(@Param("runId") String runId,
                                @Param("generation") int generation,
                                @Param("cancelRequestId") String cancelRequestId,
                                @Param("ownerId") String ownerId);

    // ===== Usage 沉底 =====

    int insertUsageRecord(@Param("operationId") String operationId,
                           @Param("payload") String payload,
                           @Param("payloadSha256") String payloadSha256);
}
