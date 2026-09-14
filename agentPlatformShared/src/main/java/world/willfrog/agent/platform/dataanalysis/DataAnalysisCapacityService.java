package world.willfrog.agent.platform.dataanalysis;

import java.util.List;

/**
 * 数据分析容量账本的共享接口。T2 提供实现，T0/T3 只依赖该接口。
 *
 * <p>实现当前为单 Java 进程内 in-memory；多实例横向扩展前必须替换为外置持久化实现
 * (Q-03 未裁定前不选具体存储)，否则每个实例独立计容量、跨实例超卖不受保护。</p>
 */
public interface DataAnalysisCapacityService {

    DataAnalysisReservation reserve(
            DataAnalysisOperationIdentity identity,
            DataAnalysisEstimate estimate);

    DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation reservation);

    DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest request);

    DataAnalysisCapacityRecoveryReport recover(
            List<DataAnalysisReservation> durableReservations,
            int configuredMaxUnits,
            int configuredMaxHeavyActive);

    DataAnalysisAdmissionState admissionState();
}
