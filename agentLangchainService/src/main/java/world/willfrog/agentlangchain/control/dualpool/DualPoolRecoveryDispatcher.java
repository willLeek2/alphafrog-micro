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
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

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
 * <p>每一轮的上限同时管住两件事：一轮最多处理多少条通知、一轮最多往节点池投多少条。上限还要按
 * 固定名额切开：先给数据库补扫留一份，剩下的才用来处理内存提醒。提醒是无界的即时消息，光靠它自己
 * 排队就能把整轮预算吃光，重启前遗留、提醒丢了、只在数据库里的通知会一直等不到机会；数据库才是
 * 事实来源，每一轮都得进得去。</p>
 *
 * <p>没消费成的按 {@link RecoveryBackoff} 推后下次可见时间——取不走的通知留在候选队头，会把同一批
 * 里本来能取走的那些挤掉，看上去就像恢复卡住了。取不走的原因为两种：还能有下次的推后重试，再也
 * 不会被服务的由受理层落成关闭态并写明原因，不再回到扫描队头。</p>
 *
 * <p>一条通知能不能取走，由 {@link WaitGroupRecoveryIntake} 按「服务所有权 → 业务名额预留 → 消费」
 * 的顺序判定；这里只负责取候选、按轮数上限驱动、推后重试与把放行出来的下一段投给节点池。</p>
 */
@Component
@Slf4j
public class DualPoolRecoveryDispatcher {

    /** 分发器实例标识，写进通知的消费方字段，事后能看出是谁放行的。 */
    private static final String DISPATCHER_ID = "dual-pool-recovery-periodic";

    private final WaitGroupStore waitGroupStore;
    private final AgentRunMapper runMapper;
    private final DualPoolDispatcher dispatcher;
    private final WaitGroupRecoveryIntake intake;
    private final RecoveryBackoff backoff;
    private final int batchSize;
    private final int startupPages;
    /** 每轮留给数据库补扫的固定名额：内存提醒再多也挤不掉它。 */
    private final int scanQuota;
    /** 内存提醒最多压这么多条；满了就丢提醒——数据库才是事实来源。 */
    private final int wakeupCapacity;

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
    private final AtomicLong droppedWakeups = new AtomicLong();
    private final AtomicLong closed = new AtomicLong();
    private final AtomicLong lostRaces = new AtomicLong();

    public DualPoolRecoveryDispatcher(
            WaitGroupStore waitGroupStore,
            AgentRunMapper runMapper,
            DualPoolDispatcher dispatcher,
            WaitGroupRecoveryIntake intake,
            @Value("${agent.langchain.dual-pool.recovery.batch-size:8}") int batchSize,
            @Value("${agent.langchain.dual-pool.recovery.backoff-base-ms:500}") long backoffBaseMs,
            @Value("${agent.langchain.dual-pool.recovery.backoff-max-ms:5000}") long backoffMaxMs,
            @Value("${agent.langchain.dual-pool.recovery.startup-pages:8}") int startupPages,
            @Value("${agent.langchain.dual-pool.recovery.scan-quota:4}") int scanQuota,
            @Value("${agent.langchain.dual-pool.recovery.wakeup-capacity:1024}") int wakeupCapacity) {
        this.waitGroupStore = waitGroupStore;
        this.runMapper = runMapper;
        this.dispatcher = dispatcher;
        this.intake = intake;
        this.backoff = new RecoveryBackoff(Duration.ofMillis(Math.max(1L, backoffBaseMs)),
                Duration.ofMillis(Math.max(Math.max(1L, backoffBaseMs), backoffMaxMs)));
        this.batchSize = Math.max(1, batchSize);
        this.startupPages = Math.max(1, startupPages);
        this.scanQuota = Math.max(1, scanQuota);
        this.wakeupCapacity = Math.max(1, wakeupCapacity);
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
            if (pendingWakeups.contains(notificationId)) {
                return false;
            }
            if (pendingWakeups.size() >= wakeupCapacity) {
                // 提醒满了就丢提醒：它只是「快一点」的优化，数据库补扫才是保证。
                // 丢掉一条不会丢事实——那条通知还在库里等着到期被扫到。
                droppedWakeups.incrementAndGet();
                return false;
            }
            pendingWakeups.add(notificationId);
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
        // 数据库先走，而且至少拿到固定名额：提醒再多也占不住它那一份。提醒少的时候不去浪费预算——
        // 提醒就那么多条，剩下的整份都给补扫，一轮的吞吐不因为保底而变小。
        int hintShare = Math.min(pendingWakeupDepth(), Math.max(0, budget - scanQuota));
        int scanBudget = Math.max(scanQuota, budget - hintShare);
        int handled = scanDue(scanBudget);
        int rest = budget - handled;
        if (rest > 0) {
            handled += drainWakeups(rest);
        }
        return handled;
    }

    /** 内存里还压着几条提醒：在锁里读，读到的是一个完整的深度。 */
    private int pendingWakeupDepth() {
        synchronized (wakeupLock) {
            return pendingWakeups.size();
        }
    }

