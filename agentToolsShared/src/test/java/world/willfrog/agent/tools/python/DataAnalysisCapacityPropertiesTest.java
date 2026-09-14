package world.willfrog.agent.tools.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;

class DataAnalysisCapacityPropertiesTest {

    @Nested
    @DisplayName("Classification rules per §8.1")
    class Classification {

        @Test
        void standardWhenRowsBytesAndHintsAreWithinLimits() {
            DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
            var decision = properties.classify(100_000L, 16L * 1024L * 1024L, List.of());
            assertEquals(DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision.Outcome.ACCEPTED,
                    decision.outcome());
            assertEquals(DataAnalysisResourceClass.STANDARD, decision.resourceClass());
            assertEquals(1, decision.capacityUnits());
            assertEquals(properties.getStandardMemoryLimitBytes(), decision.memoryLimitBytes());
        }

        @Test
        void standardAtExactRowThreshold() {
            DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
            var decision = properties.classify(
                    properties.getStandardRowsMax(),
                    properties.getStandardBytesMax() / 2,
                    List.of());
            assertEquals(DataAnalysisResourceClass.STANDARD, decision.resourceClass());
            assertEquals(1, decision.capacityUnits());
        }

        @Test
        void heavyWhenRowsAboveStandard() {
            DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
            var decision = properties.classify(
                    properties.getStandardRowsMax() + 1,
                    16L * 1024L * 1024L,
                    List.of());
            assertEquals(DataAnalysisResourceClass.HEAVY, decision.resourceClass());
            assertEquals(3, decision.capacityUnits());
            assertEquals(properties.getHeavyMemoryLimitBytes(), decision.memoryLimitBytes());
        }

        @Test
        void heavyWhenBytesAboveStandard() {
            DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
            var decision = properties.classify(
                    50_000L,
                    properties.getStandardBytesMax() + 1,
                    List.of());
            assertEquals(DataAnalysisResourceClass.HEAVY, decision.resourceClass());
            assertEquals(3, decision.capacityUnits());
        }

        @Test
        void heavyWhenHeavyHintsPresentEvenAtSmallRows() {
            DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
            var decision = properties.classify(
                    1_000L,
                    1024L,
                    List.of("groupby-aggregation", "join-large"));
            assertEquals(DataAnalysisResourceClass.HEAVY, decision.resourceClass());
            assertEquals(3, decision.capacityUnits());
        }

        @Test
        void heavyWhenOneRowAboveMaxRowsIsRejected() {
            DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
            var decision = properties.classify(
                    properties.getMaxRowsPerTask() + 1,
                    16L * 1024L * 1024L,
                    List.of());
            assertEquals(DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision.Outcome.REJECTED,
                    decision.outcome());
            assertNull(decision.resourceClass());
            assertEquals(0, decision.capacityUnits());
            assertNotNull(decision.rowsLimit());
        }

        @Test
        void heavyWhenBytesAboveMaxBytesIsRejected() {
            DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
            var decision = properties.classify(
                    100_000L,
                    properties.getMaxBytesPerTask() + 1,
                    List.of());
            assertEquals(DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision.Outcome.REJECTED,
                    decision.outcome());
            assertNull(decision.resourceClass());
        }

        @Test
        void standardWithNullHintsTreatedAsEmpty() {
            DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
            var decision = properties.classify(
                    properties.getStandardRowsMax(),
                    properties.getStandardBytesMax(),
                    null);
            assertEquals(DataAnalysisResourceClass.STANDARD, decision.resourceClass());
            assertEquals(1, decision.capacityUnits());
        }
    }
}