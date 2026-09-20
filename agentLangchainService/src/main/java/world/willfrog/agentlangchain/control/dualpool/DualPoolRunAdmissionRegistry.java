package world.willfrog.agentlangchain.control.dualpool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.capacity.SchedulerPermitLayer;
import world.willfrog.agent.platform.capacity.SchedulerPermitLedger;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 当前进程明确接纳的双池 Run 集合。
 *
 * <p>集合不从数据库恢复，因此进程重启后天然为空。这样旧的非终态双池记录只能读取、观察
 * 和显式取消，不会被提示扫描重新执行。只有当前进程成功创建的新 Run 才能加入集合。</p>
 */
@Component
@Slf4j
public class DualPoolRunAdmissionRegistry {

    /** 一次具体准入预留取得的令牌；数据库受理成功后才把它激活为新的生命周期。 */
    public record Admission(boolean admitted, long epoch) {
        private static Admission rejected() {
            return new Admission(false, -1L);
        }
    }

    private record AdmissionState(long activeEpoch, Set<Long> reservations) {
        private AdmissionState {
            reservations = Set.copyOf(reservations);
        }

        private boolean active() {
            return activeEpoch >= 0L;
        }
    }

    private final Set<String> knownRunIds = ConcurrentHashMap.newKeySet();
    /** activeEpoch 是已持久化一轮的令牌；reservations 是尚未完成数据库条件更新的预留。 */
    private final Map<String, AdmissionState> admissionStates = new ConcurrentHashMap<>();
    private final AtomicLong admissionEpochSequence = new AtomicLong();
    private final SchedulerPermitLedger permitLedger;
    private final NodeWorkItemStore workItemStore;
    private volatile boolean startupResidueBlocked;

    public DualPoolRunAdmissionRegistry(SchedulerPermitLedger permitLedger,
                                        NodeWorkItemStore workItemStore) {
        this.permitLedger = permitLedger;
        this.workItemStore = workItemStore;
    }

    @PostConstruct
    void detectStartupResidue() {
        startupResidueBlocked = workItemStore.hasResidueFor(SchedulerVersion.DUAL_POOL_V1);
        if (startupResidueBlocked) {
            log.error("检测到进程启动前遗留的双池工作项；本进程关闭 DUAL_POOL_V1 新建准入和执行扫描");
        }
    }

