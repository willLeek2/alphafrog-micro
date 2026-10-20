package world.willfrog.agent.platform.workitem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link MybatisNodeWorkItemStore} 的失败分类：条件更新影响行数为 0 时，到底算陈旧提交、终态、还是别的条件不匹配。
 *
 * <p>纯 mock 测试，不连数据库、不启动容器：这里验的是「0 行之后怎么判」这段判断，
 * 真实的条件更新行为由 Postgres 集成测试那一层负责。</p>
 */
@ExtendWith(MockitoExtension.class)
class MybatisNodeWorkItemStoreTest {

    private static final NodeWorkItemIdentity IDENTITY = new NodeWorkItemIdentity("run-1", 0, "node-1", 0, 0);
    /** Run 级写入要带的服务所有权凭据：这里只证明它被原样交给语句。 */
    private static final ServiceOwnershipFence FENCE = new ServiceOwnershipFence("instance-a", 7L);
    private static final SchedulerVersion VERSION = SchedulerVersion.DUAL_POOL_V1;

    @Mock
    private NodeWorkItemMapper mapper;

    private MybatisNodeWorkItemStore store;

    @BeforeEach
    void setUp() {
        store = new MybatisNodeWorkItemStore(mapper);
    }

    private NodeWorkItem row(String state, int claimEpoch) {
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(IDENTITY.runId());
        item.setPlanGeneration(IDENTITY.planGeneration());
        item.setNodeId(IDENTITY.nodeId());
        item.setNodeAttempt(IDENTITY.nodeAttempt());
        item.setSegmentSequence(IDENTITY.segmentSequence());
        item.setState(state);
        item.setClaimEpoch(claimEpoch);
        item.setContextVersion(7L);
        item.setRunControlVersion(3L);
        item.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V1.name());
        return item;
    }

    @Test
    void createReportsDuplicateIdentityInsteadOfThrowing() {
        lenient().when(mapper.insert(any(), anyString(), anyLong())).thenReturn(0);
        // 影响 0 行时回读一次：同一身份已经有行 ⇒ 唯一约束挡下，不是所有权问题。
        lenient().when(mapper.findByIdentity(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(row("RUNNABLE", 0));
        NodeWorkItem candidate = row("RUNNABLE", 0);
        NodeWorkItemMutationResult result = store.create(candidate, FENCE);
        assertThat(result.applied()).isFalse();
        assertThat(result.rejection().reason()).isEqualTo(NodeWorkItemRejectionReason.DUPLICATE_IDENTITY);
    }

    @Test
    void createReportsLostOwnershipWhenNothingWasWrittenAndNoRowExists() {
        lenient().when(mapper.insert(any(), anyString(), anyLong())).thenReturn(0);
        lenient().when(mapper.findByIdentity(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(null);
        NodeWorkItem candidate = row("RUNNABLE", 0);

        NodeWorkItemMutationResult result = store.create(candidate, FENCE);

        assertThat(result.applied()).isFalse();
        // 没有同一身份的行、也没写进去：语句里的服务所有权条件把它挡下了。
        assertThat(result.rejection().reason()).isEqualTo(NodeWorkItemRejectionReason.OWNERSHIP_LOST);
    }

    @Test
    void createRefusesAnEmptyOwnershipFence() {
        NodeWorkItem candidate = row("RUNNABLE", 0);
        assertThatThrownBy(() -> store.create(candidate, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("服务所有权凭据");
    }

    @Test
    void createRefusesMissingSchedulerVersion() {
        NodeWorkItem candidate = row("RUNNABLE", 0);
        candidate.setSchedulerVersion(null);
        assertThatThrownBy(() -> store.create(candidate, FENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("调度器版本");
    }

    @Test
    void claimReturnsEmptyWhenUpdateTouchedNoRow() {
        when(mapper.claim(anyString(), anyLong(), anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any())).thenReturn(null);
        Optional<NodeWorkItemClaim> claim = store.claim(IDENTITY,
                new NodeWorkItemVersions(7L, 3L, 0), "worker-a", Duration.ofSeconds(30),
                VERSION, FENCE);
        assertThat(claim).isEmpty();
    }

    @Test
    void claimReturnsNewEpochWhenUpdateSucceeded() {
        when(mapper.claim(anyString(), anyLong(), anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any())).thenReturn(1);
        Optional<NodeWorkItemClaim> claim = store.claim(IDENTITY,
                new NodeWorkItemVersions(7L, 3L, 0), "worker-a", Duration.ofSeconds(30),
                VERSION, FENCE);
        assertThat(claim).isPresent();
        assertThat(claim.get().claimEpoch()).isEqualTo(1);
        assertThat(claim.get().claimedBy()).isEqualTo("worker-a");
    }

    @Test
    void staleSubmissionWhenEpochHasMovedOn() {
        when(mapper.commitSegmentResult(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyInt(), anyString())).thenReturn(0);
        when(mapper.findByIdentity(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(row("CLAIMED", 2));

        NodeWorkItemMutationResult result = store.commitSegmentResult(IDENTITY,
                new NodeWorkItemVersions(7L, 3L, 1), "{}", "sandbox-task-9");

        assertThat(result.applied()).isFalse();
        assertThat(result.rejectedStaleSubmission()).isTrue();
        assertThat(result.rejection().eventName()).isEqualTo(NodeWorkItemEvents.STALE_SUBMISSION_REJECTED);
        assertThat(result.rejection().externalSideEffectRef()).isEqualTo("sandbox-task-9");
        assertThat(result.rejection().versions().claimEpoch()).isEqualTo(1);
    }

    @Test
    void sameEpochButOtherConditionMismatchIsNotReportedAsStale() {
        when(mapper.commitSegmentResult(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyInt(), anyString())).thenReturn(0);
        when(mapper.findByIdentity(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(row("EXECUTING", 1));

        NodeWorkItemMutationResult result = store.commitSegmentResult(IDENTITY,
                new NodeWorkItemVersions(7L, 3L, 1), "{}", null);

        assertThat(result.rejectedStaleSubmission()).isFalse();
        assertThat(result.rejection().reason()).isEqualTo(NodeWorkItemRejectionReason.CONDITION_MISMATCH);
    }

    @Test
    void terminalRowIsReportedAsTerminalNotStale() {
        when(mapper.commitSegmentResult(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyInt(), anyString())).thenReturn(0);
        when(mapper.findByIdentity(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(row("RESULT_COMMITTED", 1));

        NodeWorkItemMutationResult result = store.commitSegmentResult(IDENTITY,
                new NodeWorkItemVersions(7L, 3L, 1), "{}", null);

        assertThat(result.rejection().reason()).isEqualTo(NodeWorkItemRejectionReason.TERMINAL_ALREADY);
    }

    @Test
    void missingRowIsReportedAsNotFound() {
        when(mapper.markStale(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyString())).thenReturn(0);
        when(mapper.findByIdentity(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(null);

        NodeWorkItemMutationResult result = store.markStale(IDENTITY, 7L, 3L, "计划代际已推进");

        assertThat(result.rejection().reason()).isEqualTo(NodeWorkItemRejectionReason.NOT_FOUND);
    }

    @Test
    void markStaleOnUnfinishedRowWithOtherVersionsIsConditionMismatch() {
        when(mapper.markStale(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyString())).thenReturn(0);
        when(mapper.findByIdentity(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(row("EXECUTING", 1));

        NodeWorkItemMutationResult result = store.markStale(IDENTITY, 7L, 3L, "控制版本已推进");

        assertThat(result.rejection().reason()).isEqualTo(NodeWorkItemRejectionReason.CONDITION_MISMATCH);
    }

    @Test
    void payloadMustBeAJsonObject() {
        assertThatThrownBy(() -> store.commitSegmentResult(IDENTITY,
                new NodeWorkItemVersions(7L, 3L, 1), "[1,2,3]", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 对象");
        NodeWorkItem candidate = row("RUNNABLE", 0);
        candidate.setPayloadJson("42");
        assertThatThrownBy(() -> store.create(candidate, FENCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handOverReturnsEmptyWhenRowIsNotTransferable() {
        when(mapper.handOverClaim(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyInt(), anyString(), any())).thenReturn(null);
        assertThat(store.handOverClaim(IDENTITY, 1, "worker-b", Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void handOverReturnsNewEpochWhenAccepted() {
        when(mapper.handOverClaim(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyInt(), anyString(), any())).thenReturn(2);
        Optional<NodeWorkItemClaim> claim = store.handOverClaim(IDENTITY, 1, "worker-b", Duration.ofMinutes(1));
        assertThat(claim).isPresent();
        assertThat(claim.get().claimEpoch()).isEqualTo(2);
    }

    @Test
    void scanRefusesNonPositiveLimitAndMissingVersion() {
        assertThatThrownBy(() -> store.scanClaimable(SchedulerVersion.DUAL_POOL_V1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.scanClaimable(null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abandonedClaimRequeueReportsSuccessOnlyWhenOneRowMoved() {
        when(mapper.requeueAbandonedClaim(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyInt(), anyString(), anyLong(), anyString())).thenReturn(1);
        assertThat(store.requeueAbandonedClaim(IDENTITY, new NodeWorkItemVersions(7L, 3L, 2),
                FENCE, VERSION).applied()).isTrue();
    }

    @Test
    void abandonedClaimRequeueOnMovedEpochIsReportedAsStaleSubmission() {
        when(mapper.requeueAbandonedClaim(anyString(), anyInt(), anyString(), anyInt(), anyInt(),
                anyLong(), anyLong(), anyInt(), anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.findByIdentity(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(row("CLAIMED", 3));

        NodeWorkItemMutationResult result = store.requeueAbandonedClaim(IDENTITY,
                new NodeWorkItemVersions(7L, 3L, 2), FENCE, VERSION);

        assertThat(result.rejection().reason()).isEqualTo(NodeWorkItemRejectionReason.STALE_SUBMISSION);
    }

    @Test
    void residueCheckReadsCountForVersion() {
        when(mapper.countUnfinishedBySchedulerVersion(eq(SchedulerVersion.DUAL_POOL_V1.name()))).thenReturn(3);
        assertThat(store.hasResidueFor(SchedulerVersion.DUAL_POOL_V1)).isTrue();
        when(mapper.countUnfinishedBySchedulerVersion(eq(SchedulerVersion.LEGACY.name()))).thenReturn(0);
        assertThat(store.hasResidueFor(SchedulerVersion.LEGACY)).isFalse();
    }
}