    /** 按数据库补扫一批到期通知，最多处理这么多条。 */
    private int scanDue(int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<RecoveryNotification> due = waitGroupStore.scanDueRecoveryNotifications(limit);
        scanned.addAndGet(due.size());
        int handled = 0;
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
     * 试一条通知：交给受理层按「服务所有权 → 业务名额预留 → 消费」走一遍，按结局分流。
     *
     * <p>这里不再自己读 Run 判断能不能取：原因由消费语句给出，看到的就是库里的样子。Run 读不回来或
     * 版本不认识时受理层会把它收口——那样的通知永远不会再有下一步，留在扫描队头只会挡住后面的。</p>
     */
    private void attempt(RecoveryNotification notification) {
        if (!notification.consumable()) {
            return;
        }
        AgentRun run = readRun(notification.getRunId());
        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification, run, DISPATCHER_ID);
        switch (result.outcome()) {
            case CONSUMED -> {
                consumed.incrementAndGet();
                deliver(result.nextSegment());
            }
            case CLOSED -> closed.incrementAndGet();
            case LOST_RACE -> lostRaces.incrementAndGet();
            case DEFERRED -> defer(notification, run, result);
        }
    }

    /**
     * 把放行出来的下一段投给节点池。
     *
     * <p>投不进去不算失败：下一段已经是可恢复态、业务名额也已经受理，节点扫描会重新发现它。
     * 这里只记数，不重试投递。</p>
     */
    private void deliver(NodeWorkItemIdentity nextSegment) {
        if (nextSegment == null) {
            // 消费成功却拿不到完整身份：受理层已经把这种情形当成没消费成处理过，走到这里说明
            // 语句与这段代码的假设对不上，必须报出来。
            log.error("恢复通知被消费但没有返回下一段身份，等待链会停住，需要人工检查");
            hintFailed.incrementAndGet();
            return;
        }
        if (!dispatcher.offerNode(nextSegment)) {
            hintFailed.incrementAndGet();
        }
    }

    /**
     * 这一轮取不走：推后下次可见时间。
     *
     * <p>原因分两类记。一类是「本来就不该现在取」——Run 不在执行中、正在取消、通知代际落后、
     * 下一段还没到等待态、这条 Run 的服务所有权在别人手上，这类会随着别的路径推进自己变好；
     * 另一类是「再也不会被服务」，那种已经在受理层落成关闭态，不会走到这里。</p>
     */
    private void defer(RecoveryNotification notification, AgentRun run,
                       WaitGroupRecoveryIntake.IntakeResult result) {
        OffsetDateTime nextVisibleAt = backoff.nextVisibleAt(OffsetDateTime.now(),
                notification.getId(), notification.getCreatedAt());
        boolean pushedLater = waitGroupStore.deferRecoveryNotification(notification.getId(), nextVisibleAt);
        deferred.incrementAndGet();
        String reason = describe(result);
        if (pushedLater) {
            log.debug("恢复通知这一轮取不走，推后到 {}：notification={} runId={} status={} reason={}",
                    nextVisibleAt, notification.getId(), notification.getRunId(),
                    run == null ? null : run.getStatus(), reason);
        } else {
            // 推后没生效：这条通知多半刚被别人取走或关掉，下一次扫描不会再看到它。
            lostRaces.incrementAndGet();
            log.debug("恢复通知推后没有生效（已经不在等待态或时间没变晚）：notification={} reason={}",
                    notification.getId(), reason);
        }
    }

    /** 取不走的原因：优先用语句给的拒绝原因，其次用它给的补充说明。 */
    private static String describe(WaitGroupRecoveryIntake.IntakeResult result) {
        if (result.rejection() != null) {
            return result.detail() == null
                    ? result.rejection().name()
                    : result.rejection().name() + ":" + result.detail();
        }
        return result.detail() == null ? "unknown" : result.detail();
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
        snapshot.put("recoveryClosedTotal", closed.get());
        snapshot.put("recoveryLostRaceTotal", lostRaces.get());
        snapshot.put("recoveryDroppedWakeupsTotal", droppedWakeups.get());
        snapshot.put("recoveryBatchSize", batchSize);
        snapshot.put("recoveryScanQuota", scanQuota);
        snapshot.put("recoveryWakeupCapacity", wakeupCapacity);
        // 提醒深度与刷新窗口在同一把锁里读：并发提醒与出队时不能读到一个看不见的组合。
        synchronized (wakeupLock) {
            snapshot.put("recoveryPendingWakeups", pendingWakeups.size());
        }
        snapshot.put("recoveryBackoff", backoff.describe());
        snapshot.putAll(intake.snapshot());
        return snapshot;
    }

    /** 只读观测：当前正压着的唤醒提醒编号，供诊断与验收用。 */
    public List<Long> pendingWakeupIds() {
        synchronized (wakeupLock) {
            return new ArrayList<>(pendingWakeups);
        }
    }
}
