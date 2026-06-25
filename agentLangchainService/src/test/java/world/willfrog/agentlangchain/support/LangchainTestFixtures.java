package world.willfrog.agentlangchain.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentPromptService;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agentlangchain.orchestration.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.orchestration.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningStructuredOutputSettings;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class LangchainTestFixtures {

    private LangchainTestFixtures() {
    }

    public static AgentLlmProperties llmProperties() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Planning planning = new AgentLlmProperties.Planning();
        AgentLlmProperties.StructuredOutput structuredOutput = new AgentLlmProperties.StructuredOutput();
        structuredOutput.setStrategyStageEnabled(false);
        planning.setStructuredOutput(structuredOutput);
        runtime.setPlanning(planning);
        properties.setRuntime(runtime);
        AgentLlmProperties.Prompts prompts = new AgentLlmProperties.Prompts();
        prompts.setAgentRunSystemPrompt("你是专业金融分析代理。");
        prompts.setTodoPlannerSystemPromptTemplate(
                "你是任务规划器。只输出 JSON。工具: {{toolWhitelist}}，最多 {{maxTodos}} 步。");
        prompts.setDagReactSystemPrompt("你是金融分析代理，使用工具完成任务。");
        properties.setPrompts(prompts);
        return properties;
    }

    public static AgentPromptService promptService() {
        return new AgentPromptService(llmProperties(), new AgentLlmLocalConfigLoader(new ObjectMapper()));
    }

    public static LangchainPlanningStructuredOutputSettings structuredOutputSettings() {
        return new LangchainPlanningStructuredOutputSettings(
                llmProperties(),
                new AgentLlmLocalConfigLoader(new ObjectMapper()));
    }

    public static LangchainAiPlanner planner() {
        return new LangchainAiPlanner(promptService(), structuredOutputSettings(), JsonMapper.builder().build());
    }

    public static LangchainTodoNodeExecutor todoNodeExecutor() {
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = new ObjectProvider<>() {
            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject() {
                return null;
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject(Object... args) {
                return null;
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfAvailable() {
                return null;
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfUnique() {
                return null;
            }
        };
        return new LangchainTodoNodeExecutor(promptService(), provider, noopExecutionGuard(), noopBudgetService(), noopStateStore());
    }

    public static LangchainTodoNodeExecutor todoNodeExecutor(Optional<dev.langchain4j.service.tool.ToolProvider> toolProvider) {
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = new ObjectProvider<>() {
            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject() {
                return toolProvider.orElse(null);
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject(Object... args) {
                return toolProvider.orElse(null);
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfAvailable() {
                return toolProvider.orElse(null);
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfUnique() {
                return toolProvider.orElse(null);
            }
        };
        return new LangchainTodoNodeExecutor(promptService(), provider, noopExecutionGuard(), noopBudgetService(), noopStateStore());
    }

    /**
     * ccmax #59: noop budget service. effectiveConfig() returns all-zero (no limit) so shouldRecover() sees budgetHit=false.
     */
    public static world.willfrog.agent.platform.service.AgentRunBudgetService noopBudgetService() {
        world.willfrog.agent.platform.service.AgentRunBudgetService budget = mock(world.willfrog.agent.platform.service.AgentRunBudgetService.class);
        world.willfrog.agent.platform.service.AgentRunBudgetService.EffectiveRunBudget empty = new world.willfrog.agent.platform.service.AgentRunBudgetService.EffectiveRunBudget(0L, 0, 0, 0, 0);
        when(budget.effectiveConfig()).thenReturn(empty);
        return budget;
    }

    /**
     * ccmax #59: noop state store. loadObservability() returns empty Optional so readBudgetStatus() fail-soft 为未命中。
     */
    public static world.willfrog.agent.platform.service.AgentRunStateStore noopStateStore() {
        world.willfrog.agent.platform.service.AgentRunStateStore store = mock(world.willfrog.agent.platform.service.AgentRunStateStore.class);
        when(store.loadObservability(any())).thenReturn(java.util.Optional.empty());
        return store;
    }

    private static LangchainRunExecutionGuard noopExecutionGuard() {
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        return guard;
    }
}
