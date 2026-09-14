package world.willfrog.agent.platform.dataanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DataAnalysisResourceUsageP012Test {

    private static final long MB100 = 1024L * 1024 * 100;
    private static final long MB10 = 1024L * 1024 * 10;

    // -----------------------------------------------------------------------
    // P0-12 core scenario: collector failure (cpuMillis missing)
    // -----------------------------------------------------------------------

    @Test
    void cpuMillisMissingProducesAttributionIncomplete() {
        DataAnalysisResourceUsage usage = new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                null,                    // cpuMillis — MISSING (simulated Docker stats failure)
                MB100,                   // memoryPeakBytes = 100MB
                null,                    // memoryByteMillis
                MB10,                    // logicalBytesScanned = 10MB
                null,                    // artifactBytesWritten
                null,                    // temporaryBytesWritten
                150L,                    // queueWaitMillis
                200L,                    // prepareMillis
                5000L,                   // executionWallMillis
                100L,                    // cleanupMillis
                3,                       // datasetOpenCount
                "SUCCEEDED",             // exitReason
                false,                   // oomKilled
                false,                   // timedOut
                false,                   // attributionComplete
                null,                    // samplingIntervalMillis
                List.of("cpuMillis")     // missingFields — must exactly match null P0 fields
        );

        assertThat(usage.attributionComplete()).isFalse();
        assertThat(usage.missingFields()).containsExactly("cpuMillis");
        assertThat(usage.cpuMillis()).isNull();

        // All 8 other P0 required measured fields are present with correct values
        assertThat(usage.memoryPeakBytes()).isEqualTo(MB100);
        assertThat(usage.logicalBytesScanned()).isEqualTo(MB10);
        assertThat(usage.queueWaitMillis()).isEqualTo(150L);
        assertThat(usage.prepareMillis()).isEqualTo(200L);
        assertThat(usage.executionWallMillis()).isEqualTo(5000L);
        assertThat(usage.cleanupMillis()).isEqualTo(100L);
        assertThat(usage.datasetOpenCount()).isEqualTo(3);
        assertThat(usage.exitReason()).isEqualTo("SUCCEEDED");

        // Non-P0 fields
        assertThat(usage.resourceClass()).isEqualTo(DataAnalysisResourceClass.STANDARD);
        assertThat(usage.memoryByteMillis()).isNull();
        assertThat(usage.artifactBytesWritten()).isNull();
        assertThat(usage.temporaryBytesWritten()).isNull();
        assertThat(usage.samplingIntervalMillis()).isNull();
        assertThat(usage.oomKilled()).isFalse();
        assertThat(usage.timedOut()).isFalse();
    }

    // -----------------------------------------------------------------------
    // P0-12 complementary positive case
    // -----------------------------------------------------------------------

    @Test
    void allFieldsPresentProducesAttributionComplete() {
        DataAnalysisResourceUsage usage = new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.HEAVY,
                1200L,                   // cpuMillis
                MB100,                   // memoryPeakBytes
                null,                    // memoryByteMillis
                MB10,                    // logicalBytesScanned
                null,                    // artifactBytesWritten
                null,                    // temporaryBytesWritten
                150L,                    // queueWaitMillis
                200L,                    // prepareMillis
                5000L,                   // executionWallMillis
                100L,                    // cleanupMillis
                3,                       // datasetOpenCount
                "SUCCEEDED",             // exitReason
                false,                   // oomKilled
                false,                   // timedOut
                true,                    // attributionComplete
                null,                    // samplingIntervalMillis
                List.of()                // missingFields — empty, all P0 fields present
        );

        assertThat(usage.attributionComplete()).isTrue();
        assertThat(usage.missingFields()).isEmpty();

        // All 9 P0 fields have non-null values
        assertThat(usage.cpuMillis()).isEqualTo(1200L);
        assertThat(usage.memoryPeakBytes()).isEqualTo(MB100);
        assertThat(usage.logicalBytesScanned()).isEqualTo(MB10);
        assertThat(usage.queueWaitMillis()).isEqualTo(150L);
        assertThat(usage.prepareMillis()).isEqualTo(200L);
        assertThat(usage.executionWallMillis()).isEqualTo(5000L);
        assertThat(usage.cleanupMillis()).isEqualTo(100L);
        assertThat(usage.datasetOpenCount()).isEqualTo(3);
        assertThat(usage.exitReason()).isEqualTo("SUCCEEDED");
    }

    // -----------------------------------------------------------------------
    // Constructor invariant: missingFields must exactly match null P0 fields
    // -----------------------------------------------------------------------

    @Test
    void missingFieldsMustExactlyMatchNullP0Fields() {
        // missingFields declares "cpuMillis" as missing but cpuMillis is actually provided (non-null)
        assertThatThrownBy(() -> new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                1L,                      // cpuMillis — PRESENT
                MB100,                   // memoryPeakBytes
                null,                    // memoryByteMillis
                MB10,                    // logicalBytesScanned
                null,                    // artifactBytesWritten
                null,                    // temporaryBytesWritten
                150L,                    // queueWaitMillis
                200L,                    // prepareMillis
                5000L,                   // executionWallMillis
                100L,                    // cleanupMillis
                3,                       // datasetOpenCount
                "SUCCEEDED",             // exitReason
                false,                   // oomKilled
                false,                   // timedOut
                false,                   // attributionComplete
                null,                    // samplingIntervalMillis
                List.of("cpuMillis")     // claims cpuMillis is missing, but it is not
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("missingFields must exactly match null P0 required measured fields");
    }

    @Test
    void missingFieldsRejectsNonP0Field() {
        // memoryByteMillis is NOT in P0_REQUIRED_MEASURED_FIELDS
        assertThatThrownBy(() -> new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                1L,
                MB100,
                null,                    // memoryByteMillis
                MB10,
                null,
                null,
                150L,
                200L,
                5000L,
                100L,
                3,
                "SUCCEEDED",
                false,
                false,
                true,
                null,
                List.of("memoryByteMillis")    // not a P0 field
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown or non-P0 field");
    }

    // -----------------------------------------------------------------------
    // Constructor invariant: attributionComplete requires all P0 fields present
    // -----------------------------------------------------------------------

    @Test
    void attributionCompleteRequiresNoMissingFields() {
        // attributionComplete=true but cpuMillis is null → every P0 field must be present
        assertThatThrownBy(() -> new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                null,                    // cpuMillis — missing
                MB100,                   // memoryPeakBytes
                null,                    // memoryByteMillis
                MB10,                    // logicalBytesScanned
                null,                    // artifactBytesWritten
                null,                    // temporaryBytesWritten
                150L,                    // queueWaitMillis
                200L,                    // prepareMillis
                5000L,                   // executionWallMillis
                100L,                    // cleanupMillis
                3,                       // datasetOpenCount
                "SUCCEEDED",             // exitReason
                false,                   // oomKilled
                false,                   // timedOut
                true,                    // attributionComplete conflicts with missing field
                null,                    // samplingIntervalMillis
                List.of("cpuMillis")     // accurate missingFields but incompatible with complete
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("complete attribution requires every P0 measured field");
    }

    @Test
    void attributionIncompleteRequiresAtLeastOneMissingP0Field() {
        // attributionComplete=false but no fields are actually missing
        assertThatThrownBy(() -> new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                1L,                      // cpuMillis — present
                MB100,                   // memoryPeakBytes — present
                null,                    // memoryByteMillis
                MB10,                    // logicalBytesScanned — present
                null,                    // artifactBytesWritten
                null,                    // temporaryBytesWritten
                150L,                    // queueWaitMillis — present
                200L,                    // prepareMillis — present
                5000L,                   // executionWallMillis — present
                100L,                    // cleanupMillis — present
                3,                       // datasetOpenCount — present
                "SUCCEEDED",             // exitReason — present
                false,                   // oomKilled
                false,                   // timedOut
                false,                   // attributionComplete=false but all P0 fields present
                null,                    // samplingIntervalMillis
                List.of()                // empty missingFields
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("partial attribution must declare at least one missing P0 field");
    }

    // -----------------------------------------------------------------------
    // P0-12: multiple missing fields scenario
    // -----------------------------------------------------------------------

    @Test
    void multipleMissingFields() {
        DataAnalysisResourceUsage usage = new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                null,                    // cpuMillis — missing
                null,                    // memoryPeakBytes — missing
                null,                    // memoryByteMillis
                MB10,                    // logicalBytesScanned
                null,                    // artifactBytesWritten
                null,                    // temporaryBytesWritten
                150L,                    // queueWaitMillis
                200L,                    // prepareMillis
                5000L,                   // executionWallMillis
                100L,                    // cleanupMillis
                3,                       // datasetOpenCount
                "SUCCEEDED",             // exitReason
                false,                   // oomKilled
                false,                   // timedOut
                false,                   // attributionComplete
                null,                    // samplingIntervalMillis
                List.of("cpuMillis", "memoryPeakBytes")
        );

        assertThat(usage.attributionComplete()).isFalse();
        assertThat(usage.missingFields()).containsExactly("cpuMillis", "memoryPeakBytes");
        assertThat(usage.cpuMillis()).isNull();
        assertThat(usage.memoryPeakBytes()).isNull();

        // Remaining 7 P0 fields are present
        assertThat(usage.logicalBytesScanned()).isEqualTo(MB10);
        assertThat(usage.queueWaitMillis()).isEqualTo(150L);
        assertThat(usage.prepareMillis()).isEqualTo(200L);
        assertThat(usage.executionWallMillis()).isEqualTo(5000L);
        assertThat(usage.cleanupMillis()).isEqualTo(100L);
        assertThat(usage.datasetOpenCount()).isEqualTo(3);
        assertThat(usage.exitReason()).isEqualTo("SUCCEEDED");
    }

    // -----------------------------------------------------------------------
    // Static factory: DataAnalysisResourceUsage.missing()
    // -----------------------------------------------------------------------

    @Test
    void staticFactoryMissingProducesAllFieldsMissing() {
        DataAnalysisResourceUsage usage = DataAnalysisResourceUsage.missing(
                DataAnalysisResourceClass.HEAVY);

        assertThat(usage.attributionComplete()).isFalse();
        assertThat(usage.missingFields())
                .containsExactlyInAnyOrderElementsOf(
                        DataAnalysisResourceUsage.P0_REQUIRED_MEASURED_FIELDS);
        assertThat(usage.missingFields()).hasSize(9);

        // Accept any sort order — the constructor sorts, and the static factory
        // passes P0_REQUIRED_MEASURED_FIELDS.sorted() so the list is deterministic
        assertThat(usage.missingFields()).isSorted();
        assertThat(usage.resourceClass()).isEqualTo(DataAnalysisResourceClass.HEAVY);
    }
}
