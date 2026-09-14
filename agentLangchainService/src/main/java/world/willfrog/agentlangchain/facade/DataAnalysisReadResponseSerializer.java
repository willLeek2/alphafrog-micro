package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySummary;

/**
 * 将 data-analysis observability 序列化为 JSON 字符串，嵌入已有的 observability 响应中。
 * 不直接访问 Redis/DB，响应契约保持不变。
 */
@Component
public class DataAnalysisReadResponseSerializer {

    private final ObjectMapper objectMapper;

    public DataAnalysisReadResponseSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** status 高频轮询路径：仅使用 summary，不加载完整快照。 */
    public String serializeStatusFromSummary(
            String runId,
            Optional<DataAnalysisObservabilitySummary> summary) {
        DataAnalysisReadResponseMapper mapper = new DataAnalysisReadResponseMapper();
        Map<String, Object> view = summary.isPresent()
                ? mapper.buildStatusFromSummary(runId, summary.get())
                : mapper.buildEmptyView();
        return toJson(view);
    }

    /** result / full observability 路径：完整快照含 summary + calls。 */
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
            throw new IllegalStateException("序列化 data-analysis observability 失败", e);
        }
    }
}
