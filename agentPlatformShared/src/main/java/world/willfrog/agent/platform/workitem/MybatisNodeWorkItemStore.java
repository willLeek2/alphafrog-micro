package world.willfrog.agent.platform.workitem;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;
import world.willfrog.agent.platform.treebudget.RootTreeActivityBudget;
import world.willfrog.agent.platform.treebudget.RootTreeActivityLimitException;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetStore;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * {@link NodeWorkItemStore} 的 MyBatis 实现。
 *
 * <p>状态迁移全部走带版本条件的更新语句，成败只看影响行数。影响行数为 0 时回读一次这一行，把失败分成三类：
 * 行不在了、已经进终态、以及条件不匹配；提交类语句还会区分「领取代际已经变了」的陈旧提交。回读只发生在失败
 * 路径上，不影响热路径。</p>
 */
@Service
@Slf4j
public class MybatisNodeWorkItemStore implements NodeWorkItemStore {

    private final NodeWorkItemMapper mapper;
    private final RootTreeActivityBudget activityBudget;
    private final Predicate<String> processExited;

    @Autowired
    public MybatisNodeWorkItemStore(NodeWorkItemMapper mapper, RootTreeActivityBudget activityBudget) {
        this(mapper, activityBudget, NodeWorkerProcessProof::definitelyExited);
    }

    MybatisNodeWorkItemStore(NodeWorkItemMapper mapper, RootTreeActivityBudget activityBudget,
                             Predicate<String> processExited) {
        this.mapper = mapper;
        this.activityBudget = activityBudget;
        this.processExited = processExited;
    }

    /** 旧合同测试的窄构造器；生产只注入带根树额度的构造器。 */
    public MybatisNodeWorkItemStore(NodeWorkItemMapper mapper) {
        this(mapper, null, NodeWorkerProcessProof::definitelyExited);
    }

    @Override
    public NodeWorkItemMutationResult create(NodeWorkItem item, ServiceOwnershipFence fence) {
        if (item == null) {
            throw new IllegalArgumentException("工作项不能为空");
        }
        requireFence(fence);
        if (item.getSchedulerVersion() == null || item.getSchedulerVersion().isBlank()) {
            throw new IllegalArgumentException("工作项必须带调度器版本，不能靠默认值兜");
        }
        if (item.getState() == null || item.getState().isBlank()) {
            item.setState(NodeWorkItemState.RUNNABLE.name());
        }
        if (item.getClaimEpoch() == null) {
            item.setClaimEpoch(0);
        }
        item.setPayloadJson(objectPayload(item.getPayloadJson()));
        int rows = mapper.insert(item, fence.ownerInstanceId(), fence.fencingToken());
        if (rows == 1) {
            return NodeWorkItemMutationResult.success();
        }
        NodeWorkItemIdentity identity = NodeWorkItemIdentity.of(item);
        // 影响 0 行有两种可能：同一个身份已经有一行（唯一约束挡下），或者这条 Run 级写入现在不被允许
        // （语句里的两个 EXISTS 挡下：所有权不在本进程，或者父 Run 的计划代际/控制版本/状态已经从
        // 这次协调回合读到的那一版往前走了）。回读一次把「同一身份」这种分开，不然日志会指错方向。
        if (mapper.findByIdentity(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence()) != null) {
            log.warn("同一个身份已经有工作项，创建被唯一约束拒绝：{}", identity.describe());
            return NodeWorkItemMutationResult.rejected(NodeWorkItemRejection.of(
                    NodeWorkItemRejectionReason.DUPLICATE_IDENTITY, identity,
                    NodeWorkItemVersions.of(item), null));
        }
        log.error("这条 Run 级写入被拒，工作项没有建成（所有权或父 Run 版本/状态已变）: fence={} identity={}",
                fence.describe(), identity.describe());
        return NodeWorkItemMutationResult.rejected(NodeWorkItemRejection.of(
                NodeWorkItemRejectionReason.OWNERSHIP_LOST, identity, NodeWorkItemVersions.of(item),
                fence.describe()));
    }

