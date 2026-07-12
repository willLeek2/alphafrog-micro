package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;

@Component
public class DataAnalysisReadResponseSerializer {

    private final ObjectMapper objectMapper;

    public DataAnalysisReadResponseSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serializeStatusView(Optional<DataAnalysisObservabilitySnapshot> snapshot) {
        DataAnalysisReadResponseMapper mapper = new DataAnalysisReadResponseMapper();
        Map<String, Object> view = snapshot.isPresent()
                ? mapper.buildStatusView(snapshot.get())
                : mapper.buildEmptyView();
        return toJson(view);
    }

    public String serializeResultView(Optional<DataAnalysisObservabilitySnapshot> snapshot) {
        DataAnalysisReadResponseMapper mapper = new DataAnalysisReadResponseMapper();
        Map<String, Object> view = snapshot.isPresent()
                ? mapper.buildResultView(snapshot.get())
                : mapper.buildEmptyView();
        return toJson(view);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize data-analysis observability", e);
        }
    }
}
