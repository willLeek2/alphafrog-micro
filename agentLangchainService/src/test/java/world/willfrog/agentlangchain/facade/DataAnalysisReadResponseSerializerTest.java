package world.willfrog.agentlangchain.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

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
    void absentStatusSummaryProducesEmptyJson() {
        assertEquals("{}", serializer.serializeStatusFromSummary("r1", Optional.empty()));
    }

    @Test
    void absentResultSnapshotProducesEmptyJson() {
        assertEquals("{}", serializer.serializeResultView(Optional.empty()));
    }

    @Test
    void statusViewFromSummaryIsValidJson() throws Exception {
        var snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        String json = serializer.serializeStatusFromSummary(
                snapshot.runId(), Optional.of(snapshot.summary()));

        ObjectMapper om = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = om.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);

        assertEquals(1, root.get("version"));
        assertTrue(root.containsKey("summary"));
        assertTrue(!root.containsKey("calls"));
    }

    @Test
    void resultViewHasSummaryAndCalls() throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        String json = serializer.serializeResultView(Optional.of(snapshot));

        ObjectMapper om = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = om.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);

        assertTrue(root.containsKey("summary"));
        assertTrue(root.containsKey("calls"));
    }
}
