package world.willfrog.agent.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.RunStageConfig;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves which LLM config search evidence judge should use.
 *
 * <p>Priority: explicit {@code search_judge} stage → inherit current run phase stage
 * → deprecated {@code runtime.judge.routes}.</p>
 */
@Service
@Slf4j
public class SearchEvidenceJudgeModelResolver {

    public enum ModelSource {
        SEARCH_JUDGE,
        INHERITED_STAGE,
        RUN_EXECUTION,
        ROUTE_FALLBACK
    }

    public record ResolvedStageModel(StageLlmConfig config, ModelSource source) {
    }

    public Optional<ResolvedStageModel> resolve() {
        RunStageConfig stageConfig = AgentContext.getStageConfig();
        if (stageConfig != null && stageConfig.getSearchJudge() != null && stageConfig.getSearchJudge().isValid()) {
            return Optional.of(new ResolvedStageModel(stageConfig.getSearchJudge(), ModelSource.SEARCH_JUDGE));
        }
        StageLlmConfig inherited = resolveInheritedStageConfig(stageConfig);
        if (inherited != null && inherited.isValid()) {
            return Optional.of(new ResolvedStageModel(inherited, ModelSource.INHERITED_STAGE));
        }
        // 无专用 stage 配置时，退化使用 run 的 execution 阶段生效配置（与 final_answer 行为一致）
        StageLlmConfig runExecution = AgentContext.getEffectiveExecutionStageConfig();
        if (runExecution != null && runExecution.isValid()) {
            return Optional.of(new ResolvedStageModel(runExecution, ModelSource.RUN_EXECUTION));
        }
        return Optional.empty();
    }

    StageLlmConfig resolveInheritedStageConfig(RunStageConfig stageConfig) {
        if (stageConfig == null) {
            return null;
        }
        String phase = normalize(AgentContext.getPhase());
        if (phase.contains("planning")) {
            return stageConfig.getPlanning();
        }
        if (phase.contains("summarizing") || phase.contains("final_answer")) {
            StageLlmConfig finalAnswer = stageConfig.getFinalAnswer();
            if (finalAnswer != null && finalAnswer.isValid()) {
                return finalAnswer;
            }
        }
        return stageConfig.getExecution();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
