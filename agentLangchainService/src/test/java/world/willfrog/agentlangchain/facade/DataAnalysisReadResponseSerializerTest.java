package world.willfrog.agentlangchain.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityContractFixtures;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;

class DataAnalysisReadResponseSerializerTest {

    private DataAnalysisReadResponseSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new DataAnalysisReadResponseSerializer(new ObjectMapper());
    }

    @Test
    void absentSnapshotProducesEmptyJson() {
        assertEquals("{}", serializer.serializeStatusView(java.util.Optional.empty()));
        assertEquals("{}", serializer.serializeResultView(java.util.Optional.empty()));
    }

    @Test
    void statusViewIsValidJsonWithSummaryOnly() throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        String json = serializer.serializeStatusView(java.util.Optional.of(snapshot));

        ObjectMapper om = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = om.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);

        assertEquals(1, root.get("version"));
        assertEquals("fixture-run-1", root.get("runId"));
        assertTrue(root.containsKey("summary"));
        assertTrue(!root.containsKey("calls"));
    }

    @Test
    void resultViewIsValidJsonWithSummaryAndCalls() throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        String json = serializer.serializeResultView(java.util.Optional.of(snapshot));

        ObjectMapper om = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = om.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);

        assertTrue(root.containsKey("summary"));
        assertTrue(root.containsKey("calls"));
    }

    @Test
    void resultViewCallsAreSortedByToolCallId() throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        String json = serializer.serializeResultView(java.util.Optional.of(snapshot));

        ObjectMapper om = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = om.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> calls = (java.util.List<Map<String, Object>>) root.get("calls");

        assertEquals("call-a", calls.get(0).get("toolCallId"));
        assertEquals("call-b", calls.get(1).get("toolCallId"));
    }
}
