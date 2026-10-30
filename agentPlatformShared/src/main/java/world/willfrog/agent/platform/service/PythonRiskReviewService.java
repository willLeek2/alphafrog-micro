package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.dataanalysis.PythonRiskDecisionRow;
import world.willfrog.agent.platform.mapper.PythonRiskDecisionMapper;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.input.AgentModelInputs;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.prompt.LightweightPromptPreparation;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Python 脚本派发前的可选评分；同一持久操作号只接受第一次落库的决定。 */
@Service
@RequiredArgsConstructor
public class PythonRiskReviewService {
    private final AgentLlmProperties baseProperties;
    private final AgentLlmLocalConfigLoader configLoader;
    private final AgentLlmResolver llmResolver;
    private final AgentAiServiceFactory aiServiceFactory;
    private final PythonRiskDecisionMapper decisionMapper;
    private final ObjectMapper objectMapper;
    @Autowired
    private LightweightPromptPreparation promptPreparation;
    @Autowired
    private AgentModelInputs modelInputs;

    public Evaluation evaluate(String operationId, String runId, String script) {
        if (operationId == null || operationId.isBlank() || runId == null || runId.isBlank()) {
            throw new Unavailable("Python 审查缺少持久调用身份");
        }
        try {
            PythonRiskDecisionRow saved = decisionMapper.findByOperation(operationId);
            if (saved != null) {
                return fromSaved(saved, runId, true);
            }

            if (!configLoader.hotConfigIsAuthoritative()) {
                throw new Unavailable("Python 审查热配置尚未就绪");
            }
            AgentLlmProperties local = configLoader.currentSnapshot().config();
            AgentLlmProperties.Agent agent = local == null ? baseProperties.getAgent() : local.getAgent();
            AgentLlmProperties.PythonRiskReview config = agent == null
                    ? new AgentLlmProperties.PythonRiskReview() : agent.getPythonRiskReview();
            if (config == null) {
                config = new AgentLlmProperties.PythonRiskReview();
            }
            boolean enabled = Boolean.TRUE.equals(config.getEnabled());
            String endpoint = trim(config.getEndpointName());
            String modelName = trim(config.getModelName());
            Integer threshold = config.getThreshold();
            Integer score = null;
            String decision = "PASS";
            if (enabled) {
                if (endpoint.isEmpty() || modelName.isEmpty() || threshold == null
                        || threshold < 0 || threshold > 100 || script == null) {
                    throw new Unavailable("Python 审查模型或阈值配置无效");
                }
                // 模型端点和允许列表从本次捕获的同一份热配置解析；热推不能在中途换路由。
                AgentLlmResolver.ResolvedLlm route = llmResolver.resolveFromSnapshot(
                        endpoint, modelName, local);
                AgentContext.StructuredOutputSpec previousSpec = AgentContext.getStructuredOutputSpec();
                ChatResponse response;
                try {
                    AgentContext.clearStructuredOutputSpec();
                    if (promptPreparation == null || modelInputs == null || !runId.equals(AgentContext.getRunId())) {
                        throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                                "Python审查缺少当前运行的正文或输入保存上下文");
                    }
                    List<PromptMessage> messages = List.of(
                            PromptMessage.system(promptPreparation.policy("pythonRiskReviewSystemPrompt")),
                            PromptMessage.runtimeData(new UserMessage(script)));
                    ChatModel model = aiServiceFactory.buildArchivedPythonRiskReviewModel(route);
                    try (ModelInputScope ignored = modelInputs.open(route.endpointName(), PromptMessage.toModelInputSources(messages))) {
                        response = model.chat(messages.stream().map(PromptMessage::message).toList());
                    }
                } finally {
                    if (previousSpec == null) AgentContext.clearStructuredOutputSpec();
                    else AgentContext.setStructuredOutputSpec(previousSpec);
                }
                score = parseScore(response == null || response.aiMessage() == null
                        ? null : response.aiMessage().text());
                decision = score > threshold ? "REJECT" : "PASS";
            }

            Map<String, Object> frozenConfig = new LinkedHashMap<>();
            frozenConfig.put("enabled", enabled);
            frozenConfig.put("endpointName", endpoint);
            frozenConfig.put("modelName", modelName);
            frozenConfig.put("threshold", threshold);
            String configJson = objectMapper.writeValueAsString(frozenConfig);
            int inserted = decisionMapper.insertIfAbsent(operationId, runId, decision, score, configJson);
            if (inserted == 1) {
                return new Evaluation("PASS".equals(decision), score, false);
            }
            // 并发调用只服从数据库里先保存的决定；不得用本次模型结果覆盖它。
            PythonRiskDecisionRow winner = decisionMapper.findByOperation(operationId);
            if (winner == null) {
                throw new Unavailable("Python 审查决定未能确认落库");
            }
            return fromSaved(winner, runId, true);
        } catch (Unavailable e) {
            throw e;
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            throw new Unavailable("Python 审查暂不可用", e);
        }
    }

    private Evaluation fromSaved(PythonRiskDecisionRow row, String runId, boolean reused) {
        if (!runId.equals(row.getRunId()) || row.getConfigJson() == null
                || (!"PASS".equals(row.getDecision()) && !"REJECT".equals(row.getDecision()))) {
            throw new Unavailable("Python 审查决定与当前 Run 不一致");
        }
        return new Evaluation("PASS".equals(row.getDecision()), row.getRiskScore(), reused);
    }

    private int parseScore(String output) {
        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(output);
            if (root == null || !root.isObject() || root.size() != 1) {
                throw new Unavailable("Python 审查模型返回的字段不符合约定");
            }
            JsonNode score = root.get("riskScore");
            if (score == null || !score.isIntegralNumber() || !score.canConvertToInt()
                    || score.intValue() < 0 || score.intValue() > 100) {
                throw new Unavailable("Python 审查模型返回的分数无效");
            }
            return score.intValue();
        } catch (Unavailable e) {
            throw e;
        } catch (Exception e) {
            throw new Unavailable("Python 审查模型返回的 JSON 无效", e);
        }
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }

    public record Evaluation(boolean allowed, Integer riskScore, boolean reused) { }

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }
}
