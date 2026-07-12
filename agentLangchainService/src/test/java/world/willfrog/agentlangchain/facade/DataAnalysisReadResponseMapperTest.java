package world.willfrog.agentlangchain.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityContractFixtures;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;

class DataAnalysisReadResponseMapperTest {

    private final DataAnalysisReadResponseMapper mapper = new DataAnalysisReadResponseMapper();

    @Test
    void emptyViewReturnsEmptyMap() {
        assertTrue(mapper.buildEmptyView().isEmpty());
    }

    @Test
    void statusViewContainsSummaryButNoCalls() {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        Map<String, Object> view = mapper.buildStatusView(snapshot);

        assertEquals(1, view.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) view.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);
        assertEquals(1, root.get("version"));
        assertEquals("fixture-run-1", root.get("runId"));
        assertTrue(root.containsKey("summary"));
        assertTrue(!root.containsKey("calls"));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) root.get("summary");
        assertEquals(2, summary.get("toolCallCount"));
        assertEquals(2, summary.get("attemptCount"));
        assertEquals(300L, summary.get("estimatedRows"));
        assertEquals(1, summary.get("oomCount"));
        assertEquals(true, summary.get("attributionComplete"));
    }

    @Test
    void resultViewContainsSummaryAndCalls() {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        Map<String, Object> view = mapper.buildResultView(snapshot);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) view.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);
        assertTrue(root.containsKey("summary"));
        assertTrue(root.containsKey("calls"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) root.get("calls");
        assertEquals(2, calls.size());
        assertEquals("call-a", calls.get(0).get("toolCallId"));
        assertEquals("call-b", calls.get(1).get("toolCallId"));

        Map<String, Object> callA = calls.get(0);
        assertEquals(1, callA.get("attempt"));
        assertEquals(false, callA.get("background"));
        assertEquals("COMPLETED", callA.get("terminalStatus"));

        @SuppressWarnings("unchecked")
        Map<String, Object> estimate = (Map<String, Object>) callA.get("estimate");
        assertEquals(100L, estimate.get("estimatedRows"));
        assertEquals("STANDARD", estimate.get("resourceClass"));
    }

    @Test
    void resultViewPreservesOomKilledCallFields() {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        Map<String, Object> view = mapper.buildResultView(snapshot);

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) view.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) root.get("calls");
        // call-b is OOM-killed per canonicalV1 fixture
        Map<String, Object> callB = calls.get(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> usageB = (Map<String, Object>) callB.get("resourceUsage");
        assertEquals(true, usageB.get("oomKilled"));
        assertEquals("SANDBOX_OOM", usageB.get("exitReason"));
    }
}
