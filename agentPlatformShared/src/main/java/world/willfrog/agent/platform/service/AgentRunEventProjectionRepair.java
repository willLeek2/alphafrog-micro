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
 * 时这一拍直接跳过：两个入口（启动与周期）不并发改同一个游标。单行失败只隔离那一行、游标停在
 * 它前面，下一轮从同一位置再来，不让一个坏行挡住更老的缺口。</p>
 */
@Component
@Slf4j
public class AgentRunEventProjectionRepair {

    private final AgentRunEventMapper eventMapper;
    private final AgentRunEventRedisStore eventRedisStore;
    private final DeploymentIdentityProvider identityProvider;
    private final int limit;

    /** 游标：上一页扫到哪一行。只活在内存里，重启后从保留期起点重扫。 */
    private volatile OffsetDateTime cursorCreatedAt;
    private volatile long cursorId;
    /** 这一轮游标是从什么时候开始走的：用来发现「一轮没扫完就已经快过完保留期」。 */
    private volatile OffsetDateTime cursorStartedAt;

    /** 一轮只走一份：启动入口与周期入口不并发改同一个游标。 */
    private final AtomicBoolean roundInFlight = new AtomicBoolean();

    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong skippedRounds = new AtomicLong();
    private final AtomicLong examined = new AtomicLong();
    private final AtomicLong added = new AtomicLong();
    private final AtomicLong alreadyPresent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cycles = new AtomicLong();

    public AgentRunEventProjectionRepair(
            AgentRunEventMapper eventMapper,
            AgentRunEventRedisStore eventRedisStore,
            DeploymentIdentityProvider identityProvider,
            @Value("${agent.event.received-projection-repair-limit:200}") int limit) {
        this.eventMapper = eventMapper;
        this.eventRedisStore = eventRedisStore;
        this.identityProvider = identityProvider;
        this.limit = Math.max(1, limit);
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

        int repaired = repairNewestPage(deploymentId, retentionStart);
        repaired += repairCursorPage(deploymentId, retentionStart);

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
        return appendMissing(newest, null);
    }

    /**
     * 游标往后一页：把保留期内剩下的扫完，扫到尾巴就回到起点。
     *
     * <p>游标只推进到最后一个成功的行上：中间有一行失败时，下一轮从它前面重来，
     * 不让一个反复失败的坏行把更老的缺口挡在外面。</p>
     */
    private int repairCursorPage(String deploymentId, OffsetDateTime retentionStart) {
        OffsetDateTime cursorAt = cursorCreatedAt;
        long cursorRow = cursorId;
        boolean fromStart = cursorAt == null;
        List<AgentRunEvent> page;
        try {
            page = eventMapper.listReceivedFactsAfterCursor(deploymentId, retentionStart,
                    fromStart ? retentionStart : cursorAt, fromStart ? 0L : cursorRow, limit);
        } catch (RuntimeException e) {
            log.warn("接收事实修补读游标页失败，这一页这一轮跳过: reason={}", e.getMessage(), e);
            return 0;
        }
        CursorProgress progress = new CursorProgress();
        int repaired = appendMissing(page, progress);
        if (progress.halted) {
            // 有行失败：能补的已经补了，但游标只推进到第一个失败位置之前的最后一行，
            // 下一轮从那里重来——失败的行不会被跳过，更老的缺口也不会被它挡住。
            AgentRunEvent lastOk = progress.lastContiguousSuccess;
            if (lastOk != null) {
                cursorCreatedAt = lastOk.getCreatedAt();
                cursorId = lastOk.getId() == null ? 0L : lastOk.getId();
                if (cursorStartedAt == null) {
                    cursorStartedAt = OffsetDateTime.now();
                }
            }
            return repaired;
        }
        if (page.size() < limit) {
            // 这一段扫到尾巴了：下一轮从保留期起点重新开始。
            cursorCreatedAt = null;
            cursorId = 0L;
            cursorStartedAt = null;
            cycles.incrementAndGet();
        } else {
            AgentRunEvent last = page.get(page.size() - 1);
            cursorCreatedAt = last.getCreatedAt();
            cursorId = last.getId() == null ? 0L : last.getId();
            if (cursorStartedAt == null) {
                cursorStartedAt = OffsetDateTime.now();
            }
        }
        return repaired;
    }

    /**
     * 逐行补投；返回真正补进去的条数。
     *
     * <p>{@code progress} 非空时用于记录「游标能不能越过这一页」：某一行为止处理失败就把它标成
     * 停住，调用方据此不推进游标。</p>
     */
    private int appendMissing(List<AgentRunEvent> events, CursorProgress progress) {
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
                if (progress != null && !progress.halted) {
                    // 游标只越过连续成功的那些行；一旦有行失败，后面的成功行补上，但游标停在前面。
                    progress.lastContiguousSuccess = event;
                }
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                log.warn("接收事实补投这一行失败，先隔离、不带走整页: runId={} seq={} reason={}",
                        event.getRunId(), event.getSeq(), e.getMessage());
                if (progress != null) {
                    progress.halted = true;
                }
            }
        }
        return repaired;
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

    /** 一轮里的游标进度：有没有在中途停住、停住之前连续成功到哪一行。 */
    private static final class CursorProgress {
        private AgentRunEvent lastContiguousSuccess;
        private boolean halted;
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
        snapshot.put("receivedProjectionRepairCycles", cycles.get());
        snapshot.put("receivedProjectionRepairCursor",
                cursorCreatedAt == null ? "start" : cursorCreatedAt.toString());
        OffsetDateTime startedAt = cursorStartedAt;
        snapshot.put("receivedProjectionRepairCursorAgeMs",
                startedAt == null ? 0L : Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        snapshot.put("receivedProjectionRetentionDays", eventRedisStore.retention().toDays());
        snapshot.put("receivedProjectionRepairLimit", limit);
        return snapshot;
    }
}
