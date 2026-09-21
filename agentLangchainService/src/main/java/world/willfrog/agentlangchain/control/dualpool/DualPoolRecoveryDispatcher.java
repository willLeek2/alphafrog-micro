package world.willfrog.agentlangchain.control.dualpool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.RecoveryConsumptionResult;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 恢复通知的分发：三个入口进同一段处理逻辑，每一轮只处理有限条。
 *
 * <p>三个入口各管一种情形。<b>提交后唤醒</b>：写结果的那个线程已经把通知消费掉了，只有它没消费成
 * （准入还没恢复之类）时才需要一个「马上再试一次」的提醒，这条提醒只活在内存里、可以丢。
 * <b>周期补扫</b>：内存提示丢了、进程活着但没人守着，靠它按数据库把到期通知重新发现。
 * <b>启动扫描</b>：进程刚起来时集中翻几页，把重启前就已经齐备的等待链先放出去，之后交给周期补扫。</p>
 *
 * <p>每一轮的上限同时管住两件事：一轮最多处理多少条通知、一轮最多往节点池投多少条。没消费成的
 * 按 {@link RecoveryBackoff} 推后下次可见时间——取不走的通知留在候选队头，会把同一批里本来能
 * 取走的那些挤掉，看上去就像恢复卡住了。</p>
 *
 * <p>这一套只管「放行下一段」的资格流转：把已经等齐的等待链重新送回节点池。真正的执行、领取与
 * 幂等由节点池的条件更新管。</p>
 */
@Component
@Slf4j
public class DualPoolRecoveryDispatcher {

    /** 分发器实例标识，写进通知的消费方字段，事后能看出是谁放行的。 */
    private static final String DISPATCHER_ID = "dual-pool-recovery-periodic";

    private final WaitGroupStore waitGroupStore;
    private final AgentRunMapper runMapper;
    private final DualPoolDispatcher dispatcher;
    private final DualPoolRunAdmissionRegistry admissionRegistry;
    private final RecoveryBackoff backoff;
    private final int batchSize;
    private final int startupPages;

    /** 提交后唤醒的提醒：只带通知编号，取之前回库读权威状态。 */
    private final ConcurrentLinkedQueue<Long> wakeups = new ConcurrentLinkedQueue<>();
    private final LinkedHashSet<Long> pendingWakeups = new LinkedHashSet<>();
    private final Object wakeupLock = new Object();

    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();
    private final AtomicLong wakeupsHandled = new AtomicLong();
    private final AtomicLong hintFailed = new AtomicLong();
    private final AtomicLong isolated = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();

    public DualPoolRecoveryDispatcher(
            WaitGroupStore waitGroupStore,
            AgentRunMapper runMapper,
            DualPoolDispatcher dispatcher,
            DualPoolRunAdmissionRegistry admissionRegistry,
            @Value("${agent.langchain.dual-pool.recovery.batch-size:8}") int batchSize,
            @Value("${agent.langchain.dual-pool.recovery.backoff-base-ms:500}") long backoffBaseMs,
            @Value("${agent.langchain.dual-pool.recovery.backoff-max-ms:5000}") long backoffMaxMs,
            @Value("${agent.langchain.dual-pool.recovery.startup-pages:8}") int startupPages) {
        this.waitGroupStore = waitGroupStore;
        this.runMapper = runMapper;
        this.dispatcher = dispatcher;
        this.admissionRegistry = admissionRegistry;
        this.backoff = new RecoveryBackoff(Duration.ofMillis(Math.max(1L, backoffBaseMs)),
                Duration.ofMillis(Math.max(Math.max(1L, backoffBaseMs), backoffMaxMs)));
        this.batchSize = Math.max(1, batchSize);
        this.startupPages = Math.max(1, startupPages);
    }

    /**
     * 提交后唤醒：只记一条内存提示，马上返回。
     *
     * <p>在线路径已经自己消费过通知了，走到这里说明它没消费成。这里不直接消费：把「再试一次」
     * 排进下一轮，既不让写结果的那个线程多做一次库操作，也不会因为它失败而影响结果落库。</p>
     */
    public boolean wake(long notificationId) {
        if (notificationId <= 0) {
            return false;
        }
        synchronized (wakeupLock) {
            if (!pendingWakeups.add(notificationId)) {
                return false;
            }
        }
        wakeups.offer(notificationId);
        return true;
    }