    public boolean admitNewRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id_required");
        }
        if (startupResidueBlocked) {
            return false;
        }
        knownRunIds.add(runId);
        return activateNewRun(runId);
    }

    /** 同一进程内的追问或显式恢复可以重新占用业务名额；重启前未知的 Run 一律拒绝。 */
    public boolean admitExistingRun(String runId) {
        Admission admission = admitExistingRunWithLease(runId);
        return admission.admitted() && activateReservedAdmission(runId, admission);
    }

    /**
     * 与 {@link #admitExistingRun(String)} 相同，但把本次取得的 epoch 返回给需要失败回滚的入口。
     */
    public Admission admitExistingRunWithLease(String runId) {
        if (!knownRunIds.contains(runId)) {
            return Admission.rejected();
        }
        long reservationEpoch = admissionEpochSequence.incrementAndGet();
        AtomicBoolean reserved = new AtomicBoolean();
        admissionStates.compute(runId, (ignored, current) -> {
            AdmissionState state = current;
            if (state == null) {
                if (!permitLedger.tryAcquire(SchedulerPermitLayer.BUSINESS_ADMISSION)) {
                    return null;
                }
                state = new AdmissionState(-1L, Set.of());
            }
            Set<Long> reservations = new LinkedHashSet<>(state.reservations());
            reservations.add(reservationEpoch);
            reserved.set(true);
            return new AdmissionState(state.activeEpoch(), reservations);
        });
        return reserved.get()
                ? new Admission(true, reservationEpoch)
                : Admission.rejected();
    }

    /** 数据库已把 Run 持久化为新一轮 RECEIVED 后，把对应预留激活。 */
    public boolean activateReservedAdmission(String runId, Admission admission) {
        if (runId == null || admission == null || !admission.admitted()) {
            return false;
        }
        AtomicBoolean activated = new AtomicBoolean();
        admissionStates.computeIfPresent(runId, (ignored, state) -> {
            if (!state.reservations().contains(admission.epoch())) {
                return state;
            }
            Set<Long> reservations = new LinkedHashSet<>(state.reservations());
            reservations.remove(admission.epoch());
            activated.set(true);
            return new AdmissionState(admission.epoch(), reservations);
        });
        return activated.get();
    }

    /** 数据库条件更新失败时只撤销自己的预留，不能删除其他请求依赖的旧许可或新生命周期。 */
    public boolean rollbackReservedAdmission(String runId, Admission admission) {
        if (runId == null || admission == null || !admission.admitted()) {
            return false;
        }
        AtomicBoolean rolledBack = new AtomicBoolean();
        admissionStates.computeIfPresent(runId, (ignored, state) -> {
            if (!state.reservations().contains(admission.epoch())) {
                return state;
            }
            Set<Long> reservations = new LinkedHashSet<>(state.reservations());
            reservations.remove(admission.epoch());
            rolledBack.set(true);
            if (!state.active() && reservations.isEmpty()) {
                permitLedger.release(SchedulerPermitLayer.BUSINESS_ADMISSION);
                return null;
            }
            return new AdmissionState(state.activeEpoch(), reservations);
        });
        return rolledBack.get();
    }

    public boolean isAdmitted(String runId) {
        AdmissionState state = runId == null ? null : admissionStates.get(runId);
        return state != null && state.active();
    }

    /** 返回当前准入生命周期令牌；没有准入时返回 -1。 */
    public long currentAdmissionEpoch(String runId) {
        AdmissionState state = runId == null ? null : admissionStates.get(runId);
        return state == null ? -1L : state.activeEpoch();
    }

    public boolean isKnownInCurrentProcess(String runId) {
        return runId != null && knownRunIds.contains(runId);
    }

    public boolean startupResidueBlocked() {
        return startupResidueBlocked;
    }

    /** 仅在创建后的调度入口失败时撤销；正常终态仍保留，以便同进程追问继续走原版本。 */
    public void forgetFailedAdmission(String runId) {
        if (runId == null) {
            return;
        }
        releaseBusinessPermit(runId);
        knownRunIds.remove(runId);
    }

    /** Run 到达数据库终态后交还业务名额，但保留同进程身份，允许后续追问重新准入。 */
    public void releaseBusinessPermit(String runId) {
        if (runId != null && admissionStates.remove(runId) != null) {
            permitLedger.release(SchedulerPermitLayer.BUSINESS_ADMISSION);
        }
    }

    /**
     * 仅当准入生命周期仍是调用方冻结的那一代时执行清理并释放许可。
     *
     * <p>条件判断、清理与删除同处一个 {@link ConcurrentHashMap#computeIfPresent} 临界区。
     * 并发追问/恢复要么先换新令牌，使旧回调条件失败；要么等旧回调释放后重新取得许可。
     * 这样不会出现“旧终态回调检查通过后，把新一轮准入删掉”的窗口。</p>
     */
    public boolean releaseBusinessPermitIfCurrent(String runId,
                                                   long expectedEpoch,
                                                   Runnable cleanup) {
        if (runId == null || expectedEpoch < 0L) {
            return false;
        }
        AtomicBoolean released = new AtomicBoolean();
        AtomicReference<RuntimeException> cleanupFailure = new AtomicReference<>();
        admissionStates.computeIfPresent(runId, (ignored, state) -> {
            if (state.activeEpoch() != expectedEpoch || !state.reservations().isEmpty()) {
                return state;
            }
            try {
                if (cleanup != null) {
                    cleanup.run();
                }
            } catch (RuntimeException e) {
                cleanupFailure.set(e);
            }
            // 仍在同一 runId 的 compute 临界区内归还许可。这样等待进入的新一轮准入
            // 不会先看到 key 已删除、却因旧许可尚未归还而被瞬时误拒。
            permitLedger.release(SchedulerPermitLayer.BUSINESS_ADMISSION);
            released.set(true);
            return null;
        });
        RuntimeException failure = cleanupFailure.get();
        if (failure != null) {
            throw failure;
        }
        return released.get();
    }

    public boolean releaseBusinessPermitIfCurrent(String runId, long expectedEpoch) {
        return releaseBusinessPermitIfCurrent(runId, expectedEpoch, null);
    }

    public int admittedCount() {
        return admissionStates.size();
    }

    public Set<String> snapshotRunIds() {
        Set<String> activeRunIds = new LinkedHashSet<>();
        admissionStates.forEach((runId, state) -> {
            if (state.active()) {
                activeRunIds.add(runId);
            }
        });
        return Set.copyOf(activeRunIds);
    }

    private boolean activateNewRun(String runId) {
        AtomicBoolean admitted = new AtomicBoolean();
        admissionStates.compute(runId, (ignored, current) -> {
            if (current != null) {
                admitted.set(true);
                return current;
            }
            if (!permitLedger.tryAcquire(SchedulerPermitLayer.BUSINESS_ADMISSION)) {
                return null;
            }
            long nextEpoch = admissionEpochSequence.incrementAndGet();
            admitted.set(true);
            return new AdmissionState(nextEpoch, Set.of());
        });
        return admitted.get();
    }
}
