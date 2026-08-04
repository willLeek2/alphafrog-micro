package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import world.willfrog.agent.platform.config.CodeRefineProperties;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.CodeRefineLocalConfigLoader;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** 验证存在四参数测试构造器时，Spring 仍选择显式的六参数生产构造器。 */
class LangchainLinearWorkflowExecutorSpringContextTest {

    @Test
    void springShouldInstantiateExecutorWithHotReloadDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LangchainAiPlanner.class, () -> mock(LangchainAiPlanner.class));
            context.registerBean(LangchainTodoNodeExecutor.class,
                    () -> mock(LangchainTodoNodeExecutor.class));
            context.registerBean(LangchainRunExecutionGuard.class,
                    () -> mock(LangchainRunExecutionGuard.class));
            context.registerBean(AgentEventService.class, () -> mock(AgentEventService.class));
            context.registerBean(CodeRefineLocalConfigLoader.class,
                    () -> mock(CodeRefineLocalConfigLoader.class));
            context.registerBean(CodeRefineProperties.class, CodeRefineProperties::new);
            context.register(LangchainLinearWorkflowExecutor.class);
            context.refresh();

            assertNotNull(context.getBean(LangchainLinearWorkflowExecutor.class));
        }
    }
}
