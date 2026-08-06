package world.willfrog.agent.platform.dataanalysis;

import java.util.List;

/**
 * 数据分析容量账本的共享接口。T2 提供实现，T0/T3 只依赖该接口。
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
