package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.RunStageConfig;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchEvidenceJudgeModelResolverTest {

    private final SearchEvidenceJudgeModelResolver resolver = new SearchEvidenceJudgeModelResolver();

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void resolve_shouldPreferExplicitSearchJudgeStage() {
        RunStageConfig stageConfig = new RunStageConfig();
        StageLlmConfig searchJudge = stage("openrouter", "gpt-5.2");
        stageConfig.setSearchJudge(searchJudge);
        AgentContext.setStageConfig(stageConfig);
        AgentContext.setPhase("linear_execution");

        Optional<SearchEvidenceJudgeModelResolver.ResolvedStageModel> resolved = resolver.resolve();

        assertTrue(resolved.isPresent());
        assertEquals(SearchEvidenceJudgeModelResolver.ModelSource.SEARCH_JUDGE, resolved.get().source());
        assertEquals("gpt-5.2", resolved.get().config().getModelName());
    }

    @Test
    void resolve_shouldInheritExecutionStageWhenSearchJudgeMissing() {
        RunStageConfig stageConfig = new RunStageConfig();
        stageConfig.setExecution(stage("openrouter", "moonshotai/kimi-k2.5"));
        AgentContext.setStageConfig(stageConfig);
        AgentContext.setPhase("linear_execution");

        Optional<SearchEvidenceJudgeModelResolver.ResolvedStageModel> resolved = resolver.resolve();

        assertTrue(resolved.isPresent());
        assertEquals(SearchEvidenceJudgeModelResolver.ModelSource.INHERITED_STAGE, resolved.get().source());
        assertEquals("moonshotai/kimi-k2.5", resolved.get().config().getModelName());
    }

    @Test
    void resolve_shouldInheritPlanningStageForPlanningPhase() {
        RunStageConfig stageConfig = new RunStageConfig();
        stageConfig.setPlanning(stage("openrouter", "plan-model"));
        stageConfig.setExecution(stage("openrouter", "exec-model"));
        AgentContext.setStageConfig(stageConfig);
        AgentContext.setPhase("planning");

        Optional<SearchEvidenceJudgeModelResolver.ResolvedStageModel> resolved = resolver.resolve();

        assertTrue(resolved.isPresent());
        assertEquals("plan-model", resolved.get().config().getModelName());
    }

    @Test
    void resolve_shouldReturnEmptyWhenNoStageConfigAvailable() {
        AgentContext.setStageConfig(new RunStageConfig());
        AgentContext.setPhase("linear_execution");

        Optional<SearchEvidenceJudgeModelResolver.ResolvedStageModel> resolved = resolver.resolve();

        assertTrue(resolved.isEmpty());
    }

    private StageLlmConfig stage(String endpoint, String model) {
        StageLlmConfig cfg = new StageLlmConfig();
        cfg.setEndpointName(endpoint);
        cfg.setModelName(model);
        return cfg;
    }
}
