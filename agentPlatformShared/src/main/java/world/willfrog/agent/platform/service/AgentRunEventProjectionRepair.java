package world.willfrog.agent.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接收事实的投射修补：把已经落库、但没能投进事件流的那条 {@code RUN_RECEIVED} 补上。
 *
 * <p>创建 Run 时事件是先落库、提交之后再投到 Redis 与实时频道。投射那一步失败不回退创建，
 * 于是会出现「数据库里有接收事实、事件流里却没有」——只靠「谁再调一次 appendOnce」是补不上的：
 * 同键重试会直接读回原来那条 Run 并返回，不会天然再走一遍投射。这个组件就是那个消费者。</p>
 *
 * <p>补的是持久事件流，用的是库里的那一行：首次投射也改成按库里那一行做，所以两条路取到的
 * 时间与负载逐字一致，同一个事件在流里只会有一个成员。补投走
 * {@link AgentRunEventRedisStore#repairMissing}：成员已经在就不写、不续期，缺了才补，
 * 而且只补「按这条事实自己的时间算还剩的寿命」。这里不重发实时事件——订阅端已经看过的那一条
 * 不该因为补投再出现一次。</p>
 *
 * <p>扫的范围绑在事件流自己的保留期上（{@link AgentRunEventRedisStore#retention()}，与写入时
 * 用的是同一份值），并按部署取——不按构建代际：滚动替换时旧实例可能在「库里已提交、事件流
 * 还没投」的窗口里退场，按当前代际过滤会把那道缺口永远关在门外。每一轮做两份有界的工作：
 * 先按时间倒序取最新一页（刚失败的那条要在一轮之内补上），再按 {@code (created_at, id)}
 * 游标往后取一页。启动时先补一轮，之后按间隔周期补。</p>
 *
 * <p>游标只活在内存里，重启后从保留期起点重扫；重投是幂等的，代价只是多查几页。上一轮没走完
 * 时这一拍直接跳过：两个入口（启动与周期）不并发改同一个游标。</p>
 *
 * <p>一轮不只走一页：只要这一轮还有时间（{@code agent.event.received-projection-repair-round-budget-ms}）
 * 且没到最多页数（{@code agent.event.received-projection-repair-max-pages-per-round}），就继续往后走。
 * 这样一轮能扫多少由这两项决定，而不是被写死成「一拍一页」——一拍一页时，只要新事实来的速度比
 * 扫的速度快，游标就会一直落在保留期窗口的中段：它前面的行还没扫到就先过期了，而每拍都只补最新一页，
 * 中间这一段永远轮不到。每轮条数、拍间隔、每轮页数三个旋钮要按峰值写入速度配。</p>
 *
 * <p>某一行补不进去（比如这条事实的负载在库里被人改坏了）时：它进一份有界的重试清单，
 * 每一轮开头重新读回库里那一行再试一次；扫描游标照常往前走，不停在它前面。坏行要是长期补不进去，
 * 清单满了就丢最老的并记一笔——扫描继续往下走比守着一个坏行重要，丢掉的这些也在读数里看得见。</p>
 */
@Component
@Slf4j
public class AgentRunEventProjectionRepair {

    private final AgentRunEventMapper eventMapper;
    private final AgentRunEventRedisStore eventRedisStore;
    private final DeploymentIdentityProvider identityProvider;
    private final int limit;
    /** 一轮最多往后走几页。 */
    private final int maxPagesPerRound;
    /** 一轮最多走多久；到点就收工，剩下的留给下一拍，别占着调度线程。 */
    private final long roundBudgetMs;

    /** 游标：上一页扫到哪一行。只活在内存里，重启后从保留期起点重扫。 */
    private volatile OffsetDateTime cursorCreatedAt;
    private volatile long cursorId;
    /** 这一轮游标是从什么时候开始走的：用来发现「一轮没扫完就已经快过完保留期」。 */
    private volatile OffsetDateTime cursorStartedAt;

    /** 一轮只走一份：启动入口与周期入口不并发改同一个游标。 */
    private final AtomicBoolean roundInFlight = new AtomicBoolean();

    /** 补不进去的那些行：下一轮重新从库里读回来再试，不让它们挡住扫描。 */
    private final LinkedHashSet<FailedRow> failedRows = new LinkedHashSet<>();
    /** 重试清单的上限：满了先丢最老的，丢掉的在读数里记一笔。 */
    private static final int FAILED_ROWS_LIMIT = 512;

    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong skippedRounds = new AtomicLong();
    private final AtomicLong examined = new AtomicLong();
    private final AtomicLong added = new AtomicLong();
    private final AtomicLong alreadyPresent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong failedRetried = new AtomicLong();
    private final AtomicLong failedAbandoned = new AtomicLong();
    /** 最近一轮往后走了几页：配合每轮条数，够不够跟上写入速度看这个数。 */
    private volatile int pagesThisRound;

    public AgentRunEventProjectionRepair(
            AgentRunEventMapper eventMapper,
            AgentRunEventRedisStore eventRedisStore,
            DeploymentIdentityProvider identityProvider,
            @Value("${agent.event.received-projection-repair-limit:200}") int limit,
            @Value("${agent.event.received-projection-repair-max-pages-per-round:16}") int maxPagesPerRound,
            @Value("${agent.event.received-projection-repair-round-budget-ms:2000}") long roundBudgetMs) {
        this.eventMapper = eventMapper;
        this.eventRedisStore = eventRedisStore;
        this.identityProvider = identityProvider;
        this.limit = Math.max(1, limit);
        this.maxPagesPerRound = Math.max(1, maxPagesPerRound);
        this.roundBudgetMs = Math.max(1L, roundBudgetMs);
    }

    /** 进程起来先补一轮：重启前那一刻投射失败的接收事实，等下一个周期就太久了。 */
    @EventListener(ApplicationReadyEvent.class)
    public void repairOnStartup() {
        safeRepair();
    }

    @Scheduled(fixedDelayString = "${agent.event.received-projection-repair-interval-ms:30000}")
    public void repairPeriodically() {
        safeRepair();
    }

    /** 一轮修补；出错只记日志，不带走调度线程，也不影响别的路径。 */
    public int safeRepair() {
        if (!roundInFlight.compareAndSet(false, true)) {
            // 上一轮还没走完：这一拍不进来，两个入口不并发推进同一个游标。
            skippedRounds.incrementAndGet();
            return 0;
        }
        try {
            return repair();
        } catch (RuntimeException e) {
            log.warn("接收事实的投射修补这一轮失败，下一轮再试: reason={}", e.getMessage(), e);
            return 0;
        } finally {
            roundInFlight.set(false);
        }
    }

    /** 一轮：最新一页 + 游标往后一页，两份都有界；返回这一轮真正补进去的条数。 */
    public int repair() {
        // 保留期只有一份出处：写入事件流、算剩余寿命、这里回扫窗口读的是同一个值。
        Duration retention = eventRedisStore.retention();
        String deploymentId = identityProvider.current().deploymentId();
        rounds.incrementAndGet();
        OffsetDateTime retentionStart = OffsetDateTime.now().minus(retention);

        int repaired = retryFailedRows();
        repaired += repairNewestPage(deploymentId, retentionStart);

        // 往后走：一轮里能走几页就尽量走，直到扫到尾巴、用满页数、或者这一轮的时间用完。
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(roundBudgetMs).toNanos();
        int pages = 0;
        while (pages < maxPagesPerRound && System.nanoTime() < deadlineNanos) {
            PageResult walked = repairCursorPage(deploymentId, retentionStart);
            repaired += walked.repaired();
            pages++;
            if (walked.exhausted()) {
                break;
            }
        }
        pagesThisRound = pages;

        warnIfCycleTooSlow(retention);
        return repaired;
    }

    /** 最新一页：只补，不动游标——刚失败的那条要在一轮之内被看到。 */
    private int repairNewestPage(String deploymentId, OffsetDateTime retentionStart) {
        List<AgentRunEvent> newest;
        try {
            newest = eventMapper.listReceivedFactsForRepair(deploymentId, retentionStart, limit);
        } catch (RuntimeException e) {
            log.warn("接收事实修补读最新一页失败，这一页这一轮跳过: reason={}", e.getMessage(), e);
            return 0;
        }
        return appendMissing(newest);
    }

    /**
     * 游标往后一页：把保留期内剩下的扫完，扫到尾巴就回到起点。
     *
     * <p>返回「补进去多少条」和「窗口是不是扫到头了」：扫到头就该停，没扫到头还能接着往后走。
     * 中间有行失败不影响游标前进——失败的行进重试清单，扫描不等它。</p>
     */
    private PageResult repairCursorPage(String deploymentId, OffsetDateTime retentionStart) {
        OffsetDateTime cursorAt = cursorCreatedAt;
        long cursorRow = cursorId;
        boolean fromStart = cursorAt == null;
        List<AgentRunEvent> page;
        try {
            page = eventMapper.listReceivedFactsAfterCursor(deploymentId, retentionStart,
                    fromStart ? retentionStart : cursorAt, fromStart ? 0L : cursorRow, limit);
        } catch (RuntimeException e) {
            log.warn("接收事实修补读游标页失败，这一页这一轮跳过: reason={}", e.getMessage(), e);
            // 读不了也当成「这一段先走不下去」，免得在一个读不出东西的位置上把这一轮的时间用光。
            return new PageResult(0, true);
        }
        int repaired = appendMissing(page);
        if (page.size() < limit) {
            // 这一段扫到尾巴了：下一轮从保留期起点重新开始。
            cursorCreatedAt = null;
            cursorId = 0L;
            cursorStartedAt = null;
            cycles.incrementAndGet();
            return new PageResult(repaired, true);
        }
        AgentRunEvent last = page.get(page.size() - 1);
        cursorCreatedAt = last.getCreatedAt();
        cursorId = last.getId() == null ? 0L : last.getId();
        if (cursorStartedAt == null) {
            cursorStartedAt = OffsetDateTime.now();
        }
        return new PageResult(repaired, false);
    }

    /** 一页走下来的结果：补进去多少条、窗口有没有扫到头。 */
    private record PageResult(int repaired, boolean exhausted) {
    }

    /**
     * 逐行补投；返回真正补进去的条数。补不进去的行进重试清单，扫描不停在它前面。
     */
    private int appendMissing(List<AgentRunEvent> events) {
        int repaired = 0;
        for (AgentRunEvent event : events) {
            examined.incrementAndGet();
            try {
                if (eventRedisStore.repairMissing(event)) {
                    added.incrementAndGet();
                    repaired++;
                } else {
                    // 已经在流里：这一行什么都不用做，重复扫到它是正常现象。
                    alreadyPresent.incrementAndGet();
                }
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                rememberFailed(event);
                log.warn("接收事实补投这一行失败，先记下来下一轮再试、扫描继续往前走: runId={} seq={} reason={}",
                        event.getRunId(), event.getSeq(), e.getMessage());
            }
        }
        return repaired;
    }

    /**
     * 把这一轮补不进去的行重新试一遍：从库里读回那一行再补。
     *
     * <p>重新读是有意的：重试用的是库里的当前内容，不是第一次失败时抓在手里的那份——负载要是被人
     * 改坏了，改回来之后下一轮就能补上。</p>
     */
    private int retryFailedRows() {
        List<FailedRow> batch;
        synchronized (failedRows) {
            batch = List.copyOf(failedRows).stream().limit(limit).toList();
        }
        int repaired = 0;
        for (FailedRow row : batch) {
            examined.incrementAndGet();
            AgentRunEvent event = eventMapper.findByRunIdAndSeq(row.runId(), row.seq());
            if (event == null) {
                // 这一行在库里已经读不回来了：没有可补的东西，从清单里去掉。
                synchronized (failedRows) {
                    failedRows.remove(row);
                }
                continue;
            }
            try {
                if (eventRedisStore.repairMissing(event)) {
                    added.incrementAndGet();
                    repaired++;
                } else {
                    alreadyPresent.incrementAndGet();
                }
                synchronized (failedRows) {
                    failedRows.remove(row);
                }
                failedRetried.incrementAndGet();
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                log.debug("重试仍然补不进去，留在清单里等下一轮: runId={} seq={} reason={}",
                        row.runId(), row.seq(), e.getMessage());
            }
        }
        return repaired;
    }

    /** 记住一条补不进去的行；清单满了丢最老的并记一笔。 */
    private void rememberFailed(AgentRunEvent event) {
        FailedRow row = new FailedRow(event.getRunId(), event.getSeq(),
                event.getId() == null ? 0L : event.getId());
        synchronized (failedRows) {
            if (failedRows.contains(row)) {
                return;
            }
            while (failedRows.size() >= FAILED_ROWS_LIMIT) {
                FailedRow oldest = failedRows.iterator().next();
                failedRows.remove(oldest);
                failedAbandoned.incrementAndGet();
            }
            failedRows.add(row);
        }
    }

    /** 补不进去的那一行：只记定位它的三样，内容下一轮从库里重新读。 */
    private record FailedRow(String runId, int seq, long id) {
    }

    /**
     * 一轮从保留期起点扫到现在的耗时接近保留期时，说明照这个速度会有事实在扫到之前先过期。
     *
     * <p>这一轮没扫完不是错误，但是「补投来不及」的先兆：留一条能查的记录，别等缺口出现才发现。</p>
     */
    private void warnIfCycleTooSlow(Duration retention) {
        OffsetDateTime startedAt = cursorStartedAt;
        if (startedAt == null) {
            return;
        }
        Duration elapsed = Duration.between(startedAt, OffsetDateTime.now());
        if (elapsed.compareTo(retention.dividedBy(2)) > 0) {
            log.warn("接收事实补投一轮已经走了 {}，超过保留期（{}）的一半：照这个速度窗口里的事实"
                            + "可能在扫到之前先过期，需要调大每轮条数或缩短间隔",
                    elapsed, retention);
        }
    }

    /** 修补的读数：扫过多少、真正补进去多少、已经在里面多少、失败多少，以及游标走到哪。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("receivedProjectionRepairRounds", rounds.get());
        snapshot.put("receivedProjectionRepairSkippedRounds", skippedRounds.get());
        snapshot.put("receivedProjectionRepairExaminedTotal", examined.get());
        snapshot.put("receivedProjectionRepairAddedTotal", added.get());
        snapshot.put("receivedProjectionRepairAlreadyPresentTotal", alreadyPresent.get());
        snapshot.put("receivedProjectionRepairFailedTotal", failed.get());
        snapshot.put("receivedProjectionRepairFailedRetriedTotal", failedRetried.get());
        snapshot.put("receivedProjectionRepairFailedAbandonedTotal", failedAbandoned.get());
        synchronized (failedRows) {
            snapshot.put("receivedProjectionRepairFailedPending", failedRows.size());
        }
        snapshot.put("receivedProjectionRepairCycles", cycles.get());
        snapshot.put("receivedProjectionRepairCursor",
                cursorCreatedAt == null ? "start" : cursorCreatedAt.toString());
        OffsetDateTime startedAt = cursorStartedAt;
        snapshot.put("receivedProjectionRepairCursorAgeMs",
                startedAt == null ? 0L : Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        snapshot.put("receivedProjectionRetentionDays", eventRedisStore.retention().toDays());
        snapshot.put("receivedProjectionRepairLimit", limit);
        snapshot.put("receivedProjectionRepairPagesThisRound", pagesThisRound);
        snapshot.put("receivedProjectionRepairMaxPagesPerRound", maxPagesPerRound);
        snapshot.put("receivedProjectionRepairRoundBudgetMs", roundBudgetMs);
        return snapshot;
    }
}
