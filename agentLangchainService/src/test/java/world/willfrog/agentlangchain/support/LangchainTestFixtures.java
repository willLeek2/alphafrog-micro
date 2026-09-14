package world.willfrog.agentlangchain.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentPromptService;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
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
        planning.setStructuredOutput(structuredOutput);
        runtime.setPlanning(planning);
        properties.setRuntime(runtime);
        // Prompt 正文统一从 agentPlatformShared classpath 权威目录加载；测试夹具不再造第二份正文。
        properties.setPrompts(new AgentLlmProperties.Prompts());
        return properties;
    }

    /** 仅供显式验证事故降级路径的测试配置。 */
    public static AgentLlmProperties legacySingleStageLlmProperties() {
        AgentLlmProperties properties = llmProperties();
        properties.getRuntime().getPlanning().getStructuredOutput().setStrategyStageEnabled(false);
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

    /** 仅供带 legacy/fallback 语义的用例使用，不能作为生产规划回归夹具。 */
    public static LangchainAiPlanner legacySingleStagePlanner() {
        AgentLlmProperties properties = legacySingleStageLlmProperties();
        ObjectMapper objectMapper = JsonMapper.builder().build();
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(objectMapper);
        return new LangchainAiPlanner(
                new AgentPromptService(properties, loader),
                new LangchainPlanningStructuredOutputSettings(properties, loader),
                objectMapper);
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
        return new LangchainTodoNodeExecutor(promptService(), provider, noopExecutionGuard(), noopBudgetService(), noopStateStore(), noopFinanceResultComposer());
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
        return new LangchainTodoNodeExecutor(promptService(), provider, noopExecutionGuard(), noopBudgetService(), noopStateStore(), noopFinanceResultComposer());
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
     * 默认 no-op 金融结果块组合器：原样返回模型文本（不写事件、不查记录）。
     * 需要真实块行为的测试应自行构造 composer 并直接 new executor。
     */
    public static world.willfrog.agentlangchain.finance.FinanceResultComposer noopFinanceResultComposer() {
        world.willfrog.agentlangchain.finance.FinanceResultComposer composer =
                mock(world.willfrog.agentlangchain.finance.FinanceResultComposer.class);
        when(composer.appendFinanceResultBlock(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        return composer;
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
