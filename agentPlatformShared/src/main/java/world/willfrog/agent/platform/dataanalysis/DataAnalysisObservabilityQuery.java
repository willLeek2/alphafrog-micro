package world.willfrog.agent.platform.dataanalysis;

import java.util.Optional;

/** Read 层唯一消费的 data-analysis observability facade。 */
@FunctionalInterface
public interface DataAnalysisObservabilityQuery {

    Optional<DataAnalysisObservabilitySnapshot> findByRunId(String runId);
}
