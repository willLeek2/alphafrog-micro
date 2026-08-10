package world.willfrog.agent.tools.python;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.CapacityAdmissionException;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisAdmissionState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisRestoreOutcome;

/**
 * Default in-memory implementation of {@link DataAnalysisCapacityService}.
 *
 * <p>The ledger keeps the canonical reservation state, the active capacity counters, and the
 * admission barrier. The barrier is closed ({@link DataAnalysisAdmissionState#RECOVERING}) on
 * construction and only {@link #recover(List, int, int)} may flip it to OPEN / DEGRADED.
 *
 * <p>The service does <em>not</em> own durable persistence. T1 owns the durable index, T0 owns
 * the terminal envelope recorder; this class only enforces the in-memory invariants §8.4 requires.
 *
 * <p>Counter semantics:
 * <ul>
 *   <li>{@code usedUnits} tracks every reservation in any non-RELEASED state, including PREPARING.</li>
 *   <li>{@code activeCount} tracks reservations in TASK_ATTACHED, PENDING_TRANSFERRED,
 *       and TERMINAL_CONFIRMED states.</li>
 *   <li>{@code heavyActiveCount} tracks HEAVY reservations in those active states.</li>
 * </ul>
 */
@Service
public class DataAnalysisCapacityServiceImpl implements DataAnalysisCapacityService {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisCapacityServiceImpl.class);

    private final DataAnalysisCapacityProperties properties;
    private final ConcurrentHashMap<String, DataAnalysisReservation> ledger = new ConcurrentHashMap<>();
    private final AtomicInteger usedUnits = new AtomicInteger();
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicInteger heavyActiveCount = new AtomicInteger();
    private final AtomicReference<DataAnalysisAdmissionState> admissionState =
            new AtomicReference<>(DataAnalysisAdmissionState.RECOVERING);
    private final Object reserveLock = new Object();

    public DataAnalysisCapacityServiceImpl(DataAnalysisCapacityProperties properties) {
        this.properties = properties;
    }

    /** Reset the ledger and counters. Intended for test fixtures; not exposed to runtime callers. */
    void resetForTests() {
        ledger.clear();
        usedUnits.set(0);
        activeCount.set(0);
        heavyActiveCount.set(0);
        admissionState.set(DataAnalysisAdmissionState.RECOVERING);
    }

    @Override
    public DataAnalysisReservation reserve(
            DataAnalysisOperationIdentity identity, DataAnalysisEstimate estimate) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (estimate == null) {
            throw new IllegalArgumentException("estimate must not be null");
        }
        synchronized (reserveLock) {
            DataAnalysisAdmissionState state = admissionState.get();
            if (state == DataAnalysisAdmissionState.RECOVERING) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.RECOVERING,
                        "admission is closed while capacity ledger recovers");
            }
            if (state == DataAnalysisAdmissionState.DEGRADED) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.SERVER_BUSY,
                        "admission is degraded; refusing new reservations");
            }
            DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision decision =
                    properties.classify(estimate.estimatedRows(),
                            estimate.estimatedBytes(),
                            estimate.heavyOperationHints());
            if (decision.outcome()
                    == DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision.Outcome.REJECTED) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.TASK_TOO_LARGE,
                        "task exceeds hard limits rows=" + estimate.estimatedRows()
                                + " bytes=" + estimate.estimatedBytes()
                                + " (limits rows=" + decision.rowsLimit()
                                + " bytes=" + decision.bytesLimit() + ")");
            }
            /*
             * estimate 是工具层已经冻结并即将写入 durable anchor 的资源决策。容量账本仍然
             * 用本地配置重算一次，只用于校验调用方没有把 HEAVY 伪装成 STANDARD；真正写入
             * reservation 的 class/units 必须取 estimate，保证 estimate、reservation、
             * Sandbox request 和 terminal envelope 使用同一份决策。
             */
            if (estimate.resourceClass() != decision.resourceClass()
                    || estimate.capacityUnits() != decision.capacityUnits()) {
                throw new IllegalArgumentException(
                        "estimate resource decision is inconsistent with configured classifier: estimate="
                                + estimate.resourceClass() + "/" + estimate.capacityUnits()
                                + " configured=" + decision.resourceClass() + "/" + decision.capacityUnits());
            }
            DataAnalysisResourceClass resourceClass = estimate.resourceClass();
            int units = estimate.capacityUnits();
            if (ledger.containsKey(identity.reservationId())) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.ALREADY_RESERVED,
                        "reservation already exists: " + identity.reservationId());
            }
            /*
             * maxActive/maxHeavyActive 必须在 createTask 之前检查。PREPARING 虽然还没有 taskId，
             * 但 reserve 返回后调用方下一步就是创建 Sandbox 任务；若此处不把 PREPARING 算作
             * 已占用的准入名额，第三个请求会先产生真实 Sandbox 副作用，随后才在
             * PREPARING→TASK_ATTACHED 被拒绝，留下 orphan task 和无法释放的 PREPARING unit。
             *
             * activeCount/heavyActiveCount 的历史观测语义保持不变：它们只统计已经附着 taskId
             * 的 reservation。这里单独扫描所有非 RELEASED reservation，表达的是“已承诺的
             * pre-create 名额”，不能与运行中 active 指标混为一谈。
             */
            int admittedReservations = admittedReservationCount();
            if (admittedReservations + 1 > properties.getMaxActive()) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.SERVER_BUSY,
                        "no active slots left (admitted=" + admittedReservations
                                + " requested=1 max=" + properties.getMaxActive() + ")");
            }
            int admittedHeavyReservations = admittedHeavyReservationCount();
            if (resourceClass == DataAnalysisResourceClass.HEAVY
                    && admittedHeavyReservations + 1 > properties.getMaxHeavyActive()) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.SERVER_BUSY,
                        "no heavy slots left (admittedHeavy=" + admittedHeavyReservations
                                + " requested=1 max=" + properties.getMaxHeavyActive() + ")");
            }
            if (usedUnits.get() + units > properties.getMaxUnits()) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.SERVER_BUSY,
                        "no units left (used=" + usedUnits.get()
                                + " requested=" + units
                                + " max=" + properties.getMaxUnits() + ")");
            }
            DataAnalysisReservation reservation = new DataAnalysisReservation(
                    identity.reservationId(),
                    identity,
                    resourceClass,
                    units,
                    DataAnalysisReservationState.PREPARING,
                    null,
                    Instant.now());
            DataAnalysisReservation prior = ledger.putIfAbsent(
                    identity.reservationId(), reservation);
            if (prior != null) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.ALREADY_RESERVED,
                        "reservation already exists: " + identity.reservationId());
            }
            usedUnits.addAndGet(units);
            log.info("data-analysis reservation reserved id={} class={} units={}",
                    identity.reservationId(), resourceClass, units);
            return reservation;
        }
    }

    @Override
    public DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation must not be null");
        }
        synchronized (reserveLock) {
            DataAnalysisReservation existing = ledger.get(reservation.reservationId());
            if (existing != null) {
                if (existing.equals(reservation)) {
                    return DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME;
                }
                return applyTransition(existing, reservation);
            }
            DataAnalysisAdmissionState state = admissionState.get();
            if (state == DataAnalysisAdmissionState.RECOVERING) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.RECOVERING,
                        "cannot restore while capacity ledger recovers");
            }
            // Only active states count toward capacity; PREPARING without taskId is invalid by contract.
            if (!isActiveState(reservation.state())) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.ILLEGAL_RESTORE,
                        "cannot restore reservation in non-active state: " + reservation.state());
            }
            if (usedUnits.get() + reservation.capacityUnits() > properties.getMaxUnits()
                    || activeCount.get() + 1 > properties.getMaxActive()
                    || (reservation.resourceClass() == DataAnalysisResourceClass.HEAVY
                            && heavyActiveCount.get() + 1 > properties.getMaxHeavyActive())) {
                throw new CapacityAdmissionException(
                        CapacityAdmissionException.Reason.SERVER_BUSY,
                        "restore would exceed configured capacity");
            }
            DataAnalysisReservation prior = ledger.putIfAbsent(reservation.reservationId(), reservation);
            if (prior != null) {
                if (prior.equals(reservation)) {
                    return DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME;
                }
                return applyTransition(prior, reservation);
            }
            usedUnits.addAndGet(reservation.capacityUnits());
            activeCount.incrementAndGet();
            if (reservation.resourceClass() == DataAnalysisResourceClass.HEAVY) {
                heavyActiveCount.incrementAndGet();
            }
            return DataAnalysisRestoreOutcome.ADDED;
        }
    }

    /**
     * Apply a state transition for a reservation already present in the ledger. Used both for
     * PREPARING → TASK_ATTACHED promotion once a Sandbox task is created and for the subsequent
     * PENDING_TRANSFERRED / TERMINAL_CONFIRMED hops.
     */
    private DataAnalysisRestoreOutcome applyTransition(
            DataAnalysisReservation existing, DataAnalysisReservation next) {
        if (!existing.identity().equals(next.identity())) {
            log.warn("data-analysis reservation restore conflict id={} identity mismatch",
                    existing.reservationId());
            return DataAnalysisRestoreOutcome.CONFLICT;
        }
        if (existing.resourceClass() != next.resourceClass()
                || existing.capacityUnits() != next.capacityUnits()) {
            log.warn("data-analysis reservation restore conflict id={} resource class or units mismatch",
                    existing.reservationId());
            return DataAnalysisRestoreOutcome.CONFLICT;
        }
        // taskId is bound when the reservation leaves PREPARING and must stay stable thereafter.
        if (existing.taskId() != null && !existing.taskId().equals(next.taskId())) {
            log.warn("data-analysis reservation restore conflict id={} taskId drift {} -> {}",
                    existing.reservationId(), existing.taskId(), next.taskId());
            return DataAnalysisRestoreOutcome.CONFLICT;
        }
        if (!existing.state().canTransitionTo(next.state())) {
            log.warn("data-analysis reservation restore conflict id={} invalid transition {} -> {}",
                    existing.reservationId(), existing.state(), next.state());
            return DataAnalysisRestoreOutcome.CONFLICT;
        }
        boolean wasActive = isActiveState(existing.state());
        boolean isActive = isActiveState(next.state());
        if (!wasActive && isActive
                && (activeCount.get() + 1 > properties.getMaxActive()
                        || (next.resourceClass() == DataAnalysisResourceClass.HEAVY
                                && heavyActiveCount.get() + 1 > properties.getMaxHeavyActive()))) {
            throw new CapacityAdmissionException(
                    CapacityAdmissionException.Reason.SERVER_BUSY,
                    "transition would exceed configured capacity");
        }
        DataAnalysisReservation replaced = ledger.get(existing.reservationId());
        if (replaced != null && !replaced.equals(existing)) {
            // Concurrent modification between read and replace; fall back to a fresh lookup.
            if (replaced.equals(next)) {
                return DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME;
            }
            return DataAnalysisRestoreOutcome.CONFLICT;
        }
        ledger.put(existing.reservationId(), next);
        if (!wasActive && isActive) {
            activeCount.incrementAndGet();
            if (next.resourceClass() == DataAnalysisResourceClass.HEAVY) {
                heavyActiveCount.incrementAndGet();
            }
        } else if (wasActive && !isActive) {
            activeCount.decrementAndGet();
            if (next.resourceClass() == DataAnalysisResourceClass.HEAVY) {
                heavyActiveCount.decrementAndGet();
            }
        }
        return DataAnalysisRestoreOutcome.ADDED;
    }

    @Override
    public DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        DataAnalysisReservation reservation = request.reservation();
        synchronized (reserveLock) {
            DataAnalysisReservation current = ledger.get(reservation.reservationId());
            if (current == null) {
                return DataAnalysisReleaseOutcome.NOT_FOUND;
            }
            if (current.state() == DataAnalysisReservationState.RELEASED) {
                // Idempotent: the ledger already shows RELEASED for this reservationId.
                return DataAnalysisReleaseOutcome.ALREADY_RELEASED;
            }
            // The proof-bearing contract already enforces the allowed state machine for the
            // proof type. We mirror that here so the ledger does not silently release a
            // reservation whose state is still bound to a live task.
            if (!current.state().canTransitionTo(DataAnalysisReservationState.RELEASED)) {
                return DataAnalysisReleaseOutcome.CONFLICT;
            }
            DataAnalysisReservation released = new DataAnalysisReservation(
                    current.reservationId(),
                    current.identity(),
                    current.resourceClass(),
                    current.capacityUnits(),
                    DataAnalysisReservationState.RELEASED,
                    current.taskId(),
                    current.acquiredAt());
            if (!ledger.replace(current.reservationId(), current, released)) {
                DataAnalysisReservation after = ledger.get(current.reservationId());
                if (after != null && after.state() == DataAnalysisReservationState.RELEASED) {
                    return DataAnalysisReleaseOutcome.ALREADY_RELEASED;
                }
                return DataAnalysisReleaseOutcome.CONFLICT;
            }
            releaseCounters(current);
            log.info("data-analysis reservation released id={} reason={}",
                    current.reservationId(), request.reason());
            return DataAnalysisReleaseOutcome.RELEASED;
        }
    }

    @Override
    public DataAnalysisCapacityRecoveryReport recover(
            List<DataAnalysisReservation> durableReservations,
            int configuredMaxUnits,
            int configuredMaxHeavyActive) {
        if (configuredMaxUnits <= 0) {
            throw new IllegalArgumentException("configuredMaxUnits must be positive");
        }
        if (configuredMaxHeavyActive < 0) {
            throw new IllegalArgumentException("configuredMaxHeavyActive must be non-negative");
        }
        List<DataAnalysisReservation> input = durableReservations == null ? List.of() : durableReservations;
        synchronized (reserveLock) {
            ledger.clear();
            usedUnits.set(0);
            activeCount.set(0);
            heavyActiveCount.set(0);

            List<String> conflicts = new ArrayList<>();
            // Group by reservationId to detect "same id, different content".
            java.util.Map<String, List<DataAnalysisReservation>> byId = new java.util.LinkedHashMap<>();
            for (DataAnalysisReservation reservation : input) {
                byId.computeIfAbsent(reservation.reservationId(), key -> new ArrayList<>()).add(reservation);
            }
            List<DataAnalysisReservation> accepted = new ArrayList<>(input.size());
            for (java.util.Map.Entry<String, List<DataAnalysisReservation>> entry : byId.entrySet()) {
                String reservationId = entry.getKey();
                List<DataAnalysisReservation> sameId = entry.getValue();
                DataAnalysisReservation canonical = sameId.get(0);
                for (int i = 1; i < sameId.size(); i++) {
                    if (!sameId.get(i).equals(canonical)) {
                        conflicts.add(reservationId);
                    }
                }
                if (!isActiveState(canonical.state())) {
                    continue; // PREPARING without active resolution or already RELEASED entries are dropped.
                }
                accepted.add(canonical);
            }

            int restored = 0;
            int heavyActive = 0;
            int unitsUsed = 0;
            for (DataAnalysisReservation reservation : accepted) {
                ledger.put(reservation.reservationId(), reservation);
                restored++;
                unitsUsed += reservation.capacityUnits();
                if (reservation.resourceClass() == DataAnalysisResourceClass.HEAVY) {
                    heavyActive++;
                }
            }
            usedUnits.set(unitsUsed);
            activeCount.set(restored);
            heavyActiveCount.set(heavyActive);

            boolean overConfigured = unitsUsed > configuredMaxUnits;
            boolean heavyOverConfigured = heavyActive > configuredMaxHeavyActive;
            DataAnalysisAdmissionState next;
            if (!conflicts.isEmpty() || overConfigured || heavyOverConfigured) {
                next = DataAnalysisAdmissionState.DEGRADED;
            } else {
                next = DataAnalysisAdmissionState.OPEN;
            }
            admissionState.set(next);
            List<String> sortedConflicts = conflicts.stream().sorted().toList();
            log.info("data-analysis capacity recovered restored={} usedUnits={}/{} heavyActive={}/{} conflicts={} state={}",
                    restored, unitsUsed, configuredMaxUnits, heavyActive, configuredMaxHeavyActive,
                    sortedConflicts, next);
            return new DataAnalysisCapacityRecoveryReport(
                    restored,
                    restored,
                    heavyActive,
                    unitsUsed,
                    configuredMaxUnits,
                    configuredMaxHeavyActive,
                    overConfigured,
                    heavyOverConfigured,
                    sortedConflicts,
                    next);
        }
    }

    @Override
    public DataAnalysisAdmissionState admissionState() {
        return admissionState.get();
    }

    /** Active states occupy an active slot. PREPARING only consumes units. */
    private static boolean isActiveState(DataAnalysisReservationState state) {
        return state == DataAnalysisReservationState.TASK_ATTACHED
                || state == DataAnalysisReservationState.PENDING_TRANSFERRED
                || state == DataAnalysisReservationState.TERMINAL_CONFIRMED;
    }

    /**
     * 返回已经获得 pre-create 准入承诺、尚未 RELEASE 的 reservation 数。
     *
     * <p>调用方必须持有 {@link #reserveLock}。账本保留 RELEASED 项用于幂等查询，所以不能用
     * {@code ledger.size()}；PREPARING 又必须计入，否则 maxActive 仍会在 Sandbox create 之后
     * 才生效。</p>
     */
    private int admittedReservationCount() {
        return (int) ledger.values().stream()
                .filter(reservation -> reservation.state() != DataAnalysisReservationState.RELEASED)
                .count();
    }

    /**
     * 返回已经获得 pre-create 准入承诺的 HEAVY reservation 数。
     * 与 {@code heavyActiveCount} 不同，本值包含 PREPARING，用于阻止第二个 HEAVY 任务先创建
     * Sandbox 再失败。
     */
    private int admittedHeavyReservationCount() {
        return (int) ledger.values().stream()
                .filter(reservation -> reservation.state() != DataAnalysisReservationState.RELEASED)
                .filter(reservation -> reservation.resourceClass() == DataAnalysisResourceClass.HEAVY)
                .count();
    }

    private void releaseCounters(DataAnalysisReservation reservation) {
        // Reservations occupy units from the moment of reserve; only the active counters
        // come down when the reservation leaves an active state, which happens here.
        usedUnits.addAndGet(-reservation.capacityUnits());
        if (isActiveState(reservation.state())) {
            activeCount.decrementAndGet();
            if (reservation.resourceClass() == DataAnalysisResourceClass.HEAVY) {
                heavyActiveCount.decrementAndGet();
            }
        }
    }

    /** Snapshot of the current ledger. Test-only visibility. */
    Map<String, DataAnalysisReservation> ledgerSnapshot() {
        return Map.copyOf(ledger);
    }

    int usedUnitsSnapshot() {
        return usedUnits.get();
    }

    int activeCountSnapshot() {
        return activeCount.get();
    }

    int heavyActiveCountSnapshot() {
        return heavyActiveCount.get();
    }

    /** Static factory used by tests to keep dependency wiring free. */
    public static DataAnalysisCapacityServiceImpl withProperties(DataAnalysisCapacityProperties properties) {
        return new DataAnalysisCapacityServiceImpl(properties);
    }
}
