package world.willfrog.agent.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接收事实的投射修补：把已经落库、但没能投进事件流的那条 {@code RUN_RECEIVED} 重新补上。
 *
 * <p>创建 Run 时事件是先落库、提交之后再投到 Redis 与实时频道。投射那一步失败不回退创建，
 * 于是会出现「数据库里有接收事实、事件流里却没有」——只靠「谁再调一次 appendOnce」是补不上的：
 * 同键重试会直接读回原来那条 Run 并返回，不会天然再走一遍投射。这个组件就是那个消费者。</p>
 *
 * <p>补的是持久事件流：事件流按 seq 记成员，同一行重写一次是幂等的。这里不重发实时事件——
 * 订阅端已经看过的那一条不该因为修补再出现一次。</p>
 *
 * <p>扫的范围绑在事件流自己的保留期上（Redis 那份事件流的 TTL），不是随手定一个「最近几分钟」：
 * 保留期内的每一条接收事实都要有机会被补上，否则 Redis 故障时间一长就会永久缺事件。每一轮做两份
 * 有界的工作：先按时间倒序取最新一页（刚失败的那条要在一轮之内补上），再按 {@code (created_at, id)}
 * 游标往后取一页（把保留期内剩下的扫完，扫到尾巴就从头再来）。启动时先补一轮，之后按间隔周期补。</p>
 *
 * <p>重启会让游标回到保留期的起点，于是头几轮会从最老的一段重新扫一遍；重投是幂等的，代价只是
 * 多写几次同样的成员。</p>
 */
@Component
@Slf4j
public class AgentRunEventProjectionRepair {

    private final AgentRunEventMapper eventMapper;
    private final AgentRunEventRedisStore eventRedisStore;
    private final DeploymentIdentityProvider identityProvider;
    private final Duration retention;
    private final int limit;

    /** 游标：上一页扫到哪一行。只活在内存里，重启后从保留期起点重扫。 */
    private volatile OffsetDateTime cursorCreatedAt;
    private volatile long cursorId;

    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong repaired = new AtomicLong();
    private final AtomicLong cycles = new AtomicLong();

    public AgentRunEventProjectionRepair(
            AgentRunEventMapper eventMapper,
            AgentRunEventRedisStore eventRedisStore,
            DeploymentIdentityProvider identityProvider,
            @Value("${agent.event.received-projection-repair-retention-days:7}") long retentionDays,
            @Value("${agent.event.received-projection-repair-limit:200}") int limit) {
        this.eventMapper = eventMapper;
        this.eventRedisStore = eventRedisStore;
        this.identityProvider = identityProvider;
        // 保留期默认与事件流那份 TTL 对齐：补的是还有读者的那一窗口。
        this.retention = Duration.ofDays(Math.max(1L, retentionDays));
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
        try {
            return repair();
        } catch (RuntimeException e) {
            log.warn("接收事实的投射修补这一轮失败，下一轮再试: reason={}", e.getMessage(), e);
            return 0;
        }
    }

    /** 一轮：最新一页 + 游标往后一页，两份都有界；返回这一轮补投的条数。 */
    public int repair() {
        DeploymentIdentity identity = identityProvider.current();
        rounds.incrementAndGet();
        OffsetDateTime retentionStart = OffsetDateTime.now().minus(retention);

        List<AgentRunEvent> newest = eventMapper.listReceivedFactsForRepair(
                identity.deploymentId(), identity.generationId(), retentionStart, limit);
        int handled = reappended(newest);

        OffsetDateTime cursorAt = cursorCreatedAt;
        long cursorRow = cursorId;
        boolean firstPage = cursorAt == null;
        List<AgentRunEvent> page = eventMapper.listReceivedFactsAfterCursor(
                identity.deploymentId(), identity.generationId(), retentionStart,
                firstPage ? retentionStart : cursorAt, firstPage ? 0L : cursorRow, limit);
        handled += reappended(page);
        if (page.size() < limit) {
            // 这一段扫到尾巴了：下一轮从保留期起点重新开始。
            cursorCreatedAt = null;
            cursorId = 0L;
            cycles.incrementAndGet();
        } else {
            AgentRunEvent last = page.get(page.size() - 1);
            cursorCreatedAt = last.getCreatedAt();
            cursorId = last.getId() == null ? 0L : last.getId();
        }

        repaired.addAndGet(handled);
        if (handled > 0) {
            log.debug("接收事实投射修补：这一轮补投 {} 条，累计 {} 条", handled, repaired.get());
        }
        return handled;
    }

    /**
     * 把这几行重投一次；返回条数。
     *
     * <p>用的是数据库行里原来的序号、时间和负载，事件流的成员与分数与当初那条逐字一致：
     * 重新生成序号或发布时间会让同一条事实在流里变成两条。</p>
     */
    private int reappended(List<AgentRunEvent> events) {
        for (AgentRunEvent event : events) {
            // 同一行重复写进事件流是幂等的：成员相同、分数相同，等于什么都没变。
            eventRedisStore.append(event);
        }
        return events.size();
    }

    /** 修补的读数：跑过多少轮、累计补投多少条。 */
    public java.util.Map<String, Object> snapshot() {
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("receivedProjectionRepairRounds", rounds.get());
        snapshot.put("receivedProjectionRepairedTotal", repaired.get());
        snapshot.put("receivedProjectionRepairRetentionDays", retention.toDays());
        snapshot.put("receivedProjectionRepairCycles", cycles.get());
        snapshot.put("receivedProjectionRepairCursorBehind", cursorCreatedAt == null ? "start" : cursorCreatedAt.toString());
        snapshot.put("receivedProjectionRepairLimit", limit);
        return snapshot;
    }
}
