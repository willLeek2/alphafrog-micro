package world.willfrog.agent.platform.childrun;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.mapper.ChildRunIntentMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 父节点挂起事务内预留身份、投递事实和根树名额。调用方控制事务边界，任何异常使整组写入回滚。
 * 领取后的子 Run 创建和受理确认也由调用方放进同一事务，不能先确认再异步另建 Run。
 */
@Service
@RequiredArgsConstructor
public class MybatisChildRunIntentStore implements ChildRunIntentStore {
    private final ChildRunIntentMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ChildRunReservation reserveIntent(ChildRunReserveRequest request, int maxActiveChildren) {
        validate(request, maxActiveChildren);
        ChildRunParentSnapshot parent = requireParent(request.parentRunId());
        ChildRunIntentRow existing = mapper.findByCall(request);
        if (existing != null) {
            if (!sameRequest(existing, request)) {
                throw new IllegalStateException("同一父工具调用对应了不同的子执行创建内容");
            }
            return reservation(existing, ChildRunReservation.Outcome.REPLAYED);
        }
        requireCurrentParent(parent, request);
        String actualRoot = rootRunIdOf(request.parentRunId())
                .orElseThrow(() -> new IllegalStateException("父 Run 的根调用树身份无法确认"));
        if (!actualRoot.equals(request.rootRunId())) {
            throw new IllegalStateException("子执行的根调用树身份与父 Run 不一致");
        }
        if (mapper.matchesParentWaitMember(request) != 1) {
            throw new IllegalStateException("子执行意图与父等待组成员身份不一致");
        }
        mapper.ensureTreeCapacity(request.rootRunId());
        if (mapper.reserveTreeCapacity(request.rootRunId(), maxActiveChildren) != 1) {
            // 上一条子执行恰好归还最后一份容量时，空账本行会被删除；重建后再试一次。
            mapper.ensureTreeCapacity(request.rootRunId());
            if (mapper.reserveTreeCapacity(request.rootRunId(), maxActiveChildren) != 1) {
                return ChildRunReservation.limitExceeded();
            }
        }
        StringBuilder identity = new StringBuilder();
        appendIdentityField(identity, request.parentRunId());
        appendIdentityField(identity, String.valueOf(request.parentWaitGroupId()));
        appendIdentityField(identity, request.parentMemberIdentity());
        appendIdentityField(identity, String.valueOf(request.planGeneration()));
        appendIdentityField(identity, request.parentNodeId());
        appendIdentityField(identity, String.valueOf(request.nodeAttempt()));
        appendIdentityField(identity, request.toolCallId());
        String childRunId = stableUuid("child-run:" + identity);
        String operationId = "child-run:" + stableUuid("child-operation:" + identity);
        Long intentId = mapper.insertIntent(request, childRunId, operationId, parent);
        if (intentId == null) {
            throw new IllegalStateException("子执行创建意图没有返回编号");
        }
        Long outboxId = mapper.insertOutbox(intentId);
        if (outboxId == null) {
            throw new IllegalStateException("子执行投递记录没有返回编号");
        }
        return new ChildRunReservation(ChildRunReservation.Outcome.CREATED,
                intentId, childRunId, operationId, outboxId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ChildRunOutboxDelivery> claimDueOutbox(String owner, String claimToken,
                                                           OffsetDateTime now, OffsetDateTime leaseUntil) {
        required(owner, "领取者");
        required(claimToken, "领取令牌");
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("投递租期必须晚于领取时刻");
        }
        Long outboxId = mapper.claimDueOutbox(owner, claimToken, now, leaseUntil);
        if (outboxId == null) {
            return Optional.empty();
        }
        ChildRunIntentRow row = mapper.findByOutboxId(outboxId);
        if (row == null) {
            throw new IllegalStateException("已领取的子执行投递记录找不到意图");
        }
        return Optional.of(new ChildRunOutboxDelivery(outboxId, row.getId(), claimToken,
                row.getChildRunId(), row.getOperationId(), row.getRootRunId(), row.getParentRunId(),
                row.getParentWaitGroupId(), row.getParentMemberIdentity(), row.getParentNodeId(),
                row.getToolCallId(),
                row.getPlanGeneration(), row.getNodeAttempt(), row.getParentControlVersion(),
                row.getGoal(), row.getContext(), row.getChildModelName(),
                row.getChildEndpointName(), row.getChildMaxSteps(), row.getParentSchedulerVersion(),
                row.getParentDeploymentId(), row.getParentDeploymentGenerationId(),
                row.getParentConfigSnapshotDigest()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markAccepted(long outboxId, String claimToken) {
        required(claimToken, "领取令牌");
        ChildRunIntentRow candidate = mapper.findByOutboxId(outboxId);
        if (candidate == null) {
            return false;
        }
        ChildRunParentSnapshot parent = requireParent(candidate.getParentRunId());
        ChildRunIntentRow row = mapper.lockIntent(candidate.getId());
        if (row == null || !"PENDING".equals(row.getState())
                || !"CLAIMED".equals(row.getOutboxState())
                || !claimToken.equals(row.getClaimToken())
                || parentStopped(parent)
                || !Objects.equals(parent.getRunControlVersion(), row.getParentControlVersion())
                || !Objects.equals(parent.getPlanGeneration(), row.getPlanGeneration())
                || mapper.parentWaitMemberStillOpen(row.getId()) != 1) {
            return false;
        }
        if (mapper.markIntentAccepted(row.getId()) != 1
                || mapper.acknowledgeOutbox(outboxId, claimToken) != 1) {
            throw new IllegalStateException("子 Run 受理与投递确认未能一起写入");
        }
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelUnacceptedIfParentChanged(long intentId) {
        ChildRunIntentRow candidate = mapper.findByIntentId(intentId);
        if (candidate == null) {
            return false;
        }
        ChildRunParentSnapshot parent = requireParent(candidate.getParentRunId());
        ChildRunIntentRow row = mapper.lockIntent(intentId);
        if (row == null || !"PENDING".equals(row.getState())
                || (!parentStopped(parent)
                    && Objects.equals(parent.getRunControlVersion(), row.getParentControlVersion())
                    && Objects.equals(parent.getPlanGeneration(), row.getPlanGeneration())
                    && mapper.parentWaitMemberStillOpen(row.getId()) == 1)) {
            return false;
        }
        if (mapper.cancelUnaccepted(intentId) != 1 || mapper.cancelOutbox(intentId) != 1
                || mapper.releaseTreeCapacity(row.getRootRunId()) != 1) {
            throw new IllegalStateException("取消未受理子执行时未能完整归还预留");
        }
        mapper.pruneEmptyTreeCapacity(row.getRootRunId());
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requestCancellation(String childRunId) {
        ChildRunIntentRow candidate = mapper.findByChildRunId(childRunId);
        if (candidate == null) {
            return false;
        }
        ChildRunParentSnapshot parent = requireParent(candidate.getParentRunId());
        ChildRunIntentRow row = mapper.lockIntent(candidate.getId());
        if (row == null || !"ACCEPTED".equals(row.getState())
                || (!parentStopped(parent)
                    && Objects.equals(parent.getRunControlVersion(), row.getParentControlVersion()))) {
            return false;
        }
        return mapper.requestCancellation(row.getId()) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markChildTerminal(String childRunId) {
        ChildRunIntentRow row = lockChild(childRunId);
        if (row == null || mapper.markChildTerminal(row.getId()) != 1) {
            return false;
        }
        releaseIfReady(row.getId(), row.getRootRunId());
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markPhysicalStopped(String childRunId) {
        ChildRunIntentRow row = lockChild(childRunId);
        if (row == null || mapper.markPhysicalStopped(row.getId()) != 1) {
            return false;
        }
        releaseIfReady(row.getId(), row.getRootRunId());
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> rootRunIdOf(String runId) {
        required(runId, "Run 编号");
        return Optional.ofNullable(mapper.rootRunIdOf(runId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasUnsettledDescendants(String rootRunId) {
        required(rootRunId, "根 Run 编号");
        Boolean result = mapper.hasUnsettledDescendants(rootRunId);
        if (result == null) {
            throw new IllegalStateException("根调用树未结清状态查询没有返回结果");
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listReservedRootRunIds(String afterRootRunId, int limit) {
        requirePageSize(limit);
        return List.copyOf(mapper.listReservedRootRunIds(afterRootRunId, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChildRunIntentView> listAcceptedChildrenNeedingLaunch(long afterIntentId, int limit) {
        requirePage(afterIntentId, limit);
        return mapper.listAcceptedChildrenNeedingLaunch(afterIntentId, limit).stream()
                .map(MybatisChildRunIntentStore::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChildRunIntentView> listUnsettledByParent(String parentRunId, long afterIntentId, int limit) {
        required(parentRunId, "父 Run 编号");
        requirePage(afterIntentId, limit);
        return mapper.listUnsettledByParent(parentRunId, afterIntentId, limit).stream()
                .map(MybatisChildRunIntentStore::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChildRunIntentView> listUnsettledByRoot(String rootRunId, long afterIntentId, int limit) {
        required(rootRunId, "根 Run 编号");
        requirePage(afterIntentId, limit);
        return mapper.listUnsettledByRoot(rootRunId, afterIntentId, limit).stream()
                .map(MybatisChildRunIntentStore::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChildRunIntentView> listAcceptedSpawnMembersPending(long afterIntentId, int limit) {
        requirePage(afterIntentId, limit);
        return mapper.listAcceptedSpawnMembersPending(afterIntentId, limit).stream()
                .map(MybatisChildRunIntentStore::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChildRunIntentView> findByChildRunId(String childRunId) {
        required(childRunId, "子 Run 编号");
        return Optional.ofNullable(mapper.findByChildRunId(childRunId))
                .map(MybatisChildRunIntentStore::toView);
    }

    private ChildRunIntentRow lockChild(String childRunId) {
        required(childRunId, "子 Run 编号");
        ChildRunIntentRow candidate = mapper.findByChildRunId(childRunId);
        return candidate == null ? null : mapper.lockIntent(candidate.getId());
    }

    private void releaseIfReady(long intentId, String rootRunId) {
        if (mapper.releaseIntentCapacity(intentId) == 1 && mapper.releaseTreeCapacity(rootRunId) != 1) {
            throw new IllegalStateException("子执行已结清但根树容量计数无法归还");
        }
        mapper.pruneEmptyTreeCapacity(rootRunId);
    }

    private ChildRunParentSnapshot requireParent(String parentRunId) {
        ChildRunParentSnapshot parent = mapper.lockParentRun(parentRunId);
        if (parent == null) {
            throw new IllegalStateException("父 Run 不存在或不可锁定");
        }
        return parent;
    }

    private void requireCurrentParent(ChildRunParentSnapshot parent, ChildRunReserveRequest request) {
        if (parentStopped(parent)
                || !"DUAL_POOL_V2".equals(parent.getSchedulerVersion())
                || !Objects.equals(parent.getPlanGeneration(), request.planGeneration())
                || !Objects.equals(parent.getRunControlVersion(), request.parentControlVersion())) {
            throw new IllegalStateException("父 Run 版本或状态与子执行创建意图不一致");
        }
    }

    private static boolean parentStopped(ChildRunParentSnapshot parent) {
        if (parent.getStatus() == null) {
            return true;
        }
        return switch (parent.getStatus()) {
            case "CANCELING", "CANCELED", "COMPLETED", "PARTIAL", "FAILED", "EXPIRED" -> true;
            default -> false;
        };
    }

    private static boolean sameRequest(ChildRunIntentRow row, ChildRunReserveRequest request) {
        return Objects.equals(row.getRootRunId(), request.rootRunId())
                && Objects.equals(row.getParentWaitGroupId(), request.parentWaitGroupId())
                && Objects.equals(row.getParentMemberIdentity(), request.parentMemberIdentity())
                && Objects.equals(row.getGoal(), request.goal())
                && Objects.equals(row.getContext(), request.context())
                && Objects.equals(row.getChildModelName(), request.childModelName())
                && Objects.equals(row.getChildEndpointName(), request.childEndpointName())
                && Objects.equals(row.getChildMaxSteps(), request.childMaxSteps())
                && Objects.equals(row.getParentConfigSnapshotDigest(), request.parentConfigSnapshotDigest())
                && Objects.equals(row.getParentControlVersion(), request.parentControlVersion());
    }

    private static ChildRunReservation reservation(ChildRunIntentRow row, ChildRunReservation.Outcome outcome) {
        return new ChildRunReservation(outcome, row.getId(), row.getChildRunId(),
                row.getOperationId(), row.getOutboxId());
    }

    private static ChildRunIntentView toView(ChildRunIntentRow row) {
        return new ChildRunIntentView(row.getId(), row.getRootRunId(), row.getParentRunId(),
                row.getChildRunId(), row.getOperationId(), row.getParentWaitGroupId(),
                row.getParentMemberIdentity(), row.getToolCallId(), row.getPlanGeneration(),
                row.getNodeAttempt(), row.getParentControlVersion(), row.getState(),
                row.getChildRunStatus(), row.getParentMemberState(), row.getAcceptedAt(), row.getChildTerminalAt(),
                row.getPhysicalStoppedAt(), row.getCapacityReleasedAt());
    }

    private static void requirePage(long afterIntentId, int limit) {
        if (afterIntentId < 0) {
            throw new IllegalArgumentException("意图扫描起点不能为负数");
        }
        requirePageSize(limit);
    }

    private static void requirePageSize(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("扫描批量必须在 1 到 1000 之间");
        }
    }

    private static String stableUuid(String source) {
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void appendIdentityField(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static void validate(ChildRunReserveRequest request, int maxActiveChildren) {
        if (request == null || request.parentWaitGroupId() <= 0 || request.planGeneration() < 0
                || request.nodeAttempt() < 0 || request.parentControlVersion() < 0
                || maxActiveChildren < 1) {
            throw new IllegalArgumentException("子执行预留请求缺少有效身份或容量上限");
        }
        required(request.rootRunId(), "根 Run 编号");
        required(request.parentRunId(), "父 Run 编号");
        required(request.parentMemberIdentity(), "父等待成员身份");
        required(request.parentNodeId(), "父节点编号");
        required(request.toolCallId(), "工具调用编号");
        required(request.goal(), "子执行目标");
        required(request.parentConfigSnapshotDigest(), "父配置快照摘要");
        if (request.context() == null) {
            throw new IllegalArgumentException("子执行上下文不能为 null");
        }
        if (request.childMaxSteps() < 1 || request.childMaxSteps() > 12) {
            throw new IllegalArgumentException("子执行的冻结步骤上限必须在 1 到 12 之间");
        }
    }

    private static void required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }
}
