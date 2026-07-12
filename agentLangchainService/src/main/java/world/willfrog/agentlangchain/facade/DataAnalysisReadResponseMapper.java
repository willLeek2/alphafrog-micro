package world.willfrog.agentlangchain.facade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySummary;

/**
 * Builds the {@code data_analysis_observability} response structure for status
 * (summary-only) and result (full) read paths. Does not access Redis/DB directly.
 */
public final class DataAnalysisReadResponseMapper {

    DataAnalysisReadResponseMapper() {
    }

    /**
     * Status (high-frequency polling) view: version + summary only, no calls.
     */
    public Map<String, Object> buildStatusView(DataAnalysisObservabilitySnapshot snapshot) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", snapshot.version());
        root.put("runId", snapshot.runId());
        root.put("summary", serializeSummary(snapshot.summary()));
        return Map.of(DataAnalysisObservabilitySnapshot.ROOT_FIELD, root);
    }

    /**
     * Result / full observability view: version + summary + calls.
     */
    public Map<String, Object> buildResultView(DataAnalysisObservabilitySnapshot snapshot) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", snapshot.version());
        root.put("runId", snapshot.runId());
        root.put("summary", serializeSummary(snapshot.summary()));
        root.put("calls", serializeCalls(snapshot.calls()));
        return Map.of(DataAnalysisObservabilitySnapshot.ROOT_FIELD, root);
    }

    /**
     * Empty response for runs with no data-analysis activity.
     */
    public Map<String, Object> buildEmptyView() {
        return Map.of();
    }

    private Map<String, Object> serializeSummary(DataAnalysisObservabilitySummary s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolCallCount", s.toolCallCount());
        map.put("attemptCount", s.attemptCount());
        map.put("estimatedRows", s.estimatedRows());
        map.put("estimatedBytes", s.estimatedBytes());
        map.put("fileCount", s.fileCount());
        map.put("capacityUnits", s.capacityUnits());
        map.put("cpuMillis", s.cpuMillis());
        map.put("memoryPeakBytes", s.memoryPeakBytes());
        map.put("logicalBytesScanned", s.logicalBytesScanned());
        map.put("queueWaitMillis", s.queueWaitMillis());
        map.put("prepareMillis", s.prepareMillis());
        map.put("executionWallMillis", s.executionWallMillis());
        map.put("cleanupMillis", s.cleanupMillis());
        map.put("datasetOpenCount", s.datasetOpenCount());
        map.put("oomCount", s.oomCount());
        map.put("timeoutCount", s.timeoutCount());
        map.put("attributionComplete", s.attributionComplete());
        map.put("missingFields", s.missingFields());
        return map;
    }

    private List<Map<String, Object>> serializeCalls(List<DataAnalysisObservabilityCall> calls) {
        return calls.stream()
                .map(this::serializeCall)
                .collect(Collectors.toList());
    }

    private Map<String, Object> serializeCall(DataAnalysisObservabilityCall c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolCallId", c.toolCallId());
        map.put("attempt", c.attempt());
        map.put("operationId", c.operationId());
        map.put("taskId", c.taskId());
        map.put("terminalStatus", c.terminalStatus());
        map.put("success", c.success());
        map.put("retryable", c.retryable());
        map.put("terminalAt", c.terminalAt().toString());
        map.put("background", c.background());

        Map<String, Object> estimate = new LinkedHashMap<>();
        estimate.put("estimatedRows", c.estimate().estimatedRows());
        estimate.put("estimatedBytes", c.estimate().estimatedBytes());
        estimate.put("fileCount", c.estimate().fileCount());
        estimate.put("resourceClass", c.estimate().resourceClass().name());
        estimate.put("capacityUnits", c.estimate().capacityUnits());
        map.put("estimate", estimate);

        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("reservationId", c.reservation().reservationId());
        reservation.put("resourceClass", c.reservation().resourceClass().name());
        reservation.put("capacityUnits", c.reservation().capacityUnits());
        reservation.put("taskId", c.reservation().taskId());
        reservation.put("state", c.reservation().state().name());
        map.put("reservation", reservation);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("resourceClass", c.resourceUsage().resourceClass().name());
        usage.put("cpuMillis", c.resourceUsage().cpuMillis());
        usage.put("memoryPeakBytes", c.resourceUsage().memoryPeakBytes());
        usage.put("memoryByteMillis", c.resourceUsage().memoryByteMillis());
        usage.put("logicalBytesScanned", c.resourceUsage().logicalBytesScanned());
        usage.put("artifactBytesWritten", c.resourceUsage().artifactBytesWritten());
        usage.put("temporaryBytesWritten", c.resourceUsage().temporaryBytesWritten());
        usage.put("queueWaitMillis", c.resourceUsage().queueWaitMillis());
        usage.put("prepareMillis", c.resourceUsage().prepareMillis());
        usage.put("executionWallMillis", c.resourceUsage().executionWallMillis());
        usage.put("cleanupMillis", c.resourceUsage().cleanupMillis());
        usage.put("datasetOpenCount", c.resourceUsage().datasetOpenCount());
        usage.put("exitReason", c.resourceUsage().exitReason());
        usage.put("oomKilled", c.resourceUsage().oomKilled());
        usage.put("timedOut", c.resourceUsage().timedOut());
        usage.put("attributionComplete", c.resourceUsage().attributionComplete());
        usage.put("samplingIntervalMillis", c.resourceUsage().samplingIntervalMillis());
        usage.put("missingFields", c.resourceUsage().missingFields());
        map.put("resourceUsage", usage);

        return map;
    }
}
