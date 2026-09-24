package world.willfrog.agentlangchain.control.dualpool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.wait.RecoveryConsumptionResult;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.RecoveryRejection;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 恢复一条等待链的受理口径：先拿到「这条 Run 归我动」和业务名额，再取走那条不可逆的恢复资格。
 *
 * <p>三条入口（提交后马上唤醒、周期补扫、启动扫描）都必须从这里过，因为顺序不能各写各的：</p>
 *
 * <ol>
 *   <li><b>服务所有权</b>：先按租约表取得或确认这条 Run 的服务所有权，再进消费语句；消费语句在同一次
 *   条件更新里核对持有者与代际，并且把租约行与 Run 行一起锁住。非所有者、旧代际、核对与写入之间被
 *   接手都改不动任何一行。</li>
 *   <li><b>业务名额预留</b>：消费是一锤子买卖——通知只有一条，取走了就不会再来。所以名额必须在这之前
 *   拿到手，而且要是可以回滚的那种：拿不到名额就不消费，通知留在等待态按退避推后，下一轮再来。
 *   反过来的顺序（先消费再要名额）在名额满时会把 Run 永久停在「下一段已放行、通知已取走、没人受理」。</li>
 *   <li><b>消费与收尾</b>：消费成功就把预留激活；消费没成按语句给的原因分两种：不会再被服务的
 *   （Run 终态、计划或控制版本作废、下一段已不在等待态）落成关闭态并写明原因，暂时性的推后重试。
 *   两种都要把刚占的预留回滚掉，把刚取得、还没接手的租约按持有者与代际让出。</li>
 * </ol>
 *
 * <p>让出那一步是有讲究的：本进程刚取得租约却没接手时如果一直握着，续期循环会把它当成「我在服务」
 * 一直续下去，别人永远接不了手。只有本来就是我持有的租约才留着——那说明这条 Run 确实归我。</p>
 */
@Service
@Slf4j
public class WaitGroupRecoveryIntake {

    /** 一次受理的四种结局。 */
    public enum Outcome {
        /** 通知被取走、下一段已放行、名额已激活。 */
        CONSUMED,
        /** 这一轮取不走：通知留在等待态，调用方按原因推后。 */
        DEFERRED,
        /** 不会再被服务：通知已经收口成关闭态。 */
        CLOSED,
        /** 这条通知已经被别人取走或关闭：什么都不用做。 */
        LOST_RACE
    }

    /** 一次受理的结果：结局、放行出来的下一段身份，以及取不走时的原因。 */
    public record IntakeResult(Outcome outcome,
                               NodeWorkItemIdentity nextSegment,
                               RecoveryRejection rejection,
                               String detail) {

        public boolean consumed() {
            return outcome == Outcome.CONSUMED;
        }

        static IntakeResult consumed(NodeWorkItemIdentity nextSegment) {
            return new IntakeResult(Outcome.CONSUMED, nextSegment, null, null);
        }
    }

    private final WaitGroupStore waitGroupStore;
    private final RunServiceLeaseStore leaseStore;
    private final ProcessInstanceIdentity instanceIdentity;
    private final DualPoolRunAdmissionRegistry admissionRegistry;
    private final Duration leaseTtl;

    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();
    private final AtomicLong closed = new AtomicLong();
    private final AtomicLong lostRaces = new AtomicLong();
    private final AtomicLong leaseTaken = new AtomicLong();
    private final AtomicLong leaseReleased = new AtomicLong();
    private final AtomicLong admissionUnavailable = new AtomicLong();

