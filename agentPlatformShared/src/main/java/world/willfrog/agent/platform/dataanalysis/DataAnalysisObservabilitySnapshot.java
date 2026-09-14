package world.willfrog.agent.platform.dataanalysis;

import java.util.List;

/** Data-analysis observability 的稳定 v1 读取契约。 */
public record DataAnalysisObservabilitySnapshot(
        int version,
        String runId,
        DataAnalysisObservabilitySummary summary,
        List<DataAnalysisObservabilityCall> calls) {

    public static final int CURRENT_VERSION = 1;
    public static final String ROOT_FIELD = "data_analysis_observability";

    public DataAnalysisObservabilitySnapshot {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported data-analysis observability version");
        }
        runId = DataAnalysisContractSupport.requireText(runId, "runId");
        if (summary == null) {
            throw new IllegalArgumentException("summary must not be null");
        }
        calls = calls == null ? List.of() : List.copyOf(calls);
        DataAnalysisObservabilitySummary expected = DataAnalysisObservabilityBuilder.summarize(calls);
        if (!expected.equals(summary)) {
            throw new IllegalArgumentException("summary must equal calls aggregation");
        }
        for (DataAnalysisObservabilityCall call : calls) {
            if (!call.reservation().identity().runId().equals(runId)) {
                throw new IllegalArgumentException("every call must belong to snapshot runId");
            }
        }
    }

    public static DataAnalysisObservabilitySnapshot of(
            String runId,
            List<DataAnalysisObservabilityCall> calls) {
        List<DataAnalysisObservabilityCall> immutableCalls = calls == null
                ? List.of()
                : List.copyOf(calls);
        return new DataAnalysisObservabilitySnapshot(
                CURRENT_VERSION,
                runId,
                DataAnalysisObservabilityBuilder.summarize(immutableCalls),
                immutableCalls);
    }
}
