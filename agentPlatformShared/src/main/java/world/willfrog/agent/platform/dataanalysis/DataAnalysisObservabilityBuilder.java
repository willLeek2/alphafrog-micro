package world.willfrog.agent.platform.dataanalysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** 从 terminal envelopes 构造唯一、稳定排序且可校验的 observability snapshot。 */
public final class DataAnalysisObservabilityBuilder {

    private DataAnalysisObservabilityBuilder() {
    }

    public static DataAnalysisObservabilitySnapshot build(
            String runId,
            Collection<DataAnalysisTerminalEnvelope> envelopes) {
        String normalizedRunId = DataAnalysisContractSupport.requireText(runId, "runId");
        List<DataAnalysisObservabilityCall> calls = new ArrayList<>();
        Set<String> attempts = new HashSet<>();
        if (envelopes != null) {
            for (DataAnalysisTerminalEnvelope envelope : envelopes) {
                if (envelope == null) {
                    throw new IllegalArgumentException("envelopes must not contain null");
                }
                if (!normalizedRunId.equals(envelope.runId())) {
                    throw new IllegalArgumentException("every envelope must belong to runId");
                }
                String attemptKey = envelope.toolCallId() + "\u0000" + envelope.attempt();
                if (!attempts.add(attemptKey)) {
                    throw new IllegalArgumentException("duplicate toolCallId/attempt");
                }
                calls.add(DataAnalysisObservabilityCall.fromEnvelope(envelope));
            }
        }
        calls.sort(Comparator.comparing(DataAnalysisObservabilityCall::toolCallId)
                .thenComparingInt(DataAnalysisObservabilityCall::attempt));
        return DataAnalysisObservabilitySnapshot.of(normalizedRunId, calls);
    }

    static DataAnalysisObservabilitySummary summarize(List<DataAnalysisObservabilityCall> calls) {
        List<DataAnalysisObservabilityCall> safeCalls = calls == null ? List.of() : calls;
        Set<String> toolCallIds = new HashSet<>();
        Set<String> missingFields = new TreeSet<>();
        long estimatedRows = 0L;
        long estimatedBytes = 0L;
        long fileCount = 0L;
        long capacityUnits = 0L;
        int oomCount = 0;
        int timeoutCount = 0;
        boolean attributionComplete = true;
        for (DataAnalysisObservabilityCall call : safeCalls) {
            if (call == null) {
                throw new IllegalArgumentException("calls must not contain null");
            }
            toolCallIds.add(call.toolCallId());
            estimatedRows = Math.addExact(estimatedRows, call.estimate().estimatedRows());
            estimatedBytes = Math.addExact(estimatedBytes, call.estimate().estimatedBytes());
            fileCount = Math.addExact(fileCount, call.estimate().fileCount());
            capacityUnits = Math.addExact(capacityUnits, call.estimate().capacityUnits());
            DataAnalysisResourceUsage usage = call.resourceUsage();
            attributionComplete &= usage.attributionComplete();
            missingFields.addAll(usage.missingFields());
            if (usage.oomKilled()) {
                oomCount++;
            }
            if (usage.timedOut()) {
                timeoutCount++;
            }
        }
        return new DataAnalysisObservabilitySummary(
                toolCallIds.size(),
                safeCalls.size(),
                estimatedRows,
                estimatedBytes,
                fileCount,
                capacityUnits,
                sumLongOrNull(safeCalls, call -> call.resourceUsage().cpuMillis()),
                maxLongOrNull(safeCalls, call -> call.resourceUsage().memoryPeakBytes()),
                sumLongOrNull(safeCalls, call -> call.resourceUsage().logicalBytesScanned()),
                sumLongOrNull(safeCalls, call -> call.resourceUsage().queueWaitMillis()),
                sumLongOrNull(safeCalls, call -> call.resourceUsage().prepareMillis()),
                sumLongOrNull(safeCalls, call -> call.resourceUsage().executionWallMillis()),
                sumLongOrNull(safeCalls, call -> call.resourceUsage().cleanupMillis()),
                sumIntegerAsLongOrNull(safeCalls, call -> call.resourceUsage().datasetOpenCount()),
                oomCount,
                timeoutCount,
                attributionComplete,
                List.copyOf(missingFields));
    }

    private static Long sumLongOrNull(
            List<DataAnalysisObservabilityCall> calls,
            Function<DataAnalysisObservabilityCall, Long> extractor) {
        long sum = 0L;
        for (DataAnalysisObservabilityCall call : calls) {
            Long value = extractor.apply(call);
            if (value == null) {
                return null;
            }
            sum = Math.addExact(sum, value);
        }
        return sum;
    }

    private static Long maxLongOrNull(
            List<DataAnalysisObservabilityCall> calls,
            Function<DataAnalysisObservabilityCall, Long> extractor) {
        long max = 0L;
        for (DataAnalysisObservabilityCall call : calls) {
            Long value = extractor.apply(call);
            if (value == null) {
                return null;
            }
            max = Math.max(max, value);
        }
        return max;
    }

    private static Long sumIntegerAsLongOrNull(
            List<DataAnalysisObservabilityCall> calls,
            Function<DataAnalysisObservabilityCall, Integer> extractor) {
        long sum = 0L;
        for (DataAnalysisObservabilityCall call : calls) {
            Integer value = extractor.apply(call);
            if (value == null) {
                return null;
            }
            sum = Math.addExact(sum, value.longValue());
        }
        return sum;
    }
}