    /** 周期补扫：按数据库把到期通知重新发现。 */
    @Scheduled(fixedDelayString = "${agent.langchain.dual-pool.recovery.scan-interval-ms:1000}")
    public void rediscover() {
        if (!dispatcher.isReady()) {
            return;
        }
        safeRound(batchSize);
    }

    /**
     * 启动扫描：进程刚起来时集中翻几页，把重启前已经齐备的等待链先放出去。
     *
     * <p>翻几页就收手，不做「一次扫空」：剩下的由周期补扫接着发现，启动阶段不该被一张大表拖住。
     * 每一页处理多少条与周期补扫同一个上限。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!dispatcher.isReady()) {
            return;
        }
        int total = 0;
        for (int page = 0; page < startupPages; page++) {
            int handled = safeRound(batchSize);
            total += handled;
            if (handled < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("启动恢复扫描完成：本轮重新发现并处理了 {} 条恢复通知，其余交给周期补扫", total);
        }
    }

    /** 一轮处理；返回这一轮实际处理的条数，供启动分页判断还有没有下一页。 */
    public int safeRound(int limit) {
        try {
            return round(limit);
        } catch (RuntimeException e) {
            // 分发器自己出错不能把调度线程带走：下一轮接着来，问题留在日志里。
            log.error("恢复分发一轮失败: reason={}", e.getMessage(), e);
            return 0;
        }
    }

    private int round(int limit) {
        rounds.incrementAndGet();
        int budget = Math.max(1, limit);
        int handled = 0;
        handled += drainWakeups(budget);
        if (handled >= budget) {
            return handled;
        }
        List<RecoveryNotification> due = waitGroupStore.scanDueRecoveryNotifications(budget - handled);
        scanned.addAndGet(due.size());
        for (RecoveryNotification notification : due) {
            attempt(notification);
            handled++;
        }
        return handled;
    }

    private int drainWakeups(int budget) {
        int handled = 0;
        while (handled < budget) {
            Long notificationId = wakeups.poll();
            if (notificationId == null) {
                return handled;
            }
            synchronized (wakeupLock) {
                pendingWakeups.remove(notificationId);
            }
            wakeupsHandled.incrementAndGet();
            Optional<RecoveryNotification> notification = waitGroupStore.findNotification(notificationId);
            if (notification.isEmpty()) {
                continue;
            }
            attempt(notification.get());
            handled++;
        }
        return handled;
    }

    /**
     * 试一条通知：能取走就投下一段，取不走就按退避推后。
     *
     * <p>Run 的控制版本在这里读一次：消费语句会拿它当条件，读与写之间真的被改动时条件不成立，
     * 这一次就不消费——控制版本变了说明这条链的下一步已经由别人决定，谁都不该抢跑。</p>
     */
    private void attempt(RecoveryNotification notification) {
        if (!notification.consumable()) {
            return;
        }
        AgentRun run = readRun(notification.getRunId());
        if (run == null) {
            // 通知指向一个读不回来的 Run：只隔离这一条，不因此停掉整个分发器。
            isolate(notification, "run_missing");
            return;
        }
        SchedulerVersion version;
        try {
            version = SchedulerVersion.fromWire(run.getSchedulerVersion());
        } catch (RuntimeException e) {
            isolate(notification, "unknown_scheduler_version");
            return;
        }
        if (!version.usesWaitGroups()) {
            // 等待链只属于等待组那一套执行层；别的版本的通知不该出现在这里。
            isolate(notification, "version_without_wait_groups");
            return;
        }
        RecoveryConsumptionResult consumed = waitGroupStore.consumeRecovery(
                notification.getId(), DISPATCHER_ID, value(run.getRunControlVersion()));
        if (!consumed.succeeded()) {
            defer(notification, run);
            return;
        }
        this.consumed.incrementAndGet();
        deliver(consumed.nextSegment());
    }