    public WaitGroupRecoveryIntake(
            WaitGroupStore waitGroupStore,
            RunServiceLeaseStore leaseStore,
            ProcessInstanceIdentity instanceIdentity,
            DualPoolRunAdmissionRegistry admissionRegistry,
            @Value("${agent.langchain.dual-pool.service-lease-ttl-seconds:120}") long leaseTtlSeconds,
            FrozenEffectiveSettings frozenEffectiveSettings) {
        this.waitGroupStore = waitGroupStore;
        this.leaseStore = leaseStore;
        this.instanceIdentity = instanceIdentity;
        this.admissionRegistry = admissionRegistry;
        this.leaseTtl = Duration.ofSeconds(Math.max(1L, leaseTtlSeconds));
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_TTL_SECONDS,
                "WaitGroupRecoveryIntake", this.leaseTtl.toSeconds());
    }

    /**
     * 试一条通知：按「所有权 → 名额 → 消费」的顺序走完，返回这次到底成了没有。
     *
     * @param notification 这条通知当前的样子（编号与 Run 号就够了，其余以库里的为准）
     * @param run          调用方刚读到的 Run；为 {@code null} 表示这条 Run 读不回来
     * @param caller       调用方标识，写进日志便于分辨是哪条入口
     */
    public IntakeResult take(RecoveryNotification notification, AgentRun run, String caller) {
        SchedulerVersion version = versionOf(run);
        if (version == null) {
            // 这条通知指向一个读不回来或者版本不认识的 Run：等待链永远不会再往前，收口。
            return close(notification, run == null ? "run_missing" : "unknown_scheduler_version",
                    "run_unreadable", caller);
        }
        if (!version.usesWaitGroups()) {
            return close(notification, "version_without_wait_groups", "version_without_wait_groups", caller);
        }

        LeaseStep lease = takeLease(run, caller);
        if (!lease.acquired()) {
            deferred.incrementAndGet();
            return new IntakeResult(Outcome.DEFERRED, null, RecoveryRejection.LEASE_NOT_OWNED,
                    instanceIdentity.value());
        }

        DualPoolRunAdmissionRegistry.Admission admission =
                admissionRegistry.reserveForRecovery(run.getId());
        if (!admission.admitted()) {
            // 名额拿不到就先别消费：通知留在等待态，下一轮再来。消费掉却没人受理才是最糟的结局。
            admissionUnavailable.incrementAndGet();
            deferred.incrementAndGet();
            releaseIfFreshlyTaken(lease, caller);
            // 名额这一条不属于「消费语句给的原因」，所以只留补充说明，不占拒绝原因的位置。
            return new IntakeResult(Outcome.DEFERRED, null, null, "admission_unavailable");
        }

        RecoveryConsumptionResult consumedResult = waitGroupStore.consumeRecovery(
                notification.getId(), caller, value(run.getRunControlVersion()),
                instanceIdentity.value(), lease.lease().fencingToken());
        if (consumedResult.succeeded()) {
            if (admission.epoch() >= 0L
                    && !admissionRegistry.activateReservedAdmission(run.getId(), admission)) {
                // 预留不在了（这条 Run 的名额在这中间被别人回收了）：下一段已经放行，但本进程不再受理它。
                // 这里不装作没事——节点扫描不会执行未受理的 Run，需要人看一眼这条链。
                log.error("下一段已放行，但业务名额预留激活失败，这条 Run 在本进程没有受理："
                                + "runId={} notification={} caller={}",
                        run.getId(), notification.getId(), caller);
            }
            this.consumed.incrementAndGet();
            return IntakeResult.consumed(consumedResult.nextSegment());
        }

        rollback(admission, run.getId());
        releaseIfFreshlyTaken(lease, caller);

        RecoveryRejection rejection = consumedResult.rejection();
        if (rejection == null) {
            // 语句没能给出原因：既不敢收口（可能是暂时性的），也不能当成成功。退避重试。
            deferred.incrementAndGet();
            log.warn("恢复消费没成但语句没有给出原因，先按退避重试：notification={} caller={}",
                    notification.getId(), caller);
            return new IntakeResult(Outcome.DEFERRED, null, RecoveryRejection.UNKNOWN, null);
        }
        if (rejection == RecoveryRejection.NOTIFICATION_NOT_WAITING) {
            // 别人先取走了（或者已经关闭）：这条资格已经有主，什么都不用做。
            lostRaces.incrementAndGet();
            return new IntakeResult(Outcome.LOST_RACE, null, rejection, consumedResult.rejectionDetail());
        }
        if (rejection.permanent()) {
            return close(notification, rejection.closeReason(consumedResult.rejectionDetail()),
                    rejection.name(), caller);
        }
        deferred.incrementAndGet();
        return new IntakeResult(Outcome.DEFERRED, null, rejection, consumedResult.rejectionDetail());
    }

    /** 这条通知对应的 Run 版本；Run 读不回来或版本读不出来时返回空。 */
    private SchedulerVersion versionOf(AgentRun run) {
        if (run == null) {
            return null;
        }
        try {
            return SchedulerVersion.fromWire(run.getSchedulerVersion());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 收口一条通知：只对还在等待态的通知生效，抢不到就说明别人先处理了。 */
    private IntakeResult close(RecoveryNotification notification, String closeReason, String label, String caller) {
        boolean closedNow = waitGroupStore.closeRecoveryNotification(notification.getId(), closeReason);
        if (closedNow) {
            closed.incrementAndGet();
            log.info("这条恢复通知不会再被服务，已收口：notification={} runId={} reason={} caller={}",
                    notification.getId(), notification.getRunId(), closeReason, caller);
            return new IntakeResult(Outcome.CLOSED, null, null, closeReason);
        }
        lostRaces.incrementAndGet();
        log.info("这条恢复通知已经不用收口（别人先取走或关掉了）：notification={} runId={} reason={}",
                notification.getId(), notification.getRunId(), label);
        return new IntakeResult(Outcome.LOST_RACE, null, null, closeReason);
    }

    /**
     * 取得或确认这条 Run 的服务所有权。
     *
     * <p>拿不到就说明别人正活着持有它——这条 Run 现在不归我们动，也不能拿别人的所有权去消费。</p>
     */
    private LeaseStep takeLease(AgentRun run, String caller) {
        String runId = run.getId();
        boolean alreadyMine = leaseStore.find(runId)
                .map(existing -> instanceIdentity.value().equals(existing.ownerInstanceId()))
                .orElse(false);
        Optional<RunServiceLease> acquired = leaseStore.acquire(runId, instanceIdentity.value(), leaseTtl);
        if (acquired.isEmpty()) {
            log.debug("这条 Run 的服务所有权在别人手上，先不动它：runId={} caller={}", runId, caller);
            return new LeaseStep(false, null, alreadyMine);
        }
        if (!alreadyMine) {
            leaseTaken.incrementAndGet();
        }
        return new LeaseStep(true, acquired.get(), alreadyMine);
    }

    /** 刚取得、还没接手的租约要让出去：握着不服务的租约会被续期循环一直续住，别人接不了手。 */
    private void releaseIfFreshlyTaken(LeaseStep lease, String caller) {
        if (!lease.acquired() || lease.alreadyMine() || lease.lease() == null) {
            return;
        }
        boolean released = leaseStore.release(lease.lease().runId(), instanceIdentity.value(),
                lease.lease().fencingToken());
        if (released) {
            leaseReleased.incrementAndGet();
        }
        log.debug("这条 Run 没有接手就先把刚取得的服务所有权让出去：runId={} released={} caller={}",
                lease.lease().runId(), released, caller);
    }

    private void rollback(DualPoolRunAdmissionRegistry.Admission admission, String runId) {
        if (admission.epoch() >= 0L) {
            admissionRegistry.rollbackReservedAdmission(runId, admission);
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    /** 一次所有权取得的三个事实：拿到了没有、拿到的是哪一代、之前是不是我的。 */
    private record LeaseStep(boolean acquired, RunServiceLease lease, boolean alreadyMine) {
    }

    /** 受理这一层的读数：成、退避、关闭、抢不到与名额拿不到各多少，以及租约的取得与让出。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recoveryIntakeConsumedTotal", consumed.get());
        snapshot.put("recoveryIntakeDeferredTotal", deferred.get());
        snapshot.put("recoveryIntakeClosedTotal", closed.get());
        snapshot.put("recoveryIntakeLostRaceTotal", lostRaces.get());
        snapshot.put("recoveryIntakeAdmissionUnavailableTotal", admissionUnavailable.get());
        snapshot.put("recoveryIntakeLeaseTakenTotal", leaseTaken.get());
        snapshot.put("recoveryIntakeLeaseReleasedTotal", leaseReleased.get());
        return snapshot;
    }
}
