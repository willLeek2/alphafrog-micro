package world.willfrog.agentlangchain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import world.willfrog.agent.tools.dataset.ListMyDataTool;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the production component can be instantiated by Spring rather than only by direct
 * construction in unit tests.
 */
class ListMyDataToolSpringContextTest {

    @Test
    void listMyDataToolShouldUseItsObjectMapperConstructorInARealSpringContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(TestConfiguration.class);
            context.refresh();

            assertNotNull(context.getBean(ListMyDataTool.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ListMyDataTool.class)
    static class TestConfiguration {
    }
}
