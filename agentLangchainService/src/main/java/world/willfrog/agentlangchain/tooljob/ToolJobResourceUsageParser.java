package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** RELEASE proof 与 observability recorder 共用的 protobuf JSON usage 解析器。 */
final class ToolJobResourceUsageParser {

    private ToolJobResourceUsageParser() {
    }

    static DataAnalysisResourceUsage parse(
            ObjectMapper objectMapper,
            DataAnalysisResourceClass resourceClass,
            String usageJson) {
        if (usageJson == null || usageJson.isBlank()) {
            return DataAnalysisResourceUsage.missing(resourceClass);
        }
        try {
            JsonNode root = objectMapper.readTree(usageJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("resourceUsage JSON must be an object");
            }
            SandboxResourceUsage.Builder builder = SandboxResourceUsage.newBuilder();
            JsonFormat.parser().merge(usageJson, builder);
            SandboxResourceUsage usage = builder.build();

            String actualResourceClass = usage.getResourceClass().trim();
            if (!actualResourceClass.isEmpty()) {
                DataAnalysisResourceClass parsedResourceClass;
                try {
                    parsedResourceClass = DataAnalysisResourceClass.valueOf(actualResourceClass);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "resourceUsage resourceClass is unsupported: " + actualResourceClass, e);
                }
                if (parsedResourceClass != resourceClass) {
                    throw new IllegalArgumentException(
                            "resourceUsage resourceClass does not match reservation resourceClass");
                }
            }

            Set<String> declaredMissing = new LinkedHashSet<>(usage.getMissingFieldsList());
            if (!DataAnalysisResourceUsage.P0_REQUIRED_MEASURED_FIELDS.containsAll(declaredMissing)) {
                throw new IllegalArgumentException(
                        "resourceUsage missingFields contains an unknown or non-P0 field");
            }

            Long cpu = nullableLong(usage.hasCpuMillis(), usage.getCpuMillis(), "cpuMillis", declaredMissing);
            Long memoryPeak = nullableLong(
                    usage.hasMemoryPeakBytes(), usage.getMemoryPeakBytes(), "memoryPeakBytes", declaredMissing);
            Long logicalBytes = nullableLong(
                    usage.hasLogicalBytesScanned(), usage.getLogicalBytesScanned(),
                    "logicalBytesScanned", declaredMissing);
            Long queueWait = nullableLong(
                    usage.hasQueueWaitMillis(), usage.getQueueWaitMillis(), "queueWaitMillis", declaredMissing);
            Long prepare = nullableLong(
                    usage.hasPrepareMillis(), usage.getPrepareMillis(), "prepareMillis", declaredMissing);
            Long execution = nullableLong(
                    usage.hasExecutionWallMillis(), usage.getExecutionWallMillis(),
                    "executionWallMillis", declaredMissing);
            Long cleanup = nullableLong(
                    usage.hasCleanupMillis(), usage.getCleanupMillis(), "cleanupMillis", declaredMissing);
            Integer datasetOpenCount = nullableInteger(
                    usage.hasDatasetOpenCount(), usage.getDatasetOpenCount(),
                    "datasetOpenCount", declaredMissing);
            String exitReason = declaredMissing.contains("exitReason") || usage.getExitReason().isBlank()
                    ? null : usage.getExitReason().trim();

            List<String> actualMissing = new ArrayList<>();
            addMissing(actualMissing, "cpuMillis", cpu);
            addMissing(actualMissing, "memoryPeakBytes", memoryPeak);
            addMissing(actualMissing, "logicalBytesScanned", logicalBytes);
            addMissing(actualMissing, "queueWaitMillis", queueWait);
            addMissing(actualMissing, "prepareMillis", prepare);
            addMissing(actualMissing, "executionWallMillis", execution);
            addMissing(actualMissing, "cleanupMillis", cleanup);
            addMissing(actualMissing, "datasetOpenCount", datasetOpenCount);
            addMissing(actualMissing, "exitReason", exitReason);

            boolean attributionComplete = root.has("attributionComplete")
                    ? usage.getAttributionComplete()
                    : actualMissing.isEmpty();
            return new DataAnalysisResourceUsage(
                    resourceClass,
                    cpu,
                    memoryPeak,
                    optionalLong(usage.hasMemoryByteMillis(), usage.getMemoryByteMillis()),
                    logicalBytes,
                    optionalLong(usage.hasArtifactBytesWritten(), usage.getArtifactBytesWritten()),
                    optionalLong(usage.hasTemporaryBytesWritten(), usage.getTemporaryBytesWritten()),
                    queueWait,
                    prepare,
                    execution,
                    cleanup,
                    datasetOpenCount,
                    exitReason,
                    usage.getOomKilled(),
                    usage.getTimedOut(),
                    attributionComplete,
                    optionalLong(usage.hasSamplingIntervalMillis(), usage.getSamplingIntervalMillis()),
                    actualMissing);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid resourceUsage protobuf JSON", e);
        }
    }

    private static Long nullableLong(boolean present, long value, String field, Set<String> missing) {
        return present && !missing.contains(field) ? value : null;
    }

    private static Integer nullableInteger(boolean present, int value, String field, Set<String> missing) {
        return present && !missing.contains(field) ? value : null;
    }

    private static Long optionalLong(boolean present, long value) {
        return present ? value : null;
    }

    private static void addMissing(List<String> target, String field, Object value) {
        if (value == null) {
            target.add(field);
        }
    }
}
