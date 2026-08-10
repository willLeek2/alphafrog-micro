package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityQuery;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityReadMode;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.Map;

/**
 * 把 data-analysis 可观测读模型叠加到普通 Run status/result JSON 上。
 *
 * <p>这类旁路查询不再挤在 RPC 门面里；失败仍按既有合同保留原响应，不影响用户读取 Run。</p>
 */
final class LangchainDataAnalysisReadOverlay {

    private static final Logger log = LoggerFactory.getLogger(LangchainDataAnalysisReadOverlay.class);

    private final DataAnalysisObservabilityQuery query;
    private final DataAnalysisReadResponseSerializer serializer;
    private final ObjectMapper objectMapper;

    LangchainDataAnalysisReadOverlay(DataAnalysisObservabilityQuery query,
                                     DataAnalysisReadResponseSerializer serializer,
                                     ObjectMapper objectMapper) {
        this.query = query;
        this.serializer = serializer;
        this.objectMapper = objectMapper;
    }

    String mergeStatus(AgentRun run, String existingJson) {
        String runId = run.getId();
        try {
            String dataAnalysisJson = serializer.serializeStatusFromSummary(
                    runId, query.findSummaryByRunId(runId, readMode(run.getStatus())));
            return dataAnalysisJson.equals("{}")
                    ? existingJson
                    : mergeJsonObjects(runId, "status", existingJson, dataAnalysisJson);
        } catch (Exception e) {
            log.warn("合并 data-analysis status 视图失败 runId={} 异常={}/{}",
                    runId, e.getClass().getSimpleName(), e.getMessage());
            return existingJson;
        }
    }

    String mergeResult(AgentRun run, String existingJson) {
        String runId = run.getId();
        try {
            String dataAnalysisJson = serializer.serializeResultView(
                    query.findByRunId(runId, readMode(run.getStatus())));
            return dataAnalysisJson.equals("{}")
                    ? existingJson
                    : mergeJsonObjects(runId, "result", existingJson, dataAnalysisJson);
        } catch (Exception e) {
            log.warn("合并 data-analysis result 视图失败 runId={} 异常={}/{}",
                    runId, e.getClass().getSimpleName(), e.getMessage());
            return existingJson;
        }
    }

    private DataAnalysisObservabilityReadMode readMode(AgentRunStatus status) {
        if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED) {
            return DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY;
        }
        return DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST;
    }

    @SuppressWarnings("unchecked")
    private String mergeJsonObjects(String runId, String view, String baseJson, String overlayJson) {
        if (baseJson == null || baseJson.isBlank()) {
            return overlayJson;
        }
        try {
            JsonNode baseNode = objectMapper.readTree(baseJson);
            JsonNode overlayNode = objectMapper.readTree(overlayJson);
            if (!baseNode.isObject() || !overlayNode.isObject()) {
                log.warn("data-analysis JSON 非对象 runId={} view={}，保留原始响应", runId, view);
                return baseJson;
            }
            Map<String, Object> base = objectMapper.convertValue(baseNode, Map.class);
            base.putAll(objectMapper.convertValue(overlayNode, Map.class));
            return objectMapper.writeValueAsString(base);
        } catch (Exception e) {
            log.warn("JSON merge 失败 runId={} view={} 异常={}/{}，保留原始响应",
                    runId, view, e.getClass().getSimpleName(), e.getMessage());
            return baseJson;
        }
    }
}
