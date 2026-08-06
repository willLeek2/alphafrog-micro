package world.willfrog.agent.platform.dataanalysis;

import java.util.Optional;

/** Read 层唯一消费的 data-analysis observability facade。 */
public interface DataAnalysisObservabilityQuery {

    /** 高频 status 路径只读取轻量 summary，不能为了丢弃 calls 而先加载完整快照。 */
    Optional<DataAnalysisObservabilitySummary> findSummaryByRunId(
            String runId,
            DataAnalysisObservabilityReadMode mode);

    /** result/full observability 路径读取 summary + calls 的完整快照。 */
    Optional<DataAnalysisObservabilitySnapshot> findByRunId(
            String runId,
            DataAnalysisObservabilityReadMode mode);
}
