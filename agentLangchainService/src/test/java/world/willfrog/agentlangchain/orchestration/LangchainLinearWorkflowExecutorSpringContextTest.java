package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import world.willfrog.agent.platform.config.CodeRefineProperties;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.CodeRefineLocalConfigLoader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** 验证存在三参数测试构造器时，Spring 仍选择显式的五参数生产构造器。 */
class LangchainLinearWorkflowExecutorSpringContextTest {

    @Test
    void springShouldInstantiateExecutorWithHotReloadDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LangchainTodoNodeExecutor.class,
                    () -> mock(LangchainTodoNodeExecutor.class));
            context.registerBean(LangchainRunExecutionGuard.class,
                    () -> mock(LangchainRunExecutionGuard.class));
            context.registerBean(AgentRunEventService.class, () -> mock(AgentRunEventService.class));
            context.registerBean(CodeRefineLocalConfigLoader.class,
                    () -> mock(CodeRefineLocalConfigLoader.class));
            context.registerBean(CodeRefineProperties.class, CodeRefineProperties::new);
            context.register(LangchainLinearWorkflowExecutor.class);
            context.refresh();

            assertNotNull(context.getBean(LangchainLinearWorkflowExecutor.class));
        }
    }
}
