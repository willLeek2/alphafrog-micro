package world.willfrog.agentlangchain.control.dualpool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务所有权租约的续期循环：本进程正在服务或排队的 Run，趁租约还没过期就把它续下去。
 *
 * <p>不续会怎样：租约到期之后别的进程就能接手，而本进程可能还在跑这条 Run 的节点——两个进程
 * 同时动一条 Run。所以续期的节奏必须明显快于有效期，缺省是 120 秒的租约、40 秒续一次，中间
 * 容得下两次失败。</p>
 *
 * <p>续期的结果还能当体检用：先清点「本该续上几条」，再批量续，续上的比清点的少，说明其中
 * 有租约已经被别人按过期接手了。这种时候本进程在那几条 Run 上已经没有发言权，要留一条能查的
 * 记录点名是哪些——派发器下一轮会因为这些 Run 不再属于自己而不再碰它们（见
 * {@code DatabaseDualPoolWorkHandler} 里的所有权闸门），在飞的那一段执行则按代际号收尾。</p>
 *
 * <p>退出时不主动让出：进程退出的一瞬间可能还有正在跑的节点执行没有收尾，提前把所有权交出去
 * 会让接手方马上开始，两个进程撞在一条 Run 上。让租约自然过期更稳——最坏也就是多等一个有效期。</p>
 */
@Component
@Slf4j
public class RunServiceLeaseKeeper {

    private final RunServiceLeaseStore leaseStore;
    private final ProcessInstanceIdentity instanceIdentity;
    private final Duration serviceTtl;
    private final int ownedLimit;
    private final long renewIntervalMs;

    /** 一轮只走一份：启动入口与周期入口不并发续同一批租约。 */
    private final AtomicBoolean roundInFlight = new AtomicBoolean();

    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong skippedRounds = new AtomicLong();
    private final AtomicLong renewedTotal = new AtomicLong();
    private final AtomicLong lostTotal = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /** 最近一轮的读数：清点到几条、续上几条。 */
    private volatile int ownedLastRound;
    private volatile int renewedLastRound;

    public RunServiceLeaseKeeper(
            RunServiceLeaseStore leaseStore,
            ProcessInstanceIdentity instanceIdentity,
            @Value("${agent.langchain.dual-pool.service-lease-ttl-seconds:120}") long serviceTtlSeconds,
            @Value("${agent.langchain.dual-pool.service-lease-owned-limit:512}") int ownedLimit,
            @Value("${agent.langchain.dual-pool.service-lease-renew-interval-ms:40000}") long renewIntervalMs) {
        this.leaseStore = leaseStore;
        this.instanceIdentity = instanceIdentity;
        this.serviceTtl = Duration.ofSeconds(Math.max(1L, serviceTtlSeconds));
        this.ownedLimit = Math.max(1, ownedLimit);
        this.renewIntervalMs = Math.max(1L, renewIntervalMs);
    }

    /** 进程起来先续一轮：上一次退出到这一次起来之间，手上的租约可能已经过期了。 */
    @EventListener(ApplicationReadyEvent.class)
    public void renewOnStartup() {
        safeRenew();
    }

    @Scheduled(fixedDelayString = "${agent.langchain.dual-pool.service-lease-renew-interval-ms:40000}")
    public void renewPeriodically() {
        safeRenew();
    }

    /** 一轮续期；出错只记日志，不带走调度线程。 */
    public int safeRenew() {
        if (!roundInFlight.compareAndSet(false, true)) {
            skippedRounds.incrementAndGet();
            return 0;
        }
        try {
            return renewOwnedOnce();
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.warn("服务所有权续期这一轮失败，下一轮再试: reason={}", e.getMessage(), e);
            return 0;
        } finally {
            roundInFlight.set(false);
        }
    }

    /** 续一轮：清点 → 批量续 → 对不上就点名那些已经不归自己的。返回续上的条数。 */
    public int renewOwnedOnce() {
        String owner = instanceIdentity.value();
        rounds.incrementAndGet();

        List<RunServiceLease> owned = leaseStore.listOwnedWithLiveRun(owner, ownedLimit);
        ownedLastRound = owned.size();
        if (owned.isEmpty()) {
            renewedLastRound = 0;
            return 0;
        }

        int renewed = leaseStore.renewOwned(owner, serviceTtl);
        renewedLastRound = renewed;
        renewedTotal.addAndGet(renewed);
        if (renewed < owned.size()) {
            // 差数说明有租约被接手了。逐条再试一遍只是为了点名，平常这条路不会走到。
            List<String> lost = new ArrayList<>();
            for (RunServiceLease lease : owned) {
                if (!leaseStore.renew(lease.runId(), owner, lease.fencingToken(), serviceTtl)) {
                    lost.add(lease.runId());
                }
            }
            if (!lost.isEmpty()) {
                lostTotal.addAndGet(lost.size());
                log.warn("本进程已经不再持有这些 Run 的服务所有权，别的进程接手了它们在跑；"
                                + "派发器下一轮不会再动它们，在飞的那一段按代际号收尾: count={} runIds={}",
                        lost.size(), lost);
            }
        }
        return renewed;
    }

    /** 续期的读数：续了多少轮、手上该有多少条、续上多少条、丢了多少条。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("serviceLeaseRenewRounds", rounds.get());
        snapshot.put("serviceLeaseRenewSkippedRounds", skippedRounds.get());
        snapshot.put("serviceLeaseOwnedLastRound", ownedLastRound);
        snapshot.put("serviceLeaseRenewedLastRound", renewedLastRound);
        snapshot.put("serviceLeaseRenewedTotal", renewedTotal.get());
        snapshot.put("serviceLeaseLostTotal", lostTotal.get());
        snapshot.put("serviceLeaseRenewFailuresTotal", failures.get());
        snapshot.put("serviceLeaseTtlSeconds", serviceTtl.toSeconds());
        snapshot.put("serviceLeaseRenewIntervalMs", renewIntervalMs);
        return snapshot;
    }
}