    /**
     * 把放行出来的下一段投给节点池。
     *
     * <p>投不进去不算失败：下一段已经是可恢复态，数据库扫描会重新发现它。这里只记数，不重试投递。</p>
     */
    private void deliver(NodeWorkItemIdentity nextSegment) {
        if (nextSegment == null) {
            // 通知取走了却没有下一段身份：说明放行那一步写的不是我们以为的那一行。
            log.error("恢复通知被消费但没有返回下一段身份，等待链会停住，需要人工检查");
            isolated.incrementAndGet();
            return;
        }
        if (!admissionRegistry.restorePersistedToolJob(nextSegment.runId())) {
            hintFailed.incrementAndGet();
            log.warn("下一段已放成可恢复，但业务准入没有恢复，等下一轮补扫：segment={}",
                    nextSegment.describe());
            return;
        }
        if (!dispatcher.offerNode(nextSegment)) {
            hintFailed.incrementAndGet();
        }
    }

    /**
     * 这一轮取不走：推后下次可见时间。
     *
     * <p>原因分两类记。一类是「本来就不该现在取」——Run 不在执行中、通知代际落后、下一段还没到等待态，
     * 这类会随着别的路径推进自己变好；另一类是 Run 已经到终态或取消中，这条链不会再有下一步，
     * 单独记成「卡住」，供观测发现没人收尾的等待链。</p>
     */
    private void defer(RecoveryNotification notification, AgentRun run) {
        AgentRunStatus status = run.getStatus();
        boolean stuckRun = status != null && (status == AgentRunStatus.CANCELING || terminal(status));
        OffsetDateTime nextVisibleAt = backoff.nextVisibleAt(OffsetDateTime.now(),
                notification.getId(), notification.getCreatedAt());
        boolean pushedLater = waitGroupStore.deferRecoveryNotification(notification.getId(), nextVisibleAt);
        if (stuckRun) {
            stuck.incrementAndGet();
            log.warn("dual_pool_recovery_stuck 这条恢复通知对应的 Run 已经不会再有下一步，通知还留着："
                            + "notification={} runId={} status={} nextVisibleAt={}",
                    notification.getId(), notification.getRunId(), status, nextVisibleAt);
        } else {
            deferred.incrementAndGet();
            log.debug("恢复通知这一轮取不走，推后到 {}：notification={} runId={} status={}",
                    nextVisibleAt, notification.getId(), notification.getRunId(), status);
        }
        if (!pushedLater) {
            log.debug("恢复通知推后没有生效（已经不在等待态或时间没变晚）：notification={}",
                    notification.getId());
        }
    }

    /** 隔离一条说不清归属的通知：不动它、不投递，只留一条可检索的记录。 */
    private void isolate(RecoveryNotification notification, String reason) {
        isolated.incrementAndGet();
        log.warn("dual_pool_recovery_isolated 这条恢复通知无法核对归属，先隔离不处理："
                        + "notification={} runId={} reason={}",
                notification.getId(), notification.getRunId(), reason);
    }

    private AgentRun readRun(String runId) {
        return runId == null || runId.isBlank() ? null : runMapper.findById(runId);
    }

    private static boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELED || status == AgentRunStatus.EXPIRED
                || status == AgentRunStatus.PARTIAL;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    /** 分发器的当前读数：轮数、处理条数、投递失败与隔离数，以及内存里还压着几条唤醒提醒。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recoveryRounds", rounds.get());
        snapshot.put("recoveryScannedTotal", scanned.get());
        snapshot.put("recoveryConsumedTotal", consumed.get());
        snapshot.put("recoveryDeferredTotal", deferred.get());
        snapshot.put("recoveryWakeupsHandledTotal", wakeupsHandled.get());
        snapshot.put("recoveryHintFailedTotal", hintFailed.get());
        snapshot.put("recoveryIsolatedTotal", isolated.get());
        snapshot.put("recoveryStuckTotal", stuck.get());
        snapshot.put("recoveryPendingWakeups", pendingWakeups.size());
        snapshot.put("recoveryBatchSize", batchSize);
        snapshot.put("recoveryBackoff", backoff.describe());
        return snapshot;
    }

    /** 只读观测：当前正压着的唤醒提醒编号，供诊断与验收用。 */
    public List<Long> pendingWakeupIds() {
        synchronized (wakeupLock) {
            return new ArrayList<>(pendingWakeups);
        }
    }
}
