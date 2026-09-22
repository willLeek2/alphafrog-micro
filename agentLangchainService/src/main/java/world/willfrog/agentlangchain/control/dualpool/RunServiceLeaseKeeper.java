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
 * <p>续期的结果还能当体检用：逐条按代际号条件续期，哪一条续不上就说明它已经被别人按过期接手了。
 * 这种时候本进程在那几条 Run 上已经没有发言权，必须**撤销本进程的准入生命周期**
 * （按 Run 与代际号条件撤销，见 {@link DualPoolRunAdmissionRegistry#revokeOwnership}），
 * 不只是记一条日志：撤销之后协调回合与节点领取不会再为它们发起，已经领取在执行的那一段则由
 * 领取代际收尾（接手方重新排队时加一，旧执行者提交结果会因代际不匹配失败）。</p>
 *
 * <p>退出时不主动让出：进程退出的一瞬间可能还有正在跑的节点执行没有收尾，提前把所有权交出去
 * 会让接手方马上开始，两个进程撞在一条 Run 上。让租约自然过期更稳——最坏也就是多等一个有效期。</p>
 */
@Component
@Slf4j
public class RunServiceLeaseKeeper {

    private final RunServiceLeaseStore leaseStore;
    private final DualPoolRunAdmissionRegistry admissionRegistry;
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
    private final AtomicLong lostRevokedTotal = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /** 最近一轮的读数：清点到几条、续上几条。 */
    private volatile int ownedLastRound;
    private volatile int renewedLastRound;

    public RunServiceLeaseKeeper(
            RunServiceLeaseStore leaseStore,
            DualPoolRunAdmissionRegistry admissionRegistry,
            ProcessInstanceIdentity instanceIdentity,
            @Value("${agent.langchain.dual-pool.service-lease-ttl-seconds:120}") long serviceTtlSeconds,
            @Value("${agent.langchain.dual-pool.service-lease-owned-limit:512}") int ownedLimit,
            @Value("${agent.langchain.dual-pool.service-lease-renew-interval-ms:40000}") long renewIntervalMs,
            FrozenEffectiveSettings frozenEffectiveSettings) {
        this.leaseStore = leaseStore;
        this.admissionRegistry = admissionRegistry;
        this.instanceIdentity = instanceIdentity;
        this.serviceTtl = Duration.ofSeconds(Math.max(1L, serviceTtlSeconds));
        this.ownedLimit = Math.max(1, ownedLimit);
        this.renewIntervalMs = Math.max(1L, renewIntervalMs);
        // 登记归一化之后真正在用的值：读数里这一项报的就是这几个数，不是请求值。
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_TTL_SECONDS,
                COMPONENT, this.serviceTtl.toSeconds());
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_OWNED_LIMIT,
                COMPONENT, this.ownedLimit);
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_RENEW_INTERVAL_MS,
                COMPONENT, this.renewIntervalMs);
    }

    /** 读数与登记里用的组件名：同一个参数有几个消费者时，靠它分清是谁在用的数。 */
    private static final String COMPONENT = "RunServiceLeaseKeeper";

    /**
     * 续期周期的毫秒数：周期任务与读数用的是这一个数。
     *
     * <p>下面 {@link #renewPeriodically()} 上的 {@code @Scheduled} 用 SpEL 直接取这里的值，而不是各自
     * 去读一遍属性：属性值不合法（0 或负数）时这里按 1 毫秒处理，定时那一处若自己读属性就会拿到 0 而
     * 变成不停转的空转，读数上还看不出来。</p>
     */
    public long renewIntervalMillis() {
        return renewIntervalMs;
    }

    /** 进程起来先续一轮：上一次退出到这一次起来之间，手上的租约可能已经过期了。 */
    @EventListener(ApplicationReadyEvent.class)
    public void renewOnStartup() {
        safeRenew();
    }

    @Scheduled(fixedDelayString = "#{@runServiceLeaseKeeper.renewIntervalMillis()}")
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

    /**
     * 续一轮：清点 → 整批续一次（把有界那一页之外的租约也续上）→ 逐条按代际号续，续不上的当场撤销。
     *
     * <p>「续上的条数比清点的条数少」这种做法判不准：整批续期是按持有者写的，它续的是本进程**全部**
     * 租约，而清点的只是有界的一页——本进程手上的 Run 比这一页多的时候，条数永远对得上，被接手的那一条
     * 反而看不出来；反过来，一页里大部分租约被接手时，条数对不上也说不清是哪一条。所以判定改成逐条
     * 条件续期：每一条都带上自己的代际号，续得上的才算还在自己手上，续不上的点得出名、也能当即撤销。</p>
     *
     * @return 这一页里真正续上的条数
     */
    public int renewOwnedOnce() {
        String owner = instanceIdentity.value();
        rounds.incrementAndGet();

        List<RunServiceLease> owned = leaseStore.listOwnedWithLiveRun(owner, ownedLimit);
        ownedLastRound = owned.size();
        if (owned.isEmpty()) {
            renewedLastRound = 0;
            return 0;
        }

        // 这一页之外的租约靠整批续期活着：清点是有界的，本进程手上可能还有更多条。
        leaseStore.renewOwned(owner, serviceTtl);

        List<RunServiceLease> lost = new ArrayList<>();
        int renewed = 0;
        for (RunServiceLease lease : owned) {
            if (leaseStore.renew(lease.runId(), owner, lease.fencingToken(), serviceTtl)) {
                renewed++;
                continue;
            }
            lost.add(lease);
        }
        renewedLastRound = renewed;
        renewedTotal.addAndGet(renewed);
        if (!lost.isEmpty()) {
            lostTotal.addAndGet(lost.size());
            // 光记日志不够：本进程必须立刻不再把自己当成这些 Run 的服务方，否则协调回合与节点
            // 领取还会继续发起（虽然数据库那一层会因凭据不匹配挡下，但那是白跑）。
            // 撤销按「Run + 代际号」条件做：这条 Run 若已经被本进程用新代际重新取得，
            // 新生命周期不会被旧回调删掉。
            for (RunServiceLease lease : lost) {
                boolean revoked = admissionRegistry != null
                        && admissionRegistry.revokeOwnership(lease.runId(), lease.fencingToken());
                lostRevokedTotal.addAndGet(revoked ? 1 : 0);
                log.warn("本进程已经不再持有这条 Run 的服务所有权，撤销本进程的准入: runId={} fence={} 撤销={}",
                        lease.runId(), lease.describe(), revoked);
            }
            log.warn("本进程丢掉了这些 Run 的服务所有权，别的进程接手了它们在跑；"
                            + "已经领取在执行的那一段按领取代际收尾: count={} runIds={}",
                    lost.size(), lost.stream().map(RunServiceLease::runId).toList());
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
        snapshot.put("serviceLeaseLostRevokedTotal", lostRevokedTotal.get());
        snapshot.put("serviceLeaseLostTotal", lostTotal.get());
        snapshot.put("serviceLeaseRenewFailuresTotal", failures.get());
        snapshot.put("serviceLeaseTtlSeconds", serviceTtl.toSeconds());
        snapshot.put("serviceLeaseRenewIntervalMs", renewIntervalMs);
        return snapshot;
    }
}