    @Override
    public List<NodeWorkItem> scanClaimable(SchedulerVersion schedulerVersion, int limit) {
        requireVersion(schedulerVersion);
        if (limit <= 0) {
            throw new IllegalArgumentException("扫描条数必须为正数：" + limit);
        }
        return mapper.scanClaimable(schedulerVersion.name(), limit);
    }

    @Override
    public List<NodeWorkItem> scanClaimableAcrossDualPool(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("扫描条数必须为正数：" + limit);
        }
        return mapper.scanClaimableAcrossDualPool(limit);
    }

    @Override
    @Transactional
    public Optional<NodeWorkItemClaim> claim(NodeWorkItemIdentity identity,
                                             NodeWorkItemVersions expected,
                                             String claimant,
                                             Duration lease,
                                             SchedulerVersion schedulerVersion,
                                             ServiceOwnershipFence fence) {
        requireVersion(schedulerVersion);
        requireClaimant(claimant);
        requireFence(fence);
        OffsetDateTime leaseExpiresAt = leaseExpiry(lease);
        NodeWorkItem before = null;
        if (activityBudget != null) {
            String rootRunId = activityBudget.lockForRun(identity.runId());
            before = mapper.findByIdentity(identity.runId(), identity.planGeneration(), identity.nodeId(),
                    identity.nodeAttempt(), identity.segmentSequence());
            if (before == null || before.getClaimEpoch() == null
                    || before.getClaimEpoch() != expected.claimEpoch()) {
                return Optional.empty();
            }
            RootTreeBudgetStore.State reserved = activityBudget.reserveNode(
                    rootRunId, before, expected.claimEpoch() + 1);
            if (reserved == RootTreeBudgetStore.State.REJECTED) {
                throw new RootTreeActivityLimitException("ACTIVE_NODE", rootRunId);
            }
            if (reserved != RootTreeBudgetStore.State.RESERVED) {
                throw new IllegalStateException("节点领取的额度操作身份已被使用：" + identity.describe());
            }
        }
        Integer newEpoch = mapper.claim(fence.ownerInstanceId(), fence.fencingToken(),
                identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), schedulerVersion.name(),
                expected.contextVersion(), expected.runControlVersion(), expected.claimEpoch(),
                claimant, leaseExpiresAt);
        if (newEpoch == null) {
            if (activityBudget != null) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            log.debug("没有领到工作项（状态或版本不匹配，或已被别人领走）：{}", identity.describe());
            return Optional.empty();
        }
        if (activityBudget != null) {
            if (newEpoch != expected.claimEpoch() + 1) {
                throw new IllegalStateException("工作项领取代际与预留身份不一致：" + identity.describe());
            }
            activityBudget.confirmNode(before, newEpoch);
        }
        return Optional.of(new NodeWorkItemClaim(identity, claimant, newEpoch, leaseExpiresAt));
    }

    @Override
    public NodeWorkItemMutationResult startExecution(NodeWorkItemIdentity identity, int claimEpoch, String claimant) {
        requireClaimant(claimant);
        int rows = mapper.startExecution(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), claimEpoch, claimant);
        return rows == 1 ? NodeWorkItemMutationResult.success()
                : rejectWithEpochCheck(identity, null, claimEpoch, null);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult commitSegmentResult(NodeWorkItemIdentity identity,
                                                          NodeWorkItemVersions versions,
                                                          String payloadPatchJson,
                                                          String externalSideEffectRef) {
        NodeWorkItem before = activityBefore(identity);
        int rows = mapper.commitSegmentResult(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), versions.contextVersion(),
                versions.runControlVersion(), versions.claimEpoch(), objectPayload(payloadPatchJson));
        if (rows == 1) {
            return NodeWorkItemMutationResult.success();
        }
        return rejectWithEpochCheck(identity, versions, versions.claimEpoch(), externalSideEffectRef);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult suspendForToolJob(NodeWorkItemIdentity identity,
                                                        NodeWorkItemVersions versions,
                                                        String claimant,
                                                        String operationId,
                                                        String toolCallId,
                                                        int attempt) {
        requireClaimant(claimant);
        NodeWorkItem before = activityBefore(identity);
        int rows = mapper.suspendForToolJob(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), versions.contextVersion(),
                versions.runControlVersion(), versions.claimEpoch(), claimant, operationId, toolCallId, attempt);
        return rows == 1 ? NodeWorkItemMutationResult.success()
                : rejectWithEpochCheck(identity, versions, versions.claimEpoch(), operationId);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult promoteToolJobResumable(NodeWorkItemIdentity identity,
                                                              NodeWorkItemVersions versions,
                                                              String operationId,
                                                              String anchorJson,
                                                              String resumePayloadJson) {
        NodeWorkItem before = activityBefore(identity);
        int rows = mapper.promoteToolJobResumable(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), versions.contextVersion(),
                versions.runControlVersion(), versions.claimEpoch(), operationId,
                objectPayload(anchorJson), objectPayload(resumePayloadJson));
        return rows == 1 ? NodeWorkItemMutationResult.success()
                : rejectWithEpochCheck(identity, versions, versions.claimEpoch(), operationId);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult commitResumedToolJobResult(NodeWorkItemIdentity identity,
                                                                 NodeWorkItemVersions versions,
                                                                 String operationId,
                                                                 String payloadPatchJson,
                                                                 String externalSideEffectRef) {
        NodeWorkItem before = activityBefore(identity);
        int rows = mapper.commitResumedToolJobResult(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), versions.contextVersion(),
                versions.runControlVersion(), versions.claimEpoch(), operationId,
                objectPayload(payloadPatchJson));
        return rows == 1 ? NodeWorkItemMutationResult.success()
                : rejectWithEpochCheck(identity, versions, versions.claimEpoch(), externalSideEffectRef);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult requeueAbandonedClaim(NodeWorkItemIdentity identity,
                                                            NodeWorkItemVersions versions,
                                                            ServiceOwnershipFence fence,
                                                            SchedulerVersion schedulerVersion) {
        requireVersion(schedulerVersion);
        requireFence(fence);
        NodeWorkItem before = activityBefore(identity);
        if (activityBudget != null && before != null
                && !processExited.test(before.getClaimedBy())) {
            throw new NodeWorkerExitUnconfirmedException("原节点进程尚未确认退出，不能重新领取或归还额度：" + identity.describe());
        }
        int rows = mapper.requeueAbandonedClaim(identity.runId(), identity.planGeneration(),
                identity.nodeId(), identity.nodeAttempt(), identity.segmentSequence(),
                versions.contextVersion(), versions.runControlVersion(), versions.claimEpoch(),
                fence.ownerInstanceId(), fence.fencingToken(), schedulerVersion.name());
        if (rows == 1) {
            releaseIfActive(before);
            log.warn("恢复把一段被放弃的分段放回可领取: {} 原代际 {}", identity.describe(),
                    versions.claimEpoch());
            return NodeWorkItemMutationResult.success();
        }
        return rejectWithEpochCheck(identity, versions, versions.claimEpoch(), null);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult requeueInterruptedToolJob(NodeWorkItemIdentity identity,
                                                                NodeWorkItemVersions versions,
                                                                String operationId) {
        NodeWorkItem before = activityBefore(identity);
        boolean previousProcessExited = activityBudget != null && before != null
                && processExited.test(before.getClaimedBy());
        if (activityBudget != null && before != null && !previousProcessExited
                && !NodeWorkerProcessProof.isCurrentProcess(before.getClaimedBy())) {
            throw new NodeWorkerExitUnconfirmedException("原节点进程尚未确认退出，不能重新开放长工具分段："
                    + identity.describe());
        }
        int rows = mapper.requeueInterruptedToolJob(
                identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), versions.contextVersion(),
                versions.runControlVersion(), versions.claimEpoch(), operationId);
        if (rows == 1) {
            // 旧 JVM 已退出时，重排与旧代际归还必须在同一事务；否则新领取会抬高行上的
            // claim_epoch，周期对账再也找不到旧代际。当前 JVM 的额度由 worker 的 finally 归还。
            if (previousProcessExited) {
                activityBudget.releaseNode(before, versions.claimEpoch());
            }
            return NodeWorkItemMutationResult.success();
        }
        return rejectWithEpochCheck(identity, versions, versions.claimEpoch(), operationId);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult reportExecutionFailure(NodeWorkItemIdentity identity,
                                                             int claimEpoch,
                                                             String claimant,
                                                             String reason) {
        requireClaimant(claimant);
        NodeWorkItem before = activityBefore(identity);
        int rows = mapper.reportExecutionFailure(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), claimEpoch, claimant, reason);
        return rows == 1 ? NodeWorkItemMutationResult.success()
                : rejectWithEpochCheck(identity, null, claimEpoch, null);
    }

    @Override
    public NodeWorkItemMutationResult renewLease(NodeWorkItemIdentity identity,
                                                 int claimEpoch,
                                                 String claimant,
                                                 Duration lease) {
        requireClaimant(claimant);
        int rows = mapper.renewLease(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), claimEpoch, claimant, leaseExpiry(lease));
        return rows == 1 ? NodeWorkItemMutationResult.success()
                : rejectWithEpochCheck(identity, null, claimEpoch, null);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult cancel(NodeWorkItemIdentity identity,
                                             long runControlVersion,
                                             int claimEpoch,
                                             String reason) {
        NodeWorkItem before = activityBefore(identity);
        int rows = mapper.cancel(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), runControlVersion, claimEpoch, reason);
        // 业务取消可以先于实际节点线程退出，原领取代际的额度由线程退出确认归还。
        if (rows == 1 && activityBudget != null && before == null) {
            throw new IllegalStateException("工作项已取消但读不到取消前的持久行：" + identity.describe());
        }
        return rows == 1 ? NodeWorkItemMutationResult.success() : rejectByCurrentRow(identity, null, null);
    }

    @Override
    @Transactional
    public NodeWorkItemMutationResult markStale(NodeWorkItemIdentity identity,
                                                long contextVersion,
                                                long runControlVersion,
                                                String reason) {
        NodeWorkItem before = activityBefore(identity);
        int rows = mapper.markStale(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), contextVersion, runControlVersion, reason);
        if (rows == 1 && activityBudget != null && before == null) {
            throw new IllegalStateException("工作项已过期但读不到变更前的持久行：" + identity.describe());
        }
        return rows == 1 ? NodeWorkItemMutationResult.success() : rejectByCurrentRow(identity, null, null);
    }

    @Override
    public NodeWorkItemMutationResult deferDispatch(NodeWorkItemIdentity identity,
                                                    NodeDispatchDeferReason reason,
                                                    OffsetDateTime nextVisibleAt) {
        NodeDispatchDeferReason required = requireDispatchReason(reason);
        OffsetDateTime dueAt = requireDueTime(nextVisibleAt);
        int rows = mapper.deferDispatch(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), required.name(), dueAt);
        return rows == 1 ? NodeWorkItemMutationResult.success() : rejectByCurrentRow(identity, null, null);
    }

    @Override
    public NodeWorkItemMutationResult markDispatched(NodeWorkItemIdentity identity) {
        int rows = mapper.markDispatched(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence());
        return rows == 1 ? NodeWorkItemMutationResult.success() : rejectByCurrentRow(identity, null, null);
    }

    @Override
    @Transactional
    public Optional<NodeWorkItemClaim> handOverClaim(NodeWorkItemIdentity identity,
                                                     int expectedClaimEpoch,
                                                     String newOwner,
                                                     Duration lease) {
        requireClaimant(newOwner);
        OffsetDateTime leaseExpiresAt = leaseExpiry(lease);
        String rootRunId = null;
        NodeWorkItem before = null;
        if (activityBudget != null) {
            rootRunId = activityBudget.lockForRun(identity.runId());
            before = mapper.findByIdentity(identity.runId(), identity.planGeneration(), identity.nodeId(),
                    identity.nodeAttempt(), identity.segmentSequence());
            if (before == null || before.getClaimEpoch() == null
                    || before.getClaimEpoch() != expectedClaimEpoch) {
                return Optional.empty();
            }
            if (!processExited.test(before.getClaimedBy())) {
                throw new NodeWorkerExitUnconfirmedException("原节点线程尚未确认退出，不能转交领取代际："
                        + identity.describe());
            }
            releaseIfActive(before);
            RootTreeBudgetStore.State reserved = activityBudget.reserveNode(
                    rootRunId, before, expectedClaimEpoch + 1);
            if (reserved == RootTreeBudgetStore.State.REJECTED) {
                throw new RootTreeActivityLimitException("ACTIVE_NODE", rootRunId);
            }
            if (reserved != RootTreeBudgetStore.State.RESERVED) {
                throw new IllegalStateException("转交的节点额度身份已被使用：" + identity.describe());
            }
        }
        Integer newEpoch = mapper.handOverClaim(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), expectedClaimEpoch, newOwner, leaseExpiresAt);
        if (newEpoch == null) {
            if (activityBudget != null) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            log.warn("显式转交没有生效（期望代际或状态不匹配）：{} 期望代际 {}", identity.describe(), expectedClaimEpoch);
            return Optional.empty();
        }
        if (activityBudget != null) {
            if (newEpoch != expectedClaimEpoch + 1) {
                throw new IllegalStateException("转交代际与额度身份不一致：" + identity.describe());
            }
            activityBudget.confirmNode(before, newEpoch);
        }
        log.info("显式转交生效：{} 交给 {}，代际 {}", identity.describe(), newOwner, newEpoch);
        return Optional.of(new NodeWorkItemClaim(identity, newOwner, newEpoch, leaseExpiresAt));
    }

    @Override
    @Transactional
    public void acknowledgeWorkerExit(NodeWorkItemIdentity identity, int claimEpoch) {
        if (activityBudget == null) return;
        if (claimEpoch <= 0) throw new IllegalArgumentException("退出确认缺少领取代际");
        NodeWorkItem current = activityBefore(identity);
        if (current == null) return;
        // finally 证明的是传入的旧领取代际已经退出，与工作项此刻是否被下一代领取无关。
        // 额度操作用 (工作项 ID, 领取代际) 定位，幂等释放，不会释放下一代的额度。
        activityBudget.releaseNode(current, claimEpoch);
    }

    /** 崩溃可能发生在业务交出节点之后、finally 回执之前；证实旧进程退出后补回执。 */
    @Transactional
    public boolean reconcileExitedWorker(NodeWorkItemIdentity identity, int claimEpoch) {
        if (activityBudget == null) return false;
        NodeWorkItem current = activityBefore(identity);
        if (current == null || current.getClaimEpoch() == null || current.getClaimEpoch() != claimEpoch
                || !(current.terminal() || current.stateEnum() == NodeWorkItemState.WAITING
                        || current.stateEnum() == NodeWorkItemState.RESUMABLE)
                || !processExited.test(current.getClaimedBy())) return false;
        activityBudget.releaseNode(current, claimEpoch);
        return true;
    }

    private NodeWorkItem activityBefore(NodeWorkItemIdentity identity) {
        if (activityBudget == null) return null;
        activityBudget.lockForRun(identity.runId());
        return mapper.findByIdentity(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence());
    }

    private void releaseIfActive(NodeWorkItem before) {
        if (activityBudget == null) return;
        if (before == null) {
            throw new IllegalStateException("工作项状态已变化但读不到释放前的持久行");
        }
        if (before.stateEnum() == NodeWorkItemState.CLAIMED
                || before.stateEnum() == NodeWorkItemState.EXECUTING) {
            activityBudget.releaseNode(before, before.getClaimEpoch());
        }
    }

    @Override
    public Optional<NodeWorkItem> findByIdentity(NodeWorkItemIdentity identity) {
        return Optional.ofNullable(mapper.findByIdentity(identity.runId(), identity.planGeneration(),
                identity.nodeId(), identity.nodeAttempt(), identity.segmentSequence()));
    }

    @Override
    public List<NodeWorkItem> listLatestSegments(String runId, int planGeneration) {
        return mapper.listLatestSegments(runId, planGeneration);
    }

    @Override
    public List<NodeWorkItem> listUnfinishedByRun(String runId) {
        return mapper.listUnfinishedByRun(runId);
    }

    @Override
    public int countUnfinished() {
        return mapper.countUnfinished();
    }

    @Override
    public int countUnfinishedByRun(String runId) {
        return mapper.countUnfinishedByRun(runId);
    }

    @Override
    public int maxPlanGenerationByRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Run ID 不能为空");
        }
        return mapper.maxPlanGenerationByRun(runId);
    }

    @Override
    public int maxUnfinishedPerRun() {
        return mapper.maxUnfinishedPerRun();
    }

    @Override
    public List<NodeWorkItem> listUnfinishedBySchedulerVersion(SchedulerVersion schedulerVersion, int limit) {
        requireVersion(schedulerVersion);
        if (limit <= 0) {
            throw new IllegalArgumentException("扫描条数必须为正数：" + limit);
        }
        return mapper.listUnfinishedBySchedulerVersion(schedulerVersion.name(), limit);
    }

    @Override
    public int countUnfinishedBySchedulerVersion(SchedulerVersion schedulerVersion) {
        requireVersion(schedulerVersion);
        return mapper.countUnfinishedBySchedulerVersion(schedulerVersion.name());
    }

    @Override
    public List<NodeWorkItem> listUnfinishedByRunSchedulerVersion(SchedulerVersion schedulerVersion,
                                                                  int limit) {
        requireVersion(schedulerVersion);
        if (limit <= 0) {
            throw new IllegalArgumentException("扫描条数必须为正数：" + limit);
        }
        return mapper.listUnfinishedByRunSchedulerVersion(schedulerVersion.name(), limit);
    }

    @Override
    public int countUnfinishedByRunSchedulerVersion(SchedulerVersion schedulerVersion) {
        requireVersion(schedulerVersion);
        return mapper.countUnfinishedByRunSchedulerVersion(schedulerVersion.name());
    }

    /**
     * 提交类语句被拒时，区分「这一行已经属于新的领取者」与其它条件不匹配。
     * 只有领取代际确实变了才算陈旧提交，这一个判断决定要不要上报
     * {@link NodeWorkItemEvents#STALE_SUBMISSION_REJECTED}。
     *
     * @param submittedVersions 调用方这次提交带的版本；只有领取代际的调用点传 null，由当前行补齐
     */
    private NodeWorkItemMutationResult rejectWithEpochCheck(NodeWorkItemIdentity identity,
                                                            NodeWorkItemVersions submittedVersions,
                                                            int submittedEpoch,
                                                            String externalSideEffectRef) {
        NodeWorkItem current = mapper.findByIdentity(identity.runId(), identity.planGeneration(),
                identity.nodeId(), identity.nodeAttempt(), identity.segmentSequence());
        NodeWorkItemVersions versions = submittedVersions != null
                ? submittedVersions
                : versionsWithSubmittedEpoch(current, submittedEpoch);
        if (current == null) {
            return reject(NodeWorkItemRejectionReason.NOT_FOUND, identity, versions, externalSideEffectRef, null);
        }
        if (current.terminal()) {
            return reject(NodeWorkItemRejectionReason.TERMINAL_ALREADY, identity, versions,
                    externalSideEffectRef, current);
        }
        if (current.getClaimEpoch() != null && current.getClaimEpoch() != submittedEpoch) {
            return reject(NodeWorkItemRejectionReason.STALE_SUBMISSION, identity, versions,
                    externalSideEffectRef, current);
        }
        return reject(NodeWorkItemRejectionReason.CONDITION_MISMATCH, identity, versions,
                externalSideEffectRef, current);
    }

    /** 取消与标过期被拒时，只区分行不在了、已经进终态、以及别的条件不匹配。 */
    private NodeWorkItemMutationResult rejectByCurrentRow(NodeWorkItemIdentity identity,
                                                          NodeWorkItemVersions submittedVersions,
                                                          String externalSideEffectRef) {
        NodeWorkItem current = mapper.findByIdentity(identity.runId(), identity.planGeneration(),
                identity.nodeId(), identity.nodeAttempt(), identity.segmentSequence());
        NodeWorkItemVersions versions = submittedVersions != null
                ? submittedVersions
                : (current != null ? current.versions() : new NodeWorkItemVersions(0L, 0L, 0));
        if (current == null) {
            return reject(NodeWorkItemRejectionReason.NOT_FOUND, identity, versions, externalSideEffectRef, null);
        }
        NodeWorkItemRejectionReason reason = current.terminal()
                ? NodeWorkItemRejectionReason.TERMINAL_ALREADY
                : NodeWorkItemRejectionReason.CONDITION_MISMATCH;
        return reject(reason, identity, versions, externalSideEffectRef, current);
    }

    /**
     * 只知道领取代际的调用点（开始执行、报执行失败、续租）用这个补齐另外两个版本：
     * 它们本来就不参与那几条语句的条件，取当前行的值不会把拒绝事实说错。
     */
    private static NodeWorkItemVersions versionsWithSubmittedEpoch(NodeWorkItem current, int submittedEpoch) {
        long contextVersion = current != null && current.getContextVersion() != null ? current.getContextVersion() : 0L;
        long controlVersion = current != null && current.getRunControlVersion() != null
                ? current.getRunControlVersion() : 0L;
        return new NodeWorkItemVersions(contextVersion, controlVersion, submittedEpoch);
    }

    /**
     * 记一条被拒事实。版本取调用方提交的值，另外把这一行此刻的真实状态与代际写进说明里——
     * 判读「为什么被拒」看的是这两件事合起来，单看提交值会以为这一行还是老样子。
     */
    private NodeWorkItemMutationResult reject(NodeWorkItemRejectionReason reason,
                                              NodeWorkItemIdentity identity,
                                              NodeWorkItemVersions submittedVersions,
                                              String externalSideEffectRef,
                                              NodeWorkItem current) {
        String detail = reason.detail();
        if (current != null) {
            detail = detail + "（这一行现在是 " + current.getState() + "，代际 " + current.getClaimEpoch() + "）";
        }
        NodeWorkItemRejection rejection = new NodeWorkItemRejection(reason, identity,
                submittedVersions, detail, externalSideEffectRef);
        if (rejection.staleSubmission()) {
            // 已经发生的执行与外部副作用要如实留痕：不接入上下文，不等于当它没发生过。
            log.warn("{}｜{}", rejection.eventName(), rejection.describe());
        } else {
            log.debug("条件更新被拒：{}｜{}", rejection.eventName(), rejection.describe());
        }
        return NodeWorkItemMutationResult.rejected(rejection);
    }

    /**
     * 节点本地载荷只收 JSON 对象：库里的合并是对象浅合并，传进来一个数组或标量会静默拼出别的东西。
     * 空值按空对象处理。
     */
    private static String objectPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "{}";
        }
        String trimmed = payloadJson.strip();
        if (!trimmed.startsWith("{")) {
            throw new IllegalArgumentException("节点本地载荷必须是 JSON 对象，收到的是：" + brief(trimmed));
        }
        return trimmed;
    }

    private static String brief(String text) {
        return text.length() <= 32 ? text : text.substring(0, 32) + "…";
    }

    /**
     * Run 级写入（新建工作项、领取）必须带服务所有权凭据：没有凭据就不是「我该写」这件事，
     * 与其写进去再解释，不如在这里直接拒掉。
     */
    private static void requireFence(ServiceOwnershipFence fence) {
        if (fence == null) {
            throw new IllegalArgumentException("Run 级写入必须带服务所有权凭据");
        }
    }

    private static void requireVersion(SchedulerVersion schedulerVersion) {
        if (schedulerVersion == null) {
            throw new IllegalArgumentException("调度器版本不能为空：工作项必须按精确版本处理");
        }
    }

    private static void requireClaimant(String claimant) {
        if (claimant == null || claimant.isBlank()) {
            throw new IllegalArgumentException("领取者不能为空");
        }
    }

    private static OffsetDateTime leaseExpiry(Duration lease) {
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("租约时长必须为正数");
        }
        return OffsetDateTime.now().plus(lease);
    }

    private static NodeDispatchDeferReason requireDispatchReason(NodeDispatchDeferReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("派发延期原因不能为空：没有原因就不要写这一列");
        }
        return reason;
    }

    private static OffsetDateTime requireDueTime(OffsetDateTime nextVisibleAt) {
        if (nextVisibleAt == null) {
            throw new IllegalArgumentException("派发失败必须给出下次可见时间，否则这条工作项会一直不可见");
        }
        return nextVisibleAt;
    }
}
