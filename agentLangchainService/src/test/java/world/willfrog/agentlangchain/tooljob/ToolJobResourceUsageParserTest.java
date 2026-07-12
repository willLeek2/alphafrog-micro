package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ToolJobResourceUsageParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void protobufJsonPreservesMeasuredZeroAndIsIdenticalForReleaseProof() throws Exception {
        SandboxResourceUsage usage = completeUsage().setCpuMillis(0).setDatasetOpenCount(0).build();
        String json = json(usage);

        DataAnalysisResourceUsage parsed = ToolJobResourceUsageParser.parse(
                objectMapper, DataAnalysisResourceClass.STANDARD, json);
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                mock(ToolJobAnchorService.class), mock(ToolJobRedisCache.class),
                mock(world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class));

        assertThat(parsed.cpuMillis()).isZero();
        assertThat(parsed.datasetOpenCount()).isZero();
        assertThat(parsed.attributionComplete()).isTrue();
        assertThat(finalizer.buildResourceUsage(DataAnalysisResourceClass.STANDARD, json))
                .isEqualTo(parsed);
    }

    @Test
    void loaderPartialTurnsAmbiguousZerosIntoStableP0Missing() throws Exception {
        SandboxResourceUsage usage = completeUsage()
                .setLogicalBytesScanned(0)
                .setDatasetOpenCount(0)
                .setAttributionComplete(false)
                .addMissingFields("logicalBytesScanned")
                .addMissingFields("datasetOpenCount")
                .build();

        DataAnalysisResourceUsage parsed = ToolJobResourceUsageParser.parse(
                objectMapper, DataAnalysisResourceClass.STANDARD, json(usage));

        assertThat(parsed.logicalBytesScanned()).isNull();
        assertThat(parsed.datasetOpenCount()).isNull();
        assertThat(parsed.attributionComplete()).isFalse();
        assertThat(parsed.missingFields())
                .containsExactly("datasetOpenCount", "logicalBytesScanned");
    }

    @Test
    void samplingPartialTurnsAmbiguousValuesIntoStableP0Missing() throws Exception {
        SandboxResourceUsage usage = completeUsage()
                .setCpuMillis(123)
                .setMemoryPeakBytes(456)
                .setAttributionComplete(false)
                .addMissingFields("cpuMillis")
                .addMissingFields("memoryPeakBytes")
                .build();

        DataAnalysisResourceUsage parsed = ToolJobResourceUsageParser.parse(
                objectMapper, DataAnalysisResourceClass.STANDARD, json(usage));

        assertThat(parsed.cpuMillis()).isNull();
        assertThat(parsed.memoryPeakBytes()).isNull();
        assertThat(parsed.attributionComplete()).isFalse();
        assertThat(parsed.missingFields()).containsExactly("cpuMillis", "memoryPeakBytes");
    }

    @Test
    void diagnosticOnlyMissingCauseFailsClosedInsteadOfUpgradingToComplete() throws Exception {
        SandboxResourceUsage usage = completeUsage()
                .setAttributionComplete(false)
                .addMissingFields("containerSampling")
                .build();

        assertThatThrownBy(() -> ToolJobResourceUsageParser.parse(
                objectMapper, DataAnalysisResourceClass.STANDARD, json(usage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown or non-P0");
    }

    @Test
    void actualResourceClassMismatchFailsClosedInsteadOfBeingOverwrittenByReservation() throws Exception {
        SandboxResourceUsage usage = completeUsage().setResourceClass("HEAVY").build();

        assertThatThrownBy(() -> ToolJobResourceUsageParser.parse(
                objectMapper, DataAnalysisResourceClass.STANDARD, json(usage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match reservation");
    }

    @Test
    void legacyBlankResourceClassUsesDurableReservationClass() throws Exception {
        SandboxResourceUsage usage = completeUsage().clearResourceClass().build();

        DataAnalysisResourceUsage parsed = ToolJobResourceUsageParser.parse(
                objectMapper, DataAnalysisResourceClass.HEAVY, json(usage));

        assertThat(parsed.resourceClass()).isEqualTo(DataAnalysisResourceClass.HEAVY);
    }

    private SandboxResourceUsage.Builder completeUsage() {
        return SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setCpuMillis(11)
                .setMemoryPeakBytes(22)
                .setLogicalBytesScanned(33)
                .setQueueWaitMillis(1)
                .setPrepareMillis(2)
                .setExecutionWallMillis(3)
                .setCleanupMillis(4)
                .setDatasetOpenCount(5)
                .setExitReason("SUCCESS")
                .setAttributionComplete(true)
                .setSamplingIntervalMillis(100);
    }

    private String json(SandboxResourceUsage usage) throws Exception {
        return JsonFormat.printer().omittingInsignificantWhitespace().print(usage);
    }
}
